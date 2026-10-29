package okay.shop

import okay.shop.html.*
import okay.shop.model.*

/** A SITE BEFORE THE MODES IS AS IT WAS (specs/shop.md "How the work happens"): every static page of the three
 * fictional sites, a booking's letters and its page, by their SHA-256 — taken from the engine before the modes */
object OldPages:
  /** a look of nothing but the engine's defaults */
  object Plain extends Look:
    def theme = Theme("--void: #fff;", ".bg { }")
    def seo(site: Site)(using l: Lang) = Seo(s"${site.name} ${l.code}", "description")

class OldPagesSuite extends munit.FunSuite:
  import OldPages.Plain

  private def sha(s: String) = Sha256.hex(s.getBytes("UTF-8"))
  def digest(id: String): String =
    sha(StaticPages.of(Fixtures.site(id), Plain, "SITEKEY").sortBy(_._1).map((k, v) => k + "\n" + v).mkString("\n"))
  def letters: String =
    val w = World("salon")
    val b = Booking("TSLB-1", "KEY", "cut", "main", 1793282400000L + 19 * 3600000L, 45, "Anna", "anna@example.com",
      Lang.pl, Consent(1793282400000L, "2026-10-28"), 1793282400000L)
    sha(Vector(Letters.booked(w.conf, b), Letters.bookedOwner(w.conf, b), Letters.reminder(w.conf, b),
      Letters.unbooked(w.conf, b)).map(n => n.subject + n.html + n.text).mkString +
      BookingPage.page(w.conf, Lang.pl, b) + okay.shop.booking.Calendar.ics(w.site, Vector(b), 1793282400000L))

  test("the three sites' pages byte for byte as before the modes") {
    assertEquals(digest("atelier"), "207ef226246f361bdc88424b966041f7625c3c2e67284a49a036011251587531")
    assertEquals(digest("studio"), "b546f5b82880472cb901808e7a93cad29082fce803684e914348e4f4225f7e11")
    assertEquals(digest("salon"), "a80431ae4ac98556d4667fb9b2b3d11a4e84877b9c20d7a1052c42d6d8fb5495")
  }

  test("a salon's booking letters, its page and its calendar as before the modes") {
    assertEquals(letters, "6ac1e93a598f38327a15d6de7be2d78a848803a88aec6590c0f3fc04a079243c")
  }
