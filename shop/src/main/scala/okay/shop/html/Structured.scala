package okay.shop.html

import okay.shop.model.*

/**
 * THE BUSINESS AS SCHEMA.ORG READS IT (specs/shop.md "Structured data"): one node, `@id` `<origin>/#business` — the
 * same node `ReviewPage.structured` gives its rating to —, its type the look's (`ClothingStore`, `HairSalon`, ...),
 * the look's fields, and its catalog: every shown offer as an `Offer` of a `Service`, in the page's language, its
 * page linked, its price in PLN when it has one (a quoted one: none). A look puts it in its `Seo.head`; a look that
 * does not has its pages as before.
 */
object Structured:
  private def s(x: String) = "\"" + Html.json(x) + "\""

  /** «60.00» of 6000 grosze, as schema.org's `price` */
  def price(grosze: Long): String = f"${grosze / 100}.${grosze % 100}%02d"

  def business(site: Site, kind: String = "LocalBusiness", fields: Vector[(String, String)] = Vector.empty)
              (using l: Lang): String =
    val home = site.origin + "/"
    val offers = site.shown.map { o =>
      val service = s""""itemOffered":{"@type":"Service","name":${s(o.name(l))}""" +
        (if o.description(l).isEmpty then "" else s""","description":${s(o.description(l))}""") + "}"
      val priced = if o.priced && !o.quote then s""","price":"${price(o.price)}","priceCurrency":"PLN"""" else ""
      s"""{"@type":"Offer","url":${s(s"$home${l.code}/offer/${o.key}/")},$service$priced}"""
    }
    val catalog = if offers.isEmpty then "" else
      s""","hasOfferCatalog":{"@type":"OfferCatalog","name":${s(site.name)},"itemListElement":[${
        offers.mkString(",")}]}"""
    val more = fields.map((k, v) => s",${s(k)}:${s(v)}").mkString
    s"""<script type="application/ld+json">{"@context":"https://schema.org","@type":${s(kind)},"@id":${
      s(site.origin + "/#business")},"name":${s(site.name)},"url":${s(home)}$more$catalog}</script>"""
