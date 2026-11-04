package okay.shop

import okay.shop.html.*
import okay.shop.model.*

/** the engine's pages, drawn with a look of a test's: language, switch, alternates, forms, Turnstile, consent */
class PagesSuite extends munit.FunSuite:
  object TestLook extends Look:
    def theme = Theme("--void: #fff;", ".bg { }")
    def seo(site: Site)(using l: Lang) = Seo(s"${site.name} ${l.code}", "description")
    override def sections(at: At)(using Lang) = "<section class=\"own\">its own</section>"
    override def privacy(site: Site, l: Lang, mail: Privacy.Mail) = s"privacy ${l.code}"
    override def policy = "2026-10-28"
    override def pages(at: Lang => (String => At)) =
      Lang.values.toVector.map(l => s"${l.code}/extra/index.html" -> s"extra ${at(l)("extra/").url(l)}")

  private val szy = StaticPages.of(Fixtures.site("atelier"), TestLook, "SITEKEY").toMap

  test("every page: four languages of the storefront, each offer, privacy, the look's own; a 404; the workshop") {
    assertEquals(szy.size, 4 * (1 + 7 + 1 + 1) + 4)
    assertEquals(szy("pl/extra/index.html"), "extra https://atelier.example/pl/extra/")
    assert(szy("workshop/index.html").contains("""data-name="Test Atelier""""))
  }

  test("a page is in its language: lang, the switch's `on`, the text, the alternates, the look's parts") {
    val en = szy("en/index.html")
    assert(en.startsWith("""<!doctype html><html lang="en">"""))
    assert(en.contains("""<a data-l="en" href="/en/" class="on">EN</a>"""))
    assert(en.contains("""<a data-l="uk" href="/uk/">UA</a>"""))
    assert(en.contains("We sew and mend"))
    assert(en.contains("""hreflang="x-default" href="https://atelier.example/pl/""""))
    assert(en.contains("<title>Test Atelier en</title>") && en.contains("its own") && en.contains("--void: #fff;"))
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
    val it = Fixtures.site("studio")
    assert(Privacy.page(it, Lang.pl).contains(Privacy.version))
    assert(Privacy.page(it, Lang.pl).contains("Test Studio"))
    assert(!Privacy.page(it, Lang.en).contains("InPost"))
    assert(Privacy.page(Fixtures.site("atelier"), Lang.en).contains("InPost"))
    assert(Privacy.page(Fixtures.site("atelier"), Lang.pl).contains("mailto:kontakt@atelier.example"))
    assert(Privacy.page(it, Lang.pl, "office@example.com").contains("mailto:office@example.com"))
  }

  test("the site's host in the privacy policy: its items first among the recipients; none, the same bytes") {
    val atelier = Fixtures.site("atelier")
    for l <- Lang.values do
      assertEquals(Privacy.page(atelier, l, host = Vector.empty), Privacy.page(atelier, l))
    val hosted = Privacy.page(atelier, Lang.pl, host = Vector("""Platforma <a href="https://host.example/">Host</a>"""))
    assert(hosted.contains("<ul>\n<li>Platforma <a href=\"https://host.example/\">Host</a></li>\n<li>Cloudflare, Inc."),
      hosted)
    assertEquals(hosted.replace("""<li>Platforma <a href="https://host.example/">Host</a></li>""" + "\n", ""),
      Privacy.page(atelier, Lang.pl))
  }

  test("the policy from the config: the mail's way named, Brevo only when used; DPF; AI, geocoding, cookies") {
    import Privacy.Mail
    val studio = Fixtures.site("studio")
    assertEquals(Mail.of("cloudflare", "smtp-relay.brevo.com"), Mail.Cloudflare)
    assertEquals(Mail.of("smtp", "smtp-relay.brevo.com"), Mail.Brevo)
    assertEquals(Mail.of("", "mail.example.com"), Mail.Smtp("mail.example.com"))
    assertEquals(Mail.of("", ""), Mail.Off)
    assertEquals(Mail.of("resend", ""), Mail.Resend)
    for l <- Lang.values do
      val plain = Privacy.page(studio, l)
      assert(!plain.contains("Brevo") && plain.contains("Cloudflare Email Service"), plain)
      assert(plain.contains("EU-US Data Privacy Framework"), plain)
      assert(Privacy.page(studio, l, mail = Mail.Brevo).contains("Sendinblue SAS (Brevo"))
      assert(!Privacy.page(studio, l, mail = Mail.Off).contains("Cloudflare Email Service"))
      assert(!plain.contains("Workers AI") && Privacy.page(studio, l, chat = 30).contains("Cloudflare Workers AI ("))
      assert(plain.contains("<code>lang</code>") && plain.contains("<code>__Host-ws</code>"))
      assert(!plain.contains("okc-warm") && Privacy.page(studio, l, chat = 30).contains("okc-warm"))
      assert(Privacy.page(studio, l, cookies = Vector("<code>z-test</code> — a host's")).contains(
        "<li><code>z-test</code> — a host's</li>"))
      // the geocoding only for a travel radius, on a host that geocodes
      assert(!Privacy.page(studio, l, geocoding = true).contains("Nominatim"))
    // the last processor ends the list with a full stop (no InPost for the studio)
    assert(Privacy.page(studio, Lang.en).contains("sending e-mails to clients.</li>\n</ul>"))
    assert(Privacy.page(studio, Lang.en, mail = Mail.Smtp("mail.example.com")).contains(
      "the mail server mail.example.com — sending e-mails to clients.</li>"))
  }

  test("the workshop: one muted word at the end of every public foot, not followed; not for search engines") {
    for (l, word) <- Seq("pl" -> "panel", "en" -> "staff", "uk" -> "панель", "ru" -> "панель") do
      for page <- Seq(s"$l/index.html", s"$l/offer/hem/index.html") do
        assert(szy(page).contains(s"""<a href="/workshop/" rel="nofollow" style="color:inherit;opacity:.65;""" +
          s"""text-decoration:none">$word</a></p>"""), page)
    assert(Privacy.page(Fixtures.site("studio"), Lang.ru).contains("""href="/workshop/" rel="nofollow""""))
    assert(Track.search(Fixtures.site("studio"), Lang.en, "", "k",
         failed = false).contains("""text-decoration:none">staff</a>"""))
    assert(szy("workshop/index.html").contains("""<meta name="robots" content="noindex">"""))
    assertEquals(szy("robots.txt"), "User-agent: *\nDisallow: /workshop/\nDisallow: /api/\n")
    assert(szy("_headers").contains("X-Robots-Tag: noindex"))
    val none = new Look:
      def theme = TestLook.theme
      def seo(site: Site)(using Lang) = TestLook.seo(site)
      override def staffLink = false
    assert(!StaticPages.of(Fixtures.site("atelier"), none, "K").toMap.apply("pl/index.html").contains("/workshop/\""))
  }
