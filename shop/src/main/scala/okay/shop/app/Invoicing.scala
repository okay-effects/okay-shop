package okay.shop.app

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response, text}
import okay.shop.effects.*
import okay.shop.html.{Conf, Html}
import okay.shop.invoice.*
import okay.shop.model.*

import java.util.Base64

/**
 * INVOICES (specs/shop.md "Invoices", "KSeF"): the buyer's data of an order, a booking or a quote — the client's
 * form's, the workshop's —, an invoice issued from a paid one (numbered in the store's step), corrected by the owner,
 * the list, the printable page, the accountant's CSV; KSeF in the object's alarm: a queued invoice sent, its status
 * asked until its KSeF number, its UPO kept, a failure tried again later
 */
object Invoicing:
  private def ok(j: Json): Response = Web.json(j)
  private def refused(why: String, status: Int = 400): Response = Web.refused(why, status)
  /** the workshop's link to the list, in its languages */
  val link = "Faktury · Рахунки-фактури · Invoices"

  // ---- THE CLIENT'S FORM: the buyer asked with an order, a booking or an inquiry

  /** the buyer of a form, when the shop takes it: refused by its field, none when not asked */
  def asked(c: Conf, f: Map[String, String]): Either[String, Option[Buyer]] =
    if !c.site.invoices then Right(None) else Buyer.asked(f)

  /** kept beside what was made (`kind`: `order`, `booking`, `quote`) */
  def keep[R[_]](kind: String, ref: String, b: Option[Buyer])(using In[Store, R]): Unit ! R =
    b.fold(unit[R])(x => Store.keepBuyer[R](kind, ref, Some(x)))

  /** a quote accepted: its buyer goes with the order or the booking it became */
  def carried[R[_]](quote: String, of: Of, ref: String)(using In[Store, R]): Unit ! R =
    Store.buyer[R]("quote", quote).flatMap(b => keep[R](of.key, ref, b))

  // ---- THE CLIENT'S PAGE: `/{lang}/invoice?ref=&k=` (and `&id=` a correction of it)

  def page[R[_]](c: Conf, r: Request)(using In[Store, R]): Response ! R =
    val ref = r.param("ref").getOrElse("").trim.toUpperCase
    val key = r.param("k").getOrElse("")
    val none = Web.page("Faktura nie została jeszcze wystawiona.", 404)
    // its order or booking, the key the second factor
    val mine: Option[Of] ! R = if ref.isEmpty then pure(None) else Store.find[R](ref).flatMap {
      case Some(o) => pure(Option.when(Api.same(o.key, key))(Of.Order))
      case None => Store.booking[R](ref).map(_.filter(b => Api.same(b.key, key)).map(_ => Of.Booking))
    }
    mine.flatMap {
      case None => pure(none)
      case Some(of) => Store.invoiceOf[R](of, ref).flatMap {
          case None => pure(none)
          case Some(original) =>
            val wanted = r.param("id").filter(original.corrections.contains).getOrElse(original.id)
            Store.invoice[R](wanted).map(_.fold(none) { i =>
              val base = s"/pl/invoice?ref=$ref&k=$key"
              val links = (if i.id != original.id then Vector(s"Faktura nr ${original.number}" -> base)
                else Vector.empty) ++ original.corrections.filter(_ != i.id).map(id => s"Korekta ($id)" ->
                  s"$base&id=$id")
              Web.page(InvoicePage.page(i, c.ksef, links))
            })
        }
    }

  // ---- THE WORKSHOP'S CALLS (signed in)

  /** what is made: an order or a booking, its service's key, what an invoice calls it, the day of the service */
  private def made[R[_]](c: Conf, of: Of, ref: String)(using In[Store, R]): Option[(String, String, String)] ! R =
    of match
      case Of.Order => Store.find[R](ref).map(_.map { o =>
          val name = c.site.offer(o.offerKey).map(_.name(Lang.pl)).getOrElse(
            if o.need.trim.nonEmpty then o.need.trim.take(120) else "Usługa")
          (o.offerKey, name, if o.paidAt > 0 then Day.of(o.paidAt) else "")
        })
      case Of.Booking => Store.booking[R](ref).map(_.map(b =>
          (b.service, c.site.offer(b.service).map(_.name(Lang.pl)).getOrElse(b.service), Day.of(b.start))))
      case Of.Manual => pure(None)

  private def summary(i: Invoice): Json.Obj = Json.obj("id" -> Json.str(i.id), "number" -> Json.str(i.number),
    "kind" -> Json.str(i.kind.key), "of" -> Json.str(i.of.key), "ref" -> Json.str(i.ref),
    "issued" -> Json.str(i.issued), "buyer" -> Json.str(i.buyer.name), "nip" -> Json.str(i.buyer.nip),
    "total" -> Json.num(if i.kind == InvoiceKind.Correction then i.difference.map(_.gross).sum else i.total),
    "ksef" -> Json.str(i.ksef.status), "ksefNumber" -> Json.str(i.ksef.number), "error" -> Json.str(i.ksef.error),
    "corrections" -> Json.Arr(i.corrections.map(Json.str)))

  /** the calls under `<desk>/api/`: none when the path is not an invoice's */
  def desk[R[_]](c: Conf, r: Request, path: List[String], email: String, now: Long)(using In[Store, R], In[Clock, R])
    : Option[Response ! R] =
    val f = Web.fields(r)
    def ofRef: Option[(Of, String)] = Of.parse(f.getOrElse("of", r.param("of").getOrElse(""))).filter(_ != Of.Manual)
      .map(_ -> f.getOrElse("ref", r.param("ref").getOrElse("")).trim)
    (r.method, path) match
      // the buyer's data and the invoice of an order or a booking
      case ("GET", List("buyer")) => Some(ofRef match
        case None => pure(refused("of"))
        case Some((of, ref)) => Store.buyer[R](of.key, ref).flatMap(b => Store.invoiceOf[R](of, ref).map(i =>
            ok(Json.obj("buyer" -> b.fold(Json.Null)(Buyer.toJson), "invoice" -> i.fold(Json.Null)(summary),
              "invoicing" -> Json.bool(c.seller.isDefined), "owner" -> Json.bool(c.owner(email)))))))
      case ("POST", List("buyer")) => Some(ofRef match
        case None => pure(refused("of"))
        case Some((of, ref)) => made[R](c, of, ref).flatMap {
            case None => pure(refused("no such " + of.key, 404))
            case Some(_) =>
              if f.get("clear").exists(_.nonEmpty) then Store.keepBuyer[R](of.key, ref, None).as(ok(Json.obj()))
              else Buyer.check(f.getOrElse("name", ""), f.getOrElse("nip", ""), f.getOrElse("street", ""),
                f.getOrElse("postcode", ""), f.getOrElse("city", "")) match
                case Left(why) => pure(refused(why))
                case Right(b) => Store.keepBuyer[R](of.key, ref, Some(b)).as(ok(Json.obj("buyer" -> Buyer.toJson(b))))
          })
      case ("POST", List("invoice", "issue")) => Some(ofRef match
        case None => pure(refused("of"))
        case Some((of, ref)) => issue[R](c, of, ref, email, now))
      case ("POST", List("invoice", "correct")) => Some(
        if !c.owner(email) then pure(refused("owner", 403)) else correct[R](c, r, email, now))
      case ("POST", List("invoice", "ksef")) => Some(
        if !c.owner(email) then pure(refused("owner", 403))
        else c.ksef match
          case None => pure(refused("ksef", 409))
          case Some(env) => Store.invoice[R](f.getOrElse("id", "")).flatMap {
              case None => pure(refused("no such invoice", 404))
              case Some(i) if i.ksef.status == "accepted" || i.ksef.status == "sent" => pure(refused("state", 409))
              case Some(i) => queue[R](i, env, now).map(x => ok(Json.obj("invoice" -> summary(x))))
            })
      case ("GET", List("invoices")) => Some(Store.invoices[R].map(is =>
          ok(Json.obj("invoices" -> Json.Arr(is.sortBy(_.id).reverse.map(summary))))))
      case ("GET", List("invoices.csv")) => Some(
        if !c.owner(email) then pure(refused("owner", 403))
        else Store.invoices[R].map(is => csvResponse(is, r.param("from").getOrElse(""), r.param("to").getOrElse(""),
          r.param("comma").contains("1"))))
      case _ => None

  /** an invoice queued for KSeF: the alarm sends it at once */
  private def queue[R[_]](i: Invoice, env: KsefEnv, now: Long)(using In[Store, R]): Invoice ! R =
    Store.ksefMarked[R](i.id, i.ksef.copy(status = "queued", env = env.key, error = "", next = now, at = now))
      .map(_.getOrElse(i))

  /** `POST <desk>/api/invoice/issue {of, ref}`: from its payment, its buyer and the seller; numbered in the store's
   * step; queued for KSeF when the shop is connected */
  def issue[R[_]](c: Conf, of: Of, ref: String, email: String, now: Long)(using In[Store, R]): Response ! R =
    made[R](c, of, ref).flatMap {
      case None => pure(refused("no such " + of.key, 404))
      case Some((service, what, served)) =>
        for
          buyer <- Store.buyer[R](of.key, ref)
          record <- Store.payment[R](of, ref)
          pay <- if record.isDefined || of != Of.Order then pure(record)
            else Store.find[R](ref).map(_.flatMap(o => Payments.ofOrder(o, None, c.site.refPrefix)))
          res <- InvoiceMath.draft(c.seller, buyer, of, ref, service, what, served, pay, Day.of(now), email,
            now) match
            case Left(why) => pure(refused(why, 409))
            case Right(d) => Store.issue[R](d, d.seller.pattern).flatMap {
                case Left(why) => pure(refused(why, 409))
                case Right(i) => c.ksef.fold(pure(i))(env => queue[R](i, env, now))
                    .map(x => ok(Json.obj("invoice" -> summary(x))))
              }
        yield res
    }

  /** `POST <desk>/api/invoice/correct {id, reason, lines:[{name, quantity, gross (zł), rate}]}`: the owner's */
  private def correct[R[_]](c: Conf, r: Request, email: String, now: Long)(using In[Store, R]): Response ! R =
    val j = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }.getOrElse(Json.Obj(Vector.empty))
    val lines = J.arr(j, "lines").map(l => Item(J.s(l, "name").replaceAll("\\s+", " ").trim,
      scala.util.Try(l("quantity").int).getOrElse(J.s(l, "quantity").toIntOption.getOrElse(0)),
      Money.parse(J.s(l, "gross")).getOrElse(-1L), Rate.parse(J.s(l, "rate")).getOrElse(Rate.R23)))
    Store.invoice[R](J.s(j, "id")).flatMap {
      case None => pure(refused("no such invoice", 404))
      case Some(original) =>
        Store.invoices[R].flatMap { all =>
          val before = InvoiceMath.current(original, all)
          // an exempt seller's lines stay exempt
          val fixed = if original.seller.exempt then lines.map(_.copy(rate = Rate.Zw)) else lines
          InvoiceMath.correction(original, before, fixed, J.s(j, "reason"), Day.of(now), email, now) match
            case Left(why) => pure(refused(why))
            case Right(d) => Store.issue[R](d, original.seller.corrections).flatMap {
                case Left(why) => pure(refused(why, 409))
                case Right(i) => c.ksef.filter(_ => original.ksef.status != "none").fold(pure(i))(env =>
                    queue[R](i, env, now)).map(x => ok(Json.obj("invoice" -> summary(x))))
              }
        }
    }

  // ---- THE PAGES (`<desk>/invoices/…`, signed in)

  def pages[R[_]](c: Conf, r: Request, rest: List[String])(using In[Store, R], In[Clock, R]): Response ! R =
    Workshop.person[R](c, r).flatMap {
      case None => pure(Response.redirect(s"${c.desk.path}/", 303).header("Cache-Control", "no-store"))
      case Some(email) =>
        val id = r.param("id").getOrElse("")
        rest match
          case Nil => Store.invoices[R].map(is => Web.page(InvoiceList.page(c, is, c.owner(email))))
          case List("view") => Store.invoice[R](id).flatMap {
              case None => pure(Response.notFound())
              case Some(i) => Store.invoices[R].map { all =>
                  val links = (i.corrects.map(o => s"Faktura korygowana nr ${o.number}" -> s"view?id=${o.id}") ++
                    all.filter(x => i.corrections.contains(x.id)).map(x => s"Korekta nr ${x.number}" ->
                      s"view?id=${x.id}")).toVector :+ ("Wszystkie faktury" -> "./")
                  Web.page(InvoicePage.page(i, c.ksef, links))
                }
            }
          case List("xml") => Store.invoice[R](id).map(_.fold(Response.notFound())(i =>
              Response(200, Map("Content-Type" -> "application/xml; charset=utf-8", "Cache-Control" -> "no-store",
                "Content-Disposition" -> s"""attachment; filename="${file(i.number)}.xml""""), xml(i))))
          case List("upo") => Store.upo[R](id).map(_.fold(Response.notFound())(x =>
              Response(200, Map("Content-Type" -> "application/xml; charset=utf-8", "Cache-Control" -> "no-store",
                "Content-Disposition" -> s"""attachment; filename="UPO-$id.xml""""), x)))
          case _ => pure(Response.notFound())
    }

  private def file(number: String) = number.map(ch => if ch.isLetterOrDigit then ch else '_')

  /** the FA(3) XML of an invoice: made at the instant it was issued — the same bytes whenever asked */
  def xml(i: Invoice): String = Fa3.xml(i, Civil.iso(i.at))

  // ---- THE ACCOUNTANT'S TABLE

  /** CSV (UTF-8 with a BOM) of the invoices issued in `from`–`to` (days, both included; empty: all), one row an
   * invoice — its number, days, buyer, kind, KSeF number (or `BFK`: outside KSeF), net and VAT by rate as JPK_V7's
   * K_ columns, gross, KSeF's status; a correction's amounts its difference */
  def csv(is: Vector[Invoice], from: String, to: String, comma: Boolean): String =
    val sep = if comma then ";" else ","
    def money(g: Long) = { val t = InvoiceMath.dot(g); if comma then t.replace('.', ',') else t }
    def q(s: String) = if s.contains(sep) || s.exists(c => c == '"' || c == '\n') then
      "\"" + s.replace("\"", "\"\"") + "\"" else s
    val head = Vector("Lp", "Numer", "Rodzaj", "Data wystawienia", "Data sprzedazy", "NIP nabywcy", "Nabywca",
      "Numer KSeF", "Netto 23% (K_19)", "VAT 23% (K_20)", "Netto 8% (K_17)", "VAT 8% (K_18)", "Netto 5% (K_15)",
      "VAT 5% (K_16)", "0% (K_13)", "Zwolnione (K_10)", "Poza kraj (K_11)", "Brutto", "Status KSeF",
      "Faktura korygowana")
    val rows = is.filter(i => (from.isEmpty || i.issued >= from) && (to.isEmpty || i.issued <= to)).sortBy(_.id)
      .zipWithIndex.map { (i, n) =>
        val ss = if i.kind == InvoiceKind.Correction then i.difference else i.sums
        def of(r: Rate) = ss.find(_.rate == r)
        def net(r: Rate) = of(r).fold("")(s => money(s.net))
        def vat(r: Rate) = of(r).fold("")(s => money(s.vat))
        Vector((n + 1).toString, i.number, if i.kind == InvoiceKind.Correction then "KOR" else "VAT", i.issued,
          if i.sold == i.issued then "" else i.sold, i.buyer.nip, i.buyer.name,
          if i.ksef.number.nonEmpty then i.ksef.number else if i.ksef.status == "none" then "BFK" else "",
          net(Rate.R23), vat(Rate.R23), net(Rate.R8), vat(Rate.R8), net(Rate.R5), vat(Rate.R5), net(Rate.R0),
          net(Rate.Zw), net(Rate.Np), money(ss.map(_.gross).sum), i.ksef.status, i.corrects.fold("")(_.number))
      }
    "﻿" + (head +: rows).map(_.map(q).mkString(sep)).mkString("\r\n") + "\r\n"

  private def csvResponse(is: Vector[Invoice], from: String, to: String, comma: Boolean): Response =
    Response(200, Map("Content-Type" -> "text/csv; charset=utf-8", "Cache-Control" -> "no-store",
      "Content-Disposition" -> s"""attachment; filename="faktury${if from.nonEmpty then "-" + from else ""}${
        if to.nonEmpty then "-" + to else ""}.csv""""), csv(is, from, to, comma))

  // ---- KSeF, IN THE OBJECT'S ALARM

  private val second = 1000L
  private val minute = 60 * second
  /** the wait before the next try: 1, 2, 4 … minutes, at most 6 hours */
  def backoff(attempts: Int): Long = (minute << attempts.min(9).max(0)).min(360 * minute)

  /** every invoice whose KSeF try is due: sent, asked about, its UPO fetched — each step's outcome marked in the
   * store, which sets the next alarm. The number of invoices handled */
  def ksef[R[_]](c: Conf)(using In[Store, R], In[Ksef, R], In[Clock, R]): Int ! R =
    Clock.now[R].flatMap(now => Store.invoices[R].flatMap { all =>
      val due = all.filter(i => i.ksef.next > 0 && i.ksef.next <= now)
      due.foldLeft(pure[Unit, R](()))((p, i) => p.flatMap(_ => step[R](i, now))).as(due.size)
    })

  private def mark[R[_]](i: Invoice, k: KsefState)(using In[Store, R]): Unit ! R =
    Store.ksefMarked[R](i.id, k).as(())

  private def step[R[_]](i: Invoice, now: Long)(using In[Store, R], In[Ksef, R]): Unit ! R =
    val k = i.ksef.copy(at = now)
    def failed(why: String) = k.copy(error = why.take(500), attempts = k.attempts + 1,
      next = now + backoff(k.attempts))
    k.status match
      case "queued" =>
        val x = xml(i)
        val hash = Base64.getUrlEncoder.withoutPadding.encodeToString(Sha256.hash(x.getBytes("UTF-8")))
        Ksef.send[R](x).flatMap {
          case Left(why) => mark[R](i, failed(why))
          case Right(sent) => mark[R](i, k.copy(status = "sent", session = sent.session, reference = sent.reference,
            error = "", attempts = 0, next = now + 3 * second, hash = hash))
        }
      case "sent" => Ksef.status[R](k.session, k.reference).flatMap {
          case Left(why) => mark[R](i, failed(why))
          case Right(st) if st.done => Ksef.upo[R](k.session, st.number).flatMap {
              case Right(upo) => Store.keepUpo[R](i.id, upo).flatMap(_ => mark[R](i, k.copy(status = "accepted",
                number = st.number, error = "", attempts = 0, next = 0, upo = true)))
              case Left(why) => mark[R](i, k.copy(status = "accepted", number = st.number, error = why.take(500),
                attempts = 1, next = now + backoff(0)))
            }
          case Right(st) if st.waiting => mark[R](i, k.copy(attempts = k.attempts + 1,
            next = now + (if k.attempts < 5 then 3 * second else backoff(k.attempts - 5))))
          case Right(st) => mark[R](i, k.copy(status = "rejected", error = s"${st.code} ${st.description}".take(500),
            next = 0))
        }
      // accepted, its UPO not yet kept
      case "accepted" if !k.upo => Ksef.upo[R](k.session, k.number).flatMap {
          case Right(upo) => Store.keepUpo[R](i.id, upo).flatMap(_ => mark[R](i, k.copy(error = "", next = 0,
            upo = true)))
          case Left(why) => mark[R](i, failed(why))
        }
      case _ => mark[R](i, k.copy(next = 0))

/** THE LIST OF INVOICES (`<desk>/invoices/`): number, kind, day, buyer, total, KSeF's status; the owner's actions —
 * send to KSeF, correct — and the CSV; a staff member reads the list */
object InvoiceList:
  private def e(s: String) = Html.escape(s)
  private def status(s: String) = s match
    case "none" => "poza KSeF"
    case "queued" => "w kolejce do KSeF"
    case "sent" => "wysłana — czeka na numer KSeF"
    case "accepted" => "przyjęta w KSeF"
    case "rejected" => "ODRZUCONA przez KSeF"
    case x => x

  def page(c: Conf, is: Vector[Invoice], owner: Boolean): String =
    val rows = is.sortBy(_.id).reverse.map { i =>
      val total = if i.kind == InvoiceKind.Correction then i.difference.map(_.gross).sum else i.total
      val actions = if !owner then "" else
        (if c.ksef.isDefined && (i.ksef.status == "none" || i.ksef.status == "rejected" ||
          i.ksef.status == "queued") then s"""<button data-ksef="${e(i.id)}">Wyślij do KSeF</button> """ else "") +
          (if i.kind == InvoiceKind.Vat then s"""<button data-correct="${e(i.id)}">Korekta</button>""" else "")
      s"""<tr><td><a href="view?id=${e(i.id)}">${e(i.number)}</a></td><td>${if i.kind == InvoiceKind.Correction
        then "korekta" else "faktura"}</td><td>${e(i.issued)}</td><td>${e(i.buyer.name)}<br><small>NIP ${
        e(i.buyer.nip)}</small></td><td class=r>${InvoiceMath.zl(total)}</td><td>${e(status(i.ksef.status))}${
        if i.ksef.number.nonEmpty then s"<br><small>${e(i.ksef.number)}</small>" else ""}${
        if i.ksef.error.nonEmpty then s"<br><small class=err>${e(i.ksef.error)}</small>" else ""}${
        if i.ksef.upo then s"""<br><a href="upo?id=${e(i.id)}">UPO</a>""" else ""} · <a href="xml?id=${e(i.id)}"
        >XML</a></td><td>$actions</td></tr>"""
    }.mkString
    s"""<!doctype html><html lang=pl><head><meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Faktury · ${e(c.site.name)}</title><style>body{font:14px/1.5 -apple-system,sans-serif;max-width:1100px;
margin:24px auto;padding:0 16px;color:#111}table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid
#ddd;padding:8px 6px;text-align:left;vertical-align:top}td.r{text-align:right;white-space:nowrap}small{color:#666}
.err{color:#a00}button{font:inherit;padding:4px 10px;cursor:pointer}.bar{display:flex;gap:12px;flex-wrap:wrap;
align-items:center;margin:12px 0}@media(max-width:640px){table,tbody,tr,td{display:block}th{display:none}
tr{border-bottom:2px solid #ccc;padding:6px 0}td{border:0;padding:2px 0}}</style></head><body>
<p><a href="${e(c.desk.path)}/">← ${e(c.site.name)}</a></p>
<h1>Faktury</h1>
${if c.seller.isEmpty then "<p>Uzupełnij najpierw dane sprzedawcy (Dane do faktur).</p>" else ""}
${c.ksef.fold("<p><small>KSeF: nie połączono.</small></p>")(env => s"<p><small>KSeF: ${e(env.key)}${
      if env != KsefEnv.Production then " (środowisko testowe — bez skutków prawnych)" else ""}</small></p>")}
${if owner then s"""<form class=bar action="${e(c.desk.path)}/api/invoices.csv" method=get>
<label>Od <input type=date name=from></label><label>Do <input type=date name=to></label>
<label><input type=checkbox name=comma value=1 checked> Excel (przecinki)</label>
<button>Pobierz CSV dla księgowej</button></form>""" else ""}
<table><tr><th>Numer</th><th>Rodzaj</th><th>Data</th><th>Nabywca</th><th>Kwota</th><th>KSeF</th><th></th></tr>
${if rows.isEmpty then "<tr><td colspan=7>Brak faktur. Faktura powstaje z opłaconego zamówienia lub wizyty " +
      "(przycisk w warsztacie).</td></tr>" else rows}</table>
<script>(function(){var api=${Json.print(Json.str(c.desk.path + "/api/"))};
function post(p,b){return fetch(api+p,{method:'POST',headers:{'content-type':'application/json'},
body:JSON.stringify(b)}).then(function(r){return r.json().then(function(j){if(!r.ok)alert('Błąd: '+(j.error||r.status));
else location.reload();});});}
document.querySelectorAll('[data-ksef]').forEach(function(b){b.onclick=function(){
post('invoice/ksef',{id:b.getAttribute('data-ksef')});};});
document.querySelectorAll('[data-correct]').forEach(function(b){b.onclick=function(){
var reason=prompt('Przyczyna korekty');if(!reason)return;var name=prompt('Nazwa usługi po korekcie');if(!name)return;
var gross=prompt('Wartość brutto po korekcie (zł)');if(gross===null)return;
var rate=prompt('Stawka VAT (23, 8, 5, 0, zw, np)','23');if(!rate)return;
post('invoice/correct',{id:b.getAttribute('data-correct'),reason:reason,lines:[{name:name,quantity:1,
gross:gross,rate:rate}]});};});})();</script>
</body></html>
"""
