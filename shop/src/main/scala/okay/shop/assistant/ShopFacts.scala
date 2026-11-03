package okay.shop.assistant

import okay.facts.{Acted, Card, Cls, Ent, Facts, Fact, Filter, Found, Lang as FLang, Noun, Op, Prop, Query,
  Render, Row, Schema, Scope, Table, Type, Value, Zone}
import okay.scala3.*
import okay.scala3.std.Clock
import okay.shop.app.Bookings
import okay.shop.booking.{Slot, Slots}
import okay.shop.effects.Store
import okay.shop.html.Conf
import okay.shop.model.*

/** what the host knows of a shop that its content does not say: its category in the four languages (the mall's
 * niche), its city (the mall's directory); the version of its config */
final case class About(category: Map[FLang, String] = Map.empty, city: String = "", version: Long = 0L)

/**
 * THE SHOP'S FACTS (specs/shop.md "The assistant"): the schema — schema.org's words with the overlay's keys and
 * cardinalities —, the rows of the shop's data, the free slots computed by the resolver, and `Facts` answered over
 * them: find, get, and the actions `open` and `book` (a card the client confirms). The model sees handles and the
 * visible words only; every value is rendered from here
 */
object ShopFacts:
  val schema: Schema = Schema(Vector(
    Cls("Shop", "shop", Vector(
      Prop("name", Type.Text, visible = true), Prop("category", Type.Text, visible = true),
      Prop("city", Type.Text, visible = true), Prop("address", Type.Text, about = "street, postcode and city"),
      Prop("url", Type.Url, about = "its storefront"), Prop("hours", Type.Text, about = "its opening hours"),
      Prop("rating", Type.Number, about = "its reviews' average, of 5"),
      Prop("reviews", Type.Count(Noun.review), about = "how many reviews")),
      key = Vector("id"), iri = "https://schema.org/LocalBusiness", about = "a shop"),
    Cls("Service", "v", Vector(
      Prop("name", Type.Text, visible = true), Prop("description", Type.Text, visible = true),
      Prop("price", Type.Money, about = "what it costs"), Prop("duration", Type.Duration, about = "how long"),
      Prop("bookable", Type.Bool, about = "booked online for a time"), Prop("shop", Type.Entity("Shop")),
      Prop("masters", Type.Entity("Master"), functional = false, about = "who does it")),
      key = Vector("shop", "key"), iri = "https://schema.org/Service", about = "a service, with its price"),
    Cls("Master", "m", Vector(
      Prop("name", Type.Text), Prop("shop", Type.Entity("Shop")),
      Prop("services", Type.Entity("Service"), functional = false)),
      key = Vector("shop", "key"), iri = "https://schema.org/Person", about = "a person doing the services"),
    Cls("Slot", "s", Vector(
      Prop("name", Type.Text, visible = true, about = "its service's name"),
      Prop("time", Type.Time, about = "when it starts"), Prop("date", Type.Date, about = "its day"),
      Prop("price", Type.Money, about = "its service's price"), Prop("duration", Type.Duration, about = "how long"),
      Prop("available", Type.Bool, about = "still free"), Prop("master", Type.Entity("Master")),
      Prop("service", Type.Entity("Service")), Prop("shop", Type.Entity("Shop"))),
      key = Vector("shop", "service", "master", "start"),
      about = "a free start of a service (find it by its service and day; never listed whole)"),
    Cls("Review", "r", Vector(
      Prop("rating", Type.Number), Prop("text", Type.Text), Prop("shop", Type.Entity("Shop"))),
      iri = "https://schema.org/Review", about = "a client's review"),
    Cls("Booking", "b", Vector(
      Prop("name", Type.Text, about = "its service's name"), Prop("time", Type.Time), Prop("price", Type.Money),
      Prop("master", Type.Text), Prop("ref", Type.Text), Prop("manage", Type.Url), Prop("shop", Type.Entity("Shop"))),
      about = "a booking made in this chat: its values as booked")))

  def lang(l: Lang): FLang = FLang.parse(l.code).getOrElse(FLang.pl)
  def shopLang(l: FLang): Lang = Lang.parse(l.toString).getOrElse(Lang.pl)
  def text(t: T): Value = Value.Text(Map(FLang.pl -> t.pl, FLang.uk -> t.uk, FLang.ru -> t.ru, FLang.en -> t.en))

  private val days: Map[FLang, Vector[String]] = Map(
    FLang.pl -> Vector("pon", "wt", "śr", "czw", "pt", "sob", "nd"),
    FLang.uk -> Vector("пн", "вт", "ср", "чт", "пт", "сб", "нд"),
    FLang.ru -> Vector("пн", "вт", "ср", "чт", "пт", "сб", "вс"),
    FLang.en -> Vector("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"))

  /** the week as text in each language: `pon 10:00–18:00; wt 10:00–18:00` */
  private def hours(week: Map[Int, Vector[Span]]): Value =
    Value.Text(FLang.values.map(l => l -> (1 to 7).flatMap(d => week.get(d).filter(_.nonEmpty).map(ss =>
      s"${days(l)(d - 1)} ${ss.map(s => s"${Span.label(s.from)}–${Span.label(s.to)}").mkString(", ")}"))
      .mkString("; ")).toMap)

  def shopEnt(source: String): Ent = Ent(source, "Shop", source)

  /** the shop's rows: itself, its shown services, its masters, its shown reviews */
  def rows(c: Conf, source: String, about: About): Vector[Row] =
    val site = c.site
    val shop = shopEnt(source)
    val location = site.work.flatMap(_.locations.headOption).map(_.address)
    val city = if about.city.nonEmpty then about.city else location.fold("")(_.city)
    val v = about.version
    val week = site.work.flatMap(_.locations.headOption).map(_.hours).filter(_.nonEmpty)
      .orElse(site.schedule.map(_.week))
    val shopRow = Row(shop, Map[String, Value]("name" -> Value.text(site.name), "url" -> Value.Url(
      s"${c.origin}/${site.lang.code}/")) ++
      Option.when(about.category.nonEmpty)("category" -> Value.Text(about.category)) ++
      Option.when(city.nonEmpty)("city" -> Value.text(city)) ++
      location.map(a => "address" -> Value.text(s"${a.street}, ${a.postcode} ${a.city}")) ++
      week.filter(_.nonEmpty).map(w => "hours" -> hours(w)) ++
      site.rated.filter(_.count > 0).toVector.flatMap(r => Vector("rating" -> Value.Num(BigDecimal(r.average)
        .setScale(1, BigDecimal.RoundingMode.HALF_UP)), "reviews" -> Value.Count(r.count, Noun.review))), v)
    val services = site.shown.map { o =>
      val masters = if site.masters.isEmpty then Vector.empty else site.masters.filter(m => m.services.isEmpty ||
        m.services.contains(o.key))
      Row(Ent(source, "Service", o.key), Map[String, Value]("name" -> text(o.name),
        "description" -> text(o.description),
        "bookable" -> Value.Bool(site.books(o)), "shop" -> Value.Entity(shop)) ++
        Option.when(o.priced && !o.quote)("price" -> Value.Money(o.price, "PLN")) ++
        Option.when(o.minutes > 0)("duration" -> Value.Minutes(o.minutes)) ++
        Option.when(masters.nonEmpty)("masters" -> Value.Many(masters.map(m => Value.Entity(Ent(source, "Master",
          m.key))))), v)
    }
    val masters = site.masters.map(m => Row(Ent(source, "Master", m.key), Map("name" -> Value.text(m.name),
      "shop" -> Value.Entity(shop), "services" -> Value.Many(site.shown.filter(o => m.services.isEmpty ||
        m.services.contains(o.key)).map(o => Value.Entity(Ent(source, "Service", o.key))))), v))
    val reviews = site.rated.toVector.flatMap(_.latest).filter(_.shown).map(r => Row(Ent(source, "Review", r.id),
      Map("rating" -> Value.Num(r.rating), "text" -> Value.text(r.text), "shop" -> Value.Entity(shop)), v))
    (shopRow +: services) ++ masters ++ reviews

  /** a slot's key: `service|master|start` */
  def slotKey(service: String, master: String, start: Long): String = s"$service|$master|$start"
  def slotOf(key: String): Option[(String, String, Long)] = key.split('|') match
    case Array(s, m, t) => t.toLongOption.map((s, m, _))
    case _ => None

  /** THE FREE SLOTS of services over days, as rows: each eligible master's own starts (the service's default mode and
   * place), a class's occurrences with seats left; in time's order */
  def slots(c: Conf, source: String, services: Vector[String], dates: Set[String], taken: Vector[Booking], now: Long,
            v: Long): Vector[Row] =
    val site = c.site
    services.flatMap(key => Bookings.plan(c, key, "", "").toOption.toVector.flatMap { p =>
      val o = p.offer
      val starts: Vector[(String, Slot)] = Bookings.masters(c, p, "") match
        case Right(who) if o.isClass =>
          val m = who.flatMap(_._1.headOption).fold(p.schedule.master)(_._1)
          Slots.classes(p.schedule, o, taken, now).filter(d => dates(d.date)).flatMap(_.slots).map(m -> _)
        case Right(None) => Slots.free(p.schedule, o.minutes, taken, now, p.before, p.after).filter(d => dates(d.date))
            .flatMap(_.slots).map(p.schedule.master -> _)
        case Right(Some((cs, keys))) => cs.flatMap((k, sch) => Slots.free(sch, o.minutes, Masters.taken(taken, k, keys),
            now, p.before, p.after).filter(d => dates(d.date)).flatMap(_.slots).map(k -> _))
        case Left(_) => Vector.empty
      starts.map((m, s) => Row(Ent(source, "Slot", slotKey(key, m, s.start)), Map[String, Value](
        "name" -> text(o.name), "time" -> Value.At(s.start), "date" -> Value.Day(Day.of(s.start)),
        "available" -> Value.Bool(true), "service" -> Value.Entity(Ent(source, "Service", key)),
        "shop" -> Value.Entity(shopEnt(source))) ++ Option.when(p.price > 0 && !o.quote)("price" -> Value.Money(p.price,
        "PLN")) ++ Option.when(o.minutes > 0)("duration" -> Value.Minutes(o.minutes)) ++
        Option.when(site.masters.exists(_.key == m))("master" -> Value.Entity(Ent(source, "Master", m))), v, now))
    }).sortBy(r => r.values("time") match
      case Value.At(t) => t
      case _ => 0L)

  private def days(now: Long, n: Int): Set[String] =
    val today = Day.of(now)
    (0 until n).map(i => Zone.plus(today, i)).toSet

  /** the services a slot query is of (its `service = …` filters, else every bookable one shown) and its days (its
   * `date = …` filters, else a week) */
  private def slotQuery(c: Conf, source: String, q: Query, now: Long): (Vector[String], Set[String]) =
    val services = q.filters.collect {
      case Filter("service", Op.Eq, Value.Entity(e)) if e.source == source => Vector(e.key)
      case Filter("service", Op.Eq, Value.Many(es)) =>
        es.collect { case Value.Entity(e) if e.source == source => e.key }
    }.flatten.distinct
    val dates = q.filters.collect { case Filter("date", Op.Eq, Value.Day(d)) => d }
    // a service asked of (none of this shop's: no slot here), else every bookable one
    (if q.filters.exists(_.prop == "service") then services else c.site.shown.filter(c.site.books).map(_.key),
      if dates.nonEmpty then dates.toSet else days(now, 7))

  /** whether a query looks at this source */
  def mine(source: String, q: Query): Boolean = q.scope match
    case Scope.Source(id) => id == source
    case _ => true

  /** THE SHOP'S `Facts`: find over the rows and the slots, a property read now, `open` and `book` */
  def facts[R[_], Rest[_], A](c: Conf, source: String, about: About)(p: A ! R)(using Split[Facts, R, Rest],
    In[Store, Rest], In[Clock, Rest]): A ! Rest =
    def bookings(now: Long): Vector[Booking] ! Rest =
      Store.bookings[Rest](now - 86400000L, now + (c.site.schedule.fold(28)(_.horizon) + 2) * 86400000L)
    def slotRow(key: String, now: Long): Option[Row] ! Rest = slotOf(key) match
      case None => pure(None)
      case Some((service, _, start)) => bookings(now).map(taken =>
        slots(c, source, Vector(service), Set(Day.of(start)), taken, now, about.version).find(_.ent.key == key)
          .orElse(c.site.offer(service).map(o => Row(Ent(source, "Slot", key), Map("name" -> text(o.name),
            "time" -> Value.At(start), "date" -> Value.Day(Day.of(start)), "available" -> Value.Bool(false),
            "service" -> Value.Entity(Ent(source, "Service", service)), "shop" -> Value.Entity(shopEnt(source))),
            about.version, now))))
    handle(p, Handler.general[Facts, Rest, A, A](pure)([X] => (op: Facts[X], k: X => A ! Rest) => op match
      case Facts.Find(q) if !mine(source, q) => k(Vector.empty[Found])
      case Facts.Find(q) if schema.isA(q.cls, "Slot") || q.cls == "Slot" => Clock.now[Rest].flatMap(now =>
          bookings(now).flatMap { taken =>
            val (services, dates) = slotQuery(c, source, q, now)
            k(Table.find(schema, slots(c, source, services, dates, taken, now, about.version), q))
          })
      case Facts.Find(q) => k(Table.find(schema, rows(c, source, about), q))
      case Facts.Get(ent, prop) if ent.cls == "Slot" => Clock.now[Rest].flatMap(now => slotRow(ent.key, now)
          .flatMap(r => k(r.flatMap(x => x.values.get(prop).map(v => Fact(v, ent, prop, x.version, x.updatedAt))))))
      case Facts.Get(ent, prop) => k(Table.get(rows(c, source, about), ent, prop))
      case Facts.Act("open", args, l) => k(args.get("shop") match
          case Some(Value.Entity(e)) if e.source == source =>
            Right(Acted(Some(e), card = Some(Card.Open(s"${c.origin}/${shopLang(l).code}/", c.site.name))))
          case _ => Left("open takes this shop's handle"))
      case Facts.Act("book", args, l) => args.get("slot") match
          case Some(Value.Entity(e)) if e.source == source && e.cls == "Slot" => Clock.now[Rest].flatMap(now =>
              slotRow(e.key, now).flatMap {
                case Some(r) if r.values.get("available").contains(Value.Bool(true)) => k(Right(Acted(card = Some(
                  card(c, e.key, r, l)))))
                case Some(_) => k(Left("taken: this time is no longer free"))
                case None => k(Left("no such slot"))
              })
          case _ => k(Left("book takes a slot's handle"))
      case Facts.Act(name, _, _) => k(Left(s"no action $name"))))

  /** a booking's card: the service, the day and time, the duration and price, the master — rendered here */
  def card(c: Conf, key: String, slot: Row, l: FLang): Card =
    val (service, master, start) = slotOf(key).get
    val o = c.site.offer(service)
    val price = for x <- o; p <- Bookings.plan(c, service, "", "").toOption if p.price > 0 yield
      Render.money(p.price, "PLN", l)
    Card.Book(key, o.fold(service)(x => x.name(shopLang(l))), Vector(Render(Value.At(start), l, "datetime")) ++
      o.filter(_.minutes > 0).map(x => Render.duration(x.minutes, l)) ++ price ++
      c.site.masters.find(_.key == master).map(_.name))

  /**
   * A BOOKING CARD CONFIRMED (specs/shop.md "The assistant"): through `Bookings.chat` (the form's checks, the limits,
   * the store's step, the letters); the booking's values pinned as booked, and its confirmation rendered here from
   * them — a fixed sentence of the four languages, each value a chip — never the model's
   */
  def confirm[R[_]](c: Conf, source: String, token: String, name: String, contact: String, consent: Boolean,
                    l: FLang, ip: String, conversation: String)(using In[Store, R], In[okay.shop.effects.Mail, R],
    In[okay.shop.effects.Ids, R], In[Clock, R], In[okay.shop.effects.Notify, R])
    : Either[(String, Int), (Booking, Acted, okay.facts.Rendered)] ! R =
    slotOf(token) match
      case None => pure(Left(("slot", 400)))
      case Some((service, master, start)) =>
        Bookings.chat[R](c, Map("service" -> service, "master" -> (if c.site.masters.exists(_.key == master) then
          master else ""), "start" -> start.toString, "name" -> name, "contact" -> contact,
          "rodo_consent" -> (if consent then "on" else ""), "lang" -> shopLang(l).code), ip, conversation)
          .map(_.map(b => confirmation(c, source, b, l)))

  /** the booking's facts, pinned, and the confirmation of them */
  def confirmation(c: Conf, source: String, b: Booking, l: FLang): (Booking, Acted, okay.facts.Rendered) =
    import okay.facts.{Answer, Chip, Icu}
    val ent = Ent(source, "Booking", b.ref)
    val service = c.site.offer(b.service).fold(Value.text(b.service))(o => text(o.name))
    val masterName = c.site.masters.find(_.key == b.master).map(m => Value.text(m.name))
    val price = Option.when(b.price > 0)(b.price).orElse(c.site.offer(b.service).filter(_.priced).map(_.price))
      .map(Value.Money(_, "PLN"))
    val pinned: Map[String, Fact] = (Map[String, Value]("name" -> service, "time" -> Value.At(b.start),
      "ref" -> Value.text(b.ref), "manage" -> Value.Url(okay.shop.html.Letters.manageLink(c, b)),
      "shop" -> Value.Entity(shopEnt(source))) ++ masterName.map("master" -> _) ++ price.map("price" -> _))
      .map((k, v) => k -> Fact(v, ent, k, 1L, b.placed))
    def chip(k: String, hint: String = ""): (String, Chip) =
      val f = pinned(k)
      k -> Chip(k, Render(f.value, l, hint), Value.typeOf(f.value).show, None, Render(service, l), Vector(f),
        f.value match
          case Value.Url(u) => Some(u)
          case _ => None)
    val chips = Vector(chip("name"), chip("time", "datetime"), chip("ref"), chip("manage")) ++
      masterName.map(_ => chip("master")) ++ price.map(_ => chip("price"))
    val (said, number, change) = l match
      case FLang.pl => ("Zarezerwowano", "Numer rezerwacji", "Zmiana lub odwołanie")
      case FLang.uk => ("Заброньовано", "Номер бронювання", "Змінити або скасувати")
      case FLang.ru => ("Забронировано", "Номер брони", "Изменить или отменить")
      case FLang.en => ("Booked", "Booking number", "Change or cancel")
    val template = s"$said: {name}, {time}" + masterName.fold("")(_ => " — {master}") +
      price.fold("")(_ => ", {price}") + s". $number: {ref}. $change: {manage}"
    val byName = chips.toMap
    val parts = Icu.parse(template).toOption.get
    val segs = Icu.format(parts, l, n => byName(n).text, _ => None, _ => "other")
    val acted = Acted(Some(ent), pinned, Vector("name" -> Render(service, l)))
    (b, acted, okay.facts.Rendered(segs, chips.map(_._2), Answer(Vector.empty, template)))
