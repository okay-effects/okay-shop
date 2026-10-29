package okay.shop

import okay.effects.json.{Descent, Json}
import okay.http.Query
import okay.serverless.cloud.{Response, headerOf, text}
import okay.shop.model.*

/** MONEY (specs/shop.md "Money", "Reports", "Receipts"): the payment rules, the store's step and its numbering, the
 * old paid orders, the reports' sums, the CSV, the receipts and their links, the owner's alone */
class MoneySuite extends munit.FunSuite:
  private val owner = "owner@example.com"
  private val staff = "staff@example.com"
  private val hour = 3600000L
  private val fri10 = 1793282400000L + 19 * hour // Friday 2026-10-30 10:00 Warsaw

  private def world(id: String = "salon") = World(id, staff = Set(staff, owner), owner = _ == owner)

  /** a person signed in by the mailed link */
  private def in(w: World, email: String): Browser =
    val b = Browser(w)
    b.post("/workshop/api/link", "email" -> email, "turnstile" -> "t")
    val link = w.letters.last._2.html.split("'").find(_.contains("/workshop/api/enter?t=")).get
    val secret = link.drop(link.indexOf("t=") + 2)
    b.send("POST", "/workshop/api/enter", Query.print(Map("t" -> List(secret))), "application/x-www-form-urlencoded")
    b

  private def obj(r: Response): Json.Obj = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }.get
  private def pay(r: Response): Json.Obj = obj(r).get("payment").collect { case o: Json.Obj => o }.get
  private def str(o: Json.Obj, k: String) = o.get(k).collect { case Json.Str(s) => s }.getOrElse("")
  private def num(o: Json.Obj, k: String) = o.get(k).collect { case Json.Num(n) => n.toLong }.getOrElse(-1L)

  private def booked(w: World, start: Long = fri10): (String, String) =
    val r = w.post("/api/book", "service" -> "cut", "start" -> start.toString, "name" -> "Anna",
      "contact" -> "anna@example.com", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t")
    assertEquals(r.status, 200, r.text)
    (w.json(r)("ref"), w.json(r)("key"))

  test("the rules: staff marks paid once; the owner changes, undoes, refunds; bounds by field") {
    def m(action: String, amount: Long = 5000, day: String = "2026-10-29", of: Of = Of.Booking) =
      Mark(of, "R1", action, amount, Method.Blik, day, "", if of == Of.Manual then "Poprawki" else "")
    val now = 1L
    val today = "2026-10-29"
    val paid = Payments.mark(None, m("paid"), staff, owner = false, cancelled = false, today, now).toOption.get
    assertEquals((paid.state, paid.amount, paid.method, paid.log.map(_.what)), (PayState.Paid, 5000L, Method.Blik,
      Vector("paid")))
    def why(current: Option[Payment], x: Mark, isOwner: Boolean, cancelled: Boolean = false) =
      Payments.mark(current, x, if isOwner then owner else staff, isOwner, cancelled, today, now).left.toOption
    assertEquals(why(Some(paid), m("paid", 6000), isOwner = false), Some("owner"))
    assertEquals(why(Some(paid), m("unpaid"), isOwner = false), Some("owner"))
    assertEquals(why(Some(paid), m("refund"), isOwner = false), Some("owner"))
    assertEquals(why(None, m("refund"), isOwner = true), Some("state"))
    assertEquals(why(None, m("unpaid"), isOwner = true), Some("state"))
    assertEquals(why(None, m("paid", 0), isOwner = false), Some("amount"))
    assertEquals(why(None, m("paid", 100000001), isOwner = false), Some("amount"))
    assertEquals(why(None, m("paid", day = "2026-10-30"), isOwner = false), Some("day"))
    assertEquals(why(None, m("paid", day = "29.10.2026"), isOwner = false), Some("day"))
    assertEquals(why(None, m("paid"), isOwner = false, cancelled = true), Some("owner"))
    assertEquals(why(None, m("paid", of = Of.Manual), isOwner = false), Some("owner"))
    assertEquals(why(None, m("paid", of = Of.Manual).copy(description = ""), isOwner = true), Some("description"))
    val changed = Payments.mark(Some(paid), m("paid", 6000), owner, owner = true, cancelled = false, today, now)
    assertEquals(changed.map(p => (p.amount, p.log.map(_.what))), Right((6000L, Vector("paid", "changed"))))
    val refunded = Payments.mark(Some(paid), m("refund", day = ""), owner, true, false, today, now).toOption.get
    assertEquals((refunded.state, refunded.refunded), (PayState.Refunded, today))
    assert(refunded.income)
    val undone = Payments.mark(Some(paid), m("unpaid"), owner, true, false, today, now).toOption.get
    assertEquals(undone.state, PayState.Unpaid)
  }

  test("marked in the store's step: numbered per year, the number kept when undone and paid again; staff vs owner") {
    val w = world()
    val (ref1, _) = booked(w)
    val (ref2, _) = booked(w, fri10 + hour)
    val s = in(w, staff)
    val o = in(w, owner)
    val first = s.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref1, "action" -> "paid", "amount" -> "70",
      "method" -> "cash")
    assertEquals(first.status, 200, first.text)
    assertEquals((str(pay(first), "receipt"), num(pay(first), "amount"), str(pay(first), "day"), str(pay(first), "by")),
      ("RACH/2026/0001", 7000L, "2026-10-29", staff))
    assertEquals(s.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref1, "action" -> "paid", "amount" -> "80",
      "method" -> "cash").status, 403)
    assertEquals(s.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref1, "action" -> "unpaid").status, 403)
    val second = s.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref2, "action" -> "paid", "amount" -> "45,50",
      "method" -> "blik", "day" -> "2026-10-28")
    assertEquals(str(pay(second), "receipt"), "RACH/2026/0002")
    assertEquals(o.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref1, "action" -> "unpaid").status, 200)
    val again = o.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref1, "action" -> "paid", "amount" -> "75",
      "method" -> "card")
    assertEquals((str(pay(again), "receipt"), num(pay(again), "amount")), ("RACH/2026/0001", 7500L))
    assertEquals(o.post("/workshop/api/pay", "of" -> "booking", "ref" -> "NOPE", "action" -> "paid", "amount" -> "1",
      "method" -> "cash").status, 404)
    assertEquals(o.post("/workshop/api/pay", "of" -> "booking", "ref" -> ref2, "action" -> "paid", "amount" -> "1",
      "method" -> "gold").status, 400)
    // a manual income: the owner's, numbered too; refused to staff
    assertEquals(s.post("/workshop/api/income", "amount" -> "200", "method" -> "transfer",
      "description" -> "Poprawki poza stroną").status, 403)
    val manual = o.post("/workshop/api/income", "amount" -> "200", "method" -> "transfer", "description" ->
      "Poprawki poza stroną", "day" -> "2026-10-02")
    assertEquals(manual.status, 200, manual.text)
    assertEquals(str(pay(manual), "receipt"), "RACH/2026/0003")
    assertEquals(Descent.parse(o.get("/workshop/api/incomes").text).toOption.get("incomes").list.size, 1)
    // a new year: numbered from 1 again
    w.now = fri10 + 69L * 24 * hour
    val (ref3, _) = booked(w, fri10 + 70L * 24 * hour) // Friday 2027-01-08 10:00
    w.now = fri10 + 70L * 24 * hour + 2 * hour
    val next = in(w, staff).post("/workshop/api/pay", "of" -> "booking", "ref" -> ref3, "action" -> "paid",
      "amount" -> "70",
      "method" -> "cash")
    assertEquals(str(pay(next), "receipt"), "RACH/2027/0001")
  }

  test("an old paid order counts as paid, its old receipt as it was; a record's receipt numbered; a booking's linked") {
    val w = world("atelier")
    val placed = w.order("hem")
    val (ref, key) = (w.json(placed)("ref"), w.json(placed)("key"))
    // the old workflow's paid: amount and paidAt, no record
    import okay.scala3.*
    run(w.storage.handler(okay.shop.handlers.StoreDurable.run(okay.shop.effects.Store.record[(Pure +
      okay.serverless.Storage + okay.shop.effects.Store)](ref, Event.Moved(Status.Paid, "", 4000, ""), w.now))))
    val o = in(w, owner)
    val p = pay(o.get(s"/workshop/api/payment?of=order&ref=$ref"))
    val number = s"RACH-2026-${ref.stripPrefix("TST-")}"
    assertEquals((str(p, "state"), num(p, "amount"), str(p, "receipt")), ("paid", 4000L, number))
    val old = w.get(s"/pl/receipt?ref=$ref&k=$key").text
    assert(old.contains(s"Rachunek nr $number") && old.contains("40 zł") && old.contains("podatnikiem VAT"))
    // the owner changes it: a record, its old number kept
    val changed = o.post("/workshop/api/pay", "of" -> "order", "ref" -> ref, "action" -> "paid", "amount" -> "45",
      "method" -> "transfer")
    assertEquals(str(pay(changed), "receipt"), s"RACH-2026-${ref.stripPrefix("TST-")}")
    assert(w.get(s"/pl/receipt?ref=$ref&k=$key").text.contains("45 zł"))
    // a booking: no receipt until paid; then linked from its page
    val b = world()
    val (bref, bkey) = booked(b)
    assertEquals(b.get(s"/pl/receipt?ref=$bref&k=$bkey").status, 404)
    assert(!b.get(s"/pl/booking?ref=$bref&k=$bkey").text.contains("/receipt?"))
    in(b, staff).post("/workshop/api/pay", "of" -> "booking", "ref" -> bref, "action" -> "paid", "amount" -> "70",
      "method" -> "blik")
    val receipt = b.get(s"/pl/receipt?ref=$bref&k=$bkey")
    assertEquals(receipt.status, 200)
    assert(receipt.text.contains("Rachunek nr RACH/2026/0001") && receipt.text.contains("70 zł") &&
      receipt.text.contains("BLIK") && receipt.text.contains("Dokument niefiskalny"))
    assertEquals(b.get(s"/pl/receipt?ref=$bref&k=wrong").status, 404)
    assert(b.get(s"/pl/booking?ref=$bref&k=$bkey").text.contains(s"/pl/receipt?ref=$bref&amp;k=$bkey"))
  }

  test("the report: periods, sums, unpaid and expected, breakdowns, the previous period; owner only") {
    val w = world()
    val (a, _) = booked(w) // Friday 10:00
    val (b, _) = booked(w, fri10 + hour)
    val (c, _) = booked(w, fri10 + 2 * hour)
    booked(w, fri10 + 24 * hour) // Saturday: ahead, unpaid
    val o = in(w, owner)
    w.now = fri10 + 5 * hour // Friday afternoon: three past
    o.post("/workshop/api/pay", "of" -> "booking", "ref" -> a, "action" -> "paid", "amount" -> "70", "method" -> "cash")
    o.post("/workshop/api/pay", "of" -> "booking", "ref" -> b, "action" -> "paid", "amount" -> "60", "method" -> "blik")
    o.post("/workshop/api/booking/noshow", "ref" -> c)
    o.post("/workshop/api/income", "amount" -> "100", "method" -> "transfer", "description" -> "Strzyżenie w domu",
      "day" -> "2026-09-30")
    val r = obj(o.get("/workshop/api/report?from=2026-10-01&to=2026-10-31"))
    assertEquals(Vector("income", "paid", "average", "expected", "expectedCount", "bookings", "visits", "noShows")
      .map(num(r, _)), Vector(13000L, 2L, 6500L, 6000L, 1L, 3L, 2L, 1L))
    def lines(k: String) = r(k).list.map(x => (x("label").string, x("amount").long))
    assertEquals(lines("byMethod"), Vector("cash" -> 7000L, "blik" -> 6000L))
    assertEquals(r("days").list.size, 31)
    assertEquals(r("days").list.find(_("day").string == "2026-10-30").map(_("amount").long), Some(13000L))
    // the previous period: September, the manual income
    assertEquals(num(r("previous").asInstanceOf[Json.Obj], "income"), 10000L)
    assertEquals((r("previous")("from").string, r("previous")("to").string), ("2026-08-31", "2026-09-30"))
    // refunded: still the October income, a refund in its own period
    o.post("/workshop/api/pay", "of" -> "booking", "ref" -> b, "action" -> "refund")
    val after = obj(o.get("/workshop/api/report?from=2026-10-01&to=2026-10-31"))
    assertEquals((num(after, "income"), num(after, "refunds"), num(after, "net")), (13000L, 6000L, 7000L))
    // bad periods; staff refused; the pages
    assertEquals(o.get("/workshop/api/report?from=2026-10-31&to=2026-10-01").status, 400)
    assertEquals(o.get("/workshop/api/report?from=2025-01-01&to=2026-10-31").status, 400)
    assertEquals(in(w, staff).get("/workshop/api/report").status, 403)
    assertEquals(in(w, staff).get("/workshop/reports/").status, 403)
    assertEquals(Browser(w).get("/workshop/reports/").headerOf("location"), Some("/workshop/"))
    val page = o.get("/workshop/reports/?from=2026-10-01&to=2026-10-31&lang=pl")
    assertEquals(page.status, 200)
    assert(page.text.contains("Raporty") && page.text.contains("130 zł") && page.text.contains("<svg"))
    assert(o.get("/workshop/reports/?lang=uk").text.contains("Звіти"))
    assert(o.get("/workshop/reports/print?from=2026-10-01&to=2026-10-31&lang=en").text.contains("@media print"))
    val ew = o.get("/workshop/reports/ewidencja?month=2026-10").text
    assert(ew.contains("To nie jest oficjalna ewidencja") && ew.contains("Zwrot:") && ew.contains("70,00 zł"))
    assert(o.get("/workshop/api/more").text.contains("/workshop/reports/"))
    assert(!in(w, staff).get("/workshop/api/more").text.contains("/workshop/reports/"))
  }

  test("the CSV: a BOM, a payment a row, quoted when needed; comma decimals with `;` columns") {
    val w = world()
    val (a, _) = booked(w)
    val o = in(w, owner)
    o.post("/workshop/api/pay", "of" -> "booking", "ref" -> a, "action" -> "paid", "amount" -> "70.5",
      "method" -> "card")
    o.post("/workshop/api/income", "amount" -> "12", "method" -> "cash", "description" -> "Guziki; \"nici\"")
    val plain = o.get("/workshop/api/report.csv?from=2026-10-01&to=2026-10-31")
    assert(plain.headerOf("content-type").exists(_.startsWith("text/csv")))
    assert(plain.headerOf("content-disposition").exists(_.contains("report-2026-10-01-2026-10-31.csv")))
    val rows = plain.text.split("\r\n").toVector
    assert(rows.head.startsWith("﻿date,receipt,kind,"))
    assertEquals(rows.size, 3)
    assert(rows.exists(_.endsWith(",card,70.50,paid")))
    val pl = o.get("/workshop/api/report.csv?from=2026-10-01&to=2026-10-31&comma=1").text.split("\r\n").toVector
    assert(pl.exists(_.contains(";card;70,50;paid")))
    assert(pl.exists(_.contains("\"Guziki; \"\"nici\"\"\"")))
    assertEquals(Reports.amount(-1205, comma = true), "-12,05")
  }

  test("periods and months: a month's days, the previous of a month the same number of days before") {
    assertEquals(Reports.month("2026-02-14"), Period("2026-02-01", "2026-02-28"))
    assertEquals(Reports.month("2026-12-31"), Period("2026-12-01", "2026-12-31"))
    assertEquals(Period("2026-10-01", "2026-10-31").previous, Period("2026-08-31", "2026-09-30"))
    assertEquals(Period("2026-10-29", "2026-10-29").previous, Period("2026-10-28", "2026-10-28"))
    assertEquals(Reports.period("2026-02-30", "2026-03-01"), Left("day"))
    assertEquals(Reports.period("2026-01-01", "2026-12-31").map(_.days.size), Right(365))
  }
