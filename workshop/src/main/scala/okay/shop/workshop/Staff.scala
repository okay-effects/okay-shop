package okay.shop.workshop

import okay.scala3.*
import okay.shop.model.*

/** an order as the workshop sees it: the order, its service's name and price, the statuses it may go to next */
final case class Job(order: Order, service: String, price: String, next: Vector[Status])

/** an order opened: the job, its history, and whether the move just made mailed the client (`sent`, `not sent`, or
 * nothing to say) */
final case class Opened(job: Job, history: Vector[(Long, Event)], emailed: String)

/** a booking as the workshop lists it: the booking, its service's name, its master's name (none: a shop without
 * masters) and whether it is the signed-in person's own */
final case class Booked(booking: Booking, service: String, master: String = "", mine: Boolean = false)

/** a move asked: the status, and what it needs — the amount of a payment (zł), a parcel's tracking number, a reason */
final case class Move(to: Status, amount: String = "", tracking: String = "", note: String = "")

/**
 * THE WORKSHOP'S WORLD, as the program asks it (specs/sites.md "The workshop"): who is signed in, a sign-in link
 * asked for, the orders and inquiries, a move, a letter. A refusal is a `Left` with the server's word for it. Its
 * handler is the site's API over `Http` (`StaffApi.run`)
 */
enum Staff[X]:
  case Me() extends Staff[Option[String]]
  case Link(email: String, turnstile: String) extends Staff[Either[String, Unit]]
  case Orders() extends Staff[Either[String, Vector[Job]]]
  case Open(ref: String) extends Staff[Either[String, Opened]]
  case Moved(ref: String, move: Move) extends Staff[Either[String, Opened]]
  case Letter(ref: String, subject: String, body: String) extends Staff[Either[String, Boolean]]
  case Inquiries() extends Staff[Either[String, Vector[Inquiry]]]
  case Out() extends Staff[Unit]
  /** the bookings from a day ago on, each with its service's and master's names; and whether the person signed in
   * is one of the masters */
  case Bookings() extends Staff[Either[String, (Vector[Booked], Boolean)]]
  /** a booking cancelled by the staff: whether its client was mailed */
  case Unbook(ref: String) extends Staff[Either[String, Boolean]]
  /** the inquiries for a quote, newest first, each with its service's name */
  case Quotes() extends Staff[Either[String, Vector[(Quote, String)]]]
  /** the workshop's quote: a price (zł), maybe up to a second, maybe a time (`2026-11-03 14:00`), a note; whether the
   * client was mailed */
  case Reply(ref: String, price: String, priceTo: String, start: String, note: String)
    extends Staff[Either[String, Boolean]]
  /** the host's own pages for the person signed in: their text and address (none when the host has none) */
  case More() extends Staff[Vector[(String, String)]]
  /** the payment of an order or a booking as it counts (none: unpaid), and whether the person is the owner */
  case Paying(of: Of, ref: String) extends Staff[Either[String, (Option[Payment], Boolean)]]
  /** a payment marked (specs/shop.md "Money"): `paid` with its amount (zł), method, day, note; `unpaid`; `refund` */
  case Pay(of: Of, ref: String, action: String, amount: String, method: String, day: String, note: String)
    extends Staff[Either[String, Payment]]
  /** a past booking marked a no-show */
  case NoShow(ref: String) extends Staff[Either[String, Unit]]

object Staff:
  def me[R[_]: Has[Staff]]: Option[String] ! R = perform[Staff](Me())
  def link[R[_]: Has[Staff]](email: String, turnstile: String): Either[String, Unit] ! R =
    perform[Staff](Link(email, turnstile))
  def orders[R[_]: Has[Staff]]: Either[String, Vector[Job]] ! R = perform[Staff](Orders())
  def open[R[_]: Has[Staff]](ref: String): Either[String, Opened] ! R = perform[Staff](Open(ref))
  def move[R[_]: Has[Staff]](ref: String, m: Move): Either[String, Opened] ! R = perform[Staff](Moved(ref, m))
  def letter[R[_]: Has[Staff]](ref: String, subject: String, body: String): Either[String, Boolean] ! R =
    perform[Staff](Letter(ref, subject, body))
  def inquiries[R[_]: Has[Staff]]: Either[String, Vector[Inquiry]] ! R = perform[Staff](Inquiries())
  def out[R[_]: Has[Staff]]: Unit ! R = perform[Staff](Out())
  def bookings[R[_]: Has[Staff]]: Either[String, (Vector[Booked], Boolean)] ! R = perform[Staff](Bookings())
  def unbook[R[_]: Has[Staff]](ref: String): Either[String, Boolean] ! R = perform[Staff](Unbook(ref))
  def quotes[R[_]: Has[Staff]]: Either[String, Vector[(Quote, String)]] ! R = perform[Staff](Quotes())
  def reply[R[_]: Has[Staff]](ref: String, price: String, priceTo: String, start: String, note: String)
    : Either[String, Boolean] ! R = perform[Staff](Reply(ref, price, priceTo, start, note))
  def more[R[_]: Has[Staff]]: Vector[(String, String)] ! R = perform[Staff](More())
  def paying[R[_]: Has[Staff]](of: Of, ref: String): Either[String, (Option[Payment], Boolean)] ! R =
    perform[Staff](Paying(of, ref))
  def pay[R[_]: Has[Staff]](of: Of, ref: String, action: String, amount: String = "", method: String = "",
                            day: String = "", note: String = ""): Either[String, Payment] ! R =
    perform[Staff](Pay(of, ref, action, amount, method, day, note))
  def noShow[R[_]: Has[Staff]](ref: String): Either[String, Unit] ! R = perform[Staff](NoShow(ref))

/** A PERSON, NOT A BOT: Turnstile's token for the sign-in form — the page's widget answers it, a test a constant */
enum Human[X]:
  case Token() extends Human[String]

object Human:
  def token[R[_]: Has[Human]]: String ! R = perform[Human](Token())

  def always[R[_], Rest[_], A](token: String)(p: A ! R)(using Split[Human, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Human, Rest, A, A](pure)([X] => (op: Human[X]) => op match
      case Token() => token))
