package okay.shop.assistant

import okay.effects.json.Json
import okay.facts.{Answer, Audit, Bind, Card, Conversation, Ent, Facts, Filter, Found, Handles, Lang as FLang, Op,
  Query, Reply, Scope, Setup, Value, Words, Zone}
import okay.facts.router.{Amount, Intents, Nearest, Probe, Route, Router, Spell, Table, When}
import okay.scala3.*
import okay.scala3.std.Clock

/** THE AUTHORED ANSWERS (assistant/answers.json): an ICU message per key and language; a list's item by variant (its
 * parameters numbered for each thing), the separator its items are joined by */
final case class Answers(heads: Map[String, Map[FLang, String]], items: Map[String, Map[String, Map[FLang, String]]],
                         seps: Map[String, String]):
  def apply(key: String, l: FLang): String = heads(key)(l)

  /** the key's message with its items in: item `i` (from 1) of variant `variants(i - 1)`, its parameters `x` → `xi` */
  def list(key: String, l: FLang, variants: Vector[String]): String =
    val items = variants.zipWithIndex.map((v, i) => Answers.numbered(this.items(key)(v)(l), i + 1))
    heads(key)(l).replace("{items}", items.mkString(seps.getOrElse(key, ", ")))

object Answers:
  private val placeholder = "\\{(\\w+)(?=\\s*[},])".r

  /** an item's placeholders numbered: `{t}` → `{t2}`, `{m, select, …}` → `{m2, select, …}` */
  def numbered(tpl: String, i: Int): String = placeholder.replaceAllIn(tpl, m => s"{${m.group(1)}$i")

  def parse(json: String): Answers =
    def langs(j: Json): Map[FLang, String] = FLang.values.flatMap(l => j.field(l.toString).collect {
      case Json.Str(s) => l -> s }).toMap
    val all = Json.fromText(json)("answers").record
    Answers(all.map((k, j) => k -> langs(j)).toMap,
      all.map((k, j) => k -> j.record.collect { case (v, o: Json.Obj) if v == "item" || v.startsWith("item.") =>
        v -> langs(o) }.toMap).toMap,
      all.flatMap((k, j) => j.field("sep").collect { case Json.Str(s) => k -> s }).toMap)

/**
 * THE ROUTER LANE (specs/shop.md "The assistant"; okay-facts specs/design.md §17): a turn understood with no model —
 * the intent by okay-facts' router (the shop's rules, the static table, the probe), its slots by rules and by the
 * table (the service among the shop's, the day and time, the master, the city, the price limit, the conversation's
 * earlier handles) — answered by an AUTHORED `{params, template}` over the handles that `find` gives here, validated
 * and rendered by okay-facts. What it does not understand, or cannot resolve, it declines: the host asks the model.
 * A program of `Facts` (the shop's, or the mall's), `Clock` and `Audit`
 */
