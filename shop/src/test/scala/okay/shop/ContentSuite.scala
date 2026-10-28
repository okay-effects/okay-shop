package okay.shop

import okay.shop.model.*

class ContentSuite extends munit.FunSuite:
  test("the atelier: its seven offers in order, prices in grosze, the dictionary's words") {
    val s = Fixtures.site("szykownia")
    assertEquals(s.offers.map(_.key), Vector("hem", "fit", "zipper", "repair", "complex", "costume", "bespoke"))
    assertEquals(s.offer("hem").map(_.price), Some(4000L))
    assertEquals(s.offer("hem").map(_.priceShort), Some("40 zł"))
    assertEquals(s.offer("bespoke").map(_.priced), Some(false))
    assertEquals(s.offer("hem").map(_.name(Lang.en)), Some("Shortening trousers and skirts"))
    assertEquals(s.tagline(Lang.uk), "Ваш одяг у надійних руках — надішліть InPost, отримайте відремонтованим")
    assertEquals((s.lang, s.kind, s.refPrefix, s.domain), (Lang.pl, Kind.Atelier, "SZY", "szykownia.pl"))
  }

  test("IT: five offers, the AI first (its order 0), Russian by default, a description missing stays empty") {
    val s = Fixtures.site("it")
    assertEquals(s.offers.size, 5)
    assertEquals(s.offers.head.key, "ai")
    assertEquals(s.lang, Lang.ru)
    assertEquals(s.offer("consult").map(_.description(Lang.pl)), Some(""))
    assertEquals(s.offer("ai").map(_.name(Lang.pl)), Some("Sztuczna inteligencja, która rozwiązuje"))
    assertEquals(s.contact(Lang.en), "Write to me — I reply within a day. Consultation by appointment.")
  }

  test("contacts: an email or a phone; the second factor") {
    assert(Contact.valid("anna@example.com"))
    assert(Contact.valid("+48 790-869-655"))
    assert(!Contact.valid("anna"))
    assert(!Contact.valid("12345"))
    assert(Contact.matches("ANNA@example.com", "anna@example.com"))
    assert(Contact.matches("790869655", "+48 790 869 655"))
    assert(!Contact.matches("790869656", "+48 790 869 655"))
    assert(!Contact.matches("", "anna@example.com"))
  }

  test("money and days") {
    assertEquals(Money.zl(4050), "40,50 zł")
    assertEquals(Money.parse("40.5"), Some(4050L))
    assertEquals(Day.of(1793282400000L), "2026-10-29")
    assertEquals(Day.time(1793282400000L), "2026-10-29 15:00") // winter time: UTC+1
    assertEquals(Day.time(1783000000000L), "2026-07-02 15:46") // summer time: UTC+2
  }
