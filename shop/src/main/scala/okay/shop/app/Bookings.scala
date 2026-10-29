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

  private def bookable(c: Conf, key: String): Option[(Offer, Schedule)] =
    c.site.offer(key).filter(c.site.books).flatMap(o => c.site.schedule.map(o -> _))

  def slots[R[_]](c: Conf, r: Request)(using In[Store, R], In[Clock, R]): Response ! R =
    bookable(c, r.param("service").getOrElse("")) match
      case None => pure(Web.refused("service", 404))
      case Some((o, s)) =>
        Clock.now[R].flatMap(now => Store.bookings[R](now - day, now + (s.horizon + 1) * day).map { taken =>
          val days = Slots.free(s, o.minutes, taken, now).filter(_.slots.nonEmpty).map(d => Json.obj(
            "date" -> Json.str(d.date), "weekday" -> Json.num(d.weekday),
            "slots" -> Json.Arr(d.slots.map(x =>
              Json.obj("start" -> Json.num(x.start), "label" -> Json.str(x.label))))))
          Web.json(Json.obj("service" -> Json.str(o.key), "minutes" -> Json.num(o.minutes), "days" -> Json.Arr(days)))
        })

  def book[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R])
    : Response ! R =
    val f = Web.fields(r)
    val name = f.getOrElse("name", "").trim.take(120)
    val contact = f.getOrElse("contact", "").trim.take(200)
    val start = f.getOrElse("start", "").toLongOption
    if name.isEmpty then pure(Web.refused("name"))
    else if !Contact.valid(contact) then pure(Web.refused("contact"))
    else if !f.get("rodo_consent").exists(v => v.nonEmpty && v != "false") then pure(Web.refused("consent"))
    else (bookable(c, f.getOrElse("service", "")), start) match
      case (None, _) => pure(Web.refused("service"))
      case (_, None) => pure(Web.refused("start"))
      case (Some((o, s)), Some(at)) => Clock.now[R].flatMap { now =>
          Store.allow[R](s"book:${Web.ip(r)}", 5, Api.tenMinutes, now).flatMap { allowed =>
            if !allowed then pure(Web.refused("limit", 429))
            else Captcha.verify[R](f.getOrElse("cf-turnstile-response", ""), Web.ip(r)).flatMap { human =>
              if !human then pure(Web.refused("captcha", 403))
              else taken[R](c, f, o, s, at, name, contact, now)
            }
          }
        }

  /** the booking made, if its start is still free: the store decides, in its step */
  private def taken[R[_]](c: Conf, f: Map[String, String], o: Offer, s: Schedule, at: Long, name: String,
                          contact: String, now: Long)(using In[Store, R], In[Mail, R], In[Ids, R]): Response ! R =
    val lang = Lang.parse(f.getOrElse("lang", "")).getOrElse(c.site.lang)
    for
      ref <- Ids.ref[R](c.site.refPrefix + "B")
      key <- Ids.secret[R]
      made <- Store.book[R](Booking(ref, key, o.key, s.master, at, o.minutes, name, contact, lang,
        Consent(now, c.policy), now), s, now)
      res <- made match
        case Left(why) => pure(Web.refused(why, 409))
        case Right(b) => told(c, b, Letters.booked(c, b), Letters.bookedOwner(c, b)).as(answer(c, b))
    yield res

  /** the client (when an email) and the owner told */
  private def told[R[_]](c: Conf, b: Booking, client: Note, owner: Note)(using In[Mail, R]): Unit ! R =
    Mail.toOwner[R](owner).flatMap(_ => b.email.fold(unit[R])(to => Mail.toClient[R](to, client).map(_ => ())))

  private def answer(c: Conf, b: Booking): Response = Web.json(Json.obj("ok" -> Json.bool(true),
    "ref" -> Json.str(b.ref), "key" -> Json.str(b.key), "start" -> Json.num(b.start),
    "when" -> Json.str(Day.time(b.start)), "manage" -> Json.str(Letters.manageLink(c, b))))

  /** the booking of the request's ref, when its key is right; else none — the same as no such booking */
  private def owned[R[_]](f: Map[String, String])(using In[Store, R]): Option[Booking] ! R =
    val ref = f.getOrElse("ref", "").trim.toUpperCase
    if ref.isEmpty then pure(None)
    else Store.booking[R](ref).map(_.filter(b => Api.same(b.key, f.getOrElse("k", f.getOrElse("key", "")))))

  def cancel[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Clock, R]): Response ! R =
    owned[R](Web.fields(r)).flatMap {
      case None => pure(Web.refused("no such booking", 404))
      case Some(b) => Clock.now[R].flatMap(now => Store.cancelBooking[R](b.ref, now)).flatMap {
          case None => pure(Web.refused("cancelled already", 409))
          case Some(x) => told(c, x, Letters.unbooked(c, x), Letters.unbookedOwner(c, x))
              .as(Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(x.ref))))
        }
    }

  def move[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Clock, R]): Response ! R =
    val f = Web.fields(r)
    owned[R](f).flatMap {
      case None => pure(Web.refused("no such booking", 404))
      case Some(b) => (c.site.schedule, f.getOrElse("start", "").toLongOption) match
        case (None, _) => pure(Web.refused("no schedule"))
        case (_, None) => pure(Web.refused("start"))
        case (Some(s), Some(at)) => Clock.now[R].flatMap(now => Store.rebook[R](b.ref, at, s, now)).flatMap {
            case Left(why) => pure(Web.refused(why, 409))
            case Right(x) => told(c, x, Letters.rebooked(c, x), Letters.rebookedOwner(c, x)).as(answer(c, x))
          }
    }

  def page[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R]): Response ! R =
    owned[R](Map("ref" -> r.param("ref").getOrElse(""), "k" -> r.param("k").getOrElse(""))).map {
      case Some(b) => Web.page(BookingPage.page(c, l, b))
      case None => Web.page(BookingPage.missing(c, l), 404)
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
