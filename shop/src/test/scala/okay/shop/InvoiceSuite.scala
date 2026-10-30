package okay.shop

import okay.effects.json.{Descent, Json}
import okay.http.Query
import okay.serverless.cloud.{Response, text}
import okay.shop.invoice.*
import okay.shop.model.*

/** INVOICES (specs/shop.md "Invoices", "KSeF"): the NIP, the buyer asked with an order, a booking, an inquiry; the
 * seller; sums from gross; numbering per series, never twice; the invoice issued from a paid booking, its pages, a
 * correction, the CSV; KSeF in the alarm: sent, numbered, its UPO kept, a failure retried. Every NIP here fictional
 * (1111111111, 2222222222: checksum-valid, nobody's) */
class InvoiceSuite extends munit.FunSuite:
  private val owner = "owner@example.com"
  private val staff = "staff@example.com"
  private val fri10 = 1793282400000L + 19 * 3600000L // Friday 2026-10-30 10:00 Warsaw
  private val seller = Seller("TEST Salon Sp. z o.o.", "1111111111", "ul. Testowa 1", "00-001", "Warszawa")
  private val buyerFields = Seq("invoice" -> "on", "buyer_name" -> "TEST Kupiec Sp. z o.o.",
    "buyer_nip" -> "222-222-22-22", "buyer_street" -> "ul. Przykładowa 2", "buyer_postcode" -> "30-001",
    "buyer_city" -> "Kraków")

  private def world(id: String = "salon", s: Option[Seller] = Some(seller), ksef: Option[KsefEnv] = None) =
    World(id, staff = Set(staff, owner), owner = _ == owner, edit = _.copy(invoices = true), seller = s, ksef = ksef)

  private def in(w: World, email: String): Browser =
    val b = Browser(w)
    b.post("/workshop/api/link", "email" -> email, "turnstile" -> "t")
    val link = w.letters.last._2.html.split("'").find(_.contains("/workshop/api/enter?t=")).get
    b.send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(link.drop(link.indexOf("t=") + 2)))),
      "application/x-www-form-urlencoded")
    b

  private def obj(r: Response): Json.Obj = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }.get
  private def inv(r: Response): Json.Obj = J.obj(obj(r), "invoice")

  private def booked(w: World, extra: Seq[(String, String)] = buyerFields): Response =
    w.post("/api/book", (Seq("service" -> "cut", "start" -> fri10.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t").toMap ++
      extra).toSeq*)

  private var hour = 0
  private def paidBooking(w: World, b: Browser, amount: String = "123"): (String, String) =
    hour += 1
    val week = if w.now > fri10 then 7 * 86400000L else 0L
    val r = booked(w, buyerFields ++ Seq("start" -> (fri10 + week + (hour % 6 + 1) * 3600000L).toString))
    assertEquals(r.status, 200, r.text)
    val (ref, key) = (w.json(r)("ref"), w.json(r)("key"))
    assertEquals(b.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref, "action" -> "paid", "amount" -> amount,
      "method" -> "transfer").status, 200)
    (ref, key)

  test("NIP: ten digits, its check digit; written with PL, spaces, hyphens") {
    assert(Nip.valid("1111111111") && Nip.valid("PL 222-222-22-22"))
    assertEquals(Nip.parse("526 000 12 46"), Some("5260001246"))
    assert(!Nip.valid("1111111112") && !Nip.valid("12345") && !Nip.valid("abcdefghij"))
  }

  test("the buyer: by field; a form without the tick asks nothing") {
    assertEquals(Buyer.check("", "1111111111", "a", "00-001", "b"), Left("buyer.name"))
    assertEquals(Buyer.check("X", "1111111112", "a", "00-001", "b"), Left("buyer.nip"))
    assertEquals(Buyer.check("X", "1111111111", "a", "00001", "b"), Left("buyer.address"))
    assertEquals(Buyer.asked(Map("buyer_name" -> "X")), Right(None))
  }

  test("the seller: by field; an exempt seller's rate is zw, the basis of art. 113 said") {
    def j(fields: (String, String)*) = Json.Obj((Seq("name" -> "S", "nip" -> "1111111111", "street" -> "a",
      "postcode" -> "00-001", "city" -> "W", "status" -> "vat").toMap ++ fields).toVector.map((k, v) =>
      k -> Json.str(v)))
    assertEquals(Seller.fromJson(j("nip" -> "1"), Set("cut")), Left("seller.nip"))
    assertEquals(Seller.fromJson(j("status" -> "x"), Set("cut")), Left("seller.status"))
    assertEquals(Seller.fromJson(j("status" -> "zw43"), Set("cut")), Left("seller.basis"))
    assertEquals(Seller.fromJson(j("pattern" -> "FV/{yyyy}"), Set("cut")), Left("seller.pattern"))
    assertEquals(Seller.fromJson(j("bank" -> "12 34"), Set("cut")), Left("seller.bank"))
    val withRates = Json.Obj(j().fields :+ ("rates" -> Json.obj("cut" -> Json.str("8"))))
    assertEquals(Seller.fromJson(withRates, Set("cut")).map(_.rateOf("cut")), Right(Rate.R8))
    assertEquals(Seller.fromJson(withRates, Set("other")), Left("seller.rates"))
    val exempt = Seller.fromJson(j("status" -> "zw113"), Set("cut")).toOption.get
    assertEquals((exempt.rateOf("cut"), exempt.exemption.startsWith("art. 113 ust. 1")), (Rate.Zw, true))
    assertEquals(Seller.fromJson(Seller.toJson(exempt), Set("cut")), Right(exempt))
  }

  test("sums from gross (art. 106e ust. 7): VAT rounded half up, net the rest, by rate") {
    val ss = InvoiceMath.sums(Vector(Item("a", 1, 12300, Rate.R23), Item("b", 2, 5000, Rate.R8),
      Item("c", 1, 1000, Rate.Zw), Item("d", 1, 1, Rate.R23)))
    assertEquals(ss.map(s => (s.rate, s.net, s.vat, s.gross)), Vector((Rate.R23, 10001L, 2300L, 12301L),
      (Rate.R8, 4630L, 370L, 5000L), (Rate.Zw, 1000L, 0L, 1000L)))
    assertEquals(InvoiceMath.vatOf(-12300, Rate.R23), -2300L)
    assertEquals(InvoiceMath.zl(123456789), "1 234 567,89")
    assertEquals(InvoiceMath.number("FV/{yyyy}/{mm}/{n}", "2026-10-30", 7), "FV/2026/10/7")
  }

  test("the forms: 'Potrzebuję faktury' only on a shop that takes invoices; its fields; nothing else changed") {
    object L extends okay.shop.html.Look:
      def theme = okay.shop.html.Theme("--void: #fff;", ".bg { }")
      def seo(site: Site)(using l: Lang) = okay.shop.html.Seo(site.name, "d")
    def page(site: Site, id: String) = okay.shop.html.StaticPages.of(site, L, "K").toMap.apply(id)
    val salon = Fixtures.site("salon")
    val off = page(salon, "pl/offer/cut/index.html")
    val on = page(salon.copy(invoices = true), "pl/offer/cut/index.html")
    assertEquals(on.replaceAll("(?s)<div class=\"fld inv\">.*?</div></div>\n          ", ""), off)
    val atelier = Fixtures.site("atelier")
    assert(page(atelier.copy(invoices = true), "pl/index.html").contains("buyer_nip"))
    assert(!off.contains("buyer_nip"))
    assert(on.contains("name=\"invoice\"") && on.contains("buyer_nip") && on.contains("Potrzebuję faktury"))
  }

  test("asked with a booking, an order, an inquiry: kept beside it; a wrong NIP refused by its field") {
    val w = world()
    assertEquals(w.json(booked(w, buyerFields.map((k, v) => if k == "buyer_nip" then k -> "1234567890" else k -> v)))
      .get("error"), Some("buyer.nip"))
    val r = booked(w)
    assertEquals(r.status, 200, r.text)
    val b = in(w, staff)
    val got = obj(b.get(s"/workshop/api/buyer?of=booking&ref=${w.json(r)("ref")}"))
    assertEquals(J.s(J.obj(got, "buyer"), "nip"), "2222222222")
    // nobody signed in reads nothing
    assertEquals(Browser(w).get(s"/workshop/api/buyer?of=booking&ref=${w.json(r)("ref")}").status, 401)
    val a = World("atelier", staff = Set(staff), edit = _.copy(invoices = true), seller = Some(seller))
    val o = a.order(extra = buyerFields)
    assertEquals(o.status, 200, o.text)
    val ab = in(a, staff)
    assertEquals(J.s(J.obj(obj(ab.get(s"/workshop/api/buyer?of=order&ref=${a.json(o)("ref")}")), "buyer"), "city"),
      "Kraków")
    // the staff adds or changes it
    assertEquals(ab.post("/workshop/api/buyer", "of" -> "order", "ref" -> a.json(o)("ref"), "name" -> "TEST Inna",
      "nip" -> "1111111111", "street" -> "ul. A 1", "postcode" -> "00-950", "city" -> "Warszawa").status, 200)
    assertEquals(ab.post("/workshop/api/buyer", "of" -> "order", "ref" -> a.json(o)("ref"), "name" -> "X",
      "nip" -> "1", "street" -> "a", "postcode" -> "00-950", "city" -> "W").status, 400)
  }

  test("a quote accepted: its buyer goes with the booking or the order it became") {
    val w = World("visits", staff = Set(staff, owner), owner = _ == owner, edit = _.copy(invoices = true),
      seller = Some(seller))
    val asked = w.post("/api/quote", (Seq("service" -> "fix", "name" -> "Ola", "contact" -> "ola@example.com",
      "description" -> "kapie kran", "mode" -> "atClient", "street" -> "Prosta 1", "postcode" -> "02-001",
      "city" -> "Warszawa", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t") ++ buyerFields)*)
    assertEquals(asked.status, 200, asked.text)
    val (qref, qkey) = (w.json(asked)("ref"), w.json(asked)("key"))
    val b = in(w, owner)
    assertEquals(b.post("/workshop/api/quote/reply", "ref" -> qref, "price" -> "200").status, 200)
    val accepted = w.post("/api/quote/accept", "ref" -> qref, "k" -> qkey)
    assertEquals(accepted.status, 200, accepted.text)
    val ref = w.json(accepted)("ref")
    assertEquals(J.s(J.obj(obj(b.get(s"/workshop/api/buyer?of=order&ref=$ref")), "buyer"), "nip"), "2222222222")
  }

  test("issued from a paid booking: numbered in its series, never twice; refused unpaid, without a buyer or seller") {
    val w = world()
    val b = in(w, staff)
    val r = booked(w, Nil)
    val ref = w.json(r)("ref")
    assertEquals(w.json(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)).get("error"),
      Some("buyer"))
    assertEquals(b.post("/workshop/api/buyer", "of" -> "booking", "ref" -> ref, "name" -> "TEST Kupiec",
      "nip" -> "2222222222", "street" -> "ul. B 2", "postcode" -> "30-001", "city" -> "Kraków").status, 200)
    assertEquals(w.json(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)).get("error"),
      Some("unpaid"))
    b.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref, "action" -> "paid", "amount" -> "123",
      "method" -> "card")
    val first = b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    assertEquals(first.status, 200, first.text)
    assertEquals((J.s(inv(first), "number"), J.s(inv(first), "id"), J.n(inv(first), "total"),
      J.s(inv(first), "ksef")), ("FV/2026/10/1", "000001", 12300L, "none"))
    assertEquals(w.json(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)).get("error"),
      Some("issued"))
    val (ref2, _) = paidBooking(w, b)
    assertEquals(J.s(inv(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref2)), "number"),
      "FV/2026/10/2")
    // a new month: its own series from 1
    w.now += 3 * 86400000L
    val (ref3, _) = paidBooking(w, b)
    assertEquals(J.s(inv(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref3)), "number"),
      "FV/2026/11/1")
    val none = world(s = None)
    val nb = in(none, staff)
    val (ref4, _) = paidBooking(none, nb)
    assertEquals(none.json(nb.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref4)).get("error"),
      Some("seller"))
  }

  test("the invoice's pages: the client's by the link's key (and the receipt links it), the workshop's; a stranger " +
    "nothing") {
    val w = world()
    val b = in(w, staff)
    val (ref, key) = paidBooking(w, b)
    b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    val page = w.get(s"/pl/invoice?ref=$ref&k=$key")
    assertEquals(page.status, 200)
    Seq("Faktura nr FV/2026/10/1", "TEST Salon Sp. z o.o.", "NIP: 1111111111", "TEST Kupiec Sp. z o.o.",
      "NIP: 2222222222", "100,00", "23,00", "123,00", "zapłacono w całości").foreach(x =>
      assert(page.text.contains(x), x))
    assertEquals(w.get(s"/pl/invoice?ref=$ref&k=wrong").status, 404)
    assert(w.get(s"/pl/receipt?ref=$ref&k=$key").text.contains("Faktura nr FV/2026/10/1"))
    assertEquals(b.get("/workshop/invoices/view?id=000001").status, 200)
    assert(b.get("/workshop/invoices/").text.contains("FV/2026/10/1"))
    assertEquals(Browser(w).get("/workshop/invoices/").status, 303)
    assert(b.get("/workshop/invoices/xml?id=000001").text.contains("<P_2>FV/2026/10/1</P_2>"))
    assert(obj(b.get("/workshop/api/more")).toString.contains("/workshop/invoices/"))
  }

  test("an exempt seller's invoice: no VAT, the legal basis, zw") {
    val w = world(s = Some(seller.copy(status = VatStatus.Exempt113)))
    val b = in(w, staff)
    val (ref, key) = paidBooking(w, b)
    assertEquals(b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref).status, 200)
    val page = w.get(s"/pl/invoice?ref=$ref&k=$key").text
    assert(page.contains("zwolniony z VAT") && page.contains("art. 113 ust. 1") && !page.contains("Stawka VAT"))
    assert(b.get("/workshop/invoices/xml?id=000001").text.contains("<P_13_7>123.00</P_13_7>"))
  }

  test("a correction: the owner's alone; its own series; the lines before and after, the difference; not twice the " +
    "same") {
    val w = world()
    val b = in(w, staff)
    val (ref, key) = paidBooking(w, b)
    b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    def correct(by: Browser, gross: String, reason: String = "Rabat") = by.send("POST", "/workshop/api/invoice/correct",
      Json.print(Json.obj("id" -> Json.str("000001"), "reason" -> Json.str(reason), "lines" -> Json.arr(Json.obj(
        "name" -> Json.str("Strzyżenie"), "quantity" -> Json.num(1), "gross" -> Json.str(gross),
        "rate" -> Json.str("23"))))))
    assertEquals(correct(b, "100").status, 403)
    val o = in(w, owner)
    assertEquals(w.json(correct(o, "100", "")).get("error"), Some("reason"))
    val k = correct(o, "100")
    assertEquals(k.status, 200, k.text)
    assertEquals((J.s(inv(k), "number"), J.s(inv(k), "kind"), J.n(inv(k), "total")), ("FK/2026/10/1", "kor", -2300L))
    val page = o.get(s"/workshop/invoices/view?id=${J.s(inv(k), "id")}").text
    Seq("Faktura korygująca nr FK/2026/10/1", "Korekta faktury nr FV/2026/10/1", "Przyczyna korekty: Rabat",
      "Przed korektą", "Po korekcie", "-23,00").foreach(x => assert(page.contains(x), x))
    // the next correction starts from the last one's lines: the same again is no correction
    assertEquals(w.json(correct(o, "100")).get("error"), Some("same"))
    // the client sees the correction from the invoice's page
    assert(w.get(s"/pl/invoice?ref=$ref&k=$key").text.contains("Korekta"))
    assert(o.get("/workshop/invoices/xml?id=000002").text.contains("<RodzajFaktury>KOR</RodzajFaktury>"))
  }

  test("the accountant's CSV: the owner's; a BOM, the K_ columns, a correction's difference, BFK outside KSeF") {
    val w = world()
    val b = in(w, staff)
    val (ref, _) = paidBooking(w, b)
    b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    assertEquals(b.get("/workshop/api/invoices.csv").status, 403)
    val csv = in(w, owner).get("/workshop/api/invoices.csv?comma=1").text
    assert(csv.startsWith("﻿Lp;Numer;Rodzaj"))
    assert(csv.contains("1;FV/2026/10/1;VAT;2026-10-29;2026-10-30;2222222222;TEST Kupiec Sp. z o.o.;BFK;100,00;23,00"), csv)
  }

  test("KSeF: issued while connected, queued; the alarm sends it, then its number and UPO; the QR on its page") {
    val w = world(ksef = Some(KsefEnv.Test))
    val b = in(w, staff)
    val (ref, key) = paidBooking(w, b)
    val issued = b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    assertEquals(J.s(inv(issued), "ksef"), "queued")
    assert(w.storage.alarm.isDefined)
    w.ksefFake.slow = 1
    assertEquals(w.ksefRound(), 1)
    assertEquals(w.ksefFake.sent.size, 1)
    assert(w.ksefFake.sent.head.contains("<P_2>FV/2026/10/1</P_2>"))
    def state = J.obj(obj(b.get(s"/workshop/api/buyer?of=booking&ref=$ref")), "invoice")
    assertEquals(J.s(state, "ksef"), "sent")
    w.now += 5000
    w.ksefRound() // still processing
    w.now += 5000
    w.ksefRound()
    assertEquals(J.s(state, "ksef"), "accepted")
    assert(J.s(state, "ksefNumber").matches("1111111111-20261030-[0-9A-F]{12}-[0-9A-F]{2}"))
    assert(b.get("/workshop/invoices/upo?id=000001").text.contains(J.s(state, "ksefNumber")))
    val page = w.get(s"/pl/invoice?ref=$ref&k=$key").text
    assert(page.contains("https://qr-test.ksef.mf.gov.pl/invoice/1111111111/29-10-2026/"))
    assert(page.contains("ŚRODOWISKA TESTOWEGO"))
    assert(w.storage.alarm.isEmpty || w.ksefRound() == 0)
  }

  test("KSeF down: the error kept, tried again later by the alarm; the owner sends an older invoice") {
    val w = world(ksef = Some(KsefEnv.Test))
    val b = in(w, staff)
    val (ref, _) = paidBooking(w, b)
    b.post("/workshop/api/invoice/issue", "of" -> "booking", "ref" -> ref)
    w.ksefFake.down = true
    w.ksefRound()
    def state = J.obj(obj(b.get(s"/workshop/api/buyer?of=booking&ref=$ref")), "invoice")
    assertEquals((J.s(state, "ksef"), J.s(state, "error")), ("queued", "HTTP 503"))
    assert(w.storage.alarm.exists(_ >= w.now + 60000L))
    assertEquals(w.ksefRound(), 0) // not due yet
    w.ksefFake.down = false
    w.now += 61000L
    w.ksefRound()
    w.now += 4000L
    w.ksefRound()
    assertEquals(J.s(state, "ksef"), "accepted")
    // the owner's call queues one issued before the connection; staff may not
    assertEquals(b.post("/workshop/api/invoice/ksef", "id" -> "000001").status, 403)
    assertEquals(in(w, owner).post("/workshop/api/invoice/ksef", "id" -> "000001").status, 409)
  }
