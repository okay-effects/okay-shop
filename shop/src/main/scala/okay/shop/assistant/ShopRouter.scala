package okay.shop.assistant

import okay.effects.json.Json
import okay.facts.{Answer, Audit, Bind, Card, Conversation, Ent, Facts, Filter, Found, Handles, Lang as FLang, Op,
  Query, Reply, Scope, Setup, Value, Words, Zone}
import okay.facts.router.{Amount, Combiner, Intents, Lexicon, Nearest, Probe, Route, Router, Spell, Table, When}
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
  lazy val guard: Vector[String] = Intents.guard(AssistantData.intents)
  lazy val answers: Answers = Answers.parse(AssistantData.answers)
  lazy val probe: Probe = Probe.fromBytes(java.util.Base64.getDecoder.decode(AssistantData.probe))
  /** the combiner fitted and calibrated over this probe (okay-mall's `Fit`); none yet: the evidence's glance */
  lazy val combiner: Option[Combiner] = Option.when(AssistantData.combiner.nonEmpty)(Combiner.fromBytes(
    java.util.Base64.getDecoder.decode(AssistantData.combiner)))
  lazy val lexicon: Lexicon = Lexicon.parse(AssistantData.lexicon)

  /** the lane: the router over the table, the answers, the threshold an answer needs (the combiner's, else round 2's
   * probe thresholds), a service's nearness and its lead over the next, the words of one thing a service's name is
   * said by */
  final class Lane(val table: Table, val router: Router, val answers: Answers, val minP: Double = 0.7,
                   val minMargin: Double = 0.2, val serviceAt: Double = 0.6, val serviceLead: Double = 0.06,
                   val lexicon: Lexicon = Lexicon.empty)

  def lane(table: Table): Lane = lane(table, combiner)

  def lane(table: Table, c: Option[Combiner]): Lane =
    new Lane(table, Router(intents, probe, guard, c), answers, minP = c.fold(0.7)(_.threshold),
      minMargin = if c.isDefined then 0.0 else 0.2, lexicon = lexicon)

  /** the static table the probe was fitted over: in the JVM's jar at `resource`; a Worker's host keeps it in its
   * storage under `key` (its name the table's hash: no other table is ever read with this probe) */
  val resource: String = "/okay/shop/assistant/static.safetensors"
  def key: String = s"router/static-${AssistantData.table}.safetensors"

  /** WHAT THE ROUTER DID: answered (the conversation, the reply, its route and slots), or declined (why) */
  enum Decision:
    case Answered(c: Conversation, reply: Reply, route: Route, slots: Map[String, String])
    case Declined(route: Route, why: String, slots: Map[String, String] = Map.empty)

  private final case class Built(handles: Handles, answer: Answer, cards: Vector[Card] = Vector.empty,
                                 slots: Map[String, String] = Map.empty, intent: Option[String] = None)
  private type Out = Either[(String, Map[String, String]), Built]

  /** the intents that answer a shop's own facts: no shop in sight, the model lane */
  private val ofShop = Set("hours", "address", "area", "modes", "masters", "reviews", "human", "slots", "book")

  /** THE HOST'S EVIDENCE the combiner reads (okay-facts specs/design.md §17 "The turn decision"), by name */
  val evidenceNames: Vector[String] = Vector("service", "serviceNear", "master", "person", "place", "day", "time",
    "exact", "books", "amount", "alone", "mall", "shopNamed", "short", "opener", "context")

  /** A TURN'S EVIDENCE: the previous question's intent when this one is a short follow-up, the host's evidence by
   * name — from the shop's facts in scope (read in one call, `Facts.Prefetch`) and the words */
  def evidence[R[_]](lane: Lane, s: Setup, c: Conversation, user: String, l: FLang, focus: String)(using
    In[Facts, R], In[Clock, R]): (Option[String], Map[String, Double]) ! R =
    Clock.now[R].flatMap { now =>
      prefetch[R](focus, l).flatMap(_ => Bind.focused[R](s, c, focus, l)).flatMap { (h0, _) =>
        features[R](Ctx(lane, c, h0, user, l, focus, Zone.date(now)))
      }
    }

  /** everything a turn of a shop (or of the mall's home) may read, asked at once: the services in every language
   * with their prices, durations, whether bookable, names; the masters with their names and their forms; the places;
   * the shop's own properties. A host with no cache ignores it */
  private def prefetch[R[_]](focus: String, l: FLang)(using In[Facts, R]): Unit ! R =
    val langs = l +: FLang.values.toVector.filterNot(_ == l)
    val serviceProps = Vector("name", "price", "duration", "bookable")
    Some(focus).filter(_.startsWith("shop:")).map(_.stripPrefix("shop:")) match
      case Some(id) =>
        val src = Scope.Source(id)
        val shop = Ent(id, "Shop", id)
        Facts.prefetch[R](Vector(Query("Shop", scope = src, limit = 1, lang = l) -> Vector.empty[String]) ++
          langs.map(x => Query("Service", scope = src, limit = 80, lang = x) -> (if x == l then serviceProps
            else Vector.empty)) ++
          Vector(Query("Master", scope = src, limit = 30, lang = l) -> (Vector("name") ++ Vector("gen", "dat", "acc",
            "ins", "loc").map("name." + _))) ++
          langs.map(x => Query("Place", scope = src, limit = 80, lang = x) -> Vector.empty[String]),
          Vector("hours", "address", "area", "modes", "contact", "rating", "reviews", "url", "name").map(shop -> _))
      case None =>
        Facts.prefetch[R](langs.map(x => Query("Shop", scope = Scope.All, limit = 100, lang = x) ->
          Vector.empty[String]) ++ langs.map(x => Query("Service", scope = Scope.All, limit = 200, lang = x) ->
          (if x == l then serviceProps else Vector.empty)))

  def turn[R[_]](lane: Lane, s: Setup, c: Conversation, user: String, l: FLang, focus: String)(using In[Facts, R],
    In[Clock, R], In[Audit, R]): Decision ! R =
    Clock.now[R].flatMap { now =>
      prefetch[R](focus, l).flatMap(_ => Bind.focused[R](s, c, focus, l)).flatMap { (h0, note) =>
        val ctx = Ctx(lane, c, h0, user, l, focus, Zone.date(now))
        features[R](ctx).flatMap { (prev, extras) =>
          val route = lane.router.decide(lane.table, user, prev, extras)
          // the day, the time, the price limit said: among the slots whatever the answer
          val said = Map[String, String]() ++ ctx.when.day.map("day" -> _) ++ ctx.when.at.map(a => "at" ->
            clock(a)) ++ ctx.when.from.map(a => "from" -> clock(a)) ++ ctx.when.until.map(a => "until" -> clock(a)) ++
            ctx.amount.max.map(m => "max" -> (m / 100).toString)
          if route.by == "guard" then pure(Decision.Declined(route, "guard"))
          else if route.by == "unknown" then pure(Decision.Declined(route, "no word known"))
          else if !route.confident(lane.minP, lane.minMargin) then pure(Decision.Declined(route, "not confident"))
          else if route.intent == "other" then pure(Decision.Declined(route, "other"))
          else build[R](route.intent, ctx).flatMap {
            case Left((why, slots)) => pure(Decision.Declined(route, why, said ++ slots))
            case Right(b) =>
              // the intent its answer is: a shop's question with no shop in sight answered by a search is a search
              val answered = route.copy(intent = b.intent.getOrElse(route.intent))
              Bind.authored[R](s, c, user, l, focus, b.handles, note, b.answer, b.cards, answered.intent,
                route.p).map {
                case Left(vs) => Decision.Declined(route, "invalid: " + vs.map(_.code).mkString(","),
                  said ++ b.slots)
                case Right((c2, reply)) => Decision.Answered(c2, reply, answered, said ++ b.slots)
              }
          }
        }
      }
    }

  /** the conversation's last question in the user's words */
  private def lastAsked(c: Conversation): Option[String] = c.history.reverseIterator.collectFirst {
    case j if j.field("role").contains(Json.Str("user")) => j.field("content").collect { case Json.Str(t) => t }
  }.flatten

  /** the follow-up's openers ("a jutro?", "and on Saturday?", "а в суботу?") */
  private val openers = Set("a", "а", "and", "i", "і", "и", "й", "та", "ta", "what", "how", "also", "też", "tez",
    "тоже", "також")
  /** "alone": the plain service, not a combination with it ("samo strzyżenie", "the haircut alone") */
  private val aloneWords = Set("samo", "sama", "sam", "tylko", "alone", "only", "just", "тільки", "лише", "сама",
    "само", "только", "одна", "одно", "tilky", "tolko", "bez")

  /** THE EVIDENCE of a turn (the combiner's): its follow-up's previous intent, the host's named evidence */
  private def features[R[_]](ctx: Ctx)(using In[Facts, R]): (Option[String], Map[String, Double]) ! R =
    val ws = Words.split(ctx.user)
    val short = ws.count(_.length >= 3) <= 2 && ws.size <= 5
    val opened = ws.headOption.exists(openers)
    val prev = if short then lastAsked(ctx.c).map(ctx.lane.router.glance(ctx.lane.table, _)).filter(_.by != "unknown")
      .map(_.intent) else None
    def b(x: Boolean): Double = if x then 1.0 else 0.0
    val scope = ctx.shopId.map(Scope.Source(_)).getOrElse(Scope.All)
    for
      services <- named[R]("Service", scope, ctx.l, if ctx.shopId.isDefined then 80 else 200)
      ms <- ctx.shopId.fold(pure[Vector[(Found, String)], R](Vector.empty))(masters[R](_, ctx.l))
      places <- ctx.shopId.fold(pure[Vector[(Found, Vector[String])], R](Vector.empty))(id =>
        named[R]("Place", Scope.Source(id), ctx.l))
      shops <- if ctx.shopId.isDefined then pure(Vector.empty[(Found, Vector[String])])
        else named[R]("Shop", Scope.All, ctx.l, 100)
    yield
      val near = services.flatMap(_._2.map(Nearest.lexical(ctx.user, _, ctx.lane.lexicon))).maxOption.getOrElse(0.0)
      val master = ms.exists((_, n) => n.nonEmpty && Spell.names(ctx.user, n))
      val place = places.exists((_, ns) => ns.exists(Spell.names(ctx.user, _))) || ctx.namesPlace
      val shopNamed = namedShop(ctx, shops).isDefined
      prev -> Map("service" -> b(near >= 0.999), "serviceNear" -> near, "master" -> b(master),
        "person" -> b(!master && unknownPerson(ctx, services)), "place" -> b(place), "day" -> b(ctx.when.day.isDefined),
        "time" -> b(ctx.when.at.isDefined || ctx.when.from.isDefined || ctx.when.until.isDefined),
        "exact" -> b(ctx.when.at.isDefined), "books" -> b(ctx.books), "amount" -> b(!ctx.amount.isEmpty),
        "alone" -> b(ctx.alone), "mall" -> b(ctx.shopId.isEmpty), "shopNamed" -> b(shopNamed), "short" -> b(short),
        "opener" -> b(opened), "context" -> b(lastAsked(ctx.c).isDefined))

  /** a turn's ground: the lane, the conversation, its handles as they stand, the words, where the user is */
  private final case class Ctx(lane: Lane, c: Conversation, h: Handles, user: String, l: FLang, focus: String,
                               today: String, named: Option[okay.facts.Handle] = None):
    def a(key: String): String = lane.answers(key, l)
    def list(key: String, variants: Vector[String]): String = lane.answers.list(key, l, variants)
    def shopId: Option[String] = Some(focus).filter(_.startsWith("shop:")).map(_.stripPrefix("shop:"))
    /** the shop in sight: the focus' own, else the one the conversation talked of last */
    def shop: Option[okay.facts.Handle] = named.orElse(shopId.flatMap(id => h.all.find(x => x.ent.cls == "Shop" &&
      x.ent.source == id)).orElse(if shopId.isEmpty then h.all.reverse.find(x => x.ent.cls == "Service" ||
      x.ent.cls == "Slot").flatMap(x => h.all.find(y => y.ent.cls == "Shop" && y.ent.source == x.ent.source))
      else None))
    def scope: Scope = named.map(x => Scope.Source(x.ent.source)).orElse(shopId.map(Scope.Source(_)))
      .orElse(context.map(x => Scope.Source(x.ent.source))).getOrElse(Scope.All)
    /** the service the conversation spoke of last: one its last answer named (or a free time's service of it), else
     * the last one found — a handle found again keeps its first place, so the last found is not always the last said */
    def context: Option[okay.facts.Handle] =
      val said = c.history.reverseIterator.collectFirst {
        case j if j.field("role").contains(Json.Str("assistant")) =>
          j.field("content").collect { case Json.Str(t) => t }
      }.flatten.toVector.flatMap(t => "#[a-z]+\\d+".r.findAllIn(t).toVector).distinct.flatMap(h.get)
      said.find(_.ent.cls == "Service").orElse(said.find(_.ent.cls == "Slot").flatMap(s => h.all.find(x =>
        x.ent.cls == "Service" && x.ent.source == s.ent.source && x.ent.key == s.ent.key.takeWhile(_ != '|'))))
        .orElse(h.all.reverse.find(_.ent.cls == "Service"))
    /** a follow-up: short, or pointing back ("a ile trwa?", "that one", "тот") */
    def followUp: Boolean =
      val ws = Words.split(user)
      ws.size <= 4 || ws.headOption.exists(Set("a", "а", "and", "i", "и", "і", "й", "to", "ta")) ||
        ws.exists(Set("to", "ten", "ta", "tamten", "tamto", "tamta", "it", "that", "this", "one", "це", "той", "цей",
          "то", "тот", "этот", "это"))
    def rank(cs: Vector[Nearest.Candidate]): Vector[(String, Double)] =
      Nearest.rank(lane.table, user, cs, lane.lexicon)
    lazy val when: When = When.of(user, today)
    /** the persons named: a word in capitals after a person's preposition ("u Ewy", "do Kati", "з Марком", "к Оле",
     * "with Anna"), never a day's ("for Friday") */
    lazy val persons: Vector[String] = user.split("\\s+").toVector.map(_.filter(c => c.isLetter || c == '\''))
      .sliding(2).collect { case Vector(p, w) if Ctx.toPerson(p.toLowerCase) && w.length >= 3 && w.head.isUpper => w }
      .toVector.filter(w => When.of(w, today).day.isEmpty)
    def namesPerson: Boolean = persons.nonEmpty
    /** a booking's word ("zapisz", "book", "запишіть", "забронируйте") */
    lazy val books: Boolean = Words.split(user).exists(w => Seq("zapis", "zarezerw", "umow", "book", "reserv",
      "запис", "запиш", "заброн", "zapish", "zabron", "zamow", "zamaw", "order", "замов", "закаж", "zakaz")
      .exists(w.startsWith))
    /** "alone": the plain service asked, not a combination with it ("samo strzyżenie", "the haircut alone") */
    lazy val alone: Boolean = Words.split(user).exists(aloneWords)
    /** a place named that may be the area's: a capitalised word past the first, or one after a preposition */
    lazy val namesPlace: Boolean =
      val ws = user.split("\\s+").toVector.map(_.filter(_.isLetter)).filter(_.nonEmpty)
      ws.drop(1).exists(w => w.head.isUpper && !persons.contains(w)) || ws.sliding(2).exists {
        case Vector(a, b) => Set("do", "na", "w", "we", "to", "in", "в", "у", "до", "на", "z", "ze", "from", "з", "из")
            .contains(a.toLowerCase) && b.length >= 4 && b.head.isUpper
        case _ => false
      }
    lazy val amount: Amount = Amount.of(user)

  private object Ctx:
    /** the prepositions a person's name follows */
    val toPerson: Set[String] = Set("u", "do", "z", "ze", "dla", "у", "до", "к", "ко", "з", "із", "с", "со", "для",
      "with", "by", "for", "see")

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

  /** a combination's name ("Strzyżenie + broda", "haircut and beard") */
  private def combined(name: String): Boolean =
    name.contains("+") || name.contains("&") || Words.split(name).drop(1).exists(Set("z", "ze", "i", "and", "with",
      "і", "и", "з", "с", "та", "plus"))

  /** the service a message names among the candidates: one, several too close (asked), none */
  private def service(ctx: Ctx, cands: Vector[(Found, Vector[String])]): Nearest.Pick =
    val ranked = ctx.rank(cands.zipWithIndex.map((x, i) =>
      Nearest.Candidate(i.toString, x._2)))
    // the same words of two names ("strzyżenie": men's or children's?): asked, whatever the meaning says
    def words(i: String) = cands(i.toInt)._2.map(Nearest.lexical(ctx.user, _, ctx.lane.lexicon)).maxOption
      .getOrElse(0.0)
    val top = ranked.take(4).map((i, _) => i -> words(i))
    val tied = top.headOption.filter((_, w) => w >= 0.5 && w < 0.999).toVector.flatMap((_, w) =>
      top.filter(x => math.abs(x._2 - w) < 1e-9))
    // "samo strzyżenie", "the haircut alone": the plain one of those the word names — no combination, the shop's first
    val plain = if ctx.alone then top.filter(_._2 >= 0.5).map(_._1).filterNot(i => cands(i.toInt)._2.exists(combined))
      else Vector.empty
    if plain.nonEmpty && top.count(_._2 >= 0.5) >= 2 then Nearest.Pick.One(plain.minBy(_.toInt), 1.0)
    else if tied.size >= 2 && !(ctx.followUp && ctx.context.isDefined) then Nearest.Pick.Several(tied.map(_._1))
    // no word of any name said, and the meaning not close: none named ("koloryzacja" at a barber's), never asked
    else if top.forall(_._2 == 0.0) && ranked.headOption.forall(_._2 < 0.75) then Nearest.Pick.Nothing
    // a follow-up keeps the conversation's service unless it names another by its words
    else Nearest.pick(ranked, if ctx.followUp && ctx.context.isDefined then 0.75 else ctx.lane.serviceAt,
      ctx.lane.serviceLead)

  /** the shop a message names by its name, on the mall's home ("godziny otwarcia Barber Demo", "where is Studio
   * Paznokci Lila?"): its name's words by their rarity among the shops' names (every shop's "demo" weighs little), at
   * least 0.8 of them, and no other as near */
  private def namedShop(ctx: Ctx, shops: Vector[(Found, Vector[String])]): Option[Found] =
    val names = shops.map(_._2.flatMap(Words.split).distinct)
    def weight(w: String): Double = 1.0 / math.max(1, names.count(_.contains(w)))
    val scored = shops.map((f, ns) => f -> ns.map(Nearest.lexical(ctx.user, _, ctx.lane.lexicon, weight)).maxOption
      .getOrElse(0.0)).sortBy(-_._2)
    scored match
      case (f, s) +: rest if s >= 0.8 && rest.headOption.forall(_._2 < s - 0.2) => Some(f)
      case _ => None

  private def build[R[_]](intent: String, ctx: Ctx)(using In[Facts, R], In[Clock, R]): Out ! R =
    // on the mall's home a shop named by its name: its own facts, as on its pages
    if ctx.shopId.isEmpty && ctx.named.isEmpty && (ofShop(intent) || Set("price", "reviews", "compare")(intent)) then
      named[R]("Shop", Scope.All, ctx.l, 100).flatMap(shops => namedShop(ctx, shops) match
        case Some(f) =>
          val (h, x) = ctx.h.issue(ShopFacts.schema.prefix(f.ent.cls), f.ent, f.version, f.visible)
          build0[R](intent, ctx.copy(h = h, named = Some(x)))
        case None => build0[R](intent, ctx))
    else build0[R](intent, ctx)

  private def build0[R[_]](intent: String, ctx: Ctx)(using In[Facts, R], In[Clock, R]): Out ! R =
    // the shops compared by their reviews: a judgement across the mall, the model's
    if intent == "reviews" && ctx.shop.isEmpty then pure(no("reviews with no shop in sight"))
    // a shop's own fact with no shop in sight: a search across the mall when it names something, else the model
    else if ofShop(intent) && ctx.shop.isEmpty && intent != "slots" && intent != "book" then
      search(ctx).map(_.left.map((_, slots) => ("no shop in sight", slots)).map(_.copy(intent = Some("search"))))
    // on the mall's pages, with no shop in the conversation, a service asked of is a search across the mall
    // a price asked there of one service only one shop has: its price (else a search: each shop's)
    else if ctx.scope == Scope.All && intent == "price" then
      named[R]("Service", Scope.All, ctx.l, 200).flatMap(cands => service(ctx, cands) match
        case Nearest.Pick.One(i, _) => priced(ctx, cands(i.toInt)._1)
        case _ => search(ctx).map(_.map(_.copy(intent = Some("search")))))
    else if ctx.scope == Scope.All && Set("slots", "book")(intent) then
      search(ctx).map(_.map(_.copy(intent = Some("search"))))
    else intent match
      case "greet" => pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a(greeting(ctx.user))))))
      case "help" => pure(ok(Built(ctx.h, Answer(Vector.empty, ctx.a("help")))))
      case "hours" => one(ctx, "hours", "hours.week", "h", missing(ctx, "hours.none"))
      case "address" => val sh = ctx.shop.get
        one(ctx, "address", "address.one", "a", has[R](sh.ent, "modes").flatMap(m =>
          if m then pure(ok(Built(ctx.h, Answer(Vector("m" -> s"modes(${sh.name})"), ctx.a("address.modes")))))
          else missing(ctx, "address.none")))
      case "modes" => one(ctx, "modes", "modes.list", "m", missing(ctx, "modes.none"))
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
  /** a shop's one property, or what it says when the shop has not given it */
  private def one[R[_]](ctx: Ctx, prop: String, yes: String, p: String, otherwise: => Out ! R)(using In[Facts, R])
    : Out ! R =
    val sh = ctx.shop.get
    has[R](sh.ent, prop).flatMap(x => if x then pure(ok(Built(ctx.h, Answer(Vector(p -> s"$prop(${sh.name})"),
      ctx.a(yes))))) else otherwise)

  /** WHAT THE SHOP HAS NOT SAID, said honestly (`key`): that it has not, and where to ask — its contact, else its
   * page (`key.page`) */
  private def missing[R[_]](ctx: Ctx, key: String)(using In[Facts, R]): Out ! R =
    val sh = ctx.shop.get
    has[R](sh.ent, "contact").map(e => ok(Built(ctx.h, if e then Answer(Vector("e" -> s"contact(${sh.name})"),
      ctx.a(key)) else Answer(Vector("u" -> s"url(${sh.name})"), ctx.a(s"$key.page")))))

  private def area[R[_]](ctx: Ctx)(using In[Facts, R]): Out ! R =
    val sh = ctx.shop.get
    has[R](sh.ent, "area").flatMap {
      case false => has[R](sh.ent, "modes").map(m => if m then ok(Built(ctx.h, Answer(Vector("m" ->
          s"modes(${sh.name})"), ctx.a("area.none")))) else no("no area")).flatMap(o =>
          if o.isRight then pure(o) else missing(ctx, "area.unknown"))
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
      // no one service, but a master named: that master's free times, whatever the service
      def byMaster(out: Out): Out ! R = ctx.shop.orElse(ctx.h.all.find(_.ent.cls == "Shop")) match
        case None => pure(out)
        case Some(sh) => masters[R](sh.ent.source, ctx.l).flatMap { ms =>
            ms.find((_, n) => n.nonEmpty && Spell.names(ctx.user, n)) match
              // a person named who is not the shop's ("do Kati"): its people listed, not a service asked
              case None if ms.nonEmpty && unknownPerson(ctx, cands) =>
                val (h, names) = issueAll(ctx.h, ms.map(_._1))
                pure(ok(Built(h, Answer(names.zipWithIndex.map((m, k) => s"m${k + 1}" -> s"name($m) as nom"),
                  ctx.list("masters.unknown", names.map(_ => "item"))), slots = Map("master" -> "none"))))
              case None => pure(out)
              case Some((m, _)) => masterSlots(ctx, sh.ent.source, m)
          }
      picked match
        case Left(out) => byMaster(out)
        case Right(f) => masters[R](f.ent.source, ctx.l).flatMap { ms =>
            val asked = ms.filter((_, n) => n.nonEmpty && Spell.names(ctx.user, n))
            if asked.isEmpty && unknownPerson(ctx, cands) && ms.nonEmpty then
              val (h, names) = issueAll(ctx.h, ms.map(_._1))
              pure(ok(Built(h, Answer(names.zipWithIndex.map((m, k) => s"m${k + 1}" -> s"name($m) as nom"),
                ctx.list("masters.unknown", names.map(_ => "item"))), slots = Map("master" -> "none"))))
            else slotsOf(ctx, f, asked.headOption.map(_._1), booking)
          }
    }

  /** a person named past the first word, in capitals, who is no service's name */
  private def unknownPerson(ctx: Ctx, cands: Vector[(Found, Vector[String])]): Boolean =
    ctx.persons.exists(w => !cands.exists(_._2.exists(n => Spell.names(w, n))))

  /** a master's free times of every service they do (each time once, with its service) */
  private def masterSlots[R[_]](ctx: Ctx, source: String, m: Found)(using In[Facts, R]): Out ! R =
    val w = ctx.when
    val filters = Vector(Filter("master", Op.Eq, Value.Entity(m.ent))) ++
      w.day.map(d => Filter("date", Op.Eq, Value.Day(d))) ++
      w.at.map(a => Filter("time", Op.Eq, Value.Clock(a))) ++ w.from.map(a => Filter("time", Op.Ge, Value.Clock(a))) ++
      w.until.map(a => Filter("time", Op.Lt, Value.Clock(a)))
    Facts.find[R](Query("Slot", filters = filters, scope = Scope.Source(source), limit = 40, lang = ctx.l)).flatMap {
      found =>
        // each start once (the first service of it)
        val once = found
        seq[R, Found, Option[Long]](once)(s => Facts.get[R](s.ent, "time").map(_.collect {
          case okay.facts.Fact(Value.At(t), _, _, _, _) => t })).map { times =>
          val kept = once.zip(times).groupBy(_._2).toVector.sortBy(_._1).flatMap(_._2.headOption).map(_._1).take(6)
          val (h1, mm) = issue(ctx.h, m)
          if kept.isEmpty then ok(Built(h1, Answer(Vector("m" -> s"name($mm) as nom"), ctx.a("slots.masterless")),
            slots = Map("master" -> m.ent.key)))
          else
            val (h2, ts) = issueAll(h1, kept)
            ok(Built(h2, Answer(("m" -> s"name($mm) as nom") +: ts.zipWithIndex.flatMap((t, k) => Vector(
              s"t${k + 1}" -> s"time($t) as datetime", s"s${k + 1}" -> s"name(service($t))")),
              ctx.list("slots.master", ts.map(_ => "item"))), slots = Map("master" -> m.ent.key)))
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
          val ranked = ctx.rank(cands.zipWithIndex.map((x, i) =>
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
        val ranked = ctx.rank(cands.zipWithIndex.map((x, i) =>
          Nearest.Candidate(i.toString, x._2)))
        val best = ranked.headOption.map(_._2).getOrElse(0.0)
        val near = ranked.filter(r => r._2 >= ctx.lane.serviceAt && r._2 >= best - 0.12).map(r => cands(r._1.toInt)._1)
        val cheap: Set[Ent] ! R = ctx.amount.max match
          case None => pure(near.map(_.ent).toSet)
          case Some(m) => Facts.find[R](Query("Service", filters = Vector(Filter("price", Op.Le,
              Value.Money(m, "PLN"))),
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
            val byKind = ctx.rank(shops.filter(x => inCity(x._1.ent.source)).map((f,
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