object ShopRouter:
  lazy val intents = Intents.parse(AssistantData.intents)
  lazy val answers: Answers = Answers.parse(AssistantData.answers)
  lazy val probe: Probe = Probe.fromJson(Json.fromText(AssistantData.probe))

  /** the lane: the router over the table, the answers, the thresholds (a route's probability and margin; a
   * service's nearness and its lead over the next) */
  final class Lane(val table: Table, val router: Router, val answers: Answers, val minP: Double = 0.6,
                   val minMargin: Double = 0.2, val serviceAt: Double = 0.6, val serviceLead: Double = 0.06)

  def lane(table: Table): Lane = new Lane(table, Router(intents, probe), answers)

  /** the static table the probe was fitted over: in the JVM's jar at `resource`; a Worker's host keeps it in its
   * storage under `key` (its name the table's hash: no other table is ever read with this probe) */
  val resource: String = "/okay/shop/assistant/static.safetensors"
  def key: String = s"router/static-${AssistantData.table}.safetensors"

  /** WHAT THE ROUTER DID: answered (the conversation, the reply, its route and slots), or declined (why) */
  enum Decision:
    case Answered(c: Conversation, reply: Reply, route: Route, slots: Map[String, String])
    case Declined(route: Route, why: String, slots: Map[String, String] = Map.empty)

  private final case class Built(handles: Handles, answer: Answer, cards: Vector[Card] = Vector.empty,
                                 slots: Map[String, String] = Map.empty)
  private type Out = Either[(String, Map[String, String]), Built]

  /** the intents that answer a shop's own facts: no shop in sight, the model lane */
  private val ofShop = Set("hours", "address", "area", "modes", "masters", "reviews", "human", "slots", "book")

  def turn[R[_]](lane: Lane, s: Setup, c: Conversation, user: String, l: FLang, focus: String)(using In[Facts, R],
    In[Clock, R], In[Audit, R]): Decision ! R =
    val route = lane.router.route(lane.table, user)
    if route.by == "unknown" then pure(Decision.Declined(route, "no word known"))
    else if !route.confident(lane.minP, lane.minMargin) then pure(Decision.Declined(route, "not confident"))
    else if route.intent == "other" then pure(Decision.Declined(route, "other"))
    else Clock.now[R].flatMap { now =>
      Bind.focused[R](s, c, focus, l).flatMap { (h0, note) =>
        val ctx = Ctx(lane, c, h0, user, l, focus, Zone.date(now))
        build[R](route.intent, ctx).flatMap {
          case Left((why, slots)) => pure(Decision.Declined(route, why, slots))
          case Right(b) => Bind.authored[R](s, c, user, l, focus, b.handles, note, b.answer, b.cards, route.intent,
              route.p).map {
              case Left(vs) => Decision.Declined(route, "invalid: " + vs.map(_.code).mkString(","), b.slots)
              case Right((c2, reply)) => Decision.Answered(c2, reply, route, b.slots)
            }
        }
      }
    }

  /** a turn's ground: the lane, the conversation, its handles as they stand, the words, where the user is */
  private final case class Ctx(lane: Lane, c: Conversation, h: Handles, user: String, l: FLang, focus: String,
                               today: String):
    def a(key: String): String = lane.answers(key, l)
    def list(key: String, variants: Vector[String]): String = lane.answers.list(key, l, variants)
    def shopId: Option[String] = Some(focus).filter(_.startsWith("shop:")).map(_.stripPrefix("shop:"))
    /** the shop in sight: the focus' own, else the one the conversation talked of last */
    def shop: Option[okay.facts.Handle] = shopId.flatMap(id => h.all.find(x => x.ent.cls == "Shop" &&
      x.ent.source == id)).orElse(if shopId.isEmpty then h.all.reverse.find(x => x.ent.cls == "Service" ||
      x.ent.cls == "Slot").flatMap(x => h.all.find(y => y.ent.cls == "Shop" && y.ent.source == x.ent.source))
      else None)
    def scope: Scope = shopId.map(Scope.Source(_)).orElse(context.map(x => Scope.Source(x.ent.source)))
      .getOrElse(Scope.All)
    /** the service the conversation spoke of last */
    def context: Option[okay.facts.Handle] = h.all.reverse.find(_.ent.cls == "Service")
    /** a follow-up: short, or pointing back ("a ile trwa?", "that one", "тот") */
    def followUp: Boolean =
      val ws = Words.split(user)
      ws.size <= 4 || ws.headOption.exists(Set("a", "а", "and", "i", "и", "і", "й", "to", "ta")) ||
        ws.exists(Set("to", "ten", "ta", "tamten", "tamto", "tamta", "it", "that", "this", "one", "це", "той", "цей",
          "то", "тот", "этот", "это"))
    lazy val when: When = When.of(user, today)
    lazy val amount: Amount = Amount.of(user)

  private def ok(b: Built): Out = Right(b)
  private def no(why: String, slots: Map[String, String] = Map.empty): Out = Left((why, slots))

  private def issue(h: Handles, f: Found): (Handles, String) =
    val (h2, x) = h.issue(ShopFacts.schema.prefix(f.ent.cls), f.ent, f.version, f.visible)
    (h2, x.name)

  private def issueAll(h: Handles, fs: Vector[Found]): (Handles, Vector[String]) =
    fs.foldLeft((h, Vector.empty[String])) { case ((acc, ns), f) =>
      val (a2, n) = issue(acc, f)
      (a2, ns :+ n)
    }

  private def seq[R[_], A, B](xs: Vector[A])(f: A => B ! R): Vector[B] ! R =
    xs.foldLeft(pure[Vector[B], R](Vector.empty))((acc, a) => acc.flatMap(bs => f(a).map(bs :+ _)))

  /** a class's entities in scope, with their visible names in every language (the user's first) */
  private def named[R[_]](cls: String, scope: Scope, l: FLang, limit: Int = 80)(using In[Facts, R])
    : Vector[(Found, Vector[String])] ! R =
    seq[R, FLang, Vector[Found]]((l +: FLang.values.toVector.filterNot(_ == l)))(x => Facts.find[R](Query(cls,
      scope = scope, limit = limit, lang = x))).map { byLang =>
      val first = byLang.head
      first.map(f => f -> byLang.flatMap(_.find(_.ent == f.ent).toVector.flatMap(_.visible.collect {
        case ("name", n) => n })).distinct)
    }

  /** the masters in a shop with their names (hidden from the model, read here) */
  private def masters[R[_]](source: String, l: FLang)(using In[Facts, R]): Vector[(Found, String)] ! R =
    Facts.find[R](Query("Master", scope = Scope.Source(source), limit = 30, lang = l)).flatMap(fs =>
      seq[R, Found, (Found, String)](fs)(f => Facts.get[R](f.ent, "name").map(n => f -> n.fold("")(x => x.value match
        case Value.Text(t) => Value.in(t, l)
        case _ => ""))))

  private def has[R[_]](e: Ent, prop: String)(using In[Facts, R]): Boolean ! R = Facts.get[R](e, prop).map(_.isDefined)

  /** the service a message names among the candidates: one, several too close (asked), none */
  private def service(ctx: Ctx, cands: Vector[(Found, Vector[String])]): Nearest.Pick =
    val ranked = Nearest.rank(ctx.lane.table, ctx.user, cands.zipWithIndex.map((x, i) =>
      Nearest.Candidate(i.toString, x._2)))
    // a follow-up keeps the conversation's service unless it names another by its words
    Nearest.pick(ranked, if ctx.followUp && ctx.context.isDefined then 0.75 else ctx.lane.serviceAt,
      ctx.lane.serviceLead)

  private def build[R[_]](intent: String, ctx: Ctx)(using In[Facts, R], In[Clock, R]): Out ! R =
    if ofShop(intent) && ctx.shop.isEmpty && intent != "slots" && intent != "book" then
      pure(no("no shop in sight"))
    else intent match
      case "greet" => pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a(greeting(ctx.user))))))
      case "help" => pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a("help")))))
      case "hours" => one(ctx, "hours", "hours.week", "hours.none", "h")
      case "address" => one(ctx, "address", "address.one", "address.none", "a")
      case "modes" => one(ctx, "modes", "modes.list", "address.none", "m")
      case "human" => val sh = ctx.shop.get
        has[R](sh.ent, "contact").map(e => ok(Built(ctx.h, if e then Answer(Vector("e" -> s"contact(${sh.name})"),
          ctx.a("human.contact")) else Answer(Vector("u" -> s"url(${sh.name})"), ctx.a("human.page")))))
      case "reviews" => val sh = ctx.shop.get
        has[R](sh.ent, "rating").map(r => ok(Built(ctx.h, if r then Answer(Vector("r" -> s"rating(${sh.name})",
          "n" -> s"reviews(${sh.name})"), ctx.a("reviews.rated")) else Answer(Vector.empty, ctx.a("reviews.none")))))
      case "manage" => pure(ok(Built(ctx.h, ctx.h.all.reverse.find(_.ent.cls == "Booking") match
          case Some(b) => Answer(Vector("r" -> s"ref(${b.name})", "u" -> s"manage(${b.name})"), ctx.a("manage.link"))
          case None => Answer(Vector.empty, ctx.a("manage.letter")))))
      case "area" => area(ctx)
      case "masters" => staff(ctx)
      case "price" => price(ctx)
      case "slots" | "book" => times(ctx, intent == "book")
      case "compare" => compare(ctx)
      case "search" => search(ctx)
      case other => pure(no(s"no answer for $other"))

  private def greeting(text: String): String =
    val w = Words.norm(text)
    if Seq("dzię", "dzie", "thank", "thx", "cheers", "дяку", "спасиб", "благодар").exists(w.contains) then
      "greet.thanks"
    else if Seq("widzenia", "razie", "bye", "побачення", "бувай", "свидания", "пока").exists(w.contains) then
      "greet.bye"
    else "greet.hello"

  /** a shop's one property, or its absence */
  private def one[R[_]](ctx: Ctx, prop: String, yes: String, none: String, p: String)(using In[Facts, R]): Out ! R =
    val sh = ctx.shop.get
    has[R](sh.ent, prop).map(x => ok(Built(ctx.h, if x then Answer(Vector(p -> s"$prop(${sh.name})"), ctx.a(yes))
      else Answer(Vector.empty, ctx.a(none)))))

  private def area[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    val sh = ctx.shop.get
    has[R](sh.ent, "area").flatMap {
      case false => has[R](sh.ent, "modes").map(m => if m then ok(Built(ctx.h, Answer(Vector("m" ->
          s"modes(${sh.name})"), ctx.a("area.none")))) else no("no area"))
      case true => named[R]("Place", Scope.Source(sh.ent.source), ctx.l).map { places =>
          places.find((_, ns) => ns.exists(Spell.names(ctx.user, _))) match
            case Some((f, _)) =>
              val (h, p) = issue(ctx.h, f)
              ok(Built(h, Answer(Vector("p" -> s"name($p)", "a" -> s"area(${sh.name})"), ctx.a("area.yes")),
                slots = Map("place" -> f.ent.key)))
            case None =>
              // a place named that is not the area's: a capitalised word past the first, or one after a preposition
              val ws = ctx.user.split("\\s+").toVector.map(_.filter(_.isLetter)).filter(_.nonEmpty)
              val named = ws.drop(1).exists(w => w.head.isUpper) || ws.sliding(2).exists {
                case Vector(a, b) => Set("do", "na", "w", "we", "to", "in", "в", "у", "до", "на").contains(
                  a.toLowerCase) && b.length >= 4
                case _ => false
              }
              ok(Built(ctx.h, Answer(Vector("a" -> s"area(${sh.name})"), ctx.a(if named then "area.no"
                else "area.list"))))
        }
    }

  /** the shop's masters, of a service when one is named */
  private def staff[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    val sh = ctx.shop.get
    masters[R](sh.ent.source, ctx.l).flatMap { ms =>
      if ms.isEmpty then pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a("masters.none")))))
      else named[R]("Service", Scope.Source(sh.ent.source), ctx.l).flatMap { cands =>
        service(ctx, cands) match
          case Nearest.Pick.One(i, _) =>
            val (f, _) = cands(i.toInt)
            Facts.find[R](Query("Master", filters = Vector(Filter("services", Op.Eq, Value.Entity(f.ent))),
              scope = Scope.Source(sh.ent.source), limit = 30, lang = ctx.l)).map { doing =>
              val (h1, v) = issue(ctx.h, f)
              val (h2, names) = issueAll(h1, doing)
              ok(Built(h2, Answer(("s" -> s"name($v)") +: names.zipWithIndex.map((m, k) =>
                s"m${k + 1}" -> s"name($m) as nom"), ctx.list("masters.service", names.map(_ => "item"))),
                slots = Map("service" -> f.ent.key)))
            }
          case _ =>
            val (h, names) = issueAll(ctx.h, ms.map(_._1))
            pure(ok(Built(h, Answer(names.zipWithIndex.map((m, k) => s"m${k + 1}" -> s"name($m) as nom"),
              ctx.list("masters.list", names.map(_ => "item"))))))
      }
    }

  /** a service's price and duration; several close: asked; none named: the context's, else the price list */
  private def price[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    named[R]("Service", ctx.scope, ctx.l).flatMap { cands =>
      service(ctx, cands) match
        case Nearest.Pick.One(i, _) => priced(ctx, cands(i.toInt)._1)
        case Nearest.Pick.Several(is) => pure(ask(ctx, is.map(i => cands(i.toInt)._1)))
        case Nearest.Pick.Nothing => ctx.context.filter(_ => ctx.followUp) match
            case Some(x) => priced(ctx, Found(x.ent, x.visible, x.version))
            case None if ctx.scope == Scope.All => pure(no("no service named in the mall"))
            case None => priceList(ctx, cands.map(_._1))
    }

  private def ask(ctx: Ctx, fs: Vector[Found]): Out =
    val (h, vs) = issueAll(ctx.h, fs.take(5))
    ok(Built(h, Answer(vs.zipWithIndex.map((v, k) => s"s${k + 1}" -> s"name($v)"), ctx.list("ask.service",
      vs.map(_ => "item"))), slots = Map("service" -> "?")))

  private def priced[R[_]](ctx: Ctx, f: Found)(using In[Facts, R]): Out ! R =
    for p <- has[R](f.ent, "price"); d <- has[R](f.ent, "duration") yield
      val (h, v) = issue(ctx.h, f)
      val key = if p && d then "price.both" else if p then "price.price" else if d then "price.duration"
        else "price.neither"
      ok(Built(h, Answer(Vector("s" -> s"name($v)") ++ Option.when(p)("p" -> s"price($v)") ++
        Option.when(d)("d" -> s"duration($v)"), ctx.a(key)), slots = Map("service" -> f.ent.key)))

  private def priceList[R[_]](ctx: Ctx, fs: Vector[Found])(using In[Facts, R]): Out ! R =
    if fs.isEmpty then pure(no("no services"))
    else seq[R, Found, Boolean](fs.take(8))(f => has[R](f.ent, "price")).map { priced =>
      val (h, vs) = issueAll(ctx.h, fs.take(8))
      ok(Built(h, Answer(vs.zip(priced).zipWithIndex.flatMap { case ((v, p), k) => Vector(s"s${k + 1}" ->
        s"name($v)") ++ Option.when(p)(s"p${k + 1}" -> s"price($v)") }, ctx.list("price.list", priced.map(p =>
        if p then "item" else "item.noprice"))), slots = Map("service" -> "none")))
    }

  /** free times (or a booking's card): the service, the day and the time asked, the master named */
  private def times[R[_]](ctx: Ctx, booking: Boolean)(using In[Facts, R]): Out ! R =
    named[R]("Service", ctx.scope, ctx.l).flatMap { cands =>
      val picked: Either[Out, Found] = service(ctx, cands) match
        case Nearest.Pick.One(i, _) => Right(cands(i.toInt)._1)
        case Nearest.Pick.Several(is) => Left(ask(ctx, is.map(i => cands(i.toInt)._1)))
        case Nearest.Pick.Nothing => ctx.context.map(x => Found(x.ent, x.visible, x.version)).orElse(
            Option.when(cands.size == 1)(cands.head._1)).toRight(if cands.isEmpty then no("no services")
            else ask(ctx, cands.map(_._1)))
      picked match
        case Left(out) => pure(out)
        case Right(f) => masters[R](f.ent.source, ctx.l).flatMap { ms =>
            val asked = ms.filter((_, n) => n.nonEmpty && Spell.names(ctx.user, n))
            val named = ctx.user.split("\\s+").toVector.drop(1).exists(w => w.headOption.exists(_.isUpper) &&
              w.length >= 3 && !cands.exists(_._2.exists(n => Spell.names(w, n))))
            if asked.isEmpty && named && ms.nonEmpty then
              val (h, names) = issueAll(ctx.h, ms.map(_._1))
              pure(ok(Built(h, Answer(names.zipWithIndex.map((m, k) => s"m${k + 1}" -> s"name($m) as nom"),
                ctx.list("masters.unknown", names.map(_ => "item"))), slots = Map("master" -> "none"))))
            else slotsOf(ctx, f, asked.headOption.map(_._1), booking)
          }
    }

  private def slotsOf[R[_]](ctx: Ctx, f: Found, master: Option[Found], booking: Boolean)(using In[Facts, R])
    : Out ! R =
    val w = ctx.when
    val filters = Vector(Filter("service", Op.Eq, Value.Entity(f.ent))) ++
      w.day.map(d => Filter("date", Op.Eq, Value.Day(d))) ++
      w.at.map(a => Filter("time", Op.Eq, Value.Clock(a))) ++ w.from.map(a => Filter("time", Op.Ge, Value.Clock(a))) ++
      w.until.map(a => Filter("time", Op.Lt, Value.Clock(a))) ++
      master.map(m => Filter("master", Op.Eq, Value.Entity(m.ent)))
    val slots = Map("service" -> f.ent.key) ++ w.day.map("day" -> _) ++ w.at.map(a => "at" -> clock(a)) ++
      w.from.map(a => "from" -> clock(a)) ++ w.until.map(a => "until" -> clock(a)) ++
      master.map(m => "master" -> m.ent.key)
    has[R](f.ent, "bookable").flatMap(_ => Facts.get[R](f.ent, "bookable")).flatMap { b =>
      if !b.exists(_.value == Value.Bool(true)) then
        val (h, v) = issue(ctx.h, f)
        pure(ok(Built(h, Answer(Vector("s" -> s"name($v)"), ctx.a("slots.unbookable")), slots = slots)))
      else Facts.find[R](Query("Slot", filters = filters, scope = Scope.Source(f.ent.source), limit = 6,
        lang = ctx.l)).flatMap { found =>
        val (h1, v) = issue(ctx.h, f)
        if found.isEmpty then
          // none then: the next free one of the coming days (the service and the master kept)
          Facts.find[R](Query("Slot", filters = filters.filter(x => x.prop == "service" || x.prop == "master"),
            scope = Scope.Source(f.ent.source), limit = 1, lang = ctx.l)).map {
            case Vector(next) =>
              val (h2, t) = issue(h1, next)
              ok(Built(h2, Answer(Vector("s" -> s"name($v)", "t" -> s"time($t) as datetime"), ctx.a("slots.next")),
                slots = slots))
            case _ => ok(Built(h1, Answer(Vector("s" -> s"name($v)"), ctx.a("slots.none")), slots = slots))
          }
        else if booking && w.at.isDefined then
          val (h2, t) = issue(h1, found.head)
          Facts.act[R]("book", Map("slot" -> Value.Entity(found.head.ent)), ctx.l).map {
            case Right(acted) => ok(Built(h2, Answer(Vector("s" -> s"name($v)", "t" -> s"time($t) as datetime"),
                ctx.a("book.card")), acted.card.toVector, slots))
            case Left(_) => ok(Built(h1, Answer(Vector("s" -> s"name($v)"), ctx.a("slots.none")), slots = slots))
          }
        else seq[R, Found, Boolean](found)(x => has[R](x.ent, "master")).map { withMaster =>
          val (h2, ts) = issueAll(h1, found)
          val params = Vector("s" -> s"name($v)", "d" -> s"date(${ts.head}) as long") ++ ts.zip(withMaster)
            .zipWithIndex.flatMap { case ((t, m), k) => Vector(s"t${k + 1}" -> s"time($t)") ++
              Option.when(m && !booking)(s"m${k + 1}" -> s"name(master($t)) as gen") }
          // one day's times (a list over several days says each one's day)
          val oneDay = found.map(_.visible).distinct.size <= 1 && w.day.isDefined
          val ps = if oneDay then params else params.filterNot(_._1 == "d").map((n, e) =>
            if n.startsWith("t") then n -> s"$e as datetime" else n -> e)
          val head = if booking then "book.pick" else "slots.list"
          val tpl = ctx.list(head, withMaster.map(m => if m && !booking then "item" else
            if booking then "item" else "item.nomaster"))
          ok(Built(h2, Answer(ps, if oneDay then tpl else tpl.replace(", {d}", "").replace(" {d}", "")),
            slots = slots))
        }
      }
    }

  private def clock(m: Int): String = f"${m / 60}%02d:${m % 60}%02d"

  /** a comparison: the service named against the conversation's (computed by the resolver), else the mall's lower
   * prices for the conversation's service, else the lowest price of the service named across the mall */
  private def compare[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    ctx.context match
      case Some(a) =>
        named[R]("Service", Scope.Source(a.ent.source), ctx.l).flatMap { cands =>
          service(ctx, cands) match
            case Nearest.Pick.One(i, _) if cands(i.toInt)._1.ent != a.ent =>
              val (h, b) = issue(ctx.h, cands(i.toInt)._1)
              pure(ok(Built(h, Answer(Vector("b" -> s"name($b)", "pb" -> s"price($b)", "a" -> s"name(${a.name})",
                "pa" -> s"price(${a.name})", "c" -> s"price($b) < price(${a.name})"), ctx.a("compare.pair")),
                slots = Map("service" -> cands(i.toInt)._1.ent.key))))
            case _ => Facts.get[R](a.ent, "price").flatMap {
                case None => pure(no("no price to compare"))
                case Some(pa) => Facts.find[R](Query("Service", Value.in(a.visible.collectFirst { case ("name", n) =>
                    Map(ctx.l -> n) }.getOrElse(Map.empty), ctx.l), Vector(Filter("price", Op.Lt, pa.value)),
                    Scope.All, 5, ctx.l)).map(_.filterNot(_.ent == a.ent)).flatMap { cheaper =>
                  if cheaper.isEmpty then pure(ok(Built(ctx.h, Answer(Vector("a" -> s"name(${a.name})",
                    "pa" -> s"price(${a.name})"), ctx.a("compare.none")), slots = Map("service" -> a.ent.key))))
                  else
                    val (h, vs) = issueAll(ctx.h, cheaper)
                    pure(ok(Built(h, Answer(Vector("a" -> s"name(${a.name})", "pa" -> s"price(${a.name})") ++
                      vs.zipWithIndex.flatMap((v, k) => Vector(s"s${k + 1}" -> s"name($v)", s"w${k + 1}" ->
                        s"name(shop($v))", s"p${k + 1}" -> s"price($v)", s"c${k + 1}" ->
                        s"price($v) < price(${a.name})")), ctx.list("compare.mall", vs.map(_ => "item"))),
                      slots = Map("service" -> a.ent.key))))
                }
              }
        }
      case None => named[R]("Service", Scope.All, ctx.l).flatMap { cands =>
          val ranked = Nearest.rank(ctx.lane.table, ctx.user, cands.zipWithIndex.map((x, i) =>
            Nearest.Candidate(i.toString, x._2)))
          val best = ranked.headOption.map(_._2).getOrElse(0.0)
          val near = ranked.filter(r => r._2 >= ctx.lane.serviceAt && r._2 >= best - 0.15).take(5)
            .map(r => cands(r._1.toInt)._1)
          seq[R, Found, Boolean](near)(f => has[R](f.ent, "price")).map { priced =>
            val withPrice = near.zip(priced).collect { case (f, true) => f }
            if withPrice.isEmpty then no("nothing to compare")
            else
              val (h, vs) = issueAll(ctx.h, withPrice)
              val low = s"argmin(price, ${vs.mkString(", ")})"
              ok(Built(h, Answer(Vector("s" -> s"name($low)", "w" -> s"name(shop($low))", "p" -> s"price($low)") ++
                vs.zipWithIndex.flatMap((v, k) => Vector(s"s${k + 1}" -> s"name($v)", s"w${k + 1}" ->
                  s"name(shop($v))", s"p${k + 1}" -> s"price($v)")), ctx.list("compare.lowest", vs.map(_ =>
                "item"))), slots = Map("service" -> withPrice.map(f => s"${f.ent.source}/${f.ent.key}")
                .mkString(","))))
          }
        }

  /** across the mall: the service named (by meaning), in the city named, under the price named, at the time named —
   * else the shops whose kind or name it names */
  private def search[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    named[R]("Shop", Scope.All, ctx.l, 100).flatMap { shops =>
      def city(f: Found): String = f.visible.collectFirst { case ("city", c) => c }.getOrElse("")
      val cities = shops.map((f, _) => city(f)).filter(_.nonEmpty).distinct.filter(Spell.names(ctx.user, _))
      val inCity = shops.filter((f, _) => cities.isEmpty || cities.contains(city(f))).map(_._1.ent.source).toSet
      val slots0 = Map[String, String]() ++ cities.headOption.map("city" -> _) ++
        ctx.amount.max.map(m => "max" -> (m / 100).toString) ++ ctx.when.day.map("day" -> _)
      if cities.nonEmpty && inCity.isEmpty then pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a("search.none")),
        slots = slots0)))
      else named[R]("Service", Scope.All, ctx.l, 200).flatMap { all =>
        val cands = all.filter(x => inCity(x._1.ent.source))
        val ranked = Nearest.rank(ctx.lane.table, ctx.user, cands.zipWithIndex.map((x, i) =>
          Nearest.Candidate(i.toString, x._2)))
        val best = ranked.headOption.map(_._2).getOrElse(0.0)
        val near = ranked.filter(r => r._2 >= ctx.lane.serviceAt && r._2 >= best - 0.12).map(r => cands(r._1.toInt)._1)
        val cheap: Set[Ent] ! R = ctx.amount.max match
          case None => pure(near.map(_.ent).toSet)
          case Some(m) => Facts.find[R](Query("Service", filters = Vector(Filter("price", Op.Le, Value.Money(m, "PLN"))),
              scope = Scope.All, limit = 200, lang = ctx.l)).map(_.map(_.ent).toSet)
        cheap.flatMap { under =>
          val kept = near.filter(f => under(f.ent)).take(5)
          if kept.nonEmpty then
            seq[R, Found, (Boolean, Option[Found])](kept)(f => has[R](f.ent, "price").flatMap(p =>
              (if ctx.when.isEmpty then pure(Option.empty[Found]) else Facts.find[R](Query("Slot", filters =
                Vector(Filter("service", Op.Eq, Value.Entity(f.ent))) ++ ctx.when.day.map(d => Filter("date", Op.Eq,
                Value.Day(d))) ++ ctx.when.from.map(a => Filter("time", Op.Ge, Value.Clock(a))) ++
                ctx.when.until.map(a => Filter("time", Op.Lt, Value.Clock(a))), scope = Scope.Source(f.ent.source),
                limit = 1, lang = ctx.l)).map(_.headOption)).map(p -> _))).flatMap { info =>
              val (h1, vs) = issueAll(ctx.h, kept)
              val (h2, ts) = info.foldLeft((h1, Vector.empty[Option[String]])) { case ((h, acc), (_, slot)) =>
                slot.fold((h, acc :+ None)) { s => val (h3, t) = issue(h, s); (h3, acc :+ Some(t)) }
              }
              val params = vs.zip(info).zip(ts).zipWithIndex.flatMap { case (((v, (p, _)), t), k) =>
                val n = k + 1
                Vector(s"s$n" -> s"name($v)", s"w$n" -> s"name(shop($v))", s"c$n" -> s"city(shop($v))") ++
                  Option.when(p)(s"p$n" -> s"price($v)") ++ t.map(x => s"t$n" -> s"time($x) as datetime")
              }
              val variants = info.zip(ts).map { case ((p, _), t) => if !p then "item.noprice" else if t.isDefined
                then "item.time" else "item" }
              val top = kept.head.ent.source
              Facts.act[R]("open", Map("shop" -> Value.Entity(Ent(top, "Shop", top))), ctx.l).map(acted =>
                ok(Built(h2, Answer(params, ctx.list("search.services", variants)), acted.toOption.flatMap(_.card)
                  .toVector, slots0 ++ Map("service" -> kept.map(f => s"${f.ent.source}/${f.ent.key}").mkString(",")))))
            }
          else
            // no service near enough: the shops whose kind (or name) the message names
            val byKind = Nearest.rank(ctx.lane.table, ctx.user, shops.filter(x => inCity(x._1.ent.source)).map((f,
              names) => Nearest.Candidate(f.ent.source, names ++ f.visible.collect { case ("category", k) => k })))
            val bestKind = byKind.headOption.map(_._2).getOrElse(0.0)
            val found = byKind.filter(r => r._2 >= ctx.lane.serviceAt && r._2 >= bestKind - 0.12).take(5)
              .flatMap(r => shops.find(_._1.ent.source == r._1).map(_._1))
            if found.isEmpty && cities.isEmpty then pure(no("nothing named", slots0))
            else if found.isEmpty then pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a("search.none")),
              slots = slots0)))
            else
              val (h, ws) = issueAll(ctx.h, found)
              pure(ok(Built(h, Answer(ws.zipWithIndex.flatMap((w, k) => Vector(s"w${k + 1}" -> s"name($w)",
                s"k${k + 1}" -> s"category($w)", s"c${k + 1}" -> s"city($w)")), ctx.list("search.shops",
                ws.map(_ => "item"))), slots = slots0 ++ Map("shops" -> found.map(_.ent.source).mkString(",")))))
        }
      }
    }
