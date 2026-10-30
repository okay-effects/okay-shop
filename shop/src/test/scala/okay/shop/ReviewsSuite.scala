package okay.shop

import okay.effects.json.{Descent, Json}
import okay.http.Query
import okay.serverless.cloud.{Response, text}
import okay.shop.html.*
import okay.shop.model.*

/** CLIENTS' REVIEWS (specs/shop.md "Reviews"): only a real client, by a one-use link of their own visit or order;
 * the owner's reply, hide for a reason of the list, never a deletion; the averages; the storefront's block and its
 * structured data from three reviews on; a shop without reviews as before */
class ReviewsSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val fri10 = 1793282400000L + 19 * hour // Friday 2026-10-30 10:00 Warsaw

  private def on(s: Site) = s.copy(reviews = true)
  private def salon(owner: String => Boolean = _ => true) = World("salon", edit = on, owner = owner)
  private def book(w: World, contact: String = "anna@example.com") =
    w.json(w.post("/api/book", "service" -> "cut", "start" -> fri10.toString, "name" -> "Anna Kowalska",
      "contact" -> contact, "rodo_consent" -> "on", "lang" -> "uk", "cf-turnstile-response" -> "token"))
  private def asks(w: World) = w.letters.filter(_._2.html.contains("/review?t="))
  private def token(w: World): String =
    val html = asks(w).last._2.html
    val link = html.split("'").find(_.contains("/review?t=")).get.replace("&amp;", "&")
    Query.decode(link.drop(link.indexOf("t=") + 2))
  /** the visit over and two hours more: the alarm asks for the review */
  private def visited(w: World): String =
    val ref = book(w)("ref")
    w.now = fri10 + 45 * 60000L + 2 * hour
    w.alarm()
    ref
  private def write(w: World, t: String, rating: String = "5", text: String = "Super!", name: String = "Anna K") =
    w.form("/uk/review", "t" -> t, "rating" -> rating, "text" -> text, "name" -> name)
  private def obj(r: Response): Json.Obj = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }.get

  test("a visit: the review asked two hours after it ends, once, in the client's language, by the alarm") {
    val w = salon()
    val ref = book(w)("ref")
    val end = fri10 + 45 * 60000L
    assert(asks(w).isEmpty, "not at booking")
    assertEquals(w.storage.alarm, Some(end + 2 * hour))
    w.now = end + 2 * hour - 60000L
    w.alarm()
    assert(asks(w).isEmpty, "not before")
    w.now = end + 2 * hour
    w.alarm()
    assertEquals(asks(w).map(_._1), Vector("anna@example.com"))
    assert(asks(w).head._2.subject.startsWith("Як усе пройшло?"), asks(w).head._2.subject)
    assert(asks(w).head._2.html.contains("/uk/review?t="))
    w.now += hour
    w.alarm()
    assertEquals(asks(w).size, 1, "asked once")
    assert(ref.nonEmpty)
  }

  test("a cancelled visit, or a shop that takes no reviews: nobody asked, nothing queued") {
    val w = salon()
    val b = book(w)
    w.post("/api/booking/cancel", "ref" -> b("ref"), "k" -> b("key"))
    w.now = fri10 + 3 * hour
    w.alarm()
    assert(asks(w).isEmpty)
    val off = World("salon")
    book(off)
    assertEquals(off.storage.keys("site/reviewq/").size, 0)
    off.now = fri10 + 3 * hour
    off.alarm()
    assert(asks(off).isEmpty)
  }

  test("only the client: the link's form; a wrong rating refused; written once, the link then spent") {
    val w = salon()
    visited(w)
    val t = token(w)
    val page = w.get(s"/uk/review?t=${Query.encode(t)}")
    assertEquals(page.status, 200)
    assert(page.text.contains("name=\"rating\"") && page.text.contains("Як усе пройшло?"))
    assertEquals(w.get("/uk/review?t=FORGED").status, 404)
    assertEquals(write(w, "FORGED").status, 404)
    assertEquals(write(w, t, rating = "6").status, 400)
    assertEquals(write(w, t, text = "x" * 1001).status, 400)
    assertEquals(write(w, t).status, 200)
    assertEquals(write(w, t, rating = "1").status, 404, "single use")
    assertEquals(w.get(s"/uk/review?t=${Query.encode(t)}").status, 404)
    val stored = w.storage.keys("site/review/")
    assertEquals(stored.size, 1)
    assert(w.notices.exists { case okay.shop.effects.Notice.Reviewed(r) => r.rating == 5; case _ => false })
  }

  test("an order delivered asks its client once; paid after it asks no more; the review is of that order") {
    val w = World("studio", edit = on)
    val placed = w.json(w.order("consult"))
    val b = WorkshopApiSuite().signedIn(w)
    def move(to: String, more: (String, String)*) =
      b.post("/workshop/api/move", (Seq("ref" -> placed("ref"), "status" -> to) ++ more)*)
    move("accepted"); move("in_work"); move("ready")
    assert(asks(w).isEmpty)
    move("delivered")
    assertEquals(asks(w).size, 1)
    move("paid", "amount" -> "350")
    assertEquals(asks(w).size, 1)
    assertEquals(w.form("/pl/review", "t" -> token(w), "rating" -> "4", "text" -> "", "name" -> "").status, 200)
    val r = obj(b.get("/workshop/api/reviews"))
    val one = r("reviews").list.head
    assertEquals((one("of").string, one("ref").string, one("name").string), ("order", placed("ref"), ""))
  }

  test("the owner replies (editable), hides only for a reason of the list, shows again; staff may not; all logged") {
    var owner = false
    val w = salon(_ => owner)
    visited(w)
    write(w, token(w), rating = "2", text = "Za długo czekałam")
    val b = WorkshopApiSuite().signedIn(w)
    val id = obj(b.get("/workshop/api/reviews"))("reviews").list.head("id").string
    assertEquals(b.post("/workshop/api/review", "id" -> id, "action" -> "reply", "value" -> "Przepraszamy").status,
      403)
    owner = true
    assertEquals(b.post("/workshop/api/review", "id" -> id, "action" -> "hide", "value" -> "negative").status, 400)
    assertEquals(b.post("/workshop/api/review", "id" -> id, "action" -> "reply", "value" -> "").status, 400)
    b.post("/workshop/api/review", "id" -> id, "action" -> "reply", "value" -> "Przepraszamy")
    val edited = obj(b.post("/workshop/api/review", "id" -> id, "action" -> "reply", "value" -> "Przepraszamy!"))
    assertEquals(edited("review")("reply").string, "Przepraszamy!")
    b.post("/workshop/api/review", "id" -> id, "action" -> "hide", "value" -> "personal")
    val listed = obj(b.get("/workshop/api/reviews"))
    assertEquals((listed("count").long, listed("hidden").long), (0L, 1L))
    assertEquals(listed("reviews").list.size, 1, "never deleted")
    val shown = obj(b.post("/workshop/api/review", "id" -> id, "action" -> "show", "value" -> ""))
    assertEquals(shown("review")("log").list.map(_("what").string), Vector("reply", "reply", "hide:personal",
      "show"))
    assertEquals(b.post("/workshop/api/review", "id" -> "booking-NONE", "action" -> "show", "value" -> "").status,
      404)
    val screen = b.get("/workshop/reviews/?lang=pl")
    assertEquals(screen.status, 200)
    assert(screen.text.contains("Za długo czekałam") && screen.text.contains("Ukryj"))
    assert(Browser(w).get("/workshop/reviews/").status == 303)
    assert(obj(b.get("/workshop/api/more")).toString.contains("/workshop/reviews/"))
  }

  test("the rules: the name shown as Anna K., the averages of the shown, the hidden apart") {
    assertEquals(Reviews.shownName("  anna   kowalska "), "anna K.")
    assertEquals(Reviews.shownName("Anna Maria Kowalska"), "Anna K.")
    assertEquals(Reviews.shownName("Anna"), "Anna")
    assertEquals(Reviews.shownName(""), "")
    assertEquals(Reviews.check("0", ""), Left("rating"))
    def r(n: Int, at: Long, hidden: String = "") = Review(Of.Booking, s"R$at", "cut", "main", n, "", "", Lang.pl, at,
      hidden = hidden)
    val rated = Reviews.rated(Vector(r(5, 1), r(4, 2), r(4, 3), r(1, 4, "spam"))).get
    assertEquals((rated.score, rated.count, rated.hidden), ("4.3", 3, 1))
    assertEquals(rated.latest.map(_.at), Vector(3L, 2L, 1L))
    assertEquals(Reviews.rated(Vector(r(1, 4, "spam"))), None)
  }

  test("the storefront: the average, the count and the latest with replies; JSON-LD only from three reviews") {
    val site = Fixtures.site("salon")
    def page(s: Site) = Pages.storefront(At(s, Lang.pl, "", "KEY"), OldPages.Plain)
    def x(n: Int, at: Long) = Review(Of.Booking, s"R$at", "cut", "main", n, "Świetnie <b>", "Anna K.", Lang.pl, at,
      reply = "Dziękujemy!")
    val two = page(site.copy(rated = Reviews.rated(Vector(x(5, 1), x(4, 2)))))
    assert(two.contains("4.5</b> / 5 · 2 opinie") && two.contains("Opinie zweryfikowane"), two)
    assert(two.contains("Świetnie &lt;b&gt;") && two.contains("Dziękujemy!"))
    assert(!two.contains("application/ld+json"))
    val three = page(site.copy(rated = Reviews.rated(Vector(x(5, 1), x(4, 2), x(4, 3)))))
    assert(three.contains(""""aggregateRating":{"@type":"AggregateRating","ratingValue":"4.3","reviewCount":3"""))
  }

  test("a shop without reviews: its pages byte for byte as before, the switch on or off") {
    val site = Fixtures.site("salon")
    val before = StaticPages.of(site, OldPages.Plain, "KEY")
    assertEquals(StaticPages.of(site.copy(reviews = true), OldPages.Plain, "KEY"), before)
    assertEquals(StaticPages.of(site.copy(reviews = true, rated = Reviews.rated(Vector.empty)), OldPages.Plain,
      "KEY"), before)
  }
