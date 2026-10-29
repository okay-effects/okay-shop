package okay.shop.model

/** HOW A PAYMENT CAME (specs/shop.md "Money") */
enum Method(val key: String, val label: T):
  case Cash extends Method("cash", T("Наличные", "Gotówka", "Cash", "Готівка"))
  case Card extends Method("card", T("Карта", "Karta", "Card", "Картка"))
  case Blik extends Method("blik", T("BLIK", "BLIK", "BLIK", "BLIK"))
  case Transfer extends Method("transfer", T("Перевод", "Przelew", "Transfer", "Переказ"))
  case Other extends Method("other", T("Другое", "Inne", "Other", "Інше"))

object Method:
  def parse(s: String): Option[Method] = values.find(_.key == s.trim)

enum PayState(val key: String):
  case Paid extends PayState("paid")
  case Unpaid extends PayState("unpaid")
  case Refunded extends PayState("refunded")

object PayState:
  def parse(s: String): Option[PayState] = values.find(_.key == s)

/** what a payment is of: an order, a booking, or income from outside the system */
enum Of(val key: String):
  case Order extends Of("order")
  case Booking extends Of("booking")
  case Manual extends Of("manual")

object Of:
  def parse(s: String): Option[Of] = values.find(_.key == s.trim)

/** a change of a payment: when, by whom, what was done, and the amount, method and day it left */
final case class PayLog(at: Long, by: String, what: String, amount: Long, method: Method, day: String)

/**
 * A PAYMENT (specs/shop.md "Money"): of an order, a booking or manual income, by its ref; the amount (grosze), the
 * method, the day paid (Poland's), its state, who marked it last and when, a note; a refund's day; a manual one's
 * description, service and master; its receipt's number; its log
 */
final case class Payment(of: Of, ref: String, amount: Long, method: Method, day: String, state: PayState, by: String,
                         at: Long, note: String = "", refunded: String = "", description: String = "",
                         service: String = "", master: String = "", receipt: String = "",
                         log: Vector[PayLog] = Vector.empty):
  def paid: Boolean = state == PayState.Paid
  /** whether it was ever money in: paid, or paid and refunded since */
  def income: Boolean = state == PayState.Paid || state == PayState.Refunded

/** what is asked of a payment: `paid` (new or changed), `unpaid` (undone), `refund` */
final case class Mark(of: Of, ref: String, action: String, amount: Long = 0, method: Method = Method.Cash,
                      day: String = "", note: String = "", description: String = "", service: String = "",
                      master: String = "")

object Payments:
  val most: Long = 100000000L // 1 000 000 zł

  /** a day of the calendar, `YYYY-MM-DD`, from 2000 */
  def isDay(s: String): Boolean = s.matches("20[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])")

  /**
   * THE RULES: `current` (none: never marked) changed as `m` asks, by `who` (the owner or not), on `today`; the name
   * of the refusal — `owner`, `state`, `amount`, `day`, `description`, `note`, `action` — or the payment
   */
  def mark(current: Option[Payment], m: Mark, who: String, owner: Boolean, cancelled: Boolean, today: String,
           now: Long): Either[String, Payment] =
    val state = current.map(_.state).getOrElse(PayState.Unpaid)
    def logged(p: Payment, what: String) = p.copy(by = who, at = now,
      log = p.log :+ PayLog(now, who, what, p.amount, p.method, if what == "refunded" then p.refunded else p.day))
    m.action match
      case "paid" =>
        if m.of == Of.Manual && !owner then Left("owner")
        else if state != PayState.Unpaid && !owner then Left("owner")
        else if cancelled && !owner then Left("owner")
        else if m.amount <= 0 || m.amount > most then Left("amount")
        else if !isDay(m.day) || m.day > today then Left("day")
        else if m.note.length > 300 then Left("note")
        else if m.of == Of.Manual && (m.description.trim.isEmpty || m.description.length > 200) then
          Left("description")
        else
          val base = current.getOrElse(Payment(m.of, m.ref, 0, m.method, m.day, PayState.Unpaid, who, now))
          Right(logged(base.copy(amount = m.amount, method = m.method, day = m.day, state = PayState.Paid,
            note = m.note.trim, refunded = "", description = m.description.trim.take(200),
            service = m.service.trim.take(60), master = m.master.trim.take(60)),
            if state == PayState.Unpaid then "paid" else "changed"))
      case "unpaid" =>
        if !owner then Left("owner")
        else if state == PayState.Unpaid then Left("state")
        else Right(logged(current.get.copy(state = PayState.Unpaid, refunded = ""), "unpaid"))
      case "refund" =>
        if !owner then Left("owner")
        else if state != PayState.Paid then Left("state")
        else
          val day = if m.day.isEmpty then today else m.day
          if !isDay(day) || day > today || day < current.get.day then Left("day")
          else Right(logged(current.get.copy(state = PayState.Refunded, refunded = day,
            note = if m.note.trim.nonEmpty then m.note.trim.take(300) else current.get.note), "refunded"))
      case _ => Left("action")

  /** a receipt's number: `RACH/<year>/<n>`, at least four digits */
  def receipt(year: String, n: Long): String = f"RACH/$year/$n%04d"

  /** an old paid order's receipt number, as the track page has always given it */
  def oldReceipt(o: Order, prefix: String): String = s"RACH-${Day.of(o.paidAt).take(4)}-${o.ref.stripPrefix(prefix +
    "-")}"

  /** THE PAYMENTS AS THEY COUNT: the records, and an order the old workflow moved to `paid` (no record of its own)
   * as paid by its amount on its day */
  def effective(orders: Vector[Order], records: Vector[Payment], prefix: String): Vector[Payment] =
    val recorded = records.filter(_.of == Of.Order).map(_.ref).toSet
    records ++ orders.filter(o => o.status == Status.Paid && o.amount > 0 && !recorded(o.ref)).map(o =>
      Payment(Of.Order, o.ref, o.amount, if o.paymentMethod == "transfer" then Method.Transfer else Method.Other,
        Day.of(o.paidAt), PayState.Paid, "", o.paidAt, receipt = oldReceipt(o, prefix)))

  /** the payment of one order (its record, or its old `paid`) */
  def ofOrder(o: Order, record: Option[Payment], prefix: String): Option[Payment] =
    record.orElse(effective(Vector(o), Vector.empty, prefix).headOption)
