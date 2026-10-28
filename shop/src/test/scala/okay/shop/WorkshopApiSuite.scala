package okay.shop

import okay.effects.json.{Descent, Json}
import okay.http.Query
import okay.serverless.cloud.{Response, headerOf, text}

class WorkshopApiSuite extends munit.FunSuite:
  private val staff = "staff@example.com"

  /** signed in: a link asked, its letter read, the page's button pressed */
  def signedIn(w: World): Browser =
    val b = Browser(w)
    assertEquals(b.post("/workshop/api/link", "email" -> staff.toUpperCase, "turnstile" -> "t").status, 200)
    val link = w.letters.last._2.html.split("'").find(_.contains("/workshop/api/enter?t=")).get.replace("&amp;", "&")
    assertEquals(w.letters.last._1, staff)
    val secret = link.drop(link.indexOf("t=") + 2)
    assert(b.get(s"/workshop/api/enter?t=$secret").text.contains(secret), "the page posts the secret")
    val entered = b.send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(secret))),
      "application/x-www-form-urlencoded")
    assertEquals((entered.status, entered.headerOf("location")), (303, Some("/workshop/")))
    assert(entered.headerOf("set-cookie").exists(c => c.startsWith("__Host-ws=") && c.contains("HttpOnly") &&
      c.contains("Secure") && c.contains("SameSite=Lax")))
    // the link is spent
    assertEquals(Browser(w).send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(secret))),
      "application/x-www-form-urlencoded").headerOf("location"), Some("/workshop/#expired"))
    b

  private def obj(r: Response): Json.Obj = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }.get
  private def str(o: Json.Obj, k: String): String = o.get(k).collect { case Json.Str(s) => s }.getOrElse("")
  private def order(r: Response): Json.Obj = obj(r).get("order").collect { case o: Json.Obj => o }.get
  private def next(r: Response): Vector[String] =
    order(r).get("next").collect { case Json.Arr(xs) => xs.collect { case Json.Str(s) => s } }.get

  test("sign-in: a link only to the staff, the same answer to anyone; the session; out") {
    val w = World()
    val stranger = Browser(w)
    assertEquals(stranger.post("/workshop/api/link", "email" -> "eve@example.com", "turnstile" -> "t").text,
      """{"ok":true}""")
    assert(w.letters.isEmpty, "nobody off the list is mailed")
    assertEquals(stranger.get("/workshop/api/orders").status, 401)
    val b = signedIn(w)
    assertEquals(str(obj(b.get("/workshop/api/me")), "email"), staff)
    assertEquals(b.send("POST", "/workshop/api/out", "{}", origin = Some("https://evil.example")).status, 403)
    assertEquals(b.send("POST", "/workshop/api/out", "out", "text/plain").status, 403)
    assertEquals(b.post("/workshop/api/out").status, 200)
    assertEquals(b.get("/workshop/api/me").status, 401)
  }

  test("a link past its 15 minutes signs nobody in") {
    val w = World()
    val b = Browser(w)
    b.post("/workshop/api/link", "email" -> staff, "turnstile" -> "t")
    val link = w.letters.last._2.html.split("'").find(_.contains("enter?t=")).get
    w.now += 16 * 60 * 1000L
    assertEquals(b.send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(link.drop(link.indexOf("t=") + 2)))),
      "application/x-www-form-urlencoded").headerOf("location"), Some("/workshop/#expired"))
  }

  test("an order moved through the atelier's path; ready and parcel out mail the client; paid asks the amount") {
    val w = World()
    val placed = w.json(w.order("hem", extra = Seq("deliveryMethod" -> "courier")))
    val ref = placed("ref")
    val b = signedIn(w)
    val listed = obj(b.get("/workshop/api/orders")).get("orders").collect { case Json.Arr(xs) => xs }.get
    assertEquals(listed.size, 1)
    assertEquals(next(b.get(s"/workshop/api/order?ref=$ref")), Vector("accepted", "cancelled"))
    assertEquals(b.post("/workshop/api/move", "ref" -> ref, "status" -> "ready").status, 409)
    def move(to: String, more: (String, String)*) =
      b.post("/workshop/api/move", (Seq("ref" -> ref, "status" -> to) ++ more)*)
    assertEquals(next(move("accepted")), Vector("parcel_in", "cancelled"))
    move("parcel_in"); move("in_work")
    val before = w.letters.size
    val ready = move("ready")
    assertEquals(str(obj(ready), "emailed"), "sent")
    assertEquals(w.letters.size, before + 1)
    assert(w.letters.last._2.subject.startsWith("Gotowe!"), w.letters.last._2.subject)
    val out = move("parcel_out", "tracking" -> "6800 1234")
    assertEquals(str(order(out), "tracking"), "6800 1234")
    assert(w.letters.last._2.html.contains("6800 1234"))
    move("delivered")
    assertEquals(move("paid").status, 400)
    val paid = move("paid", "amount" -> "40")
    assertEquals(str(order(paid), "status"), "paid")
    assertEquals(next(paid), Vector.empty)
    assert(w.get(s"/pl/receipt?ref=$ref&k=${placed("key")}").text.contains("40 zł"))
    val history = obj(paid).get("history").collect { case Json.Arr(xs) => xs }.get
    assertEquals(history.size, 2 + 7 + 2) // placed, emailed; seven moves; two letters
  }

  test("cancel asks a reason; a free letter to the client; none to a phone") {
    val w = World()
    val ref = w.json(w.order("fit"))("ref")
    val byPhone = w.json(w.order("fit", contact = "+48 600 100 200"))("ref")
    val b = signedIn(w)
    assertEquals(b.post("/workshop/api/move", "ref" -> ref, "status" -> "cancelled").status, 400)
    assertEquals(str(order(b.post("/workshop/api/move", "ref" -> ref, "status" -> "cancelled", "note" -> "duplicate")),
      "status"), "cancelled")
    val sent = b.post("/workshop/api/email", "ref" -> ref, "subject" -> "Pytanie", "body" -> "Dzień dobry,\n<b>?</b>")
    assertEquals(sent.text, """{"sent":true}""")
    assert(w.letters.last._2.html.contains("Dzień dobry,<br>&lt;b&gt;?&lt;/b&gt;"))
    assertEquals(b.post("/workshop/api/email", "ref" -> byPhone, "subject" -> "x", "body" -> "y").status, 400)
  }
