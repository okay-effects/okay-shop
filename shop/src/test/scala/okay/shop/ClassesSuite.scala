package okay.shop

import okay.effects.json.Descent
import okay.serverless.cloud.text
import okay.shop.html.StaticPages
import okay.shop.model.*

/** GROUP CLASSES (specs/shop.md), on the fictional visits' `yoga` (3 seats, Mondays and Wednesdays 18:00): its
 * occurrences, the seats counted, the fourth refused, a cancel freeing a seat, a move to another occurrence */
class ClassesSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val day = 24 * hour
  private val mon18 = 1793282400000L + 19 * hour + 3 * day + 8 * hour // Monday 2026-11-02 18:00 Warsaw
  private def visits = World("visits")
  private def seat(w: World, contact: String, start: Long = mon18) = w.post("/api/book", "service" -> "yoga",
    "start" -> start.toString, "name" -> "Anna", "contact" -> contact, "rodo_consent" -> "on", "lang" -> "pl",
    "cf-turnstile-response" -> "token")
  private def days(w: World) = Descent.parse(w.get("/api/slots?service=yoga").text).toOption.get("days").list

  test("a class read: its seats and weekly starts; its occurrences over the horizon, none on a closed date") {
    val yoga = Fixtures.site("visits").offer("yoga").get
    assertEquals((yoga.seats, yoga.times, yoga.isClass), (3, Vector(1 -> 1080, 3 -> 1080), true))
    val ds = days(visits)
    assertEquals(ds.map(_("date").string), Vector("2026-11-02", "2026-11-04", "2026-11-09", "2026-11-16",
      "2026-11-18"))
    assertEquals(ds.head("slots").list.head("left").int, 3)
    assertEquals(Work.times("mon 18:00, xyz 9:00"), None)
  }

  test("seats counted: three taken, the fourth refused as full; a cancel frees one; a move to another start") {
    val w = visits
    val first = w.json(seat(w, "a@example.com"))
    assert(first.contains("ref"), first)
    assertEquals(days(w).head("slots").list.head("left").int, 2)
    assertEquals(seat(w, "b@example.com").status, 200)
    assertEquals(seat(w, "c@example.com").status, 200)
    assertEquals(days(w).head("date").string, "2026-11-04", "a full one not offered")
    w.now += 11 * 60000L // past the limit's window
    val fourth = seat(w, "d@example.com")
    assertEquals((fourth.status, w.json(fourth)("error")), (409, "full"))
    assertEquals(w.post("/api/booking/cancel", "ref" -> first("ref"), "k" -> first("key")).status, 200)
    assertEquals(seat(w, "d@example.com").status, 200)
    // a start that is not an occurrence
    assertEquals(seat(w, "e@example.com", mon18 + hour).status, 409)
    val wed = w.json(seat(w, "e@example.com", mon18 + 2 * day))
    val moved = w.post("/api/booking/move", "ref" -> wed("ref"), "k" -> wed("key"), "start" -> mon18.toString)
    assertEquals(w.json(moved).get("error"), Some("full"))
    assertEquals(w.post("/api/booking/move", "ref" -> wed("ref"), "k" -> wed("key"),
      "start" -> (mon18 + 7 * day).toString).status, 200)
  }

  test("its page: the slot picker, a chip of its seats; the price per seat in the letter") {
    val pages = StaticPages.of(Fixtures.site("visits"), OldPages.Plain, "K").toMap
    val yoga = pages("pl/offer/yoga/index.html")
    assert(yoga.contains("""data-slots="yoga"""") && yoga.contains("60 min · 3 miejsc"), yoga)
    val w = visits
    seat(w, "a@example.com")
    assert(w.letters.last._2.html.contains("40 zł"), w.letters.last._2.html)
  }
