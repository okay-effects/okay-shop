package okay.shop.app

import okay.effects.json.{Descent, Json}
import okay.scala3.*
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
    /** refused, and why: `name`, `contact`, `consent`, `captcha`, `limit`, … — or the network */
    case Refused(why: String)

  def send[R[_]](base: String, kind: String, fields: Vector[(String, String)])(using In[Fetch, R]): Outcome ! R =
    val body = Json.print(Json.Obj(fields.map((k, v) => k -> Json.str(v))))
    Fetch[R](base, Request.post(s"/api/$kind", body))
      .map { r =>
        val j = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }
        def s(k: String) = j.flatMap(_.get(k)).collect { case Json.Str(v) => v }
        if r.status == 200 then s("ref").fold(Outcome.Refused("answer"))(ref => Outcome.Placed(ref, s("blik"),
             s("price"),
          s("paczkomat")))
        else Outcome.Refused(s("error").getOrElse(if r.status == 0 then "network" else s"status ${r.status}"))
      }

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
    case _ => T("Не удалось отправить, попробуйте ещё раз.", "Nie udało się wysłać, spróbuj ponownie.",
        "Could not send, please try again.", "Не вдалося надіслати, спробуйте ще раз.")(l)
