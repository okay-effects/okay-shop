package okay.shop

import okay.serverless.cloud.{Request, headerOf}
import okay.shop.app.Front

class FrontSuite extends munit.FunSuite:
  private val szy = Fixtures.site("szykownia")
  private val it = Fixtures.site("it")
  /** a GET of `url` as the Worker sees it: its path and query, its host a header */
  private def request(url: String, headers: (String, String)*): Request =
    val rest = url.stripPrefix("https://")
    val host = rest.takeWhile(_ != '/')
    Request.get(rest.drop(host.length) match { case "" => "/"; case p => p })
      .copy(headers = (("host" -> host) +: headers).map((k, v) => k -> List(v)).toMap)
  private def to(site: model.Site, url: String, headers: (String, String)*): Option[(Int, String)] =
    Front.redirect(site, request(url, headers*)).map(r => r.status -> r.headerOf("location").getOrElse(""))

  test("the root: the cookie's language, else the browser's, else the site's") {
    assertEquals(to(szy, "https://szykownia.pl/"), Some(302 -> "/pl/"))
    assertEquals(to(it, "https://it.szykownia.pl/"), Some(302 -> "/ru/"))
    assertEquals(to(szy, "https://szykownia.pl/", "accept-language" -> "uk-UA,uk;q=0.9,en;q=0.8"), Some(302 -> "/uk/"))
    assertEquals(to(szy, "https://szykownia.pl/", "accept-language" -> "de", "cookie" -> "lang=en"),
         Some(302 -> "/en/"))
  }

  test("busi's URLs, moved for good") {
    assertEquals(to(szy, "https://szykownia.pl/s/szykownia"), Some(301 -> "/pl/"))
    assertEquals(to(szy, "https://szykownia.pl/s/szykownia/offer/hem"), Some(301 -> "/pl/offer/hem/"))
    assertEquals(to(szy, "https://szykownia.pl/s/szykownia/privacy"), Some(301 -> "/pl/privacy/"))
    assertEquals(to(szy, "https://szykownia.pl/s/szykownia/track?ref=job-1"), Some(301 -> "/pl/track?ref=job-1"))
    assertEquals(to(it, "https://it.szykownia.pl/products", "cookie" -> "lang=pl"), Some(301 -> "/pl/products/"))
    assertEquals(to(szy, "https://www.szykownia.pl/pl/?a=1"), Some(301 -> "https://szykownia.pl/pl/?a=1"))
    assertEquals(to(szy, "https://szykownia.pl/pl/"), None)
    assertEquals(to(szy, "https://szykownia.pl/pl"), Some(301 -> "/pl/"))
    assertEquals(to(szy, "https://szykownia.pl/workshop"), Some(301 -> "/workshop/"))
    assertEquals(to(szy, "https://szykownia.pl/site.js"), None)
    assertEquals(to(szy, "https://szykownia.pl/pl/track"), None)
  }

  test("what the site's object answers") {
    def dyn(p: String) = Front.dynamic(request("https://szykownia.pl" + p))
    assert(dyn("/api/order"))
    assert(dyn("/pl/track"))
    assert(dyn("/workshop/api/me"))
    assert(!dyn("/pl/"))
    assert(!dyn("/xx/track"))
    assert(!dyn("/workshop/"))
  }
