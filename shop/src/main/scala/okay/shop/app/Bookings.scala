package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.booking.{Calendar, Slots}
import okay.shop.effects.*
import okay.shop.html.{BookingPage, Conf, Letters}
import okay.shop.model.*

/**
 * BOOKING (specs/shop.md "Booking"): the free starts of a service, a booking taken (Turnstile, a limit, no slot
 * twice — the store checks it in its step), its management by its key (cancel, move), the master's calendar. The
 * client and the owner told of each, by `Mail`; a letter is never the booking
 */
object Bookings:
  private val day = 86400000L

  /** WHAT IS BOOKED: the service, how it meets (none: a shop before the modes), where — a location's key, the
   * address —, the schedule of that, the buffer kept around it, its price */
  final case class Plan(offer: Offer, mode: Option[Mode], place: String, address: Option[Address], schedule: Schedule,
                        before: Int, after: Int, price: Long)

  /** WHO MAY TAKE IT (specs/shop.md "Masters"): the eligible masters — the one asked (`master`), else all of them in
   * their order, `first` (a booking's own) put before the rest — each with their schedule; and every master's key.
   * None: a shop without masters (its one calendar); `Left("master")`: the one asked does not do it */
  def masters(c: Conf, p: Plan, master: String, first: String = ""): Either[String,
    Option[(Vector[(String, Schedule)], Set[String])]] =
    if c.site.masters.isEmpty then Right(None)
    else
      val eligible = c.site.eligible(p.offer, p.mode, p.place)
      val asked = if master.isEmpty then eligible.filter(_.key == first) ++ eligible.filterNot(_.key == first)
        else eligible.filter(_.key == master)
      if asked.isEmpty then Left("master")
      else Right(Some(asked.map(m => m.key -> Masters.schedule(p.schedule, m)) -> c.site.masters.map(_.key).toSet))

  private def bookable(c: Conf, key: String): Option[(Offer, Schedule)] =
    // a class needs no week: the shop's step, horizon and lead, or the defaults
    c.site.offer(key).filter(c.site.books).map(o => o -> c.site.schedule.getOrElse(Schedule(Map.empty, Map.empty)))

  /** the plan of a service in the mode and at the place asked (the first allowed when not said), without the
   * client's address; or why not: `service`, `mode`, `place` */
  def plan(c: Conf, key: String, mode: String, place: String): Either[String, Plan] = c.site.work match
    case None => bookable(c, key).toRight("service").map((o, s) => Plan(o, None, "", None, s, 0, 0, o.price))
    case Some(w) => c.site.offer(key).filter(c.site.books).toRight("service").flatMap { o =>
        val modes = c.site.bookedModes(o)
        (if mode.isEmpty then modes.headOption else Mode.parse(mode).filter(modes.contains)).toRight("mode")
          .flatMap { m =>
            val places = c.site.places(m).filter(p => c.site.plan(o, m, p).isDefined)
            (if place.isEmpty || m != Mode.AtShop then places.headOption else places.find(_ == place))
              .toRight("place").map { p =>
                val area = w.area.filter(_ => m == Mode.AtClient)
                Plan(o, Some(m), p, w.location(p).filter(_ => m == Mode.AtShop).map(_.address),
                  c.site.plan(o, m, p).get, area.fold(0)(_.before), area.fold(0)(_.after),
                  o.price + w.extra(o.key, m) + area.fold(0L)(_.fee))
              }
          }
      }

  /** the plan with the client's address when it is at theirs: given whole and inside the area; else why not —
   * `address`, `area` */
  private def addressed(c: Conf, p: Plan, f: Map[String, String]): Either[String, Plan] =
    if !p.mode.contains(Mode.AtClient) then Right(p)
    else
      def g(k: String, n: Int) = f.getOrElse(k, "").replaceAll("\\s+", " ").trim.take(n)
      val a = Address("", g("street", 120), g("postcode", 6), g("city", 60), g("notes", 200))
      if a.street.isEmpty || a.city.isEmpty || !a.postcode.matches("[0-9]{2}-[0-9]{3}") then Left("address")
      else if !c.site.work.flatMap(_.area).exists(_.inside(a.city, a.postcode)) then Left("area")
      else Right(p.copy(address = Some(a)))

  /** the plan of a booking made: its own mode and place again, for a move */
  private def planOf(c: Conf, b: Booking): Option[Schedule] = b.mode match
    case None => c.site.schedule
    case Some(m) => c.site.offer(b.service).flatMap(o => c.site.plan(o, m, b.place))

  /** who may take a booking moved: its master kept when free (none asked), or the one asked */
  private def moving(c: Conf, b: Booking, s: Schedule, master: String)
    : Either[String, Option[(Vector[(String, Schedule)], Set[String])]] =
    c.site.offer(b.service).fold(Right(None))(o =>
      masters(c, Plan(o, b.mode, b.place, b.address, s, b.before, b.after, b.price), master, b.master))

  def slots[R[_]](c: Conf, r: Request)(using In[Store, R], In[Clock, R]): Response ! R =
    plan(c, r.param("service").getOrElse(""), r.param("mode").getOrElse(""), r.param("place").getOrElse(""))
      .flatMap(p => masters(c, p, r.param("master").getOrElse("")).map(p -> _)) match
      case Left(why) => pure(Web.refused(why, 404))
      case Right((p, who)) =>
        val (o, s) = (p.offer, p.schedule)
        Clock.now[R].flatMap(now => Store.bookings[R](now - day, now + (s.horizon + 1) * day).map { taken =>
          // one calendar, or the union of the masters' (each their own week, days off and bookings)
          val free = who match
            case _ if o.isClass => Slots.classes(s, o, taken, now)
            case None => Slots.free(s, o.minutes, taken, now, p.before, p.after)
            case Some((cs, keys)) => cs.map((k, sch) => Slots.free(sch, o.minutes, Masters.taken(taken, k, keys), now,
              p.before, p.after)).transpose.map(ds => ds.head.copy(slots = ds.flatMap(_.slots).distinctBy(_.start)
              .sortBy(_.start)))
          val days = free.filter(_.slots.nonEmpty).map(d =>
            Json.obj("date" -> Json.str(d.date), "weekday" -> Json.num(d.weekday),
              "slots" -> Json.Arr(d.slots.map(x => Json.Obj(Vector("start" -> Json.num(x.start),
                "label" -> Json.str(x.label)) ++ Option.when(x.left >= 0)("left" -> Json.num(x.left)))))))
          Web.json(Json.obj("service" -> Json.str(o.key), "minutes" -> Json.num(o.minutes), "days" -> Json.Arr(days)))
        })

  /** whether an address is inside the shop's area: the page asks before the slots */
  def area(c: Conf, r: Request): Response =
    val inside = c.site.work.flatMap(_.area).exists(_.inside(r.param("city").getOrElse(""),
      r.param("postcode").getOrElse("")))
    Web.json(Json.obj("inside" -> Json.bool(inside)))

  def book[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R],
    In[Notify, R]): Response ! R =
    val f = Web.fields(r)
    val name = f.getOrElse("name", "").trim.take(120)
    val contact = f.getOrElse("contact", "").trim.take(200)
    val start = f.getOrElse("start", "").toLongOption
    if name.isEmpty then pure(Web.refused("name"))
    else if !Contact.valid(contact) then pure(Web.refused("contact"))
    else if !f.get("rodo_consent").exists(v => v.nonEmpty && v != "false") then pure(Web.refused("consent"))
    else if Invoicing.asked(c, f).isLeft then pure(Web.refused(Invoicing.asked(c, f).left.toOption.get))
    // a hidden service takes nothing new (specs/shop.md "The owner edits the content")
    else (plan(c, f.getOrElse("service", ""), f.getOrElse("mode", ""), f.getOrElse("place", ""))
      .filterOrElse(!_.offer.hidden, "service").flatMap(addressed(c, _, f))
      .flatMap(p => masters(c, p, f.getOrElse("master", "")).map(_ => p)), start) match
      case (Left(why), _) => pure(Web.refused(why, if why == "area" then 409 else 400))
      case (_, None) => pure(Web.refused("start"))
      case (Right(p), Some(at)) => Clock.now[R].flatMap { now =>
          Store.allow[R](s"book:${Web.ip(r)}", 5, Api.tenMinutes, now).flatMap { allowed =>
            if !allowed then pure(Web.refused("limit", 429))
            else Captcha.verify[R](f.getOrElse("cf-turnstile-response", ""), Web.ip(r)).flatMap { human =>
              if !human then pure(Web.refused("captcha", 403))
              else taken[R](c, f, p, at, name, contact, now)
            }
          }
        }

  /** the booking made, if its start is still free: the store decides, in its step */
  private def taken[R[_]](c: Conf, f: Map[String, String], p: Plan, at: Long, name: String, contact: String,
                          now: Long)(using In[Store, R], In[Mail, R], In[Ids, R], In[Notify, R]): Response ! R =
    val lang = Lang.parse(f.getOrElse("lang", "")).getOrElse(c.site.lang)
    reserve[R](c, p, at, name, contact, lang, f.getOrElse("master", ""), now).flatMap {
      case Left(why) => pure(Web.refused(why, 409))
      case Right(b) => Invoicing.keep[R]("booking", b.ref, Invoicing.asked(c, f).toOption.flatten).flatMap(_ =>
          told(c, b, Letters.booked(c, b), Letters.bookedOwner(c, b), Notice.Booked(b))).as(answer(c, b))
    }

  /** A BOOKING TAKEN for `p` at `at`, no letter yet: a class's seat, a master's time, or the one calendar's — the
   * store decides in its step (`taken`, `full`) */
  def reserve[R[_]](c: Conf, p: Plan, at: Long, name: String, contact: String, lang: Lang, master: String,
                    now: Long)(using In[Store, R], In[Ids, R]): Either[String, Booking] ! R =
    val s = p.schedule
    for
      ref <- Ids.ref[R](c.site.refPrefix + "B")
      key <- Ids.secret[R]
      b = Booking(ref, key, p.offer.key, s.master, at, p.offer.minutes, name, contact, lang,
        Consent(now, c.policy), now, mode = p.mode, place = p.place, address = p.address, before = p.before,
        after = p.after, price = if p.mode.isDefined || p.offer.isClass then p.price else 0L)
      made <- masters(c, p, master) match
        // a class: its teacher the first master eligible
        case Right(who) if p.offer.isClass =>
          Store.seat[R](b.copy(master = who.flatMap(_._1.headOption).fold(s.master)(_._1)), s, p.offer, now)
        case Right(Some((cs, keys))) => Store.bookWith[R](b, cs, keys, now)
        case Right(None) => Store.book[R](b, s, now)
        case Left(why) => pure(Left(why))
    yield made

  /** the client (when an email) and the owner told; the notice given (specs/shop.md "Notices") */
  private def told[R[_]](c: Conf, b: Booking, client: Note, owner: Note, notice: Notice)(using In[Mail, R],
    In[Notify, R]): Unit ! R =
    Mail.toOwner[R](owner).flatMap(_ => Notify.tell[R](notice)).flatMap(_ =>
      b.email.fold(unit[R])(to => Mail.toClient[R](to, client).map(_ => ())))

  private def answer(c: Conf, b: Booking): Response = Web.json(Json.Obj(Vector("ok" -> Json.bool(true),
    "ref" -> Json.str(b.ref), "key" -> Json.str(b.key), "start" -> Json.num(b.start),
    "when" -> Json.str(Day.time(b.start)), "manage" -> Json.str(Letters.manageLink(c, b))) ++
    // whose it is, when the shop has masters
    Letters.master(c, b).toVector.flatMap(n => Vector("master" -> Json.str(b.master), "masterName" -> Json.str(n)))))

  /** the booking of the request's ref, when its key is right; else none — the same as no such booking */
  private def owned[R[_]](f: Map[String, String])(using In[Store, R]): Option[Booking] ! R =
    val ref = f.getOrElse("ref", "").trim.toUpperCase
    if ref.isEmpty then pure(None)
    else Store.booking[R](ref).map(_.filter(b => Api.same(b.key, f.getOrElse("k", f.getOrElse("key", "")))))

  def cancel[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Clock, R], In[Notify, R])
    : Response ! R =
    owned[R](Web.fields(r)).flatMap {
      case None => pure(Web.refused("no such booking", 404))
      case Some(b) => Clock.now[R].flatMap(now => Store.cancelBooking[R](b.ref, now)).flatMap {
          case None => pure(Web.refused("cancelled already", 409))
          case Some(x) => told(c, x, Letters.unbooked(c, x), Letters.unbookedOwner(c, x), Notice.Cancelled(x))
              .as(Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(x.ref))))
        }
    }

  def move[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Clock, R], In[Notify, R]): Response ! R =
    val f = Web.fields(r)
    owned[R](f).flatMap {
      case None => pure(Web.refused("no such booking", 404))
      case Some(b) => (planOf(c, b), f.getOrElse("start", "").toLongOption) match
        case (None, _) => pure(Web.refused("no schedule"))
        case (_, None) => pure(Web.refused("start"))
        case (Some(s), Some(at)) => Clock.now[R].flatMap(now => moving(c, b, s, f.getOrElse("master", "")) match
            case _ if c.site.offer(b.service).exists(_.isClass) =>
              Store.reseat[R](b.ref, at, s, c.site.offer(b.service).get, now)
            case Left(why) => pure(Left(why))
            case Right(Some((cs, keys))) => Store.rebookWith[R](b.ref, at, cs, keys, now)
            case Right(None) => Store.rebook[R](b.ref, at, s, now)).flatMap {
            case Left(why) => pure(Web.refused(why, 409))
            case Right(x) => told(c, x, Letters.rebooked(c, x), Letters.rebookedOwner(c, x), Notice.Moved(x))
                .as(answer(c, x))
          }
    }

  def page[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R]): Response ! R =
    owned[R](Map("ref" -> r.param("ref").getOrElse(""), "k" -> r.param("k").getOrElse(""))).flatMap {
      // a paid booking links its receipt (specs/shop.md "Money")
      case Some(b) => Store.payment[R](Of.Booking, b.ref).map(p => Web.page(BookingPage.page(c, l, b,
        receipt = p.exists(_.paid))))
      case None => pure(Web.page(BookingPage.missing(c, l), 404))
    }

  /** the master's calendar: the bookings from a week ago to the horizon, by the shop's calendar key */
  def calendar[R[_]](c: Conf, key: String)(using In[Store, R], In[Clock, R]): Response ! R =
    if c.calendarKey.isEmpty || !Api.same(key, c.calendarKey) then pure(Response.notFound())
    else Clock.now[R].flatMap(now => Store.bookings[R](now - 7 * day, now + 30 * day).map(bs =>
      Response(200, Map("Content-Type" -> "text/calendar; charset=utf-8", "Cache-Control" -> "no-store"),
        Calendar.ics(c.site, bs.filter(_.active), now))))

  /** THE REMINDERS, the object's alarm: the bookings due marked, each client told; the next alarm set by the store */
  def remind[R[_]](c: Conf)(using In[Store, R], In[Mail, R], In[Clock, R]): Int ! R =
    Clock.now[R].flatMap(now => Store.remind[R](now)).flatMap(due =>
      due.foldLeft(unit[R])((p, b) => p.andThen(b.email.fold(unit[R])(to =>
        Mail.toClient[R](to, Letters.reminder(c, b)).map(_ => ())))).as(due.size))
