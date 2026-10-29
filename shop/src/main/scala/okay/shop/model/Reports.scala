package okay.shop.model

/** a period of days of Poland, both ends included (`YYYY-MM-DD`) */
final case class Period(from: String, to: String):
  def has(day: String): Boolean = day >= from && day <= to
  /** its days, in order */
  def days: Vector[String] = Reports.daysBetween(from, to)
  /** the same number of days just before */
  def previous: Period =
    val n = days.size
    Period(Reports.plus(from, -n), Reports.plus(from, -1))

/** a payment as a report lists it: its day, receipt, what it is of, its ref, what for, by whom, how, how much */
final case class Entry(day: String, receipt: String, of: Of, ref: String, description: String, service: String,
                       master: String, mode: String, method: Method, amount: Long, state: PayState)

/** a line of a breakdown: its key, its words, its sum and count */
final case class Line(key: String, label: String, amount: Long, count: Int)

/**
 * A PERIOD'S MONEY (specs/shop.md "Reports"): income paid (by the payments' days), refunds (by theirs), net; unpaid —
 * done and not paid — and expected — booked ahead, not paid; the counts; the average ticket; the breakdowns; the days
 */
final case class Report(period: Period, income: Long, refunds: Long, paidCount: Int, unpaid: Long, unpaidCount: Int,
                        expected: Long, expectedCount: Int, orders: Int, bookings: Int, visits: Int, cancelled: Int,
                        noShows: Int, byService: Vector[Line], byMaster: Vector[Line], byMode: Vector[Line],
                        byMethod: Vector[Line], days: Vector[(String, Long)], entries: Vector[Entry]):
  def net: Long = income - refunds
  def average: Long = if paidCount == 0 then 0 else Math.round(income.toDouble / paidCount)

