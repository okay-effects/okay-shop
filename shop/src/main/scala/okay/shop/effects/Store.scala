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
