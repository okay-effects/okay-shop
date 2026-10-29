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
    Play(steps(Human.always("token")(answering(b.fetch)(StaffApi.run(w.conf.origin)(Workshop.main[T](Shown(w.site.name),
      false))))), acts*)
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
