package okay.shop.effects

import okay.scala3.*
import okay.shop.model.{Booking, Inquiry, Order, Quote}

/** WHAT HAPPENED in a shop that its people may want to know at once (specs/shop.md "Notices"): the data, never its
 * words — a host says it where it likes (a chat, a push), the shop's site in hand */
enum Notice:
  case Ordered(order: Order)
  case Booked(booking: Booking)
  case Moved(booking: Booking)
  case Cancelled(booking: Booking)
  case Inquired(inquiry: Inquiry)
  case Asked(quote: Quote)
  /** a quote decided by its client: accepted (`result` the booking's or the order's ref) or declined */
  case Decided(quote: Quote, accepted: Boolean, result: String)

/** A NOTICE GIVEN: beside the owner's letter, the same moment; what a host does with it is its handler's */
enum Notify[X]:
  case Tell(notice: Notice) extends Notify[Unit]

object Notify:
  def tell[R[_]](n: Notice)(using In[Notify, R]): Unit ! R = perform[Notify](Tell(n))

  /** a site on its own: nobody else to tell (its owner has the letter) */
  def ignore[R[_], Rest[_], A](p: A ! R)(using Split[Notify, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Notify, Rest, A, A](pure)([X] => (op: Notify[X]) => op match
      case Tell(_) => ()))

  /** IN MEMORY, for tests: each notice kept */
  def kept[R[_], Rest[_], A](told: scala.collection.mutable.Buffer[Notice])(p: A ! R)(
    using Split[Notify, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Notify, Rest, A, A](pure)([X] => (op: Notify[X]) => op match
      case Tell(n) => told += n; ()))
