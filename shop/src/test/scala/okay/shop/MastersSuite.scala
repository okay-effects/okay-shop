package okay.shop

import okay.effects.json.Descent
import okay.serverless.cloud.text
import okay.shop.booking.Slots
import okay.shop.html.StaticPages
import okay.shop.model.*

/** MASTERS (specs/shop.md "Masters"), on the fictional "Test Crew" (the visits' work and three masters): read and
 * checked, who is eligible, the union of their slots, no master booked twice, days off, a booking made before the
 * masters keeping all of them busy, the master in the letters, the page and the workshop, a move to another master */
class MastersSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val minute = 60000L
  private val fri10 = 1793282400000L + 19 * hour // Friday 2026-10-30 10:00 Warsaw
  private def site = Fixtures.site("crew")
  private def crew = World("crew")
  private def book(w: World, fields: (String, String)*) =
    val base = Map("service" -> "cut", "mode" -> "atShop", "place" -> "centrum", "start" -> fri10.toString,
      "name" -> "Anna", "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl",
      "cf-turnstile-response" -> "token")
    w.post("/api/book", (base ++ fields).toSeq*)
  private def starts(w: World, query: String, date: String): Vector[Long] =
    Descent.parse(w.get(s"/api/slots?$query").text).toOption.get("days").list.find(_("date").string == date)
      .map(_("slots").list.map(_("start").long)).getOrElse(Vector.empty)
  private def fromJson(json: String) = Masters.fromJson(Descent.parse(json).toOption.get,
    site.offers.map(_.key).toSet, site.work)

  test("the masters read: their services, modes, places, own week, days off; initials from the name") {
    assertEquals(site.masters.map(_.key), Vector("ola", "piotr", "kasia"))
    val ola = site.masters.head
    assertEquals((ola.email, ola.places, ola.shown), ("staff@example.com", Vector("centrum"), "OT"))
    assertEquals(ola.off, Vector("2026-11-02" -> "2026-11-04"))
    assertEquals(Masters.dates("2026-11-02", "2026-11-04"), Vector("2026-11-02", "2026-11-03", "2026-11-04"))
    assertEquals(Masters.fromJson(Masters.toJson(site.masters), site.offers.map(_.key).toSet, site.work),
      Right(site.masters))
    assertEquals(Fixtures.site("visits").masters, Vector.empty)
  }

  test("a master refused by the first wrong field") {
    def why(json: String) = fromJson(json).left.toOption.get
    assertEquals(why("""[{"key":"main","name":"A"}]"""), "master.key")
    assertEquals(why("""[{"name":""}]"""), "master.name")
    assertEquals(why("""[{"name":"A","email":"nope"}]"""), "master.email")
    assertEquals(why("""[{"name":"A","services":["nope"]}]"""), "master.services")
    assertEquals(why("""[{"name":"A","modes":["teleport"]}]"""), "master.modes")
    assertEquals(why("""[{"name":"A","places":["nowhere"]}]"""), "master.places")
    assertEquals(why("""[{"name":"A","hours":[{"day":"mon","from":"12:00","to":"10:00"}]}]"""), "master.hours")
    assertEquals(why("""[{"name":"A","off":[{"from":"2026-11-10","to":"2026-11-01"}]}]"""), "master.off")
    assertEquals(why("""[{"key":"a","name":"A"},{"key":"a","name":"B"}]"""), "masters.twice")
    assertEquals(why("[" + (1 to 31).map(i => s"""{"name":"M$i"}""").mkString(",") + "]"), "masters.size")
    assert(fromJson("""[{"name":"A"}]""").isRight)
  }

  test("who is eligible: by service, mode and place") {
    def who(o: String, m: Mode, p: String = "") = site.eligible(site.offer(o).get, Some(m), p).map(_.key)
    assertEquals(who("cut", Mode.AtShop, "centrum"), Vector("ola", "piotr"))
    assertEquals(who("cut", Mode.AtShop, "mokotow"), Vector("piotr"))
    assertEquals(who("visit", Mode.AtClient), Vector("piotr"))
    assertEquals(who("consult", Mode.Online), Vector("kasia"))
  }

  test("slots: one master's own week, or the union of the eligible ones'; a master who does not do it refused") {
    val w = crew
    val ola = starts(w, "service=cut&mode=atShop&place=centrum&master=ola", "2026-10-30")
    assertEquals((ola.head, ola.last), (fri10, fri10 + 195 * minute)) // 10:00 … 13:15: 45 minutes before 14:00
    val any = starts(w, "service=cut&mode=atShop&place=centrum", "2026-10-30")
    assertEquals(any.last, fri10 + 435 * minute) // Piotr's 17:15
    assert(ola.forall(any.contains))
    assertEquals(w.json(w.get("/api/slots?service=consult&mode=online&master=ola")).get("error"), Some("master"))
    assertEquals(starts(w, "service=consult&mode=online", "2026-10-30").head, fri10 + 6 * hour) // Kasia's 16:00
  }

  test("no master booked twice: the one asked, then any free one, then taken; the master's days off") {
    val w = crew
    assertEquals(w.json(book(w, "master" -> "ola"))("master"), "ola")
    assertEquals(w.json(book(w, "master" -> "ola", "start" -> (fri10 + 15 * minute).toString))("error"), "taken")
    assertEquals(w.json(book(w))("master"), "piotr")
    assertEquals(book(w).status, 409)
    // Ola is off from Monday to Wednesday
    val mon = "2026-11-02"
    assertEquals(starts(w, "service=cut&mode=atShop&place=centrum&master=ola", mon), Vector.empty)
    assert(starts(w, "service=cut&mode=atShop&place=centrum&master=ola", "2026-11-05").nonEmpty)
    assert(starts(w, "service=cut&mode=atShop&place=centrum", mon).nonEmpty, "Piotr works")
  }

  test("a booking made before the masters keeps every master busy at its time") {
    val s = site.plan(site.offer("cut").get, Mode.AtShop, "centrum").get
    val old = Booking("TCRB-OLD", "K", "cut", "main", fri10, 45, "Old", "old@example.com", Lang.pl,
      Consent(0, "p"), 0)
    val keys = site.masters.map(_.key).toSet
    site.masters.take(2).foreach(m => assert(!Slots.isFree(Masters.schedule(s, m), 45,
      Masters.taken(Vector(old), m.key, keys), fri10 - 24 * hour, fri10), m.key))
  }

  test("the master in the letters, the booking's page and its move; the workshop says whose; a move to another") {
    val w = crew
    val j = w.json(book(w, "master" -> "ola"))
    assert(w.letters(1)._2.html.contains("Ola Testowa") && w.letters(0)._2.html.contains("Майстер"))
    val page = w.get(s"/pl/booking?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("Ola Testowa") && page.contains("""<option value="ola" data-modes="atShop"""") &&
      page.contains("selected>Ola Testowa"), page)
    val listed = WorkshopApiSuite().signedIn(w).get("/workshop/api/bookings").text
    assert(listed.contains(""""masterName":"Ola Testowa"""") && listed.contains(""""mine":true""") &&
      listed.contains(""""me":"ola""""), listed)
    val moved = w.post("/api/booking/move", "ref" -> j("ref"), "k" -> j("key"), "master" -> "piotr",
      "start" -> (fri10 + 4 * hour).toString)
    assertEquals(w.json(moved).get("ref"), j.get("ref"), moved.text)
    assert(w.letters.last._2.html.contains("Piotr Próbny"))
    assert(w.get(s"/calendar/${w.conf.calendarKey}.ics").text.contains("· Piotr Próbny"))
  }

  test("the pages: a master picker with any free master first; none for a shop without masters") {
    val pages = StaticPages.of(site, okay.shop.OldPages.Plain, "K").toMap
    val cut = pages("pl/offer/cut/index.html")
    assert(cut.contains("""<option value="">Dowolny wolny specjalista</option><option value="ola"""") &&
      cut.contains("Piotr Próbny") && !cut.contains("Kasia"), cut)
    val visits = StaticPages.of(Fixtures.site("visits"), okay.shop.OldPages.Plain, "K").toMap
    assert(!visits("pl/offer/cut/index.html").contains("""name="master""""))
  }
