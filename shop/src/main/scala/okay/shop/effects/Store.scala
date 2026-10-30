package okay.shop.effects

import okay.scala3.*
import okay.shop.invoice.{Buyer, Invoice, KsefState}
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
  /** a seat of a class taken — if it still has one at the booking's start, counted in the same step:
   * `Left("full")` else */
  case Seat(booking: Booking, schedule: Schedule, offer: Offer, now: Long) extends Store[Either[String, Booking]]
  /** a seat moved to another start of its class, if that one has a seat */
  case Reseat(ref: String, start: Long, schedule: Schedule, offer: Offer, now: Long)
    extends Store[Either[String, Booking]]
  /** an inquiry for a quote asked */
  case Ask(quote: Quote) extends Store[Unit]
  case FindQuote(ref: String) extends Store[Option[Quote]]
  case Quotes(limit: Int) extends Store[Vector[Quote]]
  /** the shop's answer to an inquiry not yet decided: the quote as it is after it (none: no such, or decided) */
  case Reply(ref: String, price: Long, priceTo: Long, start: Long, note: String) extends Store[Option[Quote]]
  /** the client's decision on a quoted inquiry, with what it became: none when it is not quoted */
  case Decide(ref: String, accepted: Boolean, result: String) extends Store[Option[Quote]]
  /** the bookings whose reminder is due at `now`, marked reminded; the next reminder's alarm set */
  case Remind(now: Long) extends Store[Vector[Booking]]
  /** a payment changed as `mark` asks (specs/shop.md "Money"), by the rules, in one step: `start` the payment as it
   * counts before (its record, or an old paid order's); a receipt numbered when it is first paid */
  case Pay(mark: Mark, start: Option[Payment], who: String, owner: Boolean, cancelled: Boolean, today: String,
           now: Long) extends Store[Either[String, Payment]]
  case FindPayment(of: Of, ref: String) extends Store[Option[Payment]]
  /** every payment record */
  case Payments() extends Store[Vector[Payment]]
  /** a past booking kept marked a no-show: none when it is not such */
  case NoShow(ref: String, now: Long) extends Store[Option[Booking]]
  // ---- INVOICES (specs/shop.md "Invoices")
  /** the buyer data of an order, a booking or a quote (`kind`: `order`, `booking`, `quote`); none: taken off */
  case KeepBuyer(kind: String, ref: String, buyer: Option[Buyer]) extends Store[Unit]
  case FindBuyer(kind: String, ref: String) extends Store[Option[Buyer]]
  /** an invoice numbered in one step: the shop's next id, its series' next number (`pattern`, the invoice's issue
   * day); a VAT invoice refused `issued` when its order or booking has one; a correction kept on its original */
  case Issue(draft: Invoice, pattern: String) extends Store[Either[String, Invoice]]
  case FindInvoice(id: String) extends Store[Option[Invoice]]
  /** the VAT invoice of an order or a booking */
  case InvoiceOf(of: Of, ref: String) extends Store[Option[Invoice]]
  case Invoices() extends Store[Vector[Invoice]]
  /** an invoice's KSeF side changed in one step; kept in the queue the object's alarm retries while `next > 0` */
  case KsefMarked(id: String, state: KsefState) extends Store[Option[Invoice]]
  /** an invoice's UPO (KSeF's receipt, XML) kept, read */
  case KeepUpo(id: String, xml: String) extends Store[Unit]
  case FindUpo(id: String) extends Store[Option[String]]

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
  def seat[R[_]](b: Booking, s: Schedule, o: Offer, now: Long)(using In[Store, R]): Either[String, Booking] ! R =
    perform[Store](Seat(b, s, o, now))
  def reseat[R[_]](ref: String, start: Long, s: Schedule, o: Offer, now: Long)(using In[Store, R])
    : Either[String, Booking] ! R = perform[Store](Reseat(ref, start, s, o, now))
  def ask[R[_]](q: Quote)(using In[Store, R]): Unit ! R = perform[Store](Ask(q))
  def quote[R[_]](ref: String)(using In[Store, R]): Option[Quote] ! R = perform[Store](FindQuote(ref))
  def quotes[R[_]](limit: Int)(using In[Store, R]): Vector[Quote] ! R = perform[Store](Quotes(limit))
  def reply[R[_]](ref: String, price: Long, priceTo: Long, start: Long, note: String)(using In[Store, R])
    : Option[Quote] ! R = perform[Store](Reply(ref, price, priceTo, start, note))
  def decide[R[_]](ref: String, accepted: Boolean, result: String)(using In[Store, R]): Option[Quote] ! R =
    perform[Store](Decide(ref, accepted, result))
  def remind[R[_]](now: Long)(using In[Store, R]): Vector[Booking] ! R = perform[Store](Remind(now))
  def pay[R[_]](m: Mark, start: Option[Payment], who: String, owner: Boolean, cancelled: Boolean, today: String,
                now: Long)(using In[Store, R]): Either[String, Payment] ! R =
    perform[Store](Pay(m, start, who, owner, cancelled, today, now))
  def payment[R[_]](of: Of, ref: String)(using In[Store, R]): Option[Payment] ! R = perform[Store](FindPayment(of, ref))
  def payments[R[_]](using In[Store, R]): Vector[Payment] ! R = perform[Store](Payments())
  def noShow[R[_]](ref: String, now: Long)(using In[Store, R]): Option[Booking] ! R = perform[Store](NoShow(ref, now))
  def keepBuyer[R[_]](kind: String, ref: String, b: Option[Buyer])(using In[Store, R]): Unit ! R =
    perform[Store](KeepBuyer(kind, ref, b))
  def buyer[R[_]](kind: String, ref: String)(using In[Store, R]): Option[Buyer] ! R = perform[Store](FindBuyer(kind, ref))
  def issue[R[_]](draft: Invoice, pattern: String)(using In[Store, R]): Either[String, Invoice] ! R =
    perform[Store](Issue(draft, pattern))
  def invoice[R[_]](id: String)(using In[Store, R]): Option[Invoice] ! R = perform[Store](FindInvoice(id))
  def invoiceOf[R[_]](of: Of, ref: String)(using In[Store, R]): Option[Invoice] ! R = perform[Store](InvoiceOf(of, ref))
  def invoices[R[_]](using In[Store, R]): Vector[Invoice] ! R = perform[Store](Invoices())
  def ksefMarked[R[_]](id: String, k: KsefState)(using In[Store, R]): Option[Invoice] ! R =
    perform[Store](KsefMarked(id, k))
  def keepUpo[R[_]](id: String, xml: String)(using In[Store, R]): Unit ! R = perform[Store](KeepUpo(id, xml))
  def upo[R[_]](id: String)(using In[Store, R]): Option[String] ! R = perform[Store](FindUpo(id))
