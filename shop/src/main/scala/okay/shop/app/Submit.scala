package okay.shop.app

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.http.Query
import okay.serverless.cloud.{Fetch, Request, text}
import okay.shop.model.{Lang, T}

/**
 * A FORM OF THE PAGES POSTED (the pages' script, `site.js`): its fields as JSON to `/api/order` or `/api/inquiry`,
 * the answer read into what the done panel shows. A `Fetch` program to `base` (the page's own origin) — the browser's
 * `fetch` answers it in the page, a test's function in a test
 */
object Submit:
  enum Outcome:
    case Placed(ref: String, blik: Option[String], price: Option[String], paczkomat: Option[String])
    /** a booking made or moved: its ref, its time as the client reads it, the link that manages it */
    case Booked(ref: String, when: String, manage: String, master: String = "")
    /** an inquiry for a quote asked: its ref and the link to follow it */
    case Asked(ref: String, manage: String)
    /** refused, and why: `name`, `contact`, `consent`, `captcha`, `limit`, … — or the network */
    case Refused(why: String)

  def send[R[_]](base: String, kind: String, fields: Vector[(String, String)])(using In[Fetch, R]): Outcome ! R =
    val body = Json.print(Json.Obj(fields.map((k, v) => k -> Json.str(v))))
    Fetch[R](base, Request.post(s"/api/$kind", body))
      .map { r =>
        val j = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }
        def s(k: String) = j.flatMap(_.get(k)).collect { case Json.Str(v) => v }
        if r.status != 200 then Outcome.Refused(s("error").getOrElse(if r.status == 0 then "network"
          else s"status ${r.status}"))
        else (s("ref"), s("manage")) match
          case (None, _) => Outcome.Refused("answer")
          case (Some(ref), Some(m)) if j.flatMap(_.get("quote")).contains(Json.Bool(true)) => Outcome.Asked(ref, m)
          case (Some(ref), Some(m)) => Outcome.Booked(ref, s("when").getOrElse(""), m, s("masterName").getOrElse(""))
          case (Some(ref), None) => Outcome.Placed(ref, s("blik"), s("price"), s("paczkomat"))
      }

  /** a day of free starts, as `/api/slots` answers it; a class's seats left by start */
  final case class Day(date: String, weekday: Int, slots: Vector[(Long, String)], left: Map[Long, Int] = Map.empty)

  /** the free starts of a bookable service, by day; none when the shop does not answer */
  def slots[R[_]](base: String, service: String, mode: String = "", place: String = "", master: String = "")(
    using In[Fetch, R]): Vector[Day] ! R =
    val how = (if mode.isEmpty then "" else "&mode=" + Query.encode(mode)) +
      (if place.isEmpty then "" else "&place=" + Query.encode(place)) +
      (if master.isEmpty then "" else "&master=" + Query.encode(master))
    Fetch[R](base, Request.get("/api/slots?service=" + Query.encode(service) + how)).map { r =>
      def days(j: Json) = j("days").list.map(d =>
        Day(d("date").string, d("weekday").int, d("slots").list.map(x => x("start").long -> x("label").string),
          d("slots").list.flatMap(x => x match
            case o: Json.Obj => o.get("left").collect { case Json.Num(n) => x("start").long -> n.toInt }
            case _ => None).toMap))
      if r.status != 200 then Vector.empty
      else Descent.parse(r.text).toOption.flatMap(j => scala.util.Try(days(j)).toOption).getOrElse(Vector.empty)
    }

  /** whether an address is inside the shop's area; none when the shop does not answer */
  def area[R[_]](base: String, city: String, postcode: String)(using In[Fetch, R]): Option[Boolean] ! R =
    Fetch[R](base, Request.get("/api/area?city=" + Query.encode(city) + "&postcode=" + Query.encode(postcode)))
      .map(r => if r.status != 200 then None else Descent.parse(r.text).toOption.flatMap(j =>
        scala.util.Try(j("inside").boolean).toOption))

  def outside(l: Lang): String = T("Этот адрес вне зоны выезда — выберите другой способ или напишите нам.",
    "Ten adres jest poza obszarem dojazdu — wybierz inny sposób albo napisz do nas.",
    "That address is outside the area we come to — choose another way or write to us.",
    "Ця адреса поза зоною виїзду — оберіть інший спосіб або напишіть нам.")(l)
  def inside(l: Lang): String = T("Приедем по этому адресу.", "Dojedziemy pod ten adres.", "We come to this address.",
    "Приїдемо за цією адресою.")(l)

  /** a day's button: the weekday short and the date (`2026-11-03` → `wt 03.11`) */
  def dayLabel(l: Lang, d: Day): String =
    val names = T("пн вт ср чт пт сб вс", "pn wt śr cz pt sb nd", "Mon Tue Wed Thu Fri Sat Sun",
      "пн вт ср чт пт сб нд")(l).split(' ')
    val w = if d.weekday >= 1 && d.weekday <= 7 then names(d.weekday - 1) else ""
    d.date.split('-') match
      case Array(_, m, dd) => s"$w $dd.$m"
      case _ => d.date

  /** the done panel of a booking: its time, and where it is managed */
  def booked(l: Lang, when: String): String = T(s"Вы записаны: $when. Ссылка, чтобы перенести или отменить:",
    s"Zarezerwowano: $when. Link do przeniesienia lub odwołania:", s"Booked: $when. To move or cancel it:",
    s"Ви записані: $when. Посилання, щоб перенести або скасувати:")(l)

  /** a class's start with its seats left: `18:00 · 5 wolnych` */
  def seats(l: Lang, label: String, left: Int): String = s"$label · " + T(s"мест: $left", s"wolnych: $left",
    s"$left left", s"місць: $left")(l)

  /** the done panel of an inquiry */
  def asked(l: Lang): String = T("Запрос принят — пришлём оценку. Следить и ответить:", "Zapytanie przyjęte — " +
    "prześlemy wycenę. Śledź i odpowiedz:", "Inquiry received — we will send a quote. Follow and answer it:",
    "Запит прийнято — надішлемо оцінку. Стежити й відповісти:")(l)

  def noSlots(l: Lang): String = T("Свободного времени пока нет — напишите нам.", "Brak wolnych terminów — napisz do " +
    "nas.", "No free times yet — write to us.", "Вільного часу поки немає — напишіть нам.")(l)

  /** the BLIK line of the done panel: the prepayment when the offer has a price, else the amount to be confirmed */
  def blik(l: Lang, phone: String, price: Option[String]): String = price match
    case Some(p) => T(s"Предоплата $p — BLIK: $phone", s"Przedpłata $p — BLIK: $phone", s"Prepayment $p — BLIK: $phone",
        s"Передоплата $p — BLIK: $phone")(l)
    case None => T(s"Оплата BLIK: $phone · сумму подтвердит мастер", s"Płatność BLIK: $phone · kwotę potwierdzi " +
        s"krawcowa",
        s"Pay via BLIK: $phone · amount confirmed by the seamstress", s"Оплата BLIK: $phone · суму підтвердить " +
            s"майстер")(l)

  def paczkomat(l: Lang): String = T("Отправьте вещь через InPost — Paczkomat: ", "Wyślij rzecz przez InPost — " +
      "Paczkomat: ",
    "Send your item via InPost — Paczkomat: ", "Надішліть річ через InPost — Paczkomat: ")(l)

  /** why a form was refused, in the page's language */
  def refusal(l: Lang, why: String): String = why match
    case "name" => T("Укажите имя.", "Podaj imię.", "Please give your name.", "Вкажіть імʼя.")(l)
    case "contact" => T("Укажите email или телефон.", "Podaj email lub telefon.", "Please give an email or a phone.",
        "Вкажіть email або телефон.")(l)
    case "consent" => T("Нужно согласие на обработку данных.", "Potrzebna zgoda na przetwarzanie danych.",
        "Please consent to the processing of your data.", "Потрібна згода на обробку даних.")(l)
    case "captcha" => T("Проверка не пройдена — попробуйте ещё раз.", "Weryfikacja nie powiodła się — spróbuj " +
        "ponownie.",
        "The check did not pass — please try again.", "Перевірку не пройдено — спробуйте ще раз.")(l)
    case "limit" => T("Слишком много заявок — попробуйте через 10 минут.", "Zbyt wiele zgłoszeń — spróbuj za 10 minut.",
        "Too many requests — please try again in 10 minutes.", "Забагато заявок — спробуйте за 10 хвилин.")(l)
    case "start" => T("Выберите время.", "Wybierz termin.", "Please choose a time.", "Оберіть час.")(l)
    case "taken" => T("Это время уже заняли — выберите другое.", "Ten termin jest już zajęty — wybierz inny.",
        "That time was just taken — please choose another.", "Цей час уже зайняли — оберіть інший.")(l)
    case "area" => outside(l)
    case "full" => T("Мест на это время уже нет — выберите другое.", "Brak wolnych miejsc na ten termin — wybierz " +
        "inny.", "No seats left at that time — please choose another.",
        "Місць на цей час уже немає — оберіть інший.")(l)
    case "description" => T("Опишите работу.", "Opisz pracę.", "Please describe the job.", "Опишіть роботу.")(l)
    case "master" => T("Этот мастер не делает эту услугу так — выберите другого.", "Ten specjalista nie wykonuje " +
        "tej usługi w ten sposób — wybierz innego.", "That master does not do this service this way — choose another.",
        "Цей майстер не робить цю послугу так — оберіть іншого.")(l)
    case "address" => T("Укажите улицу, индекс (00-000) и город.", "Podaj ulicę, kod pocztowy (00-000) i miasto.",
        "Please give the street, postcode (00-000) and city.", "Вкажіть вулицю, індекс (00-000) і місто.")(l)
    case _ => T("Не удалось отправить, попробуйте ещё раз.", "Nie udało się wysłać, spróbuj ponownie.",
        "Could not send, please try again.", "Не вдалося надіслати, спробуйте ще раз.")(l)
