package okay.shop

import okay.shop.html.*
import okay.shop.model.*

/** THE SITE'S PICTURES on its storefront (specs/shop.md "Photos"): the logo in the header, "Our work" lazy and
 * escaped, their style only then; a site without pictures drawn byte for byte as before */
class GallerySuite extends munit.FunSuite:
  import OldPages.Plain
  private val atelier = Fixtures.site("atelier")
  private val logo = Photo("/_media/a/0123456789abcdef0123.png", 320, 100)
  private val shots = Vector(Photo("/_media/a/aaaa.jpg", 1200, 900, T("", "Spodnie <skrócone>", "Trousers", "")),
    Photo("/_media/a/bbbb.webp", 800, 800))
  private def pages(s: Site) = StaticPages.of(s, Plain, "SITEKEY").toMap

  test("the logo before the logo text, on the storefront and the offers; its style only then") {
    val p = pages(atelier.copy(logo = Some(logo)))
    val front = p("pl/index.html")
    assert(front.contains("""<span class="mark"><img class="logo" src="/_media/a/0123456789abcdef0123.png" """ +
      """width="320" height="100" alt="">"""), front)
    assert(front.contains(".mark .logo {") && !front.contains("""<section class="gallery""""))
    assert(p("pl/offer/hem/index.html").contains("""← <img class="logo" src="/_media/a/0123456789abcdef0123.png""""))
    // no logo text: the name is the logo's words
    assert(pages(atelier.copy(logo = Some(logo), logoText = "")).apply("en/index.html").contains(
      s"""alt="${Html.escape(atelier.name)}">"""))
  }

  test("our work: a grid, lazy, each with its size and its words in the page's language, else the site's") {
    val p = pages(atelier.copy(gallery = shots))
    val pl = p("pl/index.html")
    assert(pl.contains("""<p class="label">Nasze prace</p>"""), pl)
    assert(pl.contains("""<img src="/_media/a/aaaa.jpg" width="1200" height="900" alt="Spodnie &lt;skrócone&gt;" """ +
      """loading="lazy" decoding="async">"""), pl)
    assert(pl.contains("""<a href="/_media/a/bbbb.webp" target="_blank" rel="noopener">"""))
    assert(p("en/index.html").contains("Our work") && p("en/index.html").contains("""alt="Trousers""""))
    // no Ukrainian words: the site's own language's
    assert(p("uk/index.html").contains("Наші роботи") && p("uk/index.html").contains("alt=\"Spodnie &lt;"))
    assert(pl.indexOf("Nasze prace") < pl.indexOf("""id="contact""""))
  }

  test("none: every page as before, byte for byte") {
    assertEquals(pages(atelier.copy(logo = None, gallery = Vector.empty)), pages(atelier))
    assert(!pages(atelier).values.exists(p => p.contains("class=\"logo\"") || p.contains(".shots")))
  }
