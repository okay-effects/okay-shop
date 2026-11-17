package okay.shop.commands

import okay.scala3.*
import okay.scala3.std.Clock
import okay.shop.app.{Bookings, Workshop}
import okay.shop.effects.{Mail, Note, Store}
import okay.shop.html.{Conf, Letters}
import okay.shop.model.*

/** WHAT A COMMAND DID, read from the store's own answer */
enum Ran:
  /** `told`: whether the client's letter was sent (no email: not sent) */
  case Moved(booking: Booking, told: Boolean)
  case Cancelled(booking: Booking, told: Boolean)
  case Paid(payment: Payment)
  /** the bookings asked for, as the workshop lists them (any status), by their start */
  case Listed(bookings: Vector[Booking])
  /** `Cannot`: there was nothing to do */
  case NothingToDo

/** WHY A COMMAND DID NOT RUN — either way nothing was changed */
enum Refusal:
  /** the engine has no operation for it today: said, never faked */
  case Missing(operation: String)
  /** the engine's own refusal, by its name: `no such booking`, `taken`, `owner`, `amount`, `start` … */
  case Refused(why: String)

/**
 * THE COMPILER (specs/commands.md "A1 as built"): a command run by the engine's EXISTING operations — the same
 * store steps, rules and letters the workshop and the booking's page use; nothing of its own.
 *
 * It takes a command that WAS ALREADY CONFIRMED by the person: said back in canonical words (`Words.say`) and
 * accepted. Never call it on a reading alone — a reader's choice is not an action. `who` is the person signed in
 * to the workshop (the payment's rules ask who marks it); the caller has checked that sign-in.
 */
object Run:
  /** a booking made by the staff: what the engine lacks */
  val book = "a booking made by the staff: Bookings.reserve keeps the client's contact and their own consent to " +
    "the privacy policy, which a master's word cannot give"
  /** a master's day off: what the engine lacks */
  val dayOff = "a master's day off: Master.off is the site's content, changed only where the content is edited; " +
    "the store has no step for it"

  def apply[R[_]](c: Conf, command: Command, who: String)(using In[Store, R], In[Mail, R], In[Clock, R])
    : Either[Refusal, Ran] ! R =
    def no(why: String): Either[Refusal, Ran] ! R = pure(Left(Refusal.Refused(why)))
    def tell(b: Booking, n: Note): Boolean ! R = b.email.fold(pure(false))(to => Mail.toClient[R](to, n))
    command match
      case Command.Book(_, _, _, _, _) => pure(Left(Refusal.Missing(book)))
      case Command.DayOff(_, _) => pure(Left(Refusal.Missing(dayOff)))
      // the booking's own move (`Bookings.rebook`: its mode and place, the store's step), the client told. Its
      // master is ASKED FOR, so it is theirs still or `taken`: the words confirmed name no other master (the
      // page's move may pass to another who is free). A booking of no master of the shop's moves by the engine's rule
      case Command.Move(ref, day, time) => Day.parse(s"$day ${Span.label(time)}") match
        case None => no("start")
        case Some(at) => Store.booking[R](ref).flatMap {
          case None => no("no such booking")
          case Some(b) =>
            val keep = if c.site.masters.exists(_.key == b.master) then b.master else ""
            Clock.now[R].flatMap(now => Bookings.rebook[R](c, b, at, keep, now)).flatMap {
              case Left(why) => no(why)
              case Right(x) => tell(x, Letters.rebooked(c, x)).map(sent => Right(Ran.Moved(x, sent)))
            }
        }
      // the workshop's cancel
      case Command.Cancel(ref) => Clock.now[R].flatMap(now => Workshop.unbook[R](c, ref, now)).map {
          case None => Left(Refusal.Refused("no such booking"))
          case Some((b, sent)) => Right(Ran.Cancelled(b, sent))
        }
      // the workshop's mark (`Payments.mark`'s rules, in the store's step), paid today
      case Command.Paid(ref, amount, way) => Clock.now[R].flatMap(now => Store.booking[R](ref).flatMap {
          case None => no("no such booking")
          case Some(b) => Store.pay[R](Mark(Of.Booking, ref, "paid", amount, way, Day.of(now)), None, who,
            c.owner(who), b.status == BookingStatus.Cancelled, Day.of(now), now).map(_.fold(
              why => Left(Refusal.Refused(why)), p => Right(Ran.Paid(p))))
        })
      // the workshop's list, the day's (Poland's) and the master's
      case Command.Show(day, master) => Clock.now[R].flatMap(now => Workshop.listed[R](now)).map(bs =>
          Right(Ran.Listed(bs.filter(b => Day.of(b.start) == day && master.forall(_ == b.master)))))
      case Command.Cannot => pure(Right(Ran.NothingToDo))
