package okay.shop

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.cloud.{Fetch, Request, Response}
import okay.shop.app.Submit
import okay.serverless.cloud.text
import okay.shop.booking.{Calendar, Slots}
import okay.shop.html.*
import okay.shop.model.*

/** BOOKING (specs/shop.md "Booking"), on the fictional salon: the free starts, a booking taken once, its management by
 * its key, the reminder by the object's alarm, the master's calendar, the pages' form */
class BookingSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val day = 24 * hour
  // the world's now: Thursday 2026-10-29, 15:00 in Warsaw (14:00 UTC)
  private val fri10 = 1793282400000L + 19 * hour // Friday 2026-10-30 10:00 Warsaw
  private val sat10 = fri10 + day // Saturday 10:00 Warsaw

  private def salon = World("salon")
  private def book(w: World, service: String = "cut", start: Long = fri10, contact: String = "anna@example.com") =
    w.post("/api/book", "service" -> service, "start" -> start.toString, "name" -> "Anna", "contact" -> contact,
      "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "token")
  private def days(w: World, service: String = "cut"): Vector[Json] =
    Descent.parse(w.get(s"/api/slots?service=$service").text).toOption.get("days").list
  private def starts(w: World, date: String): Vector[Long] =
    days(w).find(_("date").string == date).map(_("slots").list.map(_("start").long)).getOrElse(Vector.empty)

  test("the salon's content: a schedule, bookable services by their minutes, the rest ordered as before") {
    val site = Fixtures.site("salon")
    assertEquals(site.kind, Kind.Salon)
    val s = site.schedule.get
    assertEquals((s.step, s.horizon, s.lead), (15, 21, 60))
    assertEquals(s.week(6), Vector(Span(600, 840)))
    assertEquals(s.exceptions("2026-11-11"), Vector.empty)
    assert(site.books(site.offer("cut").get))
    assert(!site.books(site.offer("gift").get))
    assertEquals(Fixtures.site("atelier").schedule, None)
  }

  test("the free starts: from the lead on, the step apart, the service fitting the span; Sundays and holidays none") {
    val site = Fixtures.site("salon")
    val free = Slots.free(site.schedule.get, 45, Vector.empty, World().now)
    assertEquals(free.size, 21)
    assertEquals(free.head.date, "2026-10-29")
    assertEquals(free.head.slots.map(_.label), Vector("16:00", "16:15", "16:30", "16:45", "17:00", "17:15"))
    assertEquals(free(1).slots.head, booking.Slot(fri10, "10:00"))
    assertEquals(free(1).slots.size, 30)
    assert(free.filter(_.weekday == 7).forall(_.slots.isEmpty))
    assertEquals(free.find(_.date == "2026-11-11").get.slots, Vector.empty)
    assertEquals(free.find(_.date == "2026-11-07").get.slots.map(_.label), Vector("12:00", "12:15"))
    // winter time: 10:00 in Warsaw is 09:00 UTC
    assertEquals(free(1).slots.head.start % day, 9 * hour)
  }

  test("a booking: its ref and key, the letters; the same start, or one overlapping it, refused; next to it free") {
    val w = salon
    assert(starts(w, "2026-10-30").contains(fri10))
    val r = book(w)
    assertEquals(r.status, 200, r.text)
    val j = w.json(r)
    assert(j("ref").startsWith("TSLB-"), j)
    assertEquals(j("when"), "2026-10-30 10:00")
    assertEquals(j("manage"), s"${w.conf.origin}/pl/booking?ref=${j("ref")}&k=${j("key")}")
    assertEquals(w.letters.map(_._1), Vector("owner", "anna@example.com"))
    assert(w.letters(1)._2.html.contains("2026-10-30 10:00"), w.letters(1)._2.html)
    assertEquals(book(w, contact = "+48 600 100 200").status, 409)
    assertEquals(w.json(book(w, start = fri10 + 15 * 60000L)).get("error"), Some("taken"))
    assertEquals(book(w, service = "beard", start = fri10 + 45 * 60000L).status, 200)
    assert(!starts(w, "2026-10-30").exists(s => s >= fri10 && s < fri10 + 75 * 60000L))
    assert(starts(w, "2026-10-30").contains(fri10 + 75 * 60000L))
  }

  test("what is refused: a service with no minutes, a start off the grid or past, no consent, a bot, too many") {
    val w = salon
    assertEquals(w.json(book(w, service = "gift")).get("error"), Some("service"))
    assertEquals(w.get("/api/slots?service=gift").status, 404)
    assertEquals(book(w, start = fri10 + 5 * 60000L).status, 409)
    assertEquals(book(w, start = w.now + hour / 2).status, 409)
    assertEquals(w.post("/api/book", "service" -> "cut", "start" -> fri10.toString, "name" -> "A",
      "contact" -> "a@b.pl").text, """{"error":"consent"}""")
    w.human = false
    assertEquals(book(w).status, 403)
    w.human = true
    // each try past the form's own checks counted: five in ten minutes
    assertEquals(book(w, start = fri10 + 7 * 60000L).status, 409)
    assertEquals(book(w, start = sat10).status, 200)
    assertEquals(book(w, start = sat10 + day * 2).status, 429)
    assertEquals(Fixtures.site("atelier").schedule, None)
    assertEquals(World().get("/api/slots?service=hem").status, 404)
  }

  test("its page by the letter's link: cancel frees the start; move takes another; a wrong key is no booking") {
    val w = salon
    val j = w.json(book(w))
    val page = w.get(s"/pl/booking?ref=${j("ref")}&k=${j("key")}").text
    assert(page.contains("2026-10-30 10:00") && page.contains("Strzyżenie"), page)
    assert(page.contains("""data-send="booking/move"""") && page.contains("""data-slots="cut""""))
    assertEquals(w.get(s"/pl/booking?ref=${j("ref")}&k=AAAAAAAAAAAAAAAAAAAAAAAA").status, 404)
    assertEquals(w.post("/api/booking/cancel", "ref" -> j("ref"), "k" -> "nope").status, 404)
    val moved = w.post("/api/booking/move", "ref" -> j("ref"), "k" -> j("key"), "start" -> sat10.toString)
    assertEquals(moved.status, 200, moved.text)
    assertEquals(w.json(moved)("when"), "2026-10-31 10:00")
    assert(starts(w, "2026-10-30").contains(fri10))
    assert(!starts(w, "2026-10-31").contains(sat10))
    assert(w.letters.last._2.html.contains("2026-10-31 10:00"))
    assertEquals(w.post("/api/booking/cancel", "ref" -> j("ref"), "k" -> j("key")).status, 200)
    assert(starts(w, "2026-10-31").contains(sat10))
    assertEquals(w.post("/api/booking/cancel", "ref" -> j("ref"), "k" -> j("key")).status, 409)
    assert(w.get(s"/pl/booking?ref=${j("ref")}&k=${j("key")}").text.contains("Odwołane"))
    assertEquals(w.post("/api/booking/move", "ref" -> j("ref"), "k" -> j("key"), "start" -> fri10.toString).status,
      409)
  }

  test("the reminder: the object's alarm a day before, the client told once; one inside a day is not reminded") {
    val w = salon
    book(w, start = fri10) // 19 hours away: no reminder to come
    assertEquals(w.storage.alarm, None)
    book(w, start = sat10, contact = "bob@example.com")
    assertEquals(w.storage.alarm, Some(sat10 - day))
    w.letters = Vector.empty
    w.now = sat10 - day
    assertEquals(w.alarm(), 1)
    assertEquals(w.letters.map(_._1), Vector("bob@example.com"))
    assert(w.letters.head._2.html.contains("2026-10-31 10:00"))
    assertEquals(w.storage.alarm, None)
    assertEquals(w.alarm(), 0)
    // a cancelled booking is not reminded; the alarm follows the bookings
    val j = w.json(book(w, start = sat10 + 2 * day + 3 * hour))
    assertEquals(w.storage.alarm, Some(sat10 + day + 3 * hour))
    w.post("/api/booking/cancel", "ref" -> j("ref"), "k" -> j("key"))
    w.now = sat10 + day + 3 * hour
    assertEquals(w.alarm(), 0)
  }

  test("the master's calendar: by the shop's key only, the active bookings as events") {
    val w = salon
    val j = w.json(book(w))
    w.json(book(w, start = sat10, contact = "bob@example.com"))
    val bob = w.json(book(w, service = "beard", start = sat10 + hour))
    w.post("/api/booking/cancel", "ref" -> bob("ref"), "k" -> bob("key"))
    assertEquals(w.get("/calendar/wrong.ics").status, 404)
    assertEquals(World("salon").get("/calendar/.ics").status, 404)
    val ics = w.get("/calendar/CALENDAR-KEY-0123456789.ics")
    assertEquals(ics.status, 200)
    assert(ics.headers("Content-Type").startsWith("text/calendar"))
    val text = ics.text
    assert(text.startsWith("BEGIN:VCALENDAR\r\n"), text)
    assertEquals(text.split("BEGIN:VEVENT").length - 1, 2)
    assert(text.contains(s"UID:${j("ref")}@salon.example"))
    assert(text.contains("DTSTART:20261030T090000Z") && text.contains("DTEND:20261030T094500Z"), text)
    assert(!text.contains(bob("ref")))
    assert(text.split("\r\n").forall(_.getBytes("UTF-8").length <= 75))
  }

  test("the calendar's text escaped and folded") {
    val b = Booking("TSLB-1", "k", "cut", "main", fri10, 45, "Anna; Nowak, <b>" + "x" * 80, "a@b.pl", Lang.pl,
      Consent(0L, "p"), 0L)
    val text = Calendar.ics(Fixtures.site("salon"), Vector(b), 0L)
    assert(text.contains("Anna\\; Nowak\\, <b>"), text)
    assert(text.split("\r\n").forall(_.getBytes("UTF-8").length <= 75))
  }

  test("a bookable service's page: the slot picker and the booking form; the others' order form as before") {
    object L extends Look:
      def theme = Theme("", "")
      def seo(site: Site)(using l: Lang) = Seo(site.name, "d")
      override def policy = "2026-10-28"
    val pages = StaticPages.of(Fixtures.site("salon"), L, "SITEKEY").toMap
    val cut = pages("pl/offer/cut/index.html")
    assert(cut.contains("""data-send="book"""") && cut.contains("""data-slots="cut""""), cut)
    assert(cut.contains("""name="start"""") && cut.contains("SITEKEY") && cut.contains("rodo_consent"))
    assert(cut.contains("45 min"))
    val gift = pages("pl/offer/gift/index.html")
    assert(gift.contains("""data-send="order"""") && !gift.contains("data-slots"))
    assert(pages("workshop/index.html").contains("""data-books="yes""""))
    val front = pages("pl/index.html")
    assert(front.contains("""href="#offers"""") && front.contains("""id="offers"""") && front.contains("Usługi"))
    assert(cut.contains("""<span class="chip accent">60 zł</span>"""), cut)
    assert(!StaticPages.of(Fixtures.site("atelier"), L, "K").toMap.apply("workshop/index.html").contains("data-books"))
  }

  /** the pages' script's requests answered by the shop's program, as the browser's `fetch` would */
  private def answering[A](w: World)(p: A ! (Pure + Fetch)): A =
    run(handle(p, Handler.tail[Fetch, Pure, A, A](pure)([X] => (op: Fetch[X]) => op match
      case okay.http.Fetch.Send(_, r, answer) => answer(ask(w, r))
      case okay.http.Fetch.Call(m, url, h, body, answer) => answer(ask(w, okay.http.Fetch.request(m, url, h, body))))))
  private def ask(w: World, r: Request): Response =
    if r.method == "GET" then w.get(r.path + (if r.query.isEmpty then "" else "?" + okay.http.Query.print(r.query)))
    else w.post(r.path, Descent.parse(r.body).toOption.get.record.map((k, v) => k -> v.string)*)

  test("the pages' script: the slots read by day, a booking sent, its done panel's ref, time and link") {
    val w = salon
    val ds = answering(w)(Submit.slots[Pure + Fetch](w.conf.origin, "cut"))
    assertEquals(ds.head.date, "2026-10-29")
    assertEquals(Submit.dayLabel(Lang.pl, ds(1)), "pt 30.10")
    assertEquals(Submit.dayLabel(Lang.en, ds(1)), "Fri 30.10")
    assertEquals(ds(1).slots.head, fri10 -> "10:00")
    assertEquals(answering(w)(Submit.slots[Pure + Fetch](w.conf.origin, "gift")), Vector.empty)
    val fields = Vector("service" -> "cut", "lang" -> "pl", "start" -> fri10.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "cf-turnstile-response" -> "t")
    answering(w)(Submit.send[Pure + Fetch](w.conf.origin, "book", fields)) match
      case Submit.Outcome.Booked(ref, when, manage, _) =>
        assert(ref.startsWith("TSLB-"))
        assertEquals(when, "2026-10-30 10:00")
        assert(manage.startsWith(s"${w.conf.origin}/pl/booking?ref=$ref&k="))
      case other => fail(other.toString)
    assertEquals(answering(w)(Submit.send[Pure + Fetch](w.conf.origin, "book", fields)),
      Submit.Outcome.Refused("taken"))
    assert(Submit.refusal(Lang.pl, "taken").contains("zajęty"))
  }
