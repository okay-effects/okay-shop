package okay.shop.workshop

import okay.business.model.{Button, Column, Field, Form, Part, Screen, Style, Table, Ui}
import okay.business.scala3.show
import okay.scala3.*
import okay.shop.model.*

/**
 * THE WORKSHOP (specs/sites.md "The workshop"): sign in by a link mailed to the staff, then the site's orders — one
 * opened, moved to a status the model offers, its client written to — and the inquiries. Its words Ukrainian, as
 * busi's workshop had them. The program asks only `Ui`, `Staff` and `Human`: in the page they are the Laminar face,
 * the site's API and Turnstile; in a test, `Play`, the site's own program on SQLite, a constant
 */
/** the site as the workshop shows it: its name, whether it takes bookings (the «Записи» screen), the host's own
 * pages for the person signed in (`Conf.more`: their text and address, links on the orders screen) */
final case class Shown(name: String, books: Boolean = false, more: Vector[(String, String)] = Vector.empty,
                       quotes: Boolean = false)

object Workshop:
  private val uk = Lang.uk

  def main[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, expired: Boolean): Unit ! R =
    Staff.me[R].flatMap {
      case Some(_) => Staff.more[R].flatMap(more => orders[R](site.copy(more = more), None))
      case None => signIn[R](site, expired)
    }

  // ---- SIGN-IN

  private enum Asked:
    case Email(email: String)

  def signIn[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, expired: Boolean, said: Option[String] = None)
    : Unit ! R =
    val form = Form.one(Field.text("Email").keyed("email").check("адреса email")(Contact.email(_).isDefined))
    show[Asked, R](Screen[Asked](s"Майстерня · ${site.name}")(
      (Seq(Part.text("Вхід за посиланням: його надішлемо на вашу пошту, якщо адреса є у списку майстерні.")) ++
        (if expired then Seq(Part.warning("Посилання застаріло або вже використане — попросіть нове.")) else Nil) ++
        said.map(Part.problem).toSeq :+ form)*)(
      Button.submit("Надіслати посилання", form)(Asked.Email(_)))).flatMap { case Asked.Email(email) =>
      Human.token[R].flatMap(t => Staff.link[R](email, t)).flatMap {
        case Right(()) => show[Unit, R](Screen[Unit]("Перевірте пошту")(
            Part.info(s"Якщо $email є у списку майстерні, посилання вже в дорозі. Воно діє 15 хвилин, один раз."))(
            Button("Попросити ще раз")(()))).flatMap(_ => signIn[R](site, expired = false))
        case Left(why) => signIn[R](site, expired = false, Some(refusal(why)))
      }
    }

  // ---- THE ORDERS

  private enum OnList:
    case Open(ref: String)
    case Inquiries, Bookings, Quotes, Refresh, Out

  def orders[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, said: Option[String]): Unit ! R =
    Staff.orders[R].flatMap {
      case Left("sign in") => signIn[R](site, expired = false)
      case Left(why) => show[OnList, R](Screen[OnList]("Замовлення")(Part.problem(refusal(why)))(
          Button("Оновити")(OnList.Refresh))).flatMap(_ => orders[R](site, None))
      case Right(jobs) =>
        val table = Table(jobs.toList)(
          Column.text[Job]("№")(_.order.ref), Column.text[Job]("Дата")(j => Day.time(j.order.placed)),
          Column.text[Job]("Статус")(_.order.status.label(uk)), Column.text[Job]("Клієнт")(_.order.name),
          Column.text[Job]("Послуга")(j => if j.service.nonEmpty then j.service else "заявка з сайту"),
          Column.text[Job]("Ціна")(_.price)).keyedBy(_.order.ref).whenEmpty("Замовлень ще немає.")
        val more = site.more.map((text, href) => Part.link(text, href))
        show[OnList, R](Screen[OnList](s"Замовлення · ${site.name}")((said.map(Part.info).toSeq ++ more :+ table)*)(
          (Seq(Button.select("Відкрити", table)(j => OnList.Open(j.order.ref)), Button("Заявки")(OnList.Inquiries)) ++
            Option.when(site.books)(Button("Записи")(OnList.Bookings)) ++
            Option.when(site.quotes)(Button("Запити ціни")(OnList.Quotes)) ++
            Seq(Button("Оновити")(OnList.Refresh), Button("Вийти", Style.Danger)(OnList.Out)))*)).flatMap {
          case OnList.Open(ref) => order[R](site, ref, None)
          case OnList.Inquiries => inquiries[R](site)
          case OnList.Bookings => bookings[R](site, None)
          case OnList.Quotes => quotes[R](site, None)
          case OnList.Refresh => orders[R](site, None)
          case OnList.Out => Staff.out[R].flatMap(_ => signIn[R](site, expired = false))
        }
    }

  // ---- AN ORDER

  private enum OnOrder:
    case To(status: Status)
    case Paying(action: String)
    case Buyer, Issue
    case Letter, Back

  /** the button that moves an order to `s`, in the workshop's words */
  def moveLabel(s: Status): String = s match
    case Status.Accepted => "Прийнято (BLIK отримано)"
    case Status.ParcelIn => "Посилка прийшла"
    case Status.InWork => "В роботу"
    case Status.Ready => "Готово — лист клієнту"
    case Status.ParcelOut => "Відправлено — трекінг, лист клієнту"
    case Status.Delivered => "Доставлено"
    case Status.Paid => "Оплачено — сума"
    case Status.Cancelled => "Скасувати"
    case other => other.label(uk)

  def order[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, ref: String, said: Option[Part]): Unit ! R =
    Staff.open[R](ref).flatMap {
      case Left(why) => orders[R](site, Some(refusal(why)))
      case Right(opened) => shown[R](site, opened, said)
    }

  private def shown[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, opened: Opened, said: Option[Part])
    : Unit ! R = Staff.paying[R](Of.Order, opened.job.order.ref).flatMap(paying =>
    Staff.invoicing[R](Of.Order, opened.job.order.ref).map(paying -> _.toOption)).flatMap { (paying, side) =>
    val (pay, owner) = paying.getOrElse((None, false))
    val o = opened.job.order
    val emailed = opened.emailed match
      case "sent" => Some(Part.info("Клієнту надіслано лист."))
      case "not sent" => Some(Part.warning("Лист клієнту НЕ надіслано: пошта клієнтам ще не налаштована " +
          "або не спрацювала (docs/runbook.md, крок 4)."))
      case _ => None
    val history = Table(opened.history.toList)(Column.text[(Long, Event)]("Коли")(h => Day.time(h._1)),
      Column.text[(Long, Event)]("Що")(h => happened(h._2))).whenEmpty("—")
    val moves = opened.job.next.map(s => Button(moveLabel(s), if s == Status.Cancelled then Style.Danger
      else Style.Primary)(OnOrder.To(s)))
    val letter = o.email.map(_ => Button("Лист клієнту")(OnOrder.Letter)).toSeq
    // the client's own receipt, as the track page links it
    val receipt = Option.when(o.status == Status.Paid || pay.exists(_.paid))(Part.link(
      "Квитанція (rachunek) клієнта", s"/${o.lang.code}/receipt?ref=${o.ref}&k=${o.key}")).toSeq
    show[OnOrder, R](Screen[OnOrder](s"${o.ref} · ${o.status.label(uk)}")(
      (said.toSeq ++ emailed.toSeq ++ Seq(details(opened.job), paymentPart(pay)) ++ receipt ++
        invoiceParts(side) :+ Part.section("Історія")(history))*)(
      (moves ++ payButtons(pay, owner).map((t, a) => Button(t)(OnOrder.Paying(a))) ++
        invoiceButtons(side, pay.exists(_.paid) || o.status == Status.Paid, OnOrder.Buyer, OnOrder.Issue) ++
        letter :+ Button("Назад")(OnOrder.Back))*)).flatMap {
      case OnOrder.Back => orders[R](site, None)
      case OnOrder.Buyer => buyer[R](Of.Order, o.ref, side.flatMap(_.buyer)).flatMap(told => order[R](site, o.ref, told))
      case OnOrder.Issue => issued[R](Of.Order, o.ref).flatMap(told => order[R](site, o.ref, told))
      case OnOrder.Letter => write[R](site, opened)
      case OnOrder.Paying(action) => paid[R](Of.Order, o.ref, pay, o.amount max offerPrice(opened.job), action)
          .flatMap(told => order[R](site, o.ref, told))
      case OnOrder.To(s) => asked[R](opened.job, s).flatMap {
        case None => shown[R](site, opened.copy(emailed = ""), None)
        case Some(m) => Staff.move[R](o.ref, m).flatMap {
          case Right(next) => shown[R](site, next, Some(Part.info(s"Статус: ${next.job.order.status.label(uk)}")))
          case Left(why) => shown[R](site, opened.copy(emailed = ""), Some(Part.problem(refusal(why))))
        }
      }
    }
  }

  // ---- INVOICES (specs/shop.md "Invoices")

  /** the invoice side, in the workshop's words: the buyer, the invoice and KSeF's status — nothing for a shop that
   * does not issue invoices and an order with no buyer */
  def invoiceParts(side: Option[InvoiceSide]): Seq[Part] = side.toSeq.filter(s => s.invoicing || s.buyer.isDefined)
    .flatMap { s =>
      Seq(Part.details("Фактура (faktura)" -> (s.invoice match
        case Some((_, number, ksef)) => s"$number · ${ksefState(ksef)}"
        case None => s.buyer.fold("клієнт не просив")(b => s"дані є: ${b.name}, NIP ${b.nip}, ${b.address}")))) ++
        s.invoice.map((id, number, _) => Part.link(s"Фактура $number", s"/workshop/invoices/view?id=$id")).toSeq
    }

  def ksefState(s: String): String = s match
    case "none" => "поза KSeF"
    case "queued" => "чекає на KSeF"
    case "sent" => "надіслано в KSeF"
    case "accepted" => "прийнято в KSeF"
    case "rejected" => "KSeF ВІДХИЛИВ"
    case x => x

  /** the buyer's data given or changed; issuing once paid, with a buyer, before an invoice */
  def invoiceButtons[A](side: Option[InvoiceSide], paid: Boolean, buyer: A, issue: A): Seq[Button[A]] =
    side.filter(_.invoicing).toSeq.flatMap(s =>
      Option.when(s.invoice.isEmpty)(Button("Дані для фактури")(buyer)).toSeq ++
        Option.when(s.invoice.isEmpty && s.buyer.isDefined && paid)(Button("Виставити фактуру")(issue)).toSeq)

  private def buyer[R[_]: Has[Ui]: Has[Staff]](of: Of, ref: String, b: Option[okay.shop.invoice.Buyer])
    : Option[Part] ! R =
    val f = Form((Field.text("Назва фірми").keyed("name").initially(b.fold("")(_.name)),
      Field.text("NIP").keyed("nip").check("10 цифр NIP")(okay.shop.invoice.Nip.valid).initially(b.fold("")(_.nip)),
      Field.text("Вулиця і номер").keyed("street").initially(b.fold("")(_.street)),
      Field.text("Індекс (00-000)").keyed("postcode").check("00-000")(_.trim.matches("[0-9]{2}-[0-9]{3}"))
        .initially(b.fold("")(_.postcode)),
      Field.text("Місто").keyed("city").initially(b.fold("")(_.city))))
    ask[(String, String, String, String, String), R]("Дані для фактури", f, "Зберегти").flatMap {
      case None => pure(None)
      case Some((n, nip, st, pc, city)) => okay.shop.invoice.Buyer.check(n, nip, st, pc, city) match
        case Left(why) => pure(Some(Part.problem(refusal(why))))
        case Right(x) => Staff.setBuyer[R](of, ref, x).map {
            case Right(_) => Some(Part.info("Дані для фактури збережено."))
            case Left(why) => Some(Part.problem(refusal(why)))
          }
    }

  private def issued[R[_]: Has[Staff]](of: Of, ref: String): Option[Part] ! R = Staff.issue[R](of, ref).map {
    case Right((number, ksef)) => Some(Part.info(s"Фактуру $number виставлено · ${ksefState(ksef)}."))
    case Left(why) => Some(Part.problem(refusal(why)))
  }

  // ---- MONEY (specs/shop.md "Money")

  /** a payment, in the workshop's words */
  def paymentPart(pay: Option[Payment]): Part = Part.details("Оплата" -> (pay match
    case Some(p) if p.paid => (Seq(Money.zl(p.amount), method(p.method), p.day) ++ Option.when(p.receipt.nonEmpty)(
      p.receipt) ++ Option.when(p.note.nonEmpty)(p.note)).mkString(" · ")
    case Some(p) if p.state == PayState.Refunded => s"повернено ${p.refunded} · ${Money.zl(p.amount)}"
    case _ => "не оплачено"))

  def method(m: Method): String = m.label(uk)

  /** what may be done with a payment: marked paid; the owner's change, undo and refund */
  def payButtons(pay: Option[Payment], owner: Boolean): Seq[(String, String)] = pay match
    case Some(p) if p.paid => if owner then Seq("Змінити оплату" -> "paid", "Не оплачено" -> "unpaid",
      "Повернення коштів" -> "refund") else Nil
    case Some(_) if owner => Seq("Оплачено" -> "paid")
    case Some(_) => Nil
    case None => Seq("Оплачено" -> "paid")

  private val methods = Seq("Готівка (cash)" -> Method.Cash, "Картка (card)" -> Method.Card, "BLIK" -> Method.Blik,
    "Переказ (transfer)" -> Method.Transfer, "Інше (other)" -> Method.Other)

  /** a payment marked: `paid` asks the amount (`suggested` to start with), the method, the day (blank: today), a
   * note; what was done, or why not */
  private def paid[R[_]: Has[Ui]: Has[Staff]](of: Of, ref: String, pay: Option[Payment], suggested: Long,
                                              action: String): Option[Part] ! R =
    val done: Either[String, Payment] => Option[Part] = {
      case Right(p) => Some(Part.info("Оплата: " + (p.state match
        case PayState.Paid => s"${Money.zl(p.amount)} · ${method(p.method)} · ${p.day}"
        case PayState.Refunded => s"повернено ${Money.zl(p.amount)}"
        case PayState.Unpaid => "не оплачено")))
      case Left(why) => Some(Part.problem(refusal(why)))
    }
    if action != "paid" then Staff.pay[R](of, ref, action).map(done)
    else
      val amount = BigDecimal(pay.filter(_.amount > 0).fold(suggested)(_.amount)) / 100
      val f = Form((Field.money("Сума, zł", "PLN").keyed("amount").check("більше нуля")(_ > 0)
          .initially(amount.setScale(2)),
        Field.choice("Спосіб", methods*).keyed("method").initially(pay.fold(Method.Cash)(_.method)),
        Field.text("Дата (2026-10-29; порожньо — сьогодні)").keyed("day").optional,
        Field.text("Примітка").keyed("note").optional))
      ask[(BigDecimal, Method, Option[String], Option[String]), R]("Оплата", f, "Записати").flatMap {
        case None => pure(None)
        case Some((a, m, d, n)) => Staff.pay[R](of, ref, "paid", a.bigDecimal.toPlainString, m.key, d.getOrElse(""),
          n.getOrElse("")).map(done)
      }

  /** what a move needs asked first: the amount paid, the tracking number, the reason to cancel */
  private def asked[R[_]: Has[Ui]](job: Job, to: Status): Option[Move] ! R = to match
    case Status.Paid =>
      val initial = BigDecimal(job.order.amount max offerPrice(job)) / 100
      val f = Form.one(Field.money("Сума, zł", "PLN").keyed("amount").check("більше нуля")(_ > 0)
        .initially(initial.setScale(2)))
      ask[BigDecimal, R]("Оплачено", f, "Записати оплату").map(_.map(a => Move(to,
           amount = a.bigDecimal.toPlainString)))
    case Status.ParcelOut =>
      val f = Form.one(Field.text("Номер відправлення (InPost)").keyed("tracking").optional)
      ask[Option[String], R]("Відправлено", f, "Відправлено").map(_.map(t => Move(to, tracking = t.getOrElse(""))))
    case Status.Cancelled =>
      val f = Form.one(Field.longText("Причина").keyed("reason"))
      ask[String, R]("Скасувати замовлення", f, "Скасувати замовлення").map(_.map(n => Move(to, note = n)))
    case _ => pure(Some(Move(to)))

  /** a form asked, in the workshop's words: its value, or nothing when the person goes back */
  private def ask[P, R[_]: Has[Ui]](title: String, form: Form[P], button: String): Option[P] ! R =
    show[Option[P], R](Screen[Option[P]](title)(form)(Button.submit(button, form)(Some(_)),
      Button("Назад")(None)))

  private def offerPrice(job: Job): Long = Money.parse(job.price).getOrElse(0L)

  private def write[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, opened: Opened): Unit ! R =
    val o = opened.job.order
    val f = Form((Field.text("Тема").keyed("subject").initially(s"${site.name} — ${o.ref}"),
      Field.longText("Текст").keyed("body")))
    ask[(String, String), R](s"Лист клієнту · ${o.email.getOrElse("")}", f, "Надіслати").flatMap {
      case None => shown[R](site, opened.copy(emailed = ""), None)
      case Some((subject, body)) => Staff.letter[R](o.ref, subject, body).flatMap {
        case Right(true) => order[R](site, o.ref, Some(Part.info("Лист надіслано.")))
        case Right(false) => order[R](site, o.ref, Some(Part.warning("Лист НЕ надіслано: пошта клієнтам ще не " +
            "налаштована або не спрацювала.")))
        case Left(why) => order[R](site, o.ref, Some(Part.problem(refusal(why))))
      }
    }

  /** an order's fields, in the workshop's words */
  def details(job: Job): Part =
    val o = job.order
    def handover(h: String) = h match
      case "self" => "принесе сам(а)"
      case "visit" => "візит майстра"
      case "delivery" => "доставка"
      case _ => h
    def delivery(d: String) = d match
      case "courier" => "InPost"
      case "in_person" => "особисто"
      case _ => d
    def payment(p: String) = p match
      case "transfer" => "переказ (BLIK)"
      case "cod" => "накладений платіж"
      case _ => p
    val rows = Seq("Клієнт" -> o.name, "Контакт" -> o.contact, "Послуга" -> job.service, "Ціна" -> job.price,
      "Деталі" -> o.need, "Тканина" -> o.fabric, "Передача" -> handover(o.handover),
      "Доставка" -> delivery(o.deliveryMethod), "Оплата" -> payment(o.paymentMethod),
      "Вартість речі" -> (if o.itemValue.isEmpty then "" else o.itemValue + " zł"), "Мова" -> o.lang.code,
      "Згода RODO" -> s"${Day.time(o.consent.at)} · політика ${o.consent.policy}", "Трекінг" -> o.tracking,
      "Оплачено" -> (if o.amount > 0 then Money.zl(o.amount) else ""), "Примітка" -> o.note)
    Part.details(rows.filter(_._2.nonEmpty)*)

  /** an event of the journal, in the workshop's words */
  def happened(e: Event): String = e match
    case Event.Placed(_) => "Замовлення прийнято з сайту"
    case Event.Moved(s, note, amount, tracking) =>
      (Seq(s.label(uk)) ++ Option.when(amount > 0)(Money.zl(amount)) ++ Option.when(tracking.nonEmpty)(tracking) ++
        Option.when(note.nonEmpty)(note)).mkString(" · ")
    case Event.Emailed(to, subject, sent) => s"Лист «$subject» → $to: ${if sent then "надіслано" else "НЕ надіслано"}"
    case Event.Inquired(_) => "Заявка"

  // ---- THE INQUIRIES

  private enum OnInquiries:
    case Open(i: Inquiry)
    case Back

  def inquiries[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown): Unit ! R =
    Staff.inquiries[R].flatMap {
      case Left(why) => orders[R](site, Some(refusal(why)))
      case Right(is) =>
        val table = Table(is.toList)(Column.text[Inquiry]("№")(_.ref),
             Column.text[Inquiry]("Дата")(i => Day.time(i.at)),
          Column.text[Inquiry]("Хто")(_.name), Column.text[Inquiry]("Тема")(_.subject),
          Column.text[Inquiry]("Вид")(_.kind)).keyedBy(_.ref).whenEmpty("Заявок немає.")
        show[OnInquiries, R](Screen[OnInquiries]("Заявки")(table)(
          Button.select("Відкрити", table)(OnInquiries.Open(_)), Button("Назад")(OnInquiries.Back))).flatMap {
          case OnInquiries.Back => orders[R](site, None)
          case OnInquiries.Open(i) => show[Unit, R](Screen[Unit](s"${i.ref} · ${i.subject}")(Part.details(
              "Хто" -> i.name, "Контакт" -> i.contact, "Вид" -> i.kind, "Текст" -> i.body,
              "Конфіденційно" -> (if i.confidential then "так" else "ні"), "Мова" -> i.lang.code,
              "Згода RODO" -> s"${Day.time(i.consent.at)} · політика ${i.consent.policy}"))(Button("Назад")(())))
            .flatMap(_ => inquiries[R](site))
        }
    }

  // ---- THE BOOKINGS

  private enum OnBookings:
    case Open(b: Booked)
    case Mine, All
    case Back

  /** the bookings: all of them, or — for a master signed in, by default — their own (specs/shop.md "Masters") */
  def bookings[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, said: Option[Part], all: Boolean = false)
    : Unit ! R =
    Staff.bookings[R].flatMap {
      case Left(why) => orders[R](site, Some(refusal(why)))
      case Right((list, master)) =>
        val mine = master && !all
        val shown = if mine then list.filter(_.mine) else list
        val masters = list.exists(_.master.nonEmpty)
        val table = Table(shown.toList)((Seq(Column.text[Booked]("Коли")(b => Day.time(b.booking.start)),
          Column.text[Booked]("Послуга")(b => s"${b.service} · ${b.booking.minutes} хв")) ++
          Option.when(masters)(Column.text[Booked]("Майстер")(_.master)).toSeq ++ Seq(
          Column.text[Booked]("Клієнт")(_.booking.name), Column.text[Booked]("Контакт")(_.booking.contact),
          Column.text[Booked]("Де")(b => where(b.booking)),
          Column.text[Booked]("Стан")(b => state(b.booking)),
          Column.text[Booked]("№")(_.booking.ref)))*).keyedBy(_.booking.ref).whenEmpty("Записів немає.")
        val filter = if !master then Seq.empty else if mine then Seq(Button("Усі записи")(OnBookings.All))
          else Seq(Button("Мої записи")(OnBookings.Mine))
        show[OnBookings, R](Screen[OnBookings](s"${if mine then "Мої записи" else "Записи"} · ${site.name}")(
          (said.toSeq :+ table)*)((Button.select("Відкрити", table)(OnBookings.Open(_)) +: filter :+
          Button("Назад")(OnBookings.Back))*)).flatMap {
          case OnBookings.Back => orders[R](site, None)
          case OnBookings.Mine => bookings[R](site, None)
          case OnBookings.All => bookings[R](site, None, all = true)
          case OnBookings.Open(b) => booking[R](site, b)
        }
    }

  private enum OnBooking:
    case Paying(action: String)
    case Buyer, Issue
    case Cancel, NoShow, Back

  private def booking[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, listed: Booked, said: Option[Part] = None)
    : Unit ! R = Staff.paying[R](Of.Booking, listed.booking.ref).flatMap(paying =>
    Staff.invoicing[R](Of.Booking, listed.booking.ref).map(paying -> _.toOption)).flatMap { (paying, side) =>
    val (pay, owner) = paying.getOrElse((None, false))
    val (b, service) = (listed.booking, listed.service)
    val details = Part.details(Seq("Коли" -> Day.time(b.start), "Послуга" -> s"$service · ${b.minutes} хв",
      "Майстер" -> listed.master,
      "Як" -> b.mode.fold("")(_.label(Lang.uk)), "Адреса" -> b.address.fold("")(_.line),
      "Примітки" -> b.address.fold("")(_.notes), "Карта" -> b.address.fold("")(_.mapLink),
      "Ціна" -> (if b.price > 0 then Money.zl(b.price) else ""),
      "Клієнт" -> b.name, "Контакт" -> b.contact, "Мова" -> b.lang.code,
      "Стан" -> state(b),
      "Згода RODO" -> s"${Day.time(b.consent.at)} · політика ${b.consent.policy}").filter(_._2.nonEmpty)*)
    val cancel = Option.when(b.active)(Button("Скасувати запис", Style.Danger)(OnBooking.Cancel)).toSeq
    val missed = Option.when(b.active)(Button("Не прийшов")(OnBooking.NoShow)).toSeq
    val receipt = Option.when(pay.exists(_.paid))(Part.link("Квитанція (rachunek) клієнта",
      s"/${b.lang.code}/receipt?ref=${b.ref}&k=${b.key}")).toSeq
    show[OnBooking, R](Screen[OnBooking](s"${b.ref} · ${Day.time(b.start)}")((said.toSeq ++ Seq(details,
      paymentPart(pay)) ++ receipt ++ invoiceParts(side))*)((payButtons(pay, owner).map((t, a) =>
      Button(t)(OnBooking.Paying(a))) ++ invoiceButtons(side, pay.exists(_.paid), OnBooking.Buyer, OnBooking.Issue) ++
      missed ++ cancel :+ Button("Назад")(OnBooking.Back))*)).flatMap {
      case OnBooking.Back => bookings[R](site, None)
      case OnBooking.Buyer => buyer[R](Of.Booking, b.ref, side.flatMap(_.buyer)).flatMap(told =>
          booking[R](site, listed, told))
      case OnBooking.Issue => issued[R](Of.Booking, b.ref).flatMap(told => booking[R](site, listed, told))
      case OnBooking.Paying(action) => paid[R](Of.Booking, b.ref, pay, b.price, action)
          .flatMap(told => booking[R](site, listed, told))
      case OnBooking.NoShow => Staff.noShow[R](b.ref).flatMap {
        case Right(()) => bookings[R](site, Some(Part.info("Позначено: клієнт не прийшов.")))
        case Left(why) => booking[R](site, listed, Some(Part.problem(if why == "no such booking" then
          "Лише запис, час якого вже минув." else refusal(why))))
      }
      case OnBooking.Cancel => Staff.unbook[R](b.ref).flatMap {
        case Right(sent) => bookings[R](site, Some(Part.info(if sent then "Запис скасовано, клієнту надіслано лист."
          else "Запис скасовано.")))
        case Left(why) => bookings[R](site, Some(Part.problem(refusal(why))))
      }
    }
  }

  /** a booking's state, in the workshop's words */
  def state(b: Booking): String = b.status match
    case BookingStatus.Booked => "записано"
    case BookingStatus.Cancelled => "скасовано"
    case BookingStatus.NoShow => "не прийшов"

  // ---- THE INQUIRIES FOR A QUOTE (specs/shop.md "Inquiry and quote")

  private enum OnQuotes:
    case Open(q: (Quote, String))
    case Back

  def quotes[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, said: Option[Part]): Unit ! R =
    Staff.quotes[R].flatMap {
      case Left(why) => orders[R](site, Some(refusal(why)))
      case Right(qs) =>
        type Q = (Quote, String)
        val table = Table(qs.toList)(Column.text[Q]("Коли")(q => Day.time(q._1.placed)),
          Column.text[Q]("Послуга")(_._2), Column.text[Q]("Клієнт")(_._1.name),
          Column.text[Q]("Стан")(_._1.status.label(uk)), Column.text[Q]("Ціна")(_._1.priced),
          Column.text[Q]("№")(_._1.ref)).keyedBy(_._1.ref).whenEmpty("Запитів немає.")
        show[OnQuotes, R](Screen[OnQuotes](s"Запити ціни · ${site.name}")((said.toSeq :+ table)*)(
          Button.select("Відкрити", table)(OnQuotes.Open(_)), Button("Назад")(OnQuotes.Back))).flatMap {
          case OnQuotes.Back => orders[R](site, None)
          case OnQuotes.Open(q) => quote[R](site, q._1, q._2)
        }
    }

  private enum OnQuote:
    case Sent(price: String, priceTo: String, start: String, note: String)
    case Back

  private def quote[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, q: Quote, service: String): Unit ! R =
    val details = Part.details(Seq("Послуга" -> service, "Опис" -> q.description, "Бажані дати" -> q.preferred,
      "Як" -> q.mode.fold("")(_.label(uk)), "Адреса" -> q.address.fold("")(_.line),
      "Примітки" -> q.address.fold("")(_.notes), "Клієнт" -> q.name, "Контакт" -> q.contact,
      "Стан" -> q.status.label(uk), "Ціна" -> q.priced, "Коли" -> (if q.start > 0 then Day.time(q.start) else ""),
      "Коментар" -> q.note, "Стало" -> q.result).filter(_._2.nonEmpty)*)
    val open = q.status == QuoteStatus.Asked || q.status == QuoteStatus.Quoted
    val form = Form((Field.text("Ціна, zł").keyed("price"), Field.text("До, zł (діапазон)").keyed("priceTo"),
      Field.text("Час (2026-11-03 14:00)").keyed("start"), Field.longText("Коментар").keyed("note")))
    show[OnQuote, R](Screen[OnQuote](s"${q.ref} · ${q.name}")((Seq(details) ++ Option.when(open)(form))*)(
      ((if open then Seq(Button.submit("Надіслати оцінку", form)((p, t, w, n) => OnQuote.Sent(p, t, w, n)))
        else Nil) :+
        Button("Назад")(OnQuote.Back))*)).flatMap {
      case OnQuote.Back => quotes[R](site, None)
      case OnQuote.Sent(price, to, start, note) => Staff.reply[R](q.ref, price, to, start, note).flatMap {
        case Right(sent) => quotes[R](site, Some(Part.info(if sent then "Оцінку надіслано клієнту."
          else "Оцінку збережено.")))
        case Left(why) => quotes[R](site, Some(Part.problem(refusal(why))))
      }
    }

  /** how and where a booking meets, short: the mode and the address (none before the modes) */
  def where(b: Booking): String = b.mode.fold("")(m => (m.label(Lang.uk) +: b.address.map(_.line).toSeq)
    .mkString(" · "))

  /** the server's word for a refusal, in the workshop's */
  def refusal(why: String): String = why match
    case "captcha" => "Перевірка Turnstile не пройдена — спробуйте ще раз."
    case "limit" => "Забагато спроб — зачекайте 10 хвилин."
    case "sign in" => "Сесія закінчилась — увійдіть знову."
    case "no such order" => "Такого замовлення немає."
    case "price" => "Вкажіть ціну (zł)."
    case "priceTo" => "«До» має бути не меншою за ціну."
    case "start" => "Час — у формі 2026-11-03 14:00."
    case "no such quote" => "Такого запиту немає (або на нього вже відповіли)."
    case "no such booking" => "Такого запису немає (або його вже скасовано)."
    case "no email" => "У клієнта немає email."
    case "owner" => "Лише власник може змінити, скасувати чи повернути оплату."
    case "state" => "Стан оплати вже інший — оновіть сторінку."
    case "amount" => "Сума — більше нуля, до 1 000 000 zł."
    case "day" => "Дата — у формі 2026-10-29, не пізніше сьогодні."
    case "method" => "Оберіть спосіб оплати."
    case "no connection" => "Немає зв'язку з сервером."
    case "buyer.name" => "Вкажіть назву фірми."
    case "buyer.nip" => "NIP — 10 цифр із контрольною."
    case "buyer.address" => "Адреса: вулиця, індекс 00-000, місто."
    case "buyer" => "Спершу дані для фактури."
    case "seller" => "Власник ще не заповнив дані продавця (Dane do faktur)."
    case "unpaid" => "Фактура — лише після оплати."
    case "issued" => "Фактуру вже виставлено."
    case other => s"Відмова: $other"
