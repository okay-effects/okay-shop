package okay.shop

import okay.shop.model.*

class ContentSuite extends munit.FunSuite:
  test("the atelier: its seven offers in order, prices in grosze, the dictionary's words") {
    val s = Fixtures.site("atelier")
    assertEquals(s.offers.map(_.key), Vector("hem", "fit", "zipper", "repair", "complex", "costume", "bespoke"))
    assertEquals(s.offer("hem").map(_.price), Some(4000L))
    assertEquals(s.offer("hem").map(_.priceShort), Some("40 zł"))
    assertEquals(s.offer("bespoke").map(_.priced), Some(false))
    assertEquals(s.offer("hem").map(_.name(Lang.en)), Some("Shortening trousers"))
    assertEquals(s.tagline(Lang.uk), "Шиємо й лагодимо — надішліть посилку, отримайте готове")
    assertEquals((s.lang, s.kind, s.refPrefix, s.domain), (Lang.pl, Kind.Atelier, "TST", "atelier.example"))
  }

  test("the studio (IT-like): five offers, the AI first (its order 0), Russian by default, a description missing " +
      "stays empty") {
    val s = Fixtures.site("studio")
    assertEquals(s.offers.size, 5)
    assertEquals(s.offers.head.key, "ai")
    assertEquals(s.lang, Lang.ru)
    assertEquals(s.offer("consult").map(_.description(Lang.pl)), Some(""))
    assertEquals(s.offer("ai").map(_.name(Lang.pl)), Some("Asystent AI"))
    assertEquals(s.contact(Lang.en), "Write to us — we reply within a day.")
  }

  test("contacts: an email or a phone; the second factor") {
    assert(Contact.valid("anna@example.com"))
    assert(Contact.valid("+48 600-000-000"))
    assert(!Contact.valid("anna"))
    assert(!Contact.valid("12345"))
    assert(Contact.matches("ANNA@example.com", "anna@example.com"))
    assert(Contact.matches("600000000", "+48 600 000 000"))
    assert(!Contact.matches("600000001", "+48 600 000 000"))
    assert(!Contact.matches("", "anna@example.com"))
  }

  test("money and days") {
    assertEquals(Money.zl(4050), "40,50 zł")
    assertEquals(Money.parse("40.5"), Some(4050L))
    assertEquals(Day.of(1793282400000L), "2026-10-29")
    assertEquals(Day.time(1793282400000L), "2026-10-29 15:00") // winter time: UTC+1
    assertEquals(Day.time(1783000000000L), "2026-07-02 15:46") // summer time: UTC+2
  }