object Reports:
  /** the longest period a report covers */
  val longest = 366

  def epochDay(day: String): Long = day.split('-') match
    case Array(y, m, d) => Day.days(y.toInt, m.toInt, d.toInt)
    case _ => 0L
  def ofEpoch(n: Long): String =
    val (y, m, d) = Civil.ymd(n)
    f"$y%04d-$m%02d-$d%02d"
  def plus(day: String, n: Int): String = ofEpoch(epochDay(day) + n)
  def daysBetween(from: String, to: String): Vector[String] =
    (epochDay(from) to epochDay(to)).map(ofEpoch).toVector

  /** a period asked: both days, in order, at most `longest` days; or why not */
  def period(from: String, to: String): Either[String, Period] =
    if !Payments.isDay(from) || !Payments.isDay(to) || ofEpoch(epochDay(from)) != from ||
      ofEpoch(epochDay(to)) != to then Left("day")
    else if to < from || epochDay(to) - epochDay(from) + 1 > longest then Left("period")
    else Right(Period(from, to))

  /** the month of a day: its first and last */
  def month(day: String): Period =
    val (y, m) = (day.take(4).toInt, day.slice(5, 7).toInt)
    val next = if m == 12 then f"${y + 1}%04d-01-01" else f"$y%04d-${m + 1}%02d-01"
    Period(day.take(8) + "01", plus(next, -1))

  private val done = Set(Status.Ready, Status.ParcelOut, Status.Delivered)

  /** THE REPORT of `period`: the site's orders, bookings and payments (`effective`: the old paid orders counted) at
   * `now` */
  def of(site: Site, orders: Vector[Order], bookings: Vector[Booking], payments: Vector[Payment], period: Period,
         now: Long): Report =
    val paidOf = payments.filter(_.income).map(p => (p.of, p.ref) -> p).toMap
    def service(key: String) = site.offer(key).map(_.name(site.lang)).getOrElse(key)
    def price(key: String) = site.offer(key).map(_.price).getOrElse(0L)
    def masterName(key: String) = site.masters.find(_.key == key).map(_.name).getOrElse("")
    val orderBy = orders.map(o => o.ref -> o).toMap
    val bookingBy = bookings.map(b => b.ref -> b).toMap
    val entries = payments.filter(p => p.income && period.has(p.day)).map { p =>
      p.of match
        case Of.Order =>
          val o = orderBy.get(p.ref)
          val s = o.map(_.offerKey).getOrElse("")
          Entry(p.day, p.receipt, p.of, p.ref, o.fold(p.ref)(x => s"${if s.nonEmpty then service(s) else
            "Zlecenie"} · ${x.name}"), if s.nonEmpty then service(s) else "", "", "order", p.method, p.amount, p.state)
        case Of.Booking =>
          val b = bookingBy.get(p.ref)
          Entry(p.day, p.receipt, p.of, p.ref, b.fold(p.ref)(x => s"${service(x.service)} · ${x.name}"),
            b.fold("")(x => service(x.service)), b.fold("")(x => masterName(x.master)),
            b.flatMap(_.mode).fold("booking")(_.key), p.method, p.amount, p.state)
        case Of.Manual =>
          Entry(p.day, p.receipt, p.of, p.ref, p.description, if p.service.nonEmpty then service(p.service) else "",
            Some(masterName(p.master)).filter(_.nonEmpty).getOrElse(p.master), "other", p.method, p.amount, p.state)
    }.sortBy(e => (e.day, e.receipt, e.ref))
    val refunds = payments.filter(p => p.state == PayState.Refunded && period.has(p.refunded))
    def booked(b: Booking) = period.has(Day.of(b.start))
    def placed(o: Order) = period.has(Day.of(o.placed))
    val unpaidOrders = orders.filter(o => placed(o) && done(o.status) && !paidOf.contains((Of.Order, o.ref)))
    val pastBookings = bookings.filter(b => booked(b) && b.active && b.start < now)
    val unpaidBookings = pastBookings.filter(b => !paidOf.contains((Of.Booking, b.ref)))
    val ahead = bookings.filter(b => booked(b) && b.active && b.start >= now && !paidOf.contains((Of.Booking, b.ref)))
    def worth(b: Booking) = if b.price > 0 then b.price else price(b.service)
    def lines(f: Entry => (String, String)): Vector[Line] =
      entries.groupBy(f).toVector.map { case ((k, label), es) => Line(k, label, es.map(_.amount).sum, es.size) }
        .sortBy(l => (-l.amount, l.label))
    Report(period, entries.map(_.amount).sum, refunds.map(_.amount).sum, entries.size,
      unpaidOrders.map(o => if o.amount > 0 then o.amount else price(o.offerKey)).sum +
        unpaidBookings.map(worth).sum, unpaidOrders.size + unpaidBookings.size,
      ahead.map(worth).sum, ahead.size,
      orders.count(o => placed(o) && o.status != Status.Cancelled),
      bookings.count(b => booked(b) && b.active), pastBookings.size,
      orders.count(o => placed(o) && o.status == Status.Cancelled) +
        bookings.count(b => booked(b) && b.status == BookingStatus.Cancelled),
      bookings.count(b => booked(b) && b.status == BookingStatus.NoShow),
      lines(e => (e.service, if e.service.nonEmpty then e.service else "—")),
      lines(e => (e.master, if e.master.nonEmpty then e.master else "—")),
      lines(e => (e.mode, e.mode)), lines(e => (e.method.key, e.method.key)),
      period.days.map(d => d -> entries.filter(_.day == d).map(_.amount).sum), entries)

  // ---- EXPORTS

  /** an amount as an accountant's table writes it: `1234.50`, or `1234,50` with a decimal comma */
  def amount(grosze: Long, comma: Boolean): String =
    val sign = if grosze < 0 then "-" else ""
    val g = Math.abs(grosze)
    f"$sign${g / 100}${if comma then "," else "."}${g % 100}%02d"

  private def cell(s: String, sep: Char): String =
    if s.exists(c => c == sep || c == '"' || c == '\n' || c == '\r') then "\"" + s.replace("\"", "\"\"") + "\""
    else s

  /** THE REPORT'S CSV: UTF-8 with a BOM (Excel), a payment a row; `;` columns and decimal commas with `comma` */
  def csv(r: Report, comma: Boolean): String =
    val sep = if comma then ';' else ','
    val head = Vector("date", "receipt", "kind", "ref", "description", "service", "master", "mode", "method", "amount",
      "state")
    "﻿" + (head +: r.entries.map(e => Vector(e.day, e.receipt, e.of.key, e.ref, e.description, e.service,
      e.master, e.mode, e.method.key, amount(e.amount, comma), e.state.key))).map(_.map(cell(_, sep))
      .mkString(sep.toString)).mkString("", "\r\n", "\r\n")

  /** a month's "ewidencja przychodów" HELPER: Lp., the day, the document's number, the description, the amount — the
   * refunds as their own negative lines on their days */
  def ewidencja(r: Report, refunds: Vector[Payment], describe: Payment => String): Vector[(Int, String, String, String,
    Long)] =
    val rows = r.entries.map(e => (e.day, e.receipt, e.description, e.amount)) ++
      refunds.filter(p => r.period.has(p.refunded)).map(p => (p.refunded, p.receipt, "Zwrot: " + describe(p),
        -p.amount))
    rows.sortBy(x => (x._1, x._2)).zipWithIndex.map { case ((d, n, what, a), i) => (i + 1, d, n, what, a) }

  def ewidencjaCsv(rows: Vector[(Int, String, String, String, Long)], comma: Boolean): String =
    val sep = if comma then ';' else ','
    "﻿" + (Vector("Lp.", "Data uzyskania przychodu", "Nr dowodu", "Opis", "Kwota") +:
      rows.map((n, d, doc, what, a) => Vector(n.toString, d, doc, what, amount(a, comma))) :+
      Vector("", "", "", "Razem", amount(rows.map(_._5).sum, comma))).map(_.map(cell(_, sep)).mkString(sep.toString))
      .mkString("", "\r\n", "\r\n")
