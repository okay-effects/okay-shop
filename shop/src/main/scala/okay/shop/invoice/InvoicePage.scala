package okay.shop.invoice

import okay.shop.html.Html
import okay.shop.model.{Day, Method}

/**
 * AN INVOICE AS A PRINTABLE PAGE (specs/shop.md "Invoices"), in Polish: "Faktura" — an exempt seller's with no VAT
 * columns and the legal basis of its exemption — or "Faktura korygująca" with the corrected invoice, the reason, the
 * lines before and after and the difference; the KSeF number and its QR code (KOD I: the verification link) once KSeF
 * accepted it. The browser's print makes the PDF. Every value escaped
 */
object InvoicePage:
  private def e(s: String) = Html.escape(s)
  private def zl(g: Long) = InvoiceMath.zl(g)

  /** KOD I: `<qr base>/invoice/<seller NIP>/<issue day DD-MM-YYYY>/<SHA-256 of the XML, base64url>` */
  def verification(i: Invoice, env: KsefEnv): Option[String] =
    Option.when(i.ksef.hash.nonEmpty)(s"${env.qr}/invoice/${i.seller.nip}/${i.issued.drop(8).take(2)}-" +
      s"${i.issued.slice(5, 7)}-${i.issued.take(4)}/${i.ksef.hash}")

  private def method(m: Method): String = m.label(okay.shop.model.Lang.pl).toLowerCase

  private val style = """body{font:13px/1.5 -apple-system,"Segoe UI",sans-serif;max-width:820px;margin:28px auto;
padding:0 18px;color:#111}h1{font-size:20px;margin:0 0 4px}.meta{color:#555}.two{display:grid;gap:18px;
grid-template-columns:1fr 1fr;margin:18px 0}.two h3{font-size:12px;text-transform:uppercase;letter-spacing:.06em;
color:#555;margin:0 0 4px}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #bbb;
padding:6px 8px;text-align:left;vertical-align:top}th{background:#f3f3f3;font-weight:600}td.r,th.r{text-align:right}
.total{font-size:16px;font-weight:700}.note{color:#444;font-size:12px}.test{background:#fff3cd;border:1px solid #e0c060;
padding:6px 10px;border-radius:6px}.qr{display:flex;gap:14px;align-items:center;margin-top:14px}
.qr div{width:120px;height:120px}button{font:inherit;padding:8px 14px;cursor:pointer}
@media print{button,.noprint{display:none}body{margin:0}}@media(max-width:560px){.two{grid-template-columns:1fr}}"""

  private def table(lines: Vector[Item], vat: Boolean): String =
    val head = if vat then "<tr><th>Lp.</th><th>Nazwa usługi</th><th>J.m.</th><th class=r>Ilość</th>" +
      "<th class=r>Cena jedn. brutto</th><th class=r>Stawka VAT</th><th class=r>Wartość brutto</th></tr>"
      else "<tr><th>Lp.</th><th>Nazwa usługi</th><th>J.m.</th><th class=r>Ilość</th><th class=r>Cena jedn.</th>" +
        "<th class=r>Wartość</th></tr>"
    "<table>" + head + lines.zipWithIndex.map((l, n) => s"<tr><td>${n + 1}</td><td>${e(l.name)}</td>" +
      s"<td>${e(l.unit)}</td><td class=r>${l.quantity}</td><td class=r>${zl(l.unitGross)}</td>" +
      (if vat then s"<td class=r>${e(l.rate.label)}</td>" else "") + s"<td class=r>${zl(l.gross)}</td></tr>")
      .mkString + "</table>"

  private def sums(ss: Vector[Sum]): String =
    "<table><tr><th>Stawka</th><th class=r>Netto</th><th class=r>VAT</th><th class=r>Brutto</th></tr>" +
      ss.map(s => s"<tr><td>${e(s.rate.label)}</td><td class=r>${zl(s.net)}</td><td class=r>${zl(s.vat)}</td>" +
        s"<td class=r>${zl(s.gross)}</td></tr>").mkString +
      s"<tr><th>Razem</th><th class=r>${zl(ss.map(_.net).sum)}</th><th class=r>${zl(ss.map(_.vat).sum)}</th>" +
      s"<th class=r>${zl(ss.map(_.gross).sum)}</th></tr></table>"

  /** the page; `env` KSeF's environment when connected (the QR's base); `links` more of the invoice's own (its
   * corrections, the original) as text and address */
  def page(i: Invoice, env: Option[KsefEnv], links: Vector[(String, String)] = Vector.empty): String =
    val s = i.seller
    val kor = i.kind == InvoiceKind.Correction
    val vat = !s.exempt
    val title = if kor then s"Faktura korygująca nr ${i.number}" else s"Faktura nr ${i.number}"
    val accepted = i.ksef.status == "accepted" && i.ksef.number.nonEmpty
    val qr = for x <- env if accepted; link <- verification(i, x) yield (link, i.ksef.number)
    val testEnv = env.exists(_ != KsefEnv.Production) && i.ksef.status != "none"
    val body =
      s"""<h1>${e(title)}</h1>
<p class=meta>Data wystawienia: ${e(i.issued)} · miejsce wystawienia: ${e(s.city)}${if !kor then
        s" · data wykonania usługi: ${e(i.sold)}" else ""}</p>
${if testEnv then s"""<p class=test>Dokument wysłany do ŚRODOWISKA TESTOWEGO KSeF (${e(env.get.key)}): bez skutków
prawnych.</p>""" else ""}
${i.corrects.fold("")(c => s"""<p><b>Korekta faktury nr ${e(c.number)}</b> z dnia ${e(c.issued)}${
        if c.ksef.nonEmpty then s" · numer KSeF ${e(c.ksef)}" else ""}<br>Przyczyna korekty: ${e(i.reason)}</p>""")}
<div class=two><div><h3>Sprzedawca</h3><b>${e(s.name)}</b><br>${e(s.street)}<br>${e(s.postcode)} ${e(s.city)}<br>
NIP: ${e(s.nip)}${if s.bank.nonEmpty then s"<br>Rachunek: ${e(s.bank)}" else ""}</div>
<div><h3>Nabywca</h3><b>${e(i.buyer.name)}</b><br>${e(i.buyer.street)}<br>${e(i.buyer.postcode)} ${e(i.buyer.city)}
<br>NIP: ${e(i.buyer.nip)}</div></div>
${if kor then s"<h3>Przed korektą</h3>${table(i.before, vat)}<h3>Po korekcie</h3>${table(i.lines, vat)}" +
        (if vat then s"<h3>Różnica</h3>${sums(i.difference)}" else "") +
        s"<p class=total>Różnica do zapłaty / zwrotu: ${zl(i.difference.map(_.gross).sum)} zł</p>"
      else table(i.lines, vat) + (if vat then sums(i.sums) else "") +
        s"<p class=total>Razem do zapłaty: ${zl(i.total)} zł</p>"}
${if !vat then s"<p>Sprzedawca zwolniony z VAT — podstawa prawna zwolnienia: ${e(s.exemption)}.</p>" else ""}
${if !kor then s"<p>Zapłacono: ${e(i.paidDay)} · ${e(method(i.method))} · <b>zapłacono w całości</b></p>" else ""}
${if accepted then s"<p>Numer KSeF: <b>${e(i.ksef.number)}</b></p>" else ""}
${qr.fold("")((link, label) => s"""<div class=qr><div id=qr data-link="${e(link)}"></div><div class=note>Zweryfikuj
fakturę w KSeF:<br><a href="${e(link)}">${e(link)}</a><br>${e(label)}</div></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js"></script>
<script>(function(){var d=document.getElementById('qr'),q=qrcode(0,'M');q.addData(d.getAttribute('data-link'));
q.make();d.innerHTML=q.createSvgTag({cellSize:3,margin:0,scalable:true});})();</script>""")}
${links.map((t, h) => s"""<p class=noprint><a href="${e(h)}">${e(t)}</a></p>""").mkString}
<p class=note>Wystawiono ${e(Day.time(i.at))}.</p>
<p class=noprint><button onclick="print()">Drukuj / PDF</button></p>"""
    s"""<!doctype html><html lang=pl><head><meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(title)}</title><style>$style</style></head><body>
$body
</body></html>
"""
