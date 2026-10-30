package okay.shop

import okay.serverless.cloud.text
import okay.shop.html.StaticPages

/** INQUIRY AND QUOTE (specs/shop.md), on the fictional visits' `fix` (at the client's): asked, quoted by the
 * workshop, accepted — a booking at the proposed time, or an order without one — or declined; each step's letters */
class QuotesSuite extends munit.FunSuite:
  private def visits = World("visits")
  private def ask(w: World, fields: (String, String)*) =
    val base = Map("service" -> "fix", "name" -> "Anna", "contact" -> "anna@example.com",
      "description" -> "Cieknie kran w kuchni", "preferred" -> "wtorek po 16", "street" -> "ul. Kwiatowa 7",
      "postcode" -> "00-950", "city" -> "Warszawa", "rodo_consent" -> "on", "lang" -> "pl",
      "cf-turnstile-response" -> "token")
    w.post("/api/quote", (base ++ fields).toSeq*)

  test("asked: refused without a description, outside the area, for a service not quoted; taken, both told") {
    val w = visits
    assertEquals(w.json(ask(w, "description" -> ""))("error"), "description")
    assertEquals(w.json(ask(w, "city" -> "Kraków", "postcode" -> "30-001"))("error"), "area")
    assertEquals(w.json(ask(w, "service" -> "cut"))("error"), "service")
    val j = w.json(ask(w))
    assert(j("ref").startsWith("TVSQ-") && j("manage").contains("/pl/quote?ref="), j)
    assertEquals(w.letters.map(_._1), Vector("owner", "anna@example.com"))
    assert(w.letters(0)._2.html.contains("Cieknie kran") && w.letters(0)._2.html.contains("ul. Kwiatowa 7"))
    val page = w.get(s"/pl/quote?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("Czeka na wycenę") && !page.contains("quote/accept"), page)
    assertEquals(w.get(s"/pl/quote?ref=${j("ref")}&k=AAAAAAAAAAAAAAAAAAAAAAAA").status, 404)
    val pages = StaticPages.of(Fixtures.site("visits"), OldPages.Plain, "K").toMap
    val offer = pages("pl/offer/fix/index.html")
    assert(offer.contains("""data-send="quote"""") && offer.contains("Wycena po opisie"), offer)
  }

  test("quoted by the workshop, then accepted with a time: a booking; its letters; the list's statuses") {
    val w = visits
    val j = w.json(ask(w))
    val staff = WorkshopApiSuite().signedIn(w)
    assertEquals(w.json(staff.post("/workshop/api/quote/reply", "ref" -> j("ref"), "price" -> ""))("error"), "price")
    assertEquals(w.json(staff.post("/workshop/api/quote/reply", "ref" -> j("ref"), "price" -> "150",
      "priceTo" -> "100"))("error"), "priceTo")
    val replied = staff.post("/workshop/api/quote/reply", "ref" -> j("ref"), "price" -> "150", "priceTo" -> "200",
      "start" -> "2026-11-03 16:00", "note" -> "Wymiana uszczelki")
    assertEquals(replied.status, 200, replied.text)
    assert(w.letters.last._2.html.contains("150 zł – 200 zł") && w.letters.last._2.html.contains("2026-11-03 16:00"))
    val page = w.get(s"/pl/quote?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("quote/accept") && page.contains("Wymiana uszczelki"), page)
    val accepted = w.post("/api/quote/accept", "ref" -> j("ref"), "k" -> j("key"))
    assertEquals(accepted.status, 200, accepted.text)
    val booking = w.json(accepted)("ref")
    assert(booking.startsWith("TVSB-"), booking)
    assert(w.letters.last._2.html.contains("2026-11-03 16:00") && w.letters.last._2.html.contains("Kwiatowa"))
    assert(staff.get("/workshop/api/quotes").text.contains(s""""result":"$booking""""))
    assertEquals(w.post("/api/quote/decline", "ref" -> j("ref"), "k" -> j("key")).status, 409)
  }

  test("accepted without a time: an order with the quoted price; declined: the owner told") {
    val w = visits
    val a = w.json(ask(w))
    val staff = WorkshopApiSuite().signedIn(w)
    staff.post("/workshop/api/quote/reply", "ref" -> a("ref"), "price" -> "180")
    val order = w.json(w.post("/api/quote/accept", "ref" -> a("ref"), "k" -> a("key")))("ref")
    assert(order.startsWith("TVS-"), order)
    assert(staff.get(s"/workshop/api/order?ref=$order").text.contains(""""amount":18000"""))
    w.now += 11 * 60000L
    val b = w.json(ask(w, "contact" -> "bob@example.com"))
    assertEquals(w.post("/api/quote/decline", "ref" -> b("ref"), "k" -> b("key")).status, 409, "not quoted yet")
    staff.post("/workshop/api/quote/reply", "ref" -> b("ref"), "price" -> "90")
    assertEquals(w.post("/api/quote/decline", "ref" -> b("ref"), "k" -> b("key")).status, 200)
    assert(w.letters.last._2.subject.contains("Оцінку відхилено"))
    assert(staff.get("/workshop/api/quotes").text.contains(""""status":"declined""""))
    import okay.shop.effects.Notice
    assertEquals(w.notices.toVector.map {
      case Notice.Asked(q) => s"asked ${q.ref}"
      case Notice.Decided(q, ok, result) => s"decided ${q.ref} $ok $result"
      case other => other.toString
    }, Vector(s"asked ${a("ref")}", s"decided ${a("ref")} true $order", s"asked ${b("ref")}",
      s"decided ${b("ref")} false "))
  }

  // ---- PHOTOS (specs/shop.md "Photos")

  private def withPhotos(w: World, photos: Seq[Array[Byte]], fields: (String, String)*) =
    import okay.effects.json.Json
    val base = Map("service" -> "fix", "name" -> "Anna", "contact" -> "anna@example.com",
      "description" -> "Cieknie kran w kuchni", "street" -> "ul. Kwiatowa 7", "postcode" -> "00-950",
      "city" -> "Warszawa", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "token") ++ fields
    w.postJson("/api/quote", Json.Obj(base.toVector.map((k, v) => k -> Json.str(v)) :+ ("photos" -> Json.Arr(
      photos.toVector.map(p => Json.obj("data" -> Json.str("data:image/jpeg;base64," +
        java.util.Base64.getEncoder.encodeToString(p))))))))

  test("photos: kept cleaned and private, linked for the client and the staff; the quote's form asks for them") {
    val w = World("visits", edit = _.copy(photos = true))
    val j = w.json(withPhotos(w, Seq(TestImages.jpeg, TestImages.png, TestImages.jpeg)))
    assert(j.contains("ref"), j)
    // the same picture twice is one; each cleaned, by its hash
    assertEquals(w.files.keySet.map(_.split('/').take(2).mkString("/")), Set(s"quote/${j("ref")}"))
    assertEquals(w.files.size, 2)
    val (jpg, kind) = w.files.find(_._1.endsWith(".jpg")).get._2
    assertEquals(kind, "image/jpeg")
    assert(!String(jpg.toArray, "ISO-8859-1").contains("GPS"))
    val page = w.get(s"/pl/quote?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("Zdjęcia") && page.contains(s"""<img src="/media/quote/${j("ref")}/""") &&
      page.contains("""width="20" height="30""""), page)
    val staff = WorkshopApiSuite().signedIn(w)
    val listed = staff.get("/workshop/api/quotes").text
    assert(listed.contains(""""links":["/media/quote/""") && listed.contains(""""photos":[{"file":"""), listed)
    val pages = StaticPages.of(Fixtures.site("visits").copy(photos = true), OldPages.Plain, "K").toMap
    assert(pages("pl/offer/fix/index.html").contains("""<input type="file" name="photos""""))
    assert(!StaticPages.of(Fixtures.site("visits"), OldPages.Plain, "K").toMap.apply("pl/offer/fix/index.html")
      .contains("type=\"file\""))
  }

  test("photos refused: more than 3, a GIF or an SVG, too large, or a site that keeps none — nothing kept") {
    val w = World("visits", edit = _.copy(photos = true))
    assertEquals(w.json(withPhotos(w, Seq.fill(4)(TestImages.png)))("error"), "photos")
    assertEquals(w.json(withPhotos(w, Seq(TestImages.gif)))("error"), "photo.type")
    assertEquals(w.json(withPhotos(w, Seq(TestImages.svg)))("error"), "photo.type")
    assertEquals(w.json(withPhotos(w, Seq(TestImages.jpeg.take(50))))("error"), "photo.type")
    assertEquals(w.json(withPhotos(w, Seq(Array.fill(5 * 1024 * 1024 + 10)(1.toByte))))("error"), "photo.size")
    assertEquals(w.files.size, 0)
    assertEquals(w.letters.size, 0)
    val plain = visits
    assertEquals(plain.json(withPhotos(plain, Seq(TestImages.png)))("error"), "photos")
  }
