package okay.shop.workshop

import okay.business.model.{Act, Play, Played, Screen, Ui}
import okay.business.scala3.steps
import okay.scala3.*
import okay.http.Query
import okay.serverless.cloud.{Fetch, Request, Response}
import okay.shop.{Browser, World}
import okay.shop.model.*

/** the workshop as a person uses it: the screens under okay-ui's `Play`, the site's own program behind them on
 * SQLite — the same requests the page sends */
class WorkshopSuite extends munit.FunSuite:
  type T[X] = (Ui + Human + Fetch + Staff)[X]

  /** the page's requests answered by the site's program, as a browser on that site sends them */
  private def answering[R[_], Rest[_], A](f: Request => Response)(p: A ! R)(using Split[Fetch, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Fetch, Rest, A, A](pure)([X] => (op: Fetch[X]) => op match
      case okay.http.Fetch.Send(_, r, answer) => answer(f(r))
      case okay.http.Fetch.Call(m, url, h, body, answer) => answer(f(okay.http.Fetch.request(m, url, h, body)))))

  private def play(w: World, b: Browser, acts: Act*): Played[Unit] =
    Play(steps(Human.always("token")(answering(b.fetch)(StaffApi.run(w.conf.origin)(Workshop.main[T](
      Shown(w.site.name, w.site.schedule.isDefined), false))))), acts*)
  private def texts(s: Option[Screen[?]]): String = s.toList.flatMap(_.parts).map(_.toString).mkString("\n")

  /** signed in through the screens: the email typed, the link from the letter posted */
  private def signIn(w: World, b: Browser): Unit =
    val asked = play(w, b, Act.press("Надіслати посилання", "email" -> "staff@example.com"))
    assertEquals(asked.at.map(_.title), Some("Перевірте пошту"))
    val link = w.letters.last._2.html.split("'").find(_.contains("enter?t=")).get
    b.send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(link.drop(link.indexOf("t=") + 2)))),
      "application/x-www-form-urlencoded")

  test("signed out: the sign-in screen; an email off the list is told the same and mailed nothing") {
    val w = World()
    val b = Browser(w)
    assertEquals(play(w, b).at.map(_.title), Some("Майстерня · Test Atelier"))
    val asked = play(w, b, Act.press("Надіслати посилання", "email" -> "eve@example.com"))
    assertEquals(asked.at.map(_.title), Some("Перевірте пошту"))
    assert(w.letters.isEmpty)
    assertEquals(play(w, b, Act.press("Надіслати посилання", "email" -> "nope")).problems.map(_.message),
      List("адреса email"))
  }

  test("an order through the screens: opened, accepted, ready (the client mailed), paid with its amount") {
    val w = World()
    val ref = w.json(w.order("hem", extra = Seq("deliveryMethod" -> "in_person")))("ref")
    val b = Browser(w)
    signIn(w, b)
    val list = play(w, b)
    assertEquals(list.at.map(_.title), Some("Замовлення · Test Atelier"))
    val open = Act.choose("Відкрити", ref)
    assertEquals(play(w, b, open).at.map(_.title), Some(s"$ref · ${Status.Received.label(Lang.uk)}"))
    val accepted = play(w, b, open, Act.press("Прийнято (BLIK отримано)"))
    assertEquals(accepted.at.map(_.title), Some(s"$ref · ${Status.Accepted.label(Lang.uk)}"))
    play(w, b, open, Act.press("В роботу"))
    val ready = play(w, b, open, Act.press("Готово — лист клієнту"))
    assert(texts(ready.at).contains("Клієнту надіслано лист."), texts(ready.at))
    // picked up in person: the letter says so, not InPost
    assert(w.letters.last._2.subject.startsWith("Gotowe! Możesz odebrać"), w.letters.last._2.subject)
    play(w, b, open, Act.press("Доставлено"))
    val paid = play(w, b, open, Act.press("Оплачено — сума"), Act.press("Записати оплату", "amount" -> "40.00"))
    assertEquals(paid.problems, Nil)
    assertEquals(paid.at.map(_.title), Some(s"$ref · ${Status.Paid.label(Lang.uk)}"))
    assert(texts(paid.at).contains("40 zł"))
    assert(texts(paid.at).contains(s"/pl/receipt?ref=$ref"), "the client's receipt linked")
  }

  test("the host's own pages: a link on the orders screen for the person the host names") {
    val w = World(more = _ => Vector("Wygląd · Вигляд" -> "/workshop/look/"))
    val b = Browser(w)
    signIn(w, b)
    assert(texts(play(w, b).at).contains("Link(Wygląd · Вигляд,/workshop/look/)"), texts(play(w, b).at))
    // a person who is not the owner: no link at all
    val plain = World(owner = _ => false)
    val other = Browser(plain)
    signIn(plain, other)
    assert(!texts(play(plain, other).at).contains("Link("))
  }

  test("cancel asks a reason; a letter to the client") {
    val w = World()
    val ref = w.json(w.order("fit"))("ref")
    val b = Browser(w)
    signIn(w, b)
    val open = Act.choose("Відкрити", ref)
    val letter = play(w, b, open, Act.press("Лист клієнту"),
      Act.press("Надіслати", "subject" -> "Pytanie", "body" -> "Dzień dobry"))
    assert(texts(letter.at).contains("Лист надіслано."), texts(letter.at))
    assertEquals(w.letters.last._1, "anna@example.com")
    val cancelled = play(w, b, open, Act.press("Скасувати"), Act.press("Скасувати замовлення", "reason" -> "duplikat"))
    assertEquals(cancelled.at.map(_.title), Some(s"$ref · ${Status.Cancelled.label(Lang.uk)}"))
  }

  test("the bookings: listed, opened, cancelled by the staff, the client mailed; a shop without them has no button") {
    val w = World("salon")
    val start = w.now + 19 * 3600000L // Friday 10:00 in Warsaw
    val booked = w.json(w.post("/api/book", "service" -> "cut", "start" -> start.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t"))
    val ref = booked("ref")
    val b = Browser(w)
    signIn(w, b)
    val list = play(w, b, Act.press("Записи"))
    assertEquals(list.at.map(_.title), Some("Записи · Test Salon"))
    val open = Act.choose("Відкрити", ref)
    assertEquals(play(w, b, Act.press("Записи"), open).at.map(_.title), Some(s"$ref · 2026-10-30 10:00"))
    val cancelled = play(w, b, Act.press("Записи"), open, Act.press("Скасувати запис"))
    assert(texts(cancelled.at).contains("клієнту надіслано лист"), texts(cancelled.at))
    assertEquals(w.letters.last._1, "anna@example.com")
    assertEquals(w.post("/api/booking/cancel", "ref" -> ref, "k" -> booked("key")).status, 409)
    val atelier = World()
    val other = Browser(atelier)
    signIn(atelier, other)
    assert(!play(atelier, other).at.toList.flatMap(_.buttons).map(_.toString).exists(_.contains("Записи")))
  }

  test("money on a booking: marked paid by the staff (method, amount), the receipt linked; a no-show refused ahead") {
    val w = World("salon", owner = _ => false)
    val start = w.now + 19 * 3600000L // Friday 10:00 in Warsaw
    val ref = w.json(w.post("/api/book", "service" -> "cut", "start" -> start.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t"))("ref")
    val b = Browser(w)
    signIn(w, b)
    val open = Seq(Act.press("Записи"), Act.choose("Відкрити", ref))
    assert(texts(play(w, b, open*).at).contains("не оплачено"))
    val ahead = play(w, b, (open :+ Act.press("Не прийшов"))*)
    assert(texts(ahead.at).contains("Лише запис, час якого вже минув."), texts(ahead.at))
    val paid = play(w, b, (open ++ Seq(Act.press("Оплачено"), Act.press("Записати", "amount" -> "60.00",
      "method" -> "blik")))*)
    assertEquals(paid.problems, Nil)
    assert(texts(paid.at).contains("60 zł · BLIK"), texts(paid.at))
    assert(texts(paid.at).contains(s"/pl/receipt?ref=$ref"), "the client's receipt linked")
    // the staff cannot change or undo it: no such buttons
    assert(!paid.at.toList.flatMap(_.buttons).map(_.toString).exists(_.contains("Не оплачено")))
  }

  test("invoices on a booking (specs/shop.md \"Invoices\"): the buyer's data given, a wrong NIP refused, the invoice " +
    "issued once paid and linked") {
    val seller = okay.shop.invoice.Seller("TEST Salon", "1111111111", "ul. Testowa 1", "00-001", "Warszawa")
    val w = World("salon", owner = _ => false, edit = _.copy(invoices = true), seller = Some(seller))
    val start = w.now + 19 * 3600000L
    val ref = w.json(w.post("/api/book", "service" -> "cut", "start" -> start.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t"))("ref")
    val b = Browser(w)
    signIn(w, b)
    val open = Seq(Act.press("Записи"), Act.choose("Відкрити", ref))
    val first = play(w, b, open*)
    assert(texts(first.at).contains("клієнт не просив"), texts(first.at))
    assert(!first.at.toList.flatMap(_.buttons).map(_.toString).exists(_.contains("Виставити фактуру")))
    val wrong = play(w, b, (open ++ Seq(Act.press("Дані для фактури"), Act.press("Зберегти", "name" -> "TEST Kupiec",
      "nip" -> "1234567890", "street" -> "ul. B 2", "postcode" -> "30-001", "city" -> "Kraków")))*)
    assertEquals(wrong.problems.map(_.message), List("10 цифр NIP"))
    val saved = play(w, b, (open ++ Seq(Act.press("Дані для фактури"), Act.press("Зберегти", "name" -> "TEST Kupiec",
      "nip" -> "2222222222", "street" -> "ul. B 2", "postcode" -> "30-001", "city" -> "Kraków")))*)
    assert(texts(saved.at).contains("NIP 2222222222"), texts(saved.at))
    play(w, b, (open ++ Seq(Act.press("Оплачено"), Act.press("Записати", "amount" -> "60.00", "method" -> "card")))*)
    val issued = play(w, b, (open :+ Act.press("Виставити фактуру"))*)
    assertEquals(issued.problems, Nil)
    assert(texts(issued.at).contains("FV/2026/10/1"), texts(issued.at))
    assert(texts(issued.at).contains("/workshop/invoices/view?id=000001"))
    assert(!issued.at.toList.flatMap(_.buttons).map(_.toString).exists(_.contains("Виставити фактуру")))
  }

