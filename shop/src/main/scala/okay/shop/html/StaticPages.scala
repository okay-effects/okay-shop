package okay.shop.html

import okay.shop.model.*

/** EVERY STATIC PAGE OF A SITE: its path under the site's root and its HTML — the four languages' storefront, offers,
 * privacy and the look's own pages; the 404; the workshop's frame. What a site's build writes (`StaticPages.write` on
 * the JVM), what its tests read */
object StaticPages:
  def of(site: Site, look: Look, sitekey: String): Vector[(String, String)] =
    def at(l: Lang)(path: String) = At(site, l, path, sitekey)
    Lang.values.toVector.flatMap { l =>
      Vector(s"${l.code}/index.html" -> Pages.storefront(at(l)(""), look)) ++
        site.offers.map(o => s"${l.code}/offer/${o.key}/index.html" ->
          Pages.offer(at(l)(s"offer/${o.key}/"), o, look)) ++
        Vector(s"${l.code}/privacy/index.html" -> look.privacy(site, l))
    } ++ look.pages(l => at(l)) ++
      Vector("404.html" -> Pages.missing(site, look), "workshop/index.html" -> Pages.workshop(site, sitekey),
        // the workshop and the API are not for search engines: robots told, and the workshop's frame says so too
        "robots.txt" -> "User-agent: *\nDisallow: /workshop/\nDisallow: /api/\n",
        "_headers" -> "/workshop/*\n  X-Robots-Tag: noindex\n")
