package okay.shop.model

import okay.effects.json.Json

/** HOW A SERVICE MEETS ITS CLIENT (specs/shop.md "How the work happens"): at the shop, at the client's, by parcel,
 * online. Each with its words in the four languages */
enum Mode(val key: String, val label: T):
  case AtShop extends Mode("atShop", T("У нас", "U nas", "At our place", "У нас"))
  case AtClient extends Mode("atClient", T("Выезд к клиенту", "Dojazd do klienta", "We come to you",
    "Виїзд до клієнта"))
  case ByMail extends Mode("byMail", T("Посылкой", "Wysyłka", "By parcel", "Посилкою"))
  case Online extends Mode("online", T("Онлайн", "Online", "Online", "Онлайн"))
  /** a mode with a time in the calendar: all but the parcel */
  def booked: Boolean = this != ByMail

object Mode:
  def parse(s: String): Option[Mode] = values.find(_.key == s.trim)

/** AN ADDRESS of a meeting: a location's (its name, its map link, its notes) or a client's (street, postcode, city,
 * notes like the floor or the entrance) */
final case class Address(name: String, street: String, postcode: String, city: String, notes: String = "",
                         map: String = ""):
  /** `Salon, ul. Testowa 1, 00-001 Warszawa` */
  def line: String = Vector(name, street, s"$postcode $city".trim).filter(_.nonEmpty).mkString(", ")
  /** its own map link, else a map search of it (no key needed) */
  def mapLink: String =
    if map.nonEmpty then map
    else "https://www.google.com/maps/search/?api=1&query=" +
      okay.http.Query.encode(Vector(street, s"$postcode $city".trim).filter(_.nonEmpty).mkString(", "))

/** a place the client comes to: its key, its address, its hours (none: the shop's) */
final case class Location(key: String, address: Address, hours: Map[Int, Vector[Span]] = Map.empty)

/**
 * WHERE THE MASTER GOES: cities or districts by name and postcode prefixes (either matching is inside), the travel
 * fee (grosze), the buffer reserved before and after a visit (minutes), the visits' hours (none: the shop's)
 */
final case class Area(places: Vector[String], postcodes: Vector[String], fee: Long = 0, before: Int = 0,
                      after: Int = 0, hours: Map[Int, Vector[Span]] = Map.empty):
  /** whether a client's city and postcode are inside: the city one of the places (or starting with one and a space
   * or hyphen: `Warszawa-Mokotów`), diacritics and case aside; or the postcode starting with a prefix */
  def inside(city: String, postcode: String): Boolean =
    val c = Work.fold(city)
    val p = postcode.trim
    places.map(Work.fold).exists(x => x.nonEmpty && (c == x || c.startsWith(x + " ") || c.startsWith(x + "-"))) ||
      postcodes.exists(x => x.nonEmpty && p.startsWith(x))

/** a call instead of a meeting: `video` or `phone`, and what the confirmation says (a link, "we call you") */
final case class Online(how: String, details: String = "")

/** a service's own: the modes it allows (none: all the shop's), a price difference per mode (grosze), its minutes
 * when the content gives none */
final case class ServiceWork(modes: Vector[Mode] = Vector.empty, extra: Map[Mode, Long] = Map.empty, minutes: Int = 0,
                             quote: Boolean = false, seats: Int = 0, times: Vector[(Int, Int)] = Vector.empty)

/** HOW A SHOP WORKS: its modes, its locations, its area, its online calls, its services' own */
final case class Work(modes: Vector[Mode], locations: Vector[Location] = Vector.empty, area: Option[Area] = None,
                      online: Option[Online] = None, services: Map[String, ServiceWork] = Map.empty):
  def has(m: Mode): Boolean = modes.contains(m)
  /** the modes of a service: its own, within the shop's */
  def modesOf(key: String): Vector[Mode] =
    services.get(key).map(_.modes).filter(_.nonEmpty).fold(modes)(own => modes.filter(own.contains))
  def extra(key: String, m: Mode): Long = services.get(key).flatMap(_.extra.get(m)).getOrElse(0L)
  def location(key: String): Option[Location] = locations.find(_.key == key)

