package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.*
import okay.shop.handlers.Codec
import okay.shop.html.{Conf, ReportsPage}
import okay.shop.model.*

/**
 * THE SHOP'S MONEY (specs/shop.md "Money", "Reports"): a payment marked by the workshop, manual income, a no-show;
 * the owner's report, its CSV, its pages — a program of the shop's effects, in its object
 */
object Accounts:
  private def ok(j: Json): Response = Web.json(j)
  private def refused(why: String, status: Int = 400): Response = Web.refused(why, status)

  /** everything the shop has made, and its payments as they count */
  private def all[R[_]](c: Conf)(using In[Store, R]): (Vector[Order], Vector[Booking], Vector[Payment]) ! R =
    Store.orders[R](100000).flatMap(os => Store.bookings[R](0L, Long.MaxValue).flatMap(bs =>
      Store.payments[R].map(ps => (os, bs, Payments.effective(os, ps, c.site.refPrefix)))))

  /** a payment as the workshop reads it: its fields, its amount in zł, the log */
  def json(p: Payment): Json.Obj = Json.Obj(Codec.payment(p).fields :+ ("zl" -> Json.str(Money.zl(p.amount))))

  // ---- THE WORKSHOP'S CALLS (signed in)

  /** `GET <desk>/api/payment?of=&ref=`: the payment as it counts (none: unpaid), and whether the person is the owner */
  def payment[R[_]](c: Conf, r: Request, email: String)(using In[Store, R]): Response ! R =
    val ref = r.param("ref").getOrElse("").trim
    Of.parse(r.param("of").getOrElse("")) match
      case None => pure(refused("of"))
      case Some(of) => current[R](c, of, ref).map(p => ok(Json.obj("payment" -> p.fold(Json.Null)(json),
        "owner" -> Json.bool(c.owner(email)))))

  /** the payment of `ref` as it counts: its record, or an old paid order's */
  private def current[R[_]](c: Conf, of: Of, ref: String)(using In[Store, R]): Option[Payment] ! R =
    Store.payment[R](of, ref).flatMap {
      case Some(p) => pure(Some(p))
      case None if of == Of.Order => Store.find[R](ref).map(_.flatMap(o => Payments.ofOrder(o, None,
        c.site.refPrefix)))
      case None => pure(None)
    }

  /** what is asked, read: the amount in zł, the method; the day paid today when not said */
  private def mark(f: Map[String, String], of: Of, ref: String, action: String, today: String)
    : Either[String, Mark] =
    val amount = if action == "paid" then Money.parse(f.getOrElse("amount", "")).toRight("amount") else Right(0L)
    val method = if action == "paid" then Method.parse(f.getOrElse("method", "")).toRight("method")
      else Right(Method.Other)
    for a <- amount; m <- method
    yield Mark(of, ref, action, a, m, f.getOrElse("day", "").trim match
      case "" if action == "paid" => today
      case d => d
    , f.getOrElse("note", "").replaceAll("\\s+", " ")
      .trim, f.getOrElse("description", "").replaceAll("\\s+", " ").trim, f.getOrElse("service", ""),
      f.getOrElse("master", ""))

  /** `POST <desk>/api/pay {of, ref, action, amount, method, day, note}`: by the rules, in the store's step */
  def pay[R[_]](c: Conf, f: Map[String, String], email: String, now: Long)(using In[Store, R]): Response ! R =
    val ref = f.getOrElse("ref", "").trim
    Of.parse(f.getOrElse("of", "")) match
      case None => pure(refused("of"))
      case Some(of) =>
        // what it is of: there, and whether cancelled
        val target: Option[Boolean] ! R = of match
          case Of.Order => Store.find[R](ref).map(_.map(_.status == Status.Cancelled))
          case Of.Booking => Store.booking[R](ref).map(_.map(_.status == BookingStatus.Cancelled))
          case Of.Manual => Store.payment[R](of, ref).map(_.map(_ => false))
        target.flatMap {
          case None => pure(refused("no such " + of.key, 404))
          case Some(cancelled) => mark(f, of, ref, f.getOrElse("action", ""), Day.of(now)) match
            case Left(why) => pure(refused(why))
            case Right(m) => current[R](c, of, ref).flatMap(start => Store.pay[R](m, start, email, c.owner(email),
                cancelled, Day.of(now), now)).map {
                case Left("owner") => refused("owner", 403)
                case Left(why) => refused(why, if why == "state" then 409 else 400)
                case Right(p) => ok(Json.obj("payment" -> json(p)))
              }
        }

  /** `POST <desk>/api/income {amount, day, method, description, service, master, note}`: the owner's manual income */
  def income[R[_]](c: Conf, f: Map[String, String], email: String, now: Long)(using In[Store, R], In[Ids, R])
    : Response ! R =
    if !c.owner(email) then pure(refused("owner", 403))
    else Ids.ref[R](c.site.refPrefix + "M").flatMap(ref => mark(f, Of.Manual, ref, "paid", Day.of(now)) match
      case Left(why) => pure(refused(why))
      case Right(m) => Store.pay[R](m, None, email, owner = true, cancelled = false, Day.of(now), now).map {
          case Left(why) => refused(why)
          case Right(p) => ok(Json.obj("payment" -> json(p)))
        })

  /** `GET <desk>/api/incomes`: the manual incomes still counted, newest first */
  def incomes[R[_]](c: Conf, email: String)(using In[Store, R]): Response ! R =
    if !c.owner(email) then pure(refused("owner", 403))
    else Store.payments[R].map(ps => ok(Json.obj("incomes" -> Json.Arr(ps.filter(p => p.of == Of.Manual &&
      p.income).sortBy(p => (p.day, p.at)).reverse.map(json)))))

  /** `POST <desk>/api/booking/noshow {ref}`: a past booking the client did not come to */
  def noShow[R[_]](f: Map[String, String], now: Long)(using In[Store, R]): Response ! R =
    Store.noShow[R](f.getOrElse("ref", "").trim, now).map {
      case None => refused("no such booking", 404)
      case Some(b) => ok(Json.obj("booking" -> Codec.booking(b)))
    }

  // ---- THE REPORT (the owner's)

  private def period(r: Request, now: Long): Either[String, Period] =
    val today = Day.of(now)
    // by default this month, whole: what was paid and what is booked ahead
    Reports.period(r.param("from").getOrElse(Reports.month(today).from), r.param("to").getOrElse(
      Reports.month(today).to))

  /** the report of a period and of the one before it */
  def reports[R[_]](c: Conf, p: Period, now: Long)(using In[Store, R]): (Report, Report, Vector[Payment]) ! R =
    all[R](c).map((os, bs, ps) => (Reports.of(c.site, os, bs, ps, p, now), Reports.of(c.site, os, bs, ps, p.previous,
      now), ps))

  private def lines(ls: Vector[Line]): Json = Json.Arr(ls.map(l => Json.obj("key" -> Json.str(l.key),
    "label" -> Json.str(l.label), "amount" -> Json.num(l.amount), "count" -> Json.num(l.count))))

  def totals(r: Report): Json.Obj = Json.obj("from" -> Json.str(r.period.from), "to" -> Json.str(r.period.to),
    "income" -> Json.num(r.income), "refunds" -> Json.num(r.refunds), "net" -> Json.num(r.net),
    "paid" -> Json.num(r.paidCount), "average" -> Json.num(r.average), "unpaid" -> Json.num(r.unpaid),
    "unpaidCount" -> Json.num(r.unpaidCount), "expected" -> Json.num(r.expected),
    "expectedCount" -> Json.num(r.expectedCount), "orders" -> Json.num(r.orders), "bookings" -> Json.num(r.bookings),
    "visits" -> Json.num(r.visits), "cancelled" -> Json.num(r.cancelled), "noShows" -> Json.num(r.noShows))

  def reportJson(r: Report, before: Report): Json.Obj = Json.Obj(totals(r).fields ++ Vector(
    "byService" -> lines(r.byService), "byMaster" -> lines(r.byMaster), "byMode" -> lines(r.byMode),
    "byMethod" -> lines(r.byMethod),
    "days" -> Json.Arr(r.days.map((d, a) => Json.obj("day" -> Json.str(d), "amount" -> Json.num(a)))),
    "entries" -> Json.Arr(r.entries.map(e => Json.obj("day" -> Json.str(e.day), "receipt" -> Json.str(e.receipt),
      "of" -> Json.str(e.of.key), "ref" -> Json.str(e.ref), "description" -> Json.str(e.description),
      "service" -> Json.str(e.service), "master" -> Json.str(e.master), "mode" -> Json.str(e.mode),
      "method" -> Json.str(e.method.key), "amount" -> Json.num(e.amount), "state" -> Json.str(e.state.key)))),
    "previous" -> totals(before)))

  /** `GET <desk>/api/report?from=&to=` and `report.csv?…&comma=1` */
  def report[R[_]](c: Conf, r: Request, email: String, now: Long, csv: Boolean)(using In[Store, R]): Response ! R =
    if !c.owner(email) then pure(refused("owner", 403))
    else period(r, now) match
      case Left(why) => pure(refused(why))
      case Right(p) => reports[R](c, p, now).map { (now1, before, _) =>
          if !csv then ok(reportJson(now1, before))
          else Response(200, Map("Content-Type" -> "text/csv; charset=utf-8", "Cache-Control" -> "no-store",
            "Content-Disposition" -> s"""attachment; filename="report-${p.from}-${p.to}.csv""""),
            Reports.csv(now1, r.param("comma").contains("1")))
        }

  // ---- THE PAGES (`<desk>/reports/…`, the owner's)

  def pages[R[_]](c: Conf, r: Request, rest: List[String])(using In[Store, R], In[Clock, R]): Response ! R =
    val l = r.param("lang").flatMap(Lang.parse).orElse(Web.cookie(r, "lang").flatMap(Lang.parse)).getOrElse(Lang.pl)
    Clock.now[R].flatMap(now => Workshop.person[R](c, r).flatMap {
      case None => pure(Response.redirect(s"${c.desk.path}/", 303).header("Cache-Control", "no-store"))
      case Some(e) if !c.owner(e) => pure(Web.page(ReportsPage.notOwner(l, c.desk.path), 403))
      case Some(_) => rest match
        case Nil => period(r, now) match
          case Left(why) => pure(refused(why))
          case Right(p) => reports[R](c, p, now).map((a, b, _) => Web.page(ReportsPage.page(l, c, Day.of(now), a, b)))
        case List("print") => period(r, now) match
          case Left(why) => pure(refused(why))
          case Right(p) => reports[R](c, p, now).map((a, b, _) => Web.page(ReportsPage.print(l, c, a, b)))
        case List("ewidencja") =>
          val month = Reports.month(r.param("month").filter(m => Payments.isDay(m + "-01")).map(_ + "-01")
            .getOrElse(Day.of(now)))
          reports[R](c, month, now).map { (a, _, ps) =>
            val rows = Reports.ewidencja(a, ps.filter(_.state == PayState.Refunded), p =>
              if p.description.nonEmpty then p.description else p.ref)
            if r.param("format").contains("csv") then Response(200, Map("Content-Type" -> "text/csv; charset=utf-8",
              "Cache-Control" -> "no-store", "Content-Disposition" ->
                s"""attachment; filename="ewidencja-${month.from.take(7)}.csv""""),
              Reports.ewidencjaCsv(rows, r.param("comma").forall(_ == "1")))
            else Web.page(ReportsPage.ewidencja(c, month, rows))
          }
        case _ => pure(Response.notFound())
    })
