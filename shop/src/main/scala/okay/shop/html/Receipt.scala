package okay.shop.html

import okay.shop.model.*

/**
 * A CLIENT'S RECEIPT (specs/shop.md "Receipts"): a paid order's or booking's — or manual income's — `rachunek`, its
 * number the payment's, in Polish: the shop, the client, the service and its date, the payment's date, method and
 * amount; a non-fiscal document, said so. Every value escaped
 */
object Receipt:
  private def e(s: String) = Html.escape(s)

  def page(c: Conf, p: Payment, buyer: String, service: String, served: String): String =
    val what = c.site.offer(service).map(_.name(Lang.pl)).getOrElse(if p.description.nonEmpty then p.description
      else "Usługa")
    s"""<!doctype html><html lang=pl><head><meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Rachunek ${e(p.receipt)}</title><style>
body{font:15px/1.6 -apple-system,sans-serif;max-width:560px;margin:32px auto;padding:0 16px;color:#111}
h1{font-size:19px}table{border-collapse:collapse;width:100%;margin:16px 0}
td,th{border:1px solid #bbb;padding:8px 10px;text-align:left}td.r{text-align:right}
.meta{color:#666;font-size:13px}button{font:inherit;padding:8px 14px;cursor:pointer}
@media print{a,button{display:none}body{margin:0}}</style></head><body>
<h1>Rachunek nr ${e(p.receipt)}</h1>
<p class=meta>Data wystawienia: ${e(p.day)} · data wykonania usługi: ${e(if served.nonEmpty then served else p.day)}</p>
<table>
<tr><th>Wykonawca</th><td>${e(c.site.name)}${if c.contact.nonEmpty then " · " + e(c.contact) else ""}</td></tr>
<tr><th>Nabywca</th><td>${e(buyer)}</td></tr>
<tr><th>Usługa</th><td>${e(what)}</td></tr>
<tr><th>Zapłacono</th><td>${e(p.day)} · ${e(p.method.label(Lang.pl))}</td></tr>
<tr><th>Kwota</th><td class=r><b>${e(Money.zl(p.amount))}</b></td></tr>
</table>
<p class=meta>Dokument niefiskalny: nie jest fakturą VAT ani paragonem fiskalnym. Zapłacono w całości. Dziękujemy!</p>
<p><button onclick="print()">Drukuj / PDF</button></p>
</body></html>
"""