object Work:
  private val folded: Map[Char, Char] = Map('ą' -> 'a', 'ć' -> 'c', 'ę' -> 'e', 'ł' -> 'l', 'ń' -> 'n', 'ó' -> 'o',
    'ś' -> 's', 'ź' -> 'z', 'ż' -> 'z', 'ё' -> 'е', 'й' -> 'и', 'ї' -> 'і', 'є' -> 'е', 'ґ' -> 'г')
  /** a place's name compared: lower case, Polish and Cyrillic diacritics off, spaces as one */
  def fold(s: String): String = s.trim.toLowerCase.map(c => folded.getOrElse(c, c)).replaceAll("\\s+", " ")

  val days: Vector[String] = Vector("mon", "tue", "wed", "thu", "fri", "sat", "sun")
  private val postcode = "[0-9]{2}-[0-9]{3}"

  // ---- the JSON (the content's ```json work``` block, a host's settings)

  private def zl(g: Long): String = if g % 100 == 0 then (g / 100).toString else f"${g / 100}.${g % 100}%02d"
  private def hoursJson(h: Map[Int, Vector[Span]]): Json = Json.Arr(h.toVector.sortBy(_._1).flatMap((d, ss) =>
    ss.map(s => Json.obj("day" -> Json.str(days(d - 1)), "from" -> Json.str(Span.label(s.from)),
      "to" -> Json.str(Span.label(s.to))))))

  def toJson(w: Work): Json.Obj = Json.obj("modes" -> Json.Arr(w.modes.map(m => Json.str(m.key))),
    "locations" -> Json.Arr(w.locations.map(l => Json.obj("key" -> Json.str(l.key),
      "name" -> Json.str(l.address.name), "street" -> Json.str(l.address.street),
      "postcode" -> Json.str(l.address.postcode), "city" -> Json.str(l.address.city),
      "map" -> Json.str(l.address.map), "notes" -> Json.str(l.address.notes), "hours" -> hoursJson(l.hours)))),
    "area" -> w.area.fold(Json.Null)(a => Json.obj("places" -> Json.Arr(a.places.map(Json.str)),
      "postcodes" -> Json.Arr(a.postcodes.map(Json.str)), "fee" -> Json.str(zl(a.fee)),
      "before" -> Json.num(a.before), "after" -> Json.num(a.after), "hours" -> hoursJson(a.hours))),
    "online" -> w.online.fold(Json.Null)(o => Json.obj("how" -> Json.str(o.how), "details" -> Json.str(o.details))),
    "services" -> Json.Obj(w.services.toVector.sortBy(_._1).map((k, s) => k -> Json.obj(
      "modes" -> Json.Arr(s.modes.map(m => Json.str(m.key))),
      "extra" -> Json.Obj(s.extra.toVector.sortBy(_._1.ordinal).map((m, g) => m.key -> Json.str(zl(g)))),
      "minutes" -> Json.num(s.minutes), "quote" -> Json.bool(s.quote), "seats" -> Json.num(s.seats),
      "times" -> Json.str(Work.timesText(s.times))))))

  /**
   * A SHOP'S WORK FROM ITS JSON, checked whole against its services' keys: the work, or the name of its first wrong
   * field (`modes`, `locations`, `location.name`, `location.city`, `location.postcode`, `location.map`, `hours`,
   * `area`, `area.fee`, `area.buffer`, `online`, `service`, `service.modes`, `service.extra`, `service.minutes`, …)
   */
  def fromJson(j: Json, offers: Set[String]): Either[String, Work] =
    def obj(x: Json): Option[Json.Obj] = x match
      case o: Json.Obj => Some(o)
      case _ => None
    def str(o: Json.Obj, k: String): String = o.get(k).collect { case Json.Str(v) => v.replaceAll("\\s+", " ").trim
      case Json.Num(n) => if n.isWhole then n.toLong.toString else n.toString }.getOrElse("")
    def arr(o: Json.Obj, k: String): Vector[Json] = o.get(k).collect { case Json.Arr(xs) => xs }
      .getOrElse(Vector.empty)
    def strs(o: Json.Obj, k: String): Vector[String] = arr(o, k).collect { case Json.Str(v) => v.trim }
      .filter(_.nonEmpty)
    def int(o: Json.Obj, k: String): Option[Int] = o.get(k) match
      case None | Some(Json.Null) => Some(0)
      case Some(Json.Num(n)) if n.isWhole => Some(n.toInt)
      case Some(Json.Str(s)) if s.trim.isEmpty => Some(0)
      case Some(Json.Str(s)) => s.trim.toIntOption
      case _ => None
    def money(o: Json.Obj, k: String): Option[Long] =
      if str(o, k).isEmpty then Some(0L) else Money.parse(str(o, k)).filter(_ <= 1000000L)
    def within(s: String, n: Int) = s.length <= n
    def first[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
      xs.collectFirst { case Left(why) => Left(why) }.getOrElse(Right(xs.collect { case Right(a) => a }))
    def modes(o: Json.Obj, k: String): Either[String, Vector[Mode]] =
      val ms = strs(o, k).map(Mode.parse)
      if ms.exists(_.isEmpty) then Left(k) else Right(ms.flatten.distinct.sortBy(_.ordinal))
    def hours(o: Json.Obj): Either[String, Map[Int, Vector[Span]]] =
      val hs = arr(o, "hours")
      if hs.size > 21 then Left("hours")
      else first(hs.map(h => obj(h).flatMap(x => for
        d <- Some(days.indexOf(str(x, "day").toLowerCase) + 1).filter(_ > 0)
        a <- Span.minutes(str(x, "from")); b <- Span.minutes(str(x, "to")) if a < b && b <= 24 * 60
      yield d -> Span(a, b)).toRight("hours"))).map(_.groupMap(_._1)(_._2).map((d, ss) => d -> ss.sortBy(_.from)))
    def location(x: Json, i: Int): Either[String, Location] = obj(x).toRight("locations").flatMap { o =>
      val key = Some(str(o, "key")).filter(_.nonEmpty).getOrElse(s"l${i + 1}")
      val a = Address(str(o, "name"), str(o, "street"), str(o, "postcode"), str(o, "city"), str(o, "notes"),
        str(o, "map"))
      if !key.matches("[a-z0-9][a-z0-9-]{0,29}") then Left("location.key")
      else if a.name.isEmpty || !within(a.name, 80) then Left("location.name")
      else if !within(a.street, 120) then Left("location.street")
      else if a.city.isEmpty || !within(a.city, 60) then Left("location.city")
      else if a.postcode.nonEmpty && !a.postcode.matches(postcode) then Left("location.postcode")
      else if a.map.nonEmpty && (!a.map.startsWith("https://") || !within(a.map, 500) || a.map.exists(c =>
        c.isWhitespace || c == '"' || c == '<' || c == '>')) then Left("location.map")
      else if !within(a.notes, 200) then Left("location.notes")
      else hours(o).map(h => Location(key, a, h))
    }
    def area(o: Json.Obj): Either[String, Area] =
      val places = strs(o, "places")
      val prefixes = strs(o, "postcodes")
      if places.size > 50 || places.exists(!within(_, 60)) then Left("area.places")
      else if prefixes.size > 50 || prefixes.exists(!_.matches("[0-9]{1,2}(-[0-9]{0,3})?")) then Left("area.postcodes")
      else if places.isEmpty && prefixes.isEmpty then Left("area")
      else (money(o, "fee"), int(o, "before"), int(o, "after")) match
        case (None, _, _) => Left("area.fee")
        case (_, Some(b), Some(a)) if b >= 0 && b <= 240 && a >= 0 && a <= 240 =>
          hours(o).map(h => Area(places, prefixes, money(o, "fee").get, b, a, h))
        case _ => Left("area.buffer")
    def service(k: String, x: Json, shop: Vector[Mode]): Either[String, ServiceWork] =
      obj(x).toRight("service").flatMap { o =>
        val extra = o.get("extra").flatMap(obj).map(_.fields).getOrElse(Vector.empty)
        if !offers(k) then Left("service")
        else modes(o, "modes").left.map(_ => "service.modes").flatMap { ms =>
          if ms.exists(!shop.contains(_)) then Left("service.modes")
          else first(extra.map((m, v) => (Mode.parse(m), v match
            case Json.Str(s) => Money.parse(s)
            case Json.Num(n) => Money.parse(n.toString)
            case _ => None) match
            case (Some(mode), Some(g)) if g >= 0 && g <= 1000000L => Right(mode -> g)
            case _ => Left("service.extra"))).flatMap { ex =>
            int(o, "minutes").filter(m => m == 0 || (m >= 5 && m <= 600)).toRight("service.minutes")
              .flatMap(mins => int(o, "seats").filter(n => n >= 0 && n <= 200).toRight("service.seats").flatMap(
                seats => times(str(o, "times")).toRight("service.times").map(ts =>
                  ServiceWork(ms, ex.filter(_._2 > 0).toMap, mins, o.get("quote").contains(Json.Bool(true)), seats,
                    ts))))
          }
        }
      }
    obj(j).toRight("work").flatMap { o =>
      for
        ms <- modes(o, "modes").filterOrElse(_.nonEmpty, "modes")
        locs <- first(arr(o, "locations").zipWithIndex.map(location))
        _ <- Either.cond(locs.size <= 10 && locs.map(_.key).distinct.size == locs.size, (), "locations")
        _ <- Either.cond(!ms.contains(Mode.AtShop) || locs.nonEmpty, (), "locations")
        ar <- o.get("area").flatMap(obj) match
          case Some(a) if ms.contains(Mode.AtClient) => area(a).map(Some(_))
          case None if ms.contains(Mode.AtClient) => Left("area")
          case _ => Right(None)
        on <- o.get("online").flatMap(obj) match
          case Some(x) if ms.contains(Mode.Online) =>
            if !Set("video", "phone")(str(x, "how")) then Left("online")
            else if !within(str(x, "details"), 300) then Left("online.details")
            else Right(Some(Online(str(x, "how"), str(x, "details"))))
          case None if ms.contains(Mode.Online) => Left("online")
          case _ => Right(None)
        svs <- first(o.get("services").flatMap(obj).map(_.fields).getOrElse(Vector.empty).map((k, x) =>
          service(k, x, ms).map(k -> _)))
      yield Work(ms, if ms.contains(Mode.AtShop) then locs else Vector.empty, ar, on, svs.toMap)
    }

  /** a class's weekly starts from `mon 18:00, wed 18:00` (none from an empty text; a wrong one: none at all) */
  def times(text: String): Option[Vector[(Int, Int)]] =
    val parts = text.split(',').map(_.trim).filter(_.nonEmpty).toVector
    val ts = parts.map(_.split("\\s+") match
      case Array(d, t) => for day <- Some(days.indexOf(d.toLowerCase.take(3)) + 1).filter(_ > 0)
        m <- Span.minutes(t) yield day -> m
      case _ => None)
    Option.when(ts.forall(_.isDefined) && ts.size <= 28)(ts.flatten.distinct.sorted)
  def timesText(ts: Vector[(Int, Int)]): String = ts.map((d, m) => s"${days(d - 1)} ${Span.label(m)}").mkString(", ")

  /** a week's hours as people read them: `pn–pt 10:00–18:00, sb 10:00–14:00` */
  def hoursText(h: Map[Int, Vector[Span]], l: Lang): String =
    val names = T("пн вт ср чт пт сб вс", "pn wt śr cz pt sb nd", "Mon Tue Wed Thu Fri Sat Sun",
      "пн вт ср чт пт сб нд")(l).split(' ')
    val byDay = (1 to 7).map(d => d -> h.getOrElse(d, Vector.empty).map(s => s"${Span.label(s.from)}–${
      Span.label(s.to)}").mkString(", ")).filter(_._2.nonEmpty)
    // the days in a row with the same hours, as one run
    byDay.foldLeft(Vector.empty[(Int, Int, String)]) { case (acc, (d, t)) => acc.lastOption match
      case Some((a, b, u)) if u == t && b == d - 1 => acc.init :+ (a, d, t)
      case _ => acc :+ (d, d, t)
    }.map((a, b, t) => (if a == b then names(a - 1) else s"${names(a - 1)}–${names(b - 1)}") + " " + t).mkString(", ")
