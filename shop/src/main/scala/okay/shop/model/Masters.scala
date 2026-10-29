package okay.shop.model

import okay.effects.json.Json

/**
 * A MASTER of a shop with a calendar of their own (specs/shop.md "Masters"): a key, a name, initials, an optional
 * email (a team member who signs in; none: a name only), the services, modes and locations they work in (all of each
 * when none), their own week (none: the place's or the shop's hours), their days off (date ranges, both ends in)
 */
final case class Master(key: String, name: String, initials: String = "", email: String = "",
                        services: Vector[String] = Vector.empty, modes: Vector[Mode] = Vector.empty,
                        places: Vector[String] = Vector.empty, hours: Map[Int, Vector[Span]] = Map.empty,
                        off: Vector[(String, String)] = Vector.empty):
  /** whether they do service `o` in mode `m` (none: a shop before the modes) at place `p` */
  def does(o: String, m: Option[Mode], p: String): Boolean =
    (services.isEmpty || services.contains(o)) && m.forall(x => modes.isEmpty || modes.contains(x)) &&
      (!m.contains(Mode.AtShop) || places.isEmpty || places.contains(p))
  def shown: String = if initials.nonEmpty then initials else Masters.initials(name)

object Masters:
  /** `Anna Nowak` → `AN`, `Ola` → `OL` */
  def initials(name: String): String =
    val ws = name.split("\\s+").filter(_.nonEmpty)
    (if ws.length >= 2 then ws.take(2).map(_.take(1)).mkString else name.trim.take(2)).toUpperCase

  /** a master's schedule: the plan's, their own week when they have one, their days off closed, their key */
  def schedule(base: Schedule, m: Master): Schedule =
    base.copy(week = if m.hours.nonEmpty then m.hours else base.week,
      exceptions = base.exceptions ++ m.off.flatMap((a, b) => dates(a, b)).map(_ -> Vector.empty[Span]),
      masters = Vector(m.key))

  /** the bookings as a master's calendar sees them: one of no master of the shop's (before the masters) is theirs */
  def taken(all: Vector[Booking], key: String, keys: Set[String]): Vector[Booking] =
    all.map(b => if keys(b.master) then b else b.copy(master = key))

  private def day(s: String): Option[Long] = s.trim.split('-') match
    case Array(y, m, d) => for yy <- y.toIntOption; mm <- m.toIntOption; dd <- d.toIntOption
      if s.trim.matches("20[0-9]{2}-[01][0-9]-[0-3][0-9]") && mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31
      yield Day.days(yy, mm, dd)
    case _ => None
  /** the dates from `a` to `b`, both in (at most a year) */
  def dates(a: String, b: String): Vector[String] = (day(a), day(b)) match
    case (Some(x), Some(y)) if x <= y && y - x <= 366 => (x to y).toVector.map { n =>
        val (yy, mm, dd) = Civil.ymd(n)
        f"$yy%04d-$mm%02d-$dd%02d"
      }
    case _ => Vector.empty

  def toJson(ms: Vector[Master]): Json = Json.Arr(ms.map(m => Json.obj("key" -> Json.str(m.key),
    "name" -> Json.str(m.name), "initials" -> Json.str(m.initials), "email" -> Json.str(m.email),
    "services" -> Json.Arr(m.services.map(Json.str)), "modes" -> Json.Arr(m.modes.map(x => Json.str(x.key))),
    "places" -> Json.Arr(m.places.map(Json.str)),
    "hours" -> Json.Arr(m.hours.toVector.sortBy(_._1).flatMap((d, ss) => ss.map(s => Json.obj(
      "day" -> Json.str(Work.days(d - 1)), "from" -> Json.str(Span.label(s.from)),
      "to" -> Json.str(Span.label(s.to)))))),
    "off" -> Json.Arr(m.off.map((a, b) => Json.obj("from" -> Json.str(a), "to" -> Json.str(b)))))))

  /** the masters from their JSON (a list), checked by field against the services, the work's modes and locations */
  def fromJson(j: Json, offers: Set[String], work: Option[Work]): Either[String, Vector[Master]] = j match
    case Json.Arr(xs) if xs.size > 30 => Left("masters.size")
    case Json.Arr(xs) =>
      def str(o: Json.Obj, k: String) = o.get(k).collect { case Json.Str(v) => v.replaceAll("\\s+", " ").trim }
        .getOrElse("")
      def strs(o: Json.Obj, k: String) = o.get(k).collect { case Json.Arr(vs) => vs.collect { case Json.Str(v) =>
        v.trim } }.getOrElse(Vector.empty).filter(_.nonEmpty).distinct
      val ms = xs.zipWithIndex.map {
        case (o: Json.Obj, i) =>
          val key = Some(str(o, "key")).filter(_.nonEmpty).getOrElse(s"m${i + 1}")
          val name = str(o, "name")
          val email = str(o, "email").toLowerCase
          val modes = strs(o, "modes").map(Mode.parse)
          val week = o.get("hours").collect { case Json.Arr(hs) => hs }.getOrElse(Vector.empty).map {
            case h: Json.Obj => (for
                d <- Some(Work.days.indexOf(str(h, "day").toLowerCase) + 1).filter(_ > 0)
                a <- Span.minutes(str(h, "from")); b <- Span.minutes(str(h, "to")) if a < b
              yield d -> Span(a, b)).toRight("master.hours")
            case _ => Left("master.hours")
          }
          val off = o.get("off").collect { case Json.Arr(fs) => fs }.getOrElse(Vector.empty).map {
            case f: Json.Obj => Some(str(f, "from") -> Some(str(f, "to")).filter(_.nonEmpty).getOrElse(str(f, "from")))
                .filter((a, b) => dates(a, b).nonEmpty).toRight("master.off")
            case _ => Left("master.off")
          }
          if !key.matches("[a-z0-9][a-z0-9-]{0,29}") || key == "main" then Left("master.key")
          else if name.isEmpty || name.length > 60 then Left("master.name")
          else if str(o, "initials").length > 3 then Left("master.name")
          else if email.nonEmpty && Contact.email(email).isEmpty then Left("master.email")
          else if strs(o, "services").exists(!offers(_)) then Left("master.services")
          else if modes.exists(_.isEmpty) || work.exists(w => modes.flatten.exists(!w.has(_))) then
            Left("master.modes")
          else if strs(o, "places").exists(p => !work.exists(_.location(p).isDefined)) then Left("master.places")
          else if week.size > 21 || week.exists(_.isLeft) then Left("master.hours")
          else if off.size > 40 || off.exists(_.isLeft) then Left("master.off")
          else Right(Master(key, name, str(o, "initials").toUpperCase, email, strs(o, "services"), modes.flatten,
            strs(o, "places"), week.collect { case Right(x) => x }.groupMap(_._1)(_._2).map((d, ss) =>
              d -> ss.sortBy(_.from)), off.collect { case Right(x) => x }))
        case _ => Left("master.key")
      }
      ms.collectFirst { case Left(why) => why } match
        case Some(why) => Left(why)
        case None =>
          val ok = ms.collect { case Right(m) => m }
          if ok.map(_.key).distinct.size != ok.size then Left("masters.twice") else Right(ok)
    case Json.Null => Right(Vector.empty)
    case _ => Left("masters")
