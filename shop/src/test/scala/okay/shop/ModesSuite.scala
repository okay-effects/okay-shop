package okay.shop

import okay.effects.json.Descent
import okay.serverless.cloud.text
import okay.shop.html.*
import okay.shop.model.*

/** HOW THE WORK HAPPENS (specs/shop.md), on the fictional "Test Visits": the modes read and checked, the slots per
 * location and with the travel buffer, the area, the restrictions per service, where the address is shown and
 * where it never is */
class ModesSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val minute = 60000L
  private val day = 24 * hour
  private val fri10 = 1793282400000L + 19 * hour // Friday 2026-10-30 10:00 Warsaw
  private val sat9 = fri10 + day - hour // Saturday 09:00 Warsaw

  private def site = Fixtures.site("visits")
  private def work = site.work.get
  private def visits = World("visits")
  private def book(w: World, fields: (String, String)*) =
    val base = Map("service" -> "cut", "start" -> fri10.toString, "name" -> "Anna", "contact" -> "anna@example.com",
      "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "token")
    w.post("/api/book", (base ++ fields).toSeq*)
  private val home = Seq("mode" -> "atClient", "street" -> "ul. Kwiatowa 7/12", "postcode" -> "00-950",
    "city" -> "Warszawa", "notes" -> "domofon 12")
  private def starts(w: World, query: String, date: String): Vector[Long] =
    Descent.parse(w.get(s"/api/slots?$query").text).toOption.get("days").list.find(_("date").string == date)
      .map(_("slots").list.map(_("start").long)).getOrElse(Vector.empty)
  private def parse(json: String) = Work.fromJson(Descent.parse(json).toOption.get, site.offers.map(_.key).toSet)

  test("the content's work: its modes, locations, area, online call, services' own; the minutes it gives") {
    assertEquals(work.modes, Vector(Mode.AtShop, Mode.AtClient, Mode.ByMail, Mode.Online))
    assertEquals(work.locations.map(_.key), Vector("centrum", "mokotow"))
    assertEquals(work.location("centrum").get.address.line, "Test Visits Centrum, ul. Testowa 1, 00-001 Warszawa")
    assertEquals(work.location("mokotow").get.hours, Map(6 -> Vector(Span(540, 720))))
    val a = work.area.get
    assertEquals((a.fee, a.before, a.after), (3000L, 30, 30))
    assertEquals(work.online, Some(Online("video", "Link: https://meet.example/test-visits")))
    assertEquals(site.offer("visit").get.minutes, 60)
    assertEquals(work.modesOf("visit"), Vector(Mode.AtClient))
    assertEquals(work.extra("visit", Mode.AtClient), 2000L)
    assertEquals(site.bookedModes(site.offer("cut").get), Vector(Mode.AtShop, Mode.AtClient, Mode.Online))
    assertEquals(site.bookedModes(site.offer("parcel").get), Vector.empty)
    assert(!site.books(site.offer("parcel").get))
    // written back and read again: the same
    assertEquals(Work.fromJson(Work.toJson(work), site.offers.map(_.key).toSet), Right(work))
  }

  test("a work refused by its first wrong field") {
    def why(json: String) = parse(json).left.toOption.get
    assertEquals(why("""{"modes":[]}"""), "modes")
    assertEquals(why("""{"modes":["teleport"]}"""), "modes")
    assertEquals(why("""{"modes":["atShop"]}"""), "locations")
    assertEquals(why("""{"modes":["atShop"],"locations":[{"name":"","city":"W"}]}"""), "location.name")
    assertEquals(why("""{"modes":["atShop"],"locations":[{"name":"A","city":""}]}"""), "location.city")
    assertEquals(why("""{"modes":["atShop"],"locations":[{"name":"A","city":"W","postcode":"0001"}]}"""),
      "location.postcode")
    assertEquals(why("""{"modes":["atShop"],"locations":[{"name":"A","city":"W","map":"javascript:x"}]}"""),
      "location.map")
    assertEquals(why("""{"modes":["atShop"],"locations":[{"name":"A","city":"W","hours":[{"day":"mon",
      "from":"18:00","to":"10:00"}]}]}"""), "hours")
    assertEquals(why("""{"modes":["atClient"]}"""), "area")
    assertEquals(why("""{"modes":["atClient"],"area":{"places":[],"postcodes":[]}}"""), "area")
    assertEquals(why("""{"modes":["atClient"],"area":{"postcodes":["0x-"]}}"""), "area.postcodes")
    assertEquals(why("""{"modes":["atClient"],"area":{"places":["W"],"fee":"20000"}}"""), "area.fee")
    assertEquals(why("""{"modes":["atClient"],"area":{"places":["W"],"before":300}}"""), "area.buffer")
    assertEquals(why("""{"modes":["online"],"online":{"how":"pigeon"}}"""), "online")
    assertEquals(why("""{"modes":["byMail"],"services":{"nope":{}}}"""), "service")
    assertEquals(why("""{"modes":["byMail"],"services":{"cut":{"modes":["online"]}}}"""), "service.modes")
    assertEquals(why("""{"modes":["byMail"],"services":{"cut":{"extra":{"byMail":"-5"}}}}"""), "service.extra")
    assertEquals(why("""{"modes":["byMail"],"services":{"cut":{"minutes":3}}}"""), "service.minutes")
    assert(parse("""{"modes":["byMail"]}""").isRight)
  }

  test("the area: a place by name (diacritics, case, a district after it) or a postcode prefix") {
    val a = work.area.get
    assert(a.inside("warszawa", "00-950"))
    assert(a.inside("Warszawa-Mokotów", "02-123"))
    assert(a.inside("PIASECZNO", "05-500"))
    assert(a.inside("Lesznowola", "05-506"))
    assert(!a.inside("Kraków", "30-001"))
    assert(!a.inside("Warszawianka", "11-111"))
  }

  test("old sites have no work and book as before") {
    assertEquals(Fixtures.site("atelier").work, None)
    assertEquals(Fixtures.site("salon").work, None)
    assert(Fixtures.site("salon").books(Fixtures.site("salon").offer("cut").get))
  }

  test("the slots of a location: its own hours (Saturdays), else the shop's; an unknown place refused") {
    val w = visits
    assert(starts(w, "service=cut&mode=atShop&place=centrum", "2026-10-30").contains(fri10))
    assertEquals(starts(w, "service=cut&mode=atShop&place=centrum", "2026-10-31"), Vector.empty)
    assertEquals(starts(w, "service=cut&mode=atShop&place=mokotow", "2026-10-30"), Vector.empty)
    assertEquals(starts(w, "service=cut&mode=atShop&place=mokotow", "2026-10-31").head, sat9)
    assertEquals(w.get("/api/slots?service=cut&mode=atShop&place=nowhere").status, 404)
    // none said: the first allowed mode, its first place
    assertEquals(starts(w, "service=cut", "2026-10-30"), starts(w, "service=cut&mode=atShop&place=centrum",
      "2026-10-30"))
  }

  test("the travel buffer: a visit keeps the master's time before and after it, and needs it free around itself") {
    val w = visits
    val r = book(w, (Seq("service" -> "visit", "start" -> (fri10 + hour).toString) ++ home)*)
    assertEquals(r.status, 200, r.text)
    // the visit is 11:00–12:00 with 30 minutes either side: the shop is busy 10:30–12:30
    val shop = starts(w, "service=cut&mode=atShop&place=centrum", "2026-10-30")
    assert(!shop.exists(s => s > fri10 - 15 * minute && s < fri10 + 150 * minute), shop.map(Day.time))
    assert(shop.contains(fri10 + 150 * minute))
    // another visit needs its own 30 minutes before: 12:30 + 30 = 13:00 the first
    val more = starts(w, "service=visit&mode=atClient", "2026-10-30")
    assert(!more.contains(fri10 + 150 * minute))
    assert(more.contains(fri10 + 180 * minute))
    assertEquals(book(w, (Seq("service" -> "visit", "start" -> (fri10 + 150 * minute).toString) ++ home)*).status,
      409)
  }

  test("an address outside the area, or incomplete, refused; inside by its postcode's prefix taken") {
    val w = visits
    val out = book(w, (home.filter(k => k._1 != "city" && k._1 != "postcode") ++ Seq("city" -> "Kraków",
      "postcode" -> "30-001"))*)
    assertEquals((out.status, w.json(out)("error")), (409, "area"))
    assertEquals(w.json(book(w, (home.filter(_._1 != "street"))*))("error"), "address")
    assertEquals(w.json(book(w, (home.filter(_._1 != "postcode") :+ ("postcode" -> "00950"))*))("error"), "address")
    assertEquals(w.json(w.get("/api/area?city=Krak%C3%B3w&postcode=30-001")).get("inside"), Some("false"))
    assertEquals(w.json(w.get("/api/area?city=Lesznowola&postcode=05-506")).get("inside"), Some("true"))
    val in = book(w, (home.filter(k => k._1 != "city" && k._1 != "postcode") ++ Seq("city" -> "Lesznowola",
      "postcode" -> "05-506"))*)
    assertEquals(in.status, 200, in.text)
    assertEquals(w.letters.size, 2)
  }

  test("a service's own modes: one it does not allow refused, in its slots and its booking") {
    val w = visits
    assertEquals(w.json(w.get("/api/slots?service=visit&mode=atShop")).get("error"), Some("mode"))
    assertEquals(w.json(w.get("/api/slots?service=consult&mode=atClient")).get("error"), Some("mode"))
    assertEquals(w.get("/api/slots?service=parcel").status, 404)
    assertEquals(w.json(book(w, "service" -> "visit", "mode" -> "atShop", "place" -> "centrum"))("error"), "mode")
    assertEquals(w.json(book(w, "service" -> "parcel"))("error"), "service")
    // a parcel is an order, as before
    assertEquals(w.order("parcel").status, 200)
  }

  test("the letters, the booking's page, the workshop and the calendar say how and where; the price with its fee") {
    val w = visits
    val mon11 = fri10 + 3 * day + hour
    val j = w.json(book(w, (Seq("service" -> "visit", "start" -> mon11.toString) ++ home)*))
    val (owner, client) = (w.letters(0)._2, w.letters(1)._2)
    assert(client.html.contains("Dojazd do klienta") && client.html.contains("ul. Kwiatowa 7/12, 00-950 Warszawa"),
      client.html)
    assert(client.html.contains("domofon 12") && client.html.contains("google.com/maps/search"))
    assert(client.html.contains("170 zł"), client.html) // 120 + 20 at the client's + 30 the travel
    assert(owner.html.contains("ul. Kwiatowa 7/12") && owner.html.contains("Виїзд до клієнта"), owner.html)
    val page = w.get(s"/pl/booking?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("ul. Kwiatowa 7/12") && page.contains("""name="mode" value="atClient""""), page)
    // a wrong key: no booking, no address
    val wrong = w.get(s"/pl/booking?ref=${j("ref")}&k=AAAAAAAAAAAAAAAAAAAAAAAA")
    assert(wrong.status == 404 && !wrong.text.contains("Kwiatowa"))
    val ics = w.get(s"/calendar/${w.conf.calendarKey}.ics").text
    assert(ics.contains("LOCATION:ul. Kwiatowa 7/12\\, 00-950 Warszawa (domofon 12)"), ics)
    // the reminder too
    w.now = mon11 - 20 * hour
    assertEquals(w.alarm(), 1)
    assert(w.letters.last._2.html.contains("ul. Kwiatowa 7/12"))
  }

  test("a booking at a location carries its address; online its call's details; a move keeps them") {
    val w = visits
    val j = w.json(book(w, "mode" -> "atShop", "place" -> "mokotow", "start" -> sat9.toString))
    assertEquals(j.get("ref").isDefined, true, j)
    assert(w.letters(1)._2.html.contains("Test Visits Mokotów, ul. Próbna 2, 02-002 Warszawa"))
    assert(w.letters(1)._2.html.contains("https://maps.example/mokotow"))
    val moved = w.post("/api/booking/move", "ref" -> j("ref"), "k" -> j("key"), "start" -> (sat9 + hour).toString)
    assertEquals(moved.status, 200, moved.text)
    // a Friday is not the Mokotów location's
    assertEquals(w.post("/api/booking/move", "ref" -> j("ref"), "k" -> j("key"), "start" -> fri10.toString).status,
      409)
    assert(w.letters.last._2.html.contains("Test Visits Mokotów"))
    val o = w.json(book(w, "service" -> "consult", "mode" -> "online", "start" -> (fri10 + 2 * hour).toString))
    assert(o.contains("ref"), o)
    assert(w.letters.last._2.html.contains("https://meet.example/test-visits"), w.letters.last._2.html)
  }

  test("the pages: the modes chosen on a service's page, a parcel's order form; no client's address anywhere") {
    val pages = StaticPages.of(site, okay.shop.OldPages.Plain, "SITEKEY").toMap
    val cut = pages("pl/offer/cut/index.html")
    assert(cut.contains("""name="how" value="atShop" checked""") && cut.contains("""value="online""""), cut)
    assert(cut.contains("Test Visits Mokotów") && cut.contains("sb 09:00–12:00") && cut.contains("pn–pt 10:00–18:00"))
    assert(cut.contains("""data-for="atClient" hidden""") && cut.contains("Piaseczno"))
    assert(!cut.contains("""data-how-panel="byMail""""))
    val both = pages("pl/offer/both/index.html")
    assert(both.contains("""name="how" value="byMail"""") && both.contains("""data-how-panel="byMail" hidden"""))
    val visit = pages("pl/offer/visit/index.html")
    assert(!visit.contains("""name="how"""") && visit.contains("""name="mode" value="atClient""""))
    val parcel = pages("pl/offer/parcel/index.html")
    assert(parcel.contains("""data-send="order"""") && !parcel.contains("""data-send="book""""))
    assert(pages("pl/privacy/index.html").contains("Adres wizyty"))
    val salon = StaticPages.of(Fixtures.site("salon"), okay.shop.OldPages.Plain, "K").toMap
    assert(!salon("pl/privacy/index.html").contains("Adres wizyty"))
    val w = visits
    book(w, (Seq("service" -> "visit", "start" -> (fri10 + hour).toString) ++ home)*)
    assert(Seq("/pl/", "/pl/offer/visit/", "/api/slots?service=visit&mode=atClient", "/pl/track")
      .forall(p => !w.get(p).text.contains("Kwiatowa")))
  }

  test("the workshop's list of bookings says the mode and the address, for the staff signed in only") {
    val w = visits
    book(w, (Seq("service" -> "visit", "start" -> (fri10 + hour).toString) ++ home)*)
    assertEquals(Browser(w).get("/workshop/api/bookings").status, 401)
    val listed = WorkshopApiSuite().signedIn(w).get("/workshop/api/bookings").text
    assert(listed.contains("ul. Kwiatowa 7/12") && listed.contains(""""mode":"atClient"""") &&
      listed.contains(""""price":170"""), listed)
  }
