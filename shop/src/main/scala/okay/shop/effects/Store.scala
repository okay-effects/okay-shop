package okay.shop.effects

import okay.scala3.*
import okay.shop.model.*

/** A STAFF MEMBER'S SIGN-IN: a link mailed, a session it opens — kept as the SHA-256 of their secrets */
final case class Signin(email: String, until: Long)

/**
 * THE BUSINESS'S STORE: the journal of what happened to orders and inquiries, the read tables moved with it, the
 * counters of the rate limits, the workshop's sign-ins. One site's: the handler is its Durable Object's SQLite
 */
enum Store[X]:
  case Place(order: Order) extends Store[Unit]
  case Find(ref: String) extends Store[Option[Order]]
  /** an event of an order, appended: the order as it is after it (none: no such order) */
  case Record(ref: String, event: Event, at: Long) extends Store[Option[Order]]
  case History(ref: String) extends Store[Vector[(Long, Event)]]
  case Orders(limit: Int) extends Store[Vector[Order]]
  case Inquire(inquiry: Inquiry) extends Store[Unit]
  case Inquiries(limit: Int) extends Store[Vector[Inquiry]]
  /** one more hit of `key` in the window of `window` ms that `now` is in: allowed while at most `max` */
  case Allow(key: String, max: Int, window: Long, now: Long) extends Store[Boolean]
  case KeepLink(hash: String, signin: Signin) extends Store[Unit]
  /** a link used: its sign-in if it was kept and is not past; used once */
  case UseLink(hash: String, now: Long) extends Store[Option[Signin]]
  case KeepSession(hash: String, signin: Signin) extends Store[Unit]
  case Session(hash: String, now: Long) extends Store[Option[Signin]]
  case EndSession(hash: String) extends Store[Unit]
  /** a booking taken — if its start is still free for its master, checked in the same step: `Left("taken")` else */
  case Book(booking: Booking, schedule: Schedule, now: Long) extends Store[Either[String, Booking]]
  case FindBooking(ref: String) extends Store[Option[Booking]]
  /** the bookings (any status) starting from `from` to `until` */
  case Bookings(from: Long, until: Long) extends Store[Vector[Booking]]
  /** a booking moved to `start`, if free for it (itself not in the way) */
  case Rebook(ref: String, start: Long, schedule: Schedule, now: Long) extends Store[Either[String, Booking]]
  case CancelBooking(ref: String, now: Long) extends Store[Option[Booking]]
  /** a booking taken by the first of `candidates` (a master's key and schedule) free at its start, checked in the
   * same step — the bookings of no master in `masters` keeping every master busy: `Left("taken")` when none is */
  case BookWith(booking: Booking, candidates: Vector[(String, Schedule)], masters: Set[String], now: Long)
    extends Store[Either[String, Booking]]
  /** a booking moved to `start` by the first of `candidates` free then (itself not in the way) */
  case RebookWith(ref: String, start: Long, candidates: Vector[(String, Schedule)], masters: Set[String], now: Long)
    extends Store[Either[String, Booking]]
  /** the bookings whose reminder is due at `now`, marked reminded; the next reminder's alarm set */
  case Remind(now: Long) extends Store[Vector[Booking]]

object Store:
  def place[R[_]](o: Order)(using In[Store, R]): Unit ! R = perform[Store](Place(o))
  def find[R[_]](ref: String)(using In[Store, R]): Option[Order] ! R = perform[Store](Find(ref))
  def record[R[_]](ref: String, e: Event, at: Long)(using In[Store, R]): Option[Order] ! R =
    perform[Store](Record(ref, e, at))
  def history[R[_]](ref: String)(using In[Store, R]): Vector[(Long, Event)] ! R = perform[Store](History(ref))
  def orders[R[_]](limit: Int)(using In[Store, R]): Vector[Order] ! R = perform[Store](Orders(limit))
  def inquire[R[_]](i: Inquiry)(using In[Store, R]): Unit ! R = perform[Store](Inquire(i))
  def inquiries[R[_]](limit: Int)(using In[Store, R]): Vector[Inquiry] ! R = perform[Store](Inquiries(limit))
  def allow[R[_]](key: String, max: Int, window: Long, now: Long)(using In[Store, R]): Boolean ! R =
    perform[Store](Allow(key, max, window, now))
  def book[R[_]](b: Booking, s: Schedule, now: Long)(using In[Store, R]): Either[String, Booking] ! R =
    perform[Store](Book(b, s, now))
  def booking[R[_]](ref: String)(using In[Store, R]): Option[Booking] ! R = perform[Store](FindBooking(ref))
  def bookings[R[_]](from: Long, until: Long)(using In[Store, R]): Vector[Booking] ! R =
    perform[Store](Bookings(from, until))
  def rebook[R[_]](ref: String, start: Long, s: Schedule, now: Long)(using In[Store, R]): Either[String, Booking] ! R =
    perform[Store](Rebook(ref, start, s, now))
  def cancelBooking[R[_]](ref: String, now: Long)(using In[Store, R]): Option[Booking] ! R =
    perform[Store](CancelBooking(ref, now))
  def bookWith[R[_]](b: Booking, candidates: Vector[(String, Schedule)], masters: Set[String], now: Long)(
    using In[Store, R]): Either[String, Booking] ! R = perform[Store](BookWith(b, candidates, masters, now))
  def rebookWith[R[_]](ref: String, start: Long, candidates: Vector[(String, Schedule)], masters: Set[String],
                       now: Long)(using In[Store, R]): Either[String, Booking] ! R =
    perform[Store](RebookWith(ref, start, candidates, masters, now))
  def remind[R[_]](now: Long)(using In[Store, R]): Vector[Booking] ! R = perform[Store](Remind(now))
