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
  /** the foot's words since 2026-10-29 ("made with okay-effects"), read back as they were, so the digests still pin
   * everything else */
  private val before = Vector("сделано в okay-effects" -> "сделано в busi", "zrobione w okay-effects" ->
    "zrobione w busi", "made with okay-effects" -> "made with busi", "зроблено в okay-effects" -> "зроблено в busi")
  private def asBefore(page: String) = before.foldLeft(page)((p, w) => p.replace(w._1, w._2))
  /** every page but the privacy policy (changed on purpose 2026-11-05: its processors and cookies from the config,
   * the retention said precisely — specs/shop.md "The privacy policy from the config") */
  def digest(id: String): String =
    sha(StaticPages.of(Fixtures.site(id), Plain, "SITEKEY")
      .filterNot(_._1.endsWith("/privacy/index.html")).sortBy(_._1).map((k, v) => k + "\n" + asBefore(v))
      .mkString("\n"))
  def letters: String =
    val w = World("salon")
    val b = Booking("TSLB-1", "KEY", "cut", "main", 1793282400000L + 19 * 3600000L, 45, "Anna", "anna@example.com",
      Lang.pl, Consent(1793282400000L, "2026-10-28"), 1793282400000L)
    sha(Vector(Letters.booked(w.conf, b), Letters.bookedOwner(w.conf, b), Letters.reminder(w.conf, b),
      Letters.unbooked(w.conf, b)).map(n => n.subject + n.html + n.text).mkString +
      BookingPage.page(w.conf, Lang.pl, b) + okay.shop.booking.Calendar.ics(w.site, Vector(b), 1793282400000L))

  test("the three sites' pages byte for byte as before the modes") {
    assertEquals(digest("atelier"), "7e612d3b1629bb37fbde58eebce6c709735081b520c3f0cc5dbe6cac710cc201")
    assertEquals(digest("studio"), "b0be1cf166e252ad382d17d13bbf93dc6eeec4fa5fb2e7316d7e1fd263e6b378")
    assertEquals(digest("salon"), "8f278cd20674a7445d84f04dd8301be72ffa465cd0edb4131346b65e07cb200e")
  }

  test("a salon's booking letters, its page and its calendar as before the modes") {
    assertEquals(letters, "6ac1e93a598f38327a15d6de7be2d78a848803a88aec6590c0f3fc04a079243c")
  }

  test("the foot says it was made with okay-effects, in each language") {
    val pages = StaticPages.of(Fixtures.site("atelier"), Plain, "SITEKEY").toMap
    assert(pages.values.exists(_.contains("zrobione w okay-effects")))
    assert(!pages.values.exists(p => "\\bbusi\\b".r.findFirstIn(p).isDefined))
  }