/** THE OWNER EDITS THE CONTENT (specs/shop.md): read, checked, written back as a patch of the files */
class EditSuite extends munit.FunSuite:
  import okay.shop.content.{Content, Service, Sites}
  import okay.effects.json.{Descent, Json}
  private val ids = Vector("atelier", "studio", "salon", "visits", "crew")
  private def files(id: String) = Fixtures.content(id)

  test("every fixture written unchanged is the same bytes") {
    ids.foreach { id =>
      val (md, i18n) = files(id)
      assertEquals(Content.write(Content.read(md, i18n), md, i18n), (md, i18n), id)
      // through the screen's JSON too
      val c = Content.read(md, i18n)
      val back = Content.fromJson(Descent.parse(Json.print(Content.toJson(c))).toOption.get, c.lang,
        c.services.map(_.key).toSet).toOption.get
      assertEquals(back, c, id)
    }
  }

  test("an edit read back is the edit; the site reads it; untouched lines stay") {
    val (md, i18n) = files("atelier")
    val c = Content.read(md, i18n)
    val hem = c.services.head
    val edited = c.copy(name = "Atelier Nowe", tagline = c.tagline.copy(pl = "Szyjemy szybciej", en = "We sew faster"),
      services = c.services.updated(0, hem.copy(name = hem.name.copy(pl = "Skracanie nogawek"), price = 4500,
        minutes = 30)) :+ Service("", okay.shop.model.T.all("Łatanie kurtek"), okay.shop.model.T.all("2 dni"),
        5500), auto = c.auto + "tagline.uk")
    val keyed = Content.fromJson(Content.toJson(edited), c.lang, c.services.map(_.key).toSet).toOption.get
    assertEquals(keyed.services.last.key, "latanie-kurtek")
    val (md2, i18n2) = Content.write(keyed, md, i18n)
    assertEquals(Content.read(md2, i18n2), keyed)
    val site = Sites.of("atelier", md2, i18n2)
    assertEquals(site.name, "Atelier Nowe")
    assertEquals(site.tagline(okay.shop.model.Lang.en), "We sew faster")
    assertEquals(site.offer("hem").map(o => (o.name(okay.shop.model.Lang.pl), o.price, o.minutes)),
      Some(("Skracanie nogawek", 4500L, 30)))
    assertEquals(site.offers.last.key, "latanie-kurtek")
    // the lines not edited are as they were: only the changed ones differ
    val (a, b) = (md.split("\n").toSet, md2.split("\n").toSet)
    assertEquals((a -- b).map(_.trim), Set("name: Test Atelier", "price: 40.00",
      "tagline: Szyjemy i naprawiamy — wyślij paczkę, odbierz gotowe", "name: Skracanie spodni"))
    assert(i18n2.contains("auto: uk"))
    // written again unchanged, nothing moves
    assertEquals(Content.write(Content.read(md2, i18n2), md2, i18n2), (md2, i18n2))
  }

  test("reordered: the rows in the new order, numbered; a key kept on rename; removed rows gone") {
    val (md, i18n) = files("salon")
    val c = Content.read(md, i18n)
    val turned = c.copy(services = c.services.reverse.drop(1).map(s =>
      if s.key == c.services.head.key then s.copy(name = okay.shop.model.T.all("Nowa nazwa")) else s))
    val (md2, i18n2) = Content.write(turned, md, i18n)
    val back = Content.read(md2, i18n2)
    assertEquals(back.services.map(_.key), turned.services.map(_.key))
    assertEquals(Sites.of("salon", md2, i18n2).offers.map(_.order), (1 to turned.services.size).toVector)
    assertEquals(back.services.last.name(okay.shop.model.Lang.ru), "Nowa nazwa")
  }

  test("hidden: off the pages, nothing new for it; removed while used: kept hidden") {
    val (md, i18n) = files("atelier")
    val c = Content.read(md, i18n)
    val (md2, i18n2) = Content.write(c.copy(services = c.services.map(s =>
      if s.key == "fit" then s.copy(hidden = true) else s)), md, i18n)
    assert(md2.contains("  hidden: true"))
    val site = Sites.of("atelier", md2, i18n2)
    assert(site.offer("fit").exists(_.hidden) && !site.shown.exists(_.key == "fit"))
    val pages = okay.shop.html.StaticPages.of(site, OldPages.Plain, "K").toMap
    assert(!pages.contains("pl/offer/fit/index.html") && pages.contains("pl/offer/hem/index.html"))
    assert(!pages("pl/index.html").contains("/pl/offer/fit/"))
    val (kept, hidden) = Content.keeping(c, c.copy(services = c.services.filterNot(s => s.key == "fit" ||
      s.key == "zipper")), Set("fit"))
    assertEquals(hidden, Vector("fit"))
    assertEquals(kept.services.filter(_.hidden).map(_.key), Vector("fit"))
    assert(!kept.services.exists(_.key == "zipper"))
  }

  test("checked by field; every text one line; an empty language takes the shop's own, marked") {
    val c = Content.read(files("atelier")._1, files("atelier")._2)
    def why(x: Content) = Content.check(x).left.toOption
    assertEquals(why(c.copy(name = "")), Some("name"))
    assertEquals(why(c.copy(tagline = c.tagline.copy(pl = "x" * 161))), Some("tagline"))
    assertEquals(why(c.copy(services = c.services :+ c.services.head)), Some("service.key"))
    assertEquals(why(c.copy(services = c.services.map(_.copy(price = 10000001)))), Some("service.price"))
    assertEquals(why(c.copy(services = c.services.map(_.copy(minutes = 3)))), Some("service.minutes"))
    assertEquals(why(c.copy(services = c.services.map(_.copy(seats = 5)))), Some("service.times"))
    assertEquals(why(c.copy(services = Vector.fill(51)(c.services.head))), Some("services"))
    val ok = Content.check(c.copy(name = " Test \n Atelier ", tagline = c.tagline.copy(en = ""))).toOption.get
    assertEquals(ok.name, "Test Atelier")
    assertEquals(ok.tagline.en, ok.tagline.pl)
    assert(ok.auto("tagline.en"))
  }

  test("no markup taken: a name with tags is escaped on the pages") {
    val (md, i18n) = files("atelier")
    val c = Content.read(md, i18n)
    val (md2, i18n2) = Content.write(c.copy(services = c.services.map(s => if s.key == "hem" then
      s.copy(name = okay.shop.model.T.all("<script>alert(1)</script>")) else s)), md, i18n)
    val pages = okay.shop.html.StaticPages.of(Sites.of("atelier", md2, i18n2), OldPages.Plain, "K").toMap
    assert(!pages.values.exists(_.contains("<script>alert(1)")))
    assert(pages("pl/index.html").contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
  }

  test("a hidden service takes no new order, booking or inquiry; its booking's page still names it") {
    def hide(key: String)(s: okay.shop.model.Site) =
      s.copy(offers = s.offers.map(o => if o.key == key then o.copy(hidden = true) else o))
    val atelier = World(edit = hide("hem"))
    assertEquals(atelier.json(atelier.order("hem")).get("error"), Some("offer"))
    assertEquals(atelier.order("fit").status, 200)
    val fri10 = 1793282400000L + 19 * 3600000L
    val open = World("salon")
    val made = open.post("/api/book", "service" -> "cut", "start" -> fri10.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t")
    assertEquals(made.status, 200)
    val salon = World("salon", edit = hide("cut"))
    assertEquals(salon.json(salon.post("/api/book", "service" -> "cut", "start" -> (fri10 + 3600000L).toString,
      "name" -> "Anna", "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl",
      "cf-turnstile-response" -> "t")).get("error"), Some("service"))
  }
