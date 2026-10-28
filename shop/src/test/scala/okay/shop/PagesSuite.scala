package okay.shop

import okay.shop.html.*
import okay.shop.model.*

/** the engine's pages, drawn with a look of a test's: language, switch, alternates, forms, Turnstile, consent */
class PagesSuite extends munit.FunSuite:
  object TestLook extends Look:
    def theme = Theme("--void: #fff;", ".bg { }")
    def seo(site: Site)(using l: Lang) = Seo(s"${site.name} ${l.code}", "description")
    override def sections(at: At)(using Lang) = "<section class=\"own\">its own</section>"
    override def privacy(site: Site, l: Lang) = s"privacy ${l.code}"
    override def policy = "2026-10-28"
    override def pages(at: Lang => (String => At)) =
      Lang.values.toVector.map(l => s"${l.code}/extra/index.html" -> s"extra ${at(l)("extra/").url(l)}")

  private val szy = StaticPages.of(Fixtures.site("szykownia"), TestLook, "SITEKEY").toMap

  test("every page: four languages of the storefront, each offer, privacy, the look's own; a 404; the workshop") {
    assertEquals(szy.size, 4 * (1 + 7 + 1 + 1) + 4)
    assertEquals(szy("pl/extra/index.html"), "extra https://szykownia.pl/pl/extra/")
    assert(szy("workshop/index.html").contains("""data-name="Szykownia""""))
  }

  test("a page is in its language: lang, the switch's `on`, the text, the alternates, the look's parts") {
    val en = szy("en/index.html")
    assert(en.startsWith("""<!doctype html><html lang="en">"""))
    assert(en.contains("""<a data-l="en" href="/en/" class="on">EN</a>"""))
    assert(en.contains("""<a data-l="uk" href="/uk/">UA</a>"""))
    assert(en.contains("Your clothes in good hands"))
    assert(en.contains("""hreflang="x-default" href="https://szykownia.pl/pl/""""))
    assert(en.contains("<title>Szykownia en</title>") && en.contains("its own") && en.contains("--void: #fff;"))
    assert(!en.contains("data-ru="), "no text swapped by a script")
  }

  test("forms: posted by the script, Turnstile in each, the consent required and linked to this language's policy") {
    val offer = szy("ru/offer/bespoke/index.html")
    assert(offer.contains("""data-send="order""""))
    assert(offer.contains("""name="fabric""""))
    assert(offer.contains("""data-sitekey="SITEKEY""""))
    assert(offer.contains("""name="rodo_consent" required"""))
    assert(offer.contains("""href="/ru/privacy/""""))
  }

  test("the engine's privacy policy: per site and language, its version shown, InPost only for the atelier") {
    val it = Fixtures.site("it")
    assert(Privacy.page(it, Lang.pl).contains(Privacy.version))
    assert(Privacy.page(it, Lang.pl).contains("IT Consulting"))
    assert(!Privacy.page(it, Lang.en).contains("InPost"))
    assert(Privacy.page(Fixtures.site("szykownia"), Lang.en).contains("InPost"))
  }

  test("the workshop: one muted word at the end of every public foot, not followed; not for search engines") {
    for (l, word) <- Seq("pl" -> "panel", "en" -> "staff", "uk" -> "панель", "ru" -> "панель") do
      for page <- Seq(s"$l/index.html", s"$l/offer/hem/index.html") do
        assert(szy(page).contains(s"""<a href="/workshop/" rel="nofollow" style="color:inherit;opacity:.65;""" +
          s"""text-decoration:none">$word</a></p>"""), page)
    assert(Privacy.page(Fixtures.site("it"), Lang.ru).contains("""href="/workshop/" rel="nofollow""""))
    assert(Track.search(Fixtures.site("it"), Lang.en, "", "k", failed = false).contains("""text-decoration:none">staff</a>"""))
    assert(szy("workshop/index.html").contains("""<meta name="robots" content="noindex">"""))
    assertEquals(szy("robots.txt"), "User-agent: *\nDisallow: /workshop/\nDisallow: /api/\n")
    assert(szy("_headers").contains("X-Robots-Tag: noindex"))
    val none = new Look:
      def theme = TestLook.theme
      def seo(site: Site)(using Lang) = TestLook.seo(site)
      override def staffLink = false
    assert(!StaticPages.of(Fixtures.site("szykownia"), none, "K").toMap.apply("pl/index.html").contains("/workshop/\""))
  }
