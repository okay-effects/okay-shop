package okay.shop

import okay.shop.html.*
import okay.shop.model.*

/** THE BUSINESS FOR SEARCH ENGINES (specs/shop.md "Structured data"): one node with the rating's `@id`, the shown
 * offers with their prices, a look without it as before */
class StructuredSuite extends munit.FunSuite:
  private given Lang = Lang.pl
  private val site = Fixtures.site("salon")

  test("one node: the rating's @id, the look's type and fields, every shown offer with its price in PLN") {
    val ld = Structured.business(site, "HairSalon", Vector("areaServed" -> "PL"))
    assert(ld.startsWith("""<script type="application/ld+json">{"@context":"https://schema.org","@type":"HairSalon","""
      + """"@id":"https://salon.example/#business","name":"Test Salon","url":"https://salon.example/","""
      + """"areaServed":"PL","hasOfferCatalog":{"@type":"OfferCatalog""""), ld)
    assert(ld.contains("""{"@type":"Offer","url":"https://salon.example/pl/offer/cut/","itemOffered":{"@type":"""
      + """"Service","name":"Strzyżenie","description":"nożyczki i maszynka"},"price":"60.00","priceCurrency":"PLN"}"""),
      ld)
    assert(ld.contains("\"Voucher\"") && ld.endsWith("]}}</script>"))
  }

  test("a hidden offer left out, a quoted one without a price, words escaped") {
    val edited = site.copy(name = "Test \"Salon\"", offers = site.offers.map(o =>
      if o.key == "beard" then o.copy(hidden = true) else if o.key == "cut" then o.copy(quote = true) else o))
    val ld = Structured.business(edited)
    assert(!ld.contains("Broda") && ld.contains("\"@type\":\"LocalBusiness\""), ld)
    assert(ld.contains(""""name":"Strzyżenie","description":"nożyczki i maszynka"}}"""), ld)
    assert(ld.contains("Test \\\"Salon\\\""), ld)
    assertEquals(Structured.price(4050), "40.50")
  }

  test("no offers: no catalog") {
    assert(!Structured.business(site.copy(offers = Vector.empty)).contains("hasOfferCatalog"))
  }
