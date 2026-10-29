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
final case class Shown(name: String, books: Boolean = false, more: Vector[(String, String)] = Vector.empty)

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
    case Inquiries, Bookings, Refresh, Out

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
            Seq(Button("Оновити")(OnList.Refresh), Button("Вийти", Style.Danger)(OnList.Out)))*)).flatMap {
          case OnList.Open(ref) => order[R](site, ref, None)
          case OnList.Inquiries => inquiries[R](site)
          case OnList.Bookings => bookings[R](site, None)
          case OnList.Refresh => orders[R](site, None)
          case OnList.Out => Staff.out[R].flatMap(_ => signIn[R](site, expired = false))
        }
    }

  // ---- AN ORDER

  private enum OnOrder:
    case To(status: Status)
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
    : Unit ! R =
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
    val receipt = Option.when(o.status == Status.Paid)(Part.link("Квитанція (rachunek) клієнта",
      s"/${o.lang.code}/receipt?ref=${o.ref}&k=${o.key}")).toSeq
    show[OnOrder, R](Screen[OnOrder](s"${o.ref} · ${o.status.label(uk)}")(
      (said.toSeq ++ emailed.toSeq ++ Seq(details(opened.job)) ++ receipt :+ Part.section("Історія")(history))*)(
      (moves ++ letter :+ Button("Назад")(OnOrder.Back))*)).flatMap {
      case OnOrder.Back => orders[R](site, None)
      case OnOrder.Letter => write[R](site, opened)
      case OnOrder.To(s) => asked[R](opened.job, s).flatMap {
        case None => shown[R](site, opened.copy(emailed = ""), None)
        case Some(m) => Staff.move[R](o.ref, m).flatMap {
          case Right(next) => shown[R](site, next, Some(Part.info(s"Статус: ${next.job.order.status.label(uk)}")))
          case Left(why) => shown[R](site, opened.copy(emailed = ""), Some(Part.problem(refusal(why))))
        }
      }
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
    case Open(b: (Booking, String))
    case Back

  def bookings[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, said: Option[Part]): Unit ! R =
    Staff.bookings[R].flatMap {
      case Left(why) => orders[R](site, Some(refusal(why)))
      case Right(bs) =>
        type B = (Booking, String)
        val table = Table(bs.toList)(Column.text[B]("Коли")(b => Day.time(b._1.start)),
          Column.text[B]("Послуга")(b => s"${b._2} · ${b._1.minutes} хв"), Column.text[B]("Клієнт")(_._1.name),
          Column.text[B]("Контакт")(_._1.contact), Column.text[B]("Де")(b => where(b._1)),
          Column.text[B]("Стан")(b => if b._1.active then "записано" else "скасовано"),
          Column.text[B]("№")(_._1.ref)).keyedBy(_._1.ref).whenEmpty("Записів немає.")
        show[OnBookings, R](Screen[OnBookings](s"Записи · ${site.name}")((said.toSeq :+ table)*)(
          Button.select("Відкрити", table)(OnBookings.Open(_)), Button("Назад")(OnBookings.Back))).flatMap {
          case OnBookings.Back => orders[R](site, None)
          case OnBookings.Open((b, service)) => booking[R](site, b, service)
        }
    }

  private enum OnBooking:
    case Cancel, Back

  private def booking[R[_]: Has[Ui]: Has[Staff]: Has[Human]](site: Shown, b: Booking, service: String): Unit ! R =
    val details = Part.details(Seq("Коли" -> Day.time(b.start), "Послуга" -> s"$service · ${b.minutes} хв",
      "Як" -> b.mode.fold("")(_.label(Lang.uk)), "Адреса" -> b.address.fold("")(_.line),
      "Примітки" -> b.address.fold("")(_.notes), "Карта" -> b.address.fold("")(_.mapLink),
      "Ціна" -> (if b.price > 0 then Money.zl(b.price) else ""),
      "Клієнт" -> b.name, "Контакт" -> b.contact, "Мова" -> b.lang.code,
      "Стан" -> (if b.active then "записано" else "скасовано"),
      "Згода RODO" -> s"${Day.time(b.consent.at)} · політика ${b.consent.policy}").filter(_._2.nonEmpty)*)
    val cancel = Option.when(b.active)(Button("Скасувати запис", Style.Danger)(OnBooking.Cancel)).toSeq
    show[OnBooking, R](Screen[OnBooking](s"${b.ref} · ${Day.time(b.start)}")(details)(
      (cancel :+ Button("Назад")(OnBooking.Back))*)).flatMap {
      case OnBooking.Back => bookings[R](site, None)
      case OnBooking.Cancel => Staff.unbook[R](b.ref).flatMap {
        case Right(sent) => bookings[R](site, Some(Part.info(if sent then "Запис скасовано, клієнту надіслано лист."
          else "Запис скасовано.")))
        case Left(why) => bookings[R](site, Some(Part.problem(refusal(why))))
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
    case "no such booking" => "Такого запису немає (або його вже скасовано)."
    case "no email" => "У клієнта немає email."
    case "no connection" => "Немає зв'язку з сервером."
    case other => s"Відмова: $other"
