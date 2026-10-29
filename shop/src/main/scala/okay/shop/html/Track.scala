package okay.shop.html

import okay.shop.model.*

/**
 * AN ORDER'S PAGES, as busi's `orders.ssc` drew them: the search (a ref and the email or phone it was placed with),
 * the status, "not found", the receipt of a paid order. Answered by the Worker; every value escaped
 */
object Track:
  private def e(s: String) = Html.escape(s)
  private def accent(site: Site) = if site.clothing then "#ff6f91" else site.color

  private val title = T("Статус заказа", "Status zlecenia", "Order status", "Статус замовлення")

  /** the search: the ref (prefilled from a link) and the second factor; `failed` when a lookup found nothing */
  def search(site: Site, l: Lang, ref: String, sitekey: String, failed: Boolean): String =
    val a = accent(site)
    val words = Words(l)
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>${e(title(l))} · ${e(site.logoText)}</title>
<style>body{font:16px/1.5 -apple-system,system-ui,sans-serif;margin:0;background:#faf5f0;display:flex;
align-items:center;justify-content:center;min-height:100vh;}.box{background:#fff;border-radius:16px;padding:28px 24px;
max-width:380px;width:100%;}.box h1{font-size:20px;margin:0 0
    16px;color:#2a1f14;}input{width:100%;box-sizing:border-box;
font-size:15px;padding:10px 12px;border:1px solid #d5c8b8;border-radius:10px;margin-bottom:14px;}button{width:100%;
padding:12px;border:0;border-radius:10px;background:$a;color:#fff;font-size:16px;cursor:pointer;}
.err{color:#9e1042;font-size:14px;margin:0 0 12px;}</style></head>
<body><div class="box"><h1>📦 ${e(title(l))}</h1>
${if failed then s"""<p class="err">${e(words.notFoundHint)}</p>""" else ""}
<form method="post" action="/${l.code}/track">
<input name="ref" value="${e(ref)}" placeholder="${e(words.refPlaceholder)}" required autocomplete="off"${
      if ref.isEmpty then " autofocus" else ""}>
<input name="contact" placeholder="${e(words.contactPlaceholder)}" required${if ref.nonEmpty then " autofocus" else ""}>
<div class="cf-turnstile" data-sitekey="${e(sitekey)}" data-appearance="interaction-only"></div>
<button type="submit">${e(words.check)}</button></form>
<p style="margin-top:16px;font-size:13px;text-align:center"><a href="/${l.code}/" style="color:$a">${
      e(words.toSite)}</a>${Pages.staff(using l)}</p>
</div><script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script></body></html>
"""

  /** an order's status: the service, the ref, the status, the delivery, the tracking number, the hints */
  def status(site: Site, l: Lang, o: Order, paid: Boolean = false): String =
    val a = accent(site)
    val words = Words(l)
    val item = site.offer(o.offerKey).map(_.name(l)).getOrElse(words.fromSite)
    val delivery = if o.courier then words.courier else if o.deliveryMethod == "in_person" then words.inPerson else ""
    val tracking = if o.tracking.nonEmpty then
      s"""<div class="trk">📤 ${e(words.trackingNo)}: <b>${e(o.tracking)}</b></div>""" else ""
    val hint =
      if o.status == Status.Accepted && o.courier then s"""<div class="hint">${e(words.sendHint)}</div>"""
      else if o.status == Status.ParcelOut then s"""<div class="hint">${e(words.inpostHint)}</div>"""
      else if o.status == Status.Paid || paid then
        s"""<div class="hint"><a href="/${l.code}/receipt?ref=${e(o.ref)}&amp;k=""" +
        s"""${e(o.key)}">${e(words.receiptLink)}</a></div>"""
      else ""
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(title(l))} · ${e(site.logoText)}</title>
<style>
body{font:16px/1.5 -apple-system,system-ui,sans-serif;margin:0;background:#faf5f0;color:#2a1f14;min-height:100vh;}
.top{background:$a;color:#fff;padding:14px 20px;display:flex;align-items:center;gap:12px;}
.top h1{font-size:18px;margin:0;font-weight:700;flex:1;}
.top a{color:#fff;opacity:.8;text-decoration:none;font-size:14px;}
.wrap{max-width:480px;margin:0 auto;padding:24px 16px 60px;}
.card{background:#fff;border-radius:14px;padding:18px 20px;margin-bottom:16px;box-shadow:0 1px 4px rgba(0,0,0,.08);}
.label{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:#999;margin-bottom:4px;}
.val{font-size:17px;font-weight:600;color:#2a1f14;}
.status{font-size:20px;font-weight:700;color:$a;margin-bottom:6px;}
.trk{font-size:14px;background:#f0f8ff;border-radius:8px;padding:8px 12px;margin-top:8px;color:#1a4a80;}
.ref{font-family:monospace;font-size:13px;color:#999;}
.hint{color:#777;font-size:14px;margin-top:10px;}
</style></head><body>
<div class="top"><a href="/${l.code}/">← ${e(site.logoText)}</a><h1>${e(title(l))}</h1></div>
<div class="wrap">
  <div class="card">
    <div class="label">${e(words.service)}</div><div class="val">${e(item)}</div>
    <div class="ref" style="margin-top:4px">${e(o.ref)}</div>
  </div>
  <div class="card">
    <div class="status">${e(o.status.label(l))}</div>
    <div style="font-size:14px;color:#777;">${e(delivery)}</div>
    $tracking
    $hint
  </div>
</div></body></html>
"""

  /** the receipt (rachunek) of a paid order: a non-VAT seller's, in Polish as the law has it */
  def receipt(site: Site, o: Order, contactLine: String): String =
    val day = okay.shop.model.Day.of(o.paidAt)
    val num = s"RACH-${day.take(4)}-${o.ref.stripPrefix(site.refPrefix + "-")}"
    s"""<!doctype html><html lang=pl><head><meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Rachunek ${e(num)}</title><style>
body{font:15px/1.6 -apple-system,sans-serif;max-width:560px;margin:32px auto;padding:0 16px;color:#111}
h1{font-size:19px}table{border-collapse:collapse;width:100%;margin:16px 0}
td,th{border:1px solid #bbb;padding:8px 10px;text-align:left}td.r{text-align:right}
.meta{color:#666;font-size:13px}@media print{a{display:none}}</style></head><body>
<h1>Rachunek nr ${e(num)}</h1>
<p class=meta>Data wystawienia / wykonania usługi: ${e(day)}</p>
<table>
<tr><th>Wykonawca</th><td>${e(site.name)} · ${e(contactLine)}</td></tr>
<tr><th>Nabywca</th><td>${e(o.name)}</td></tr>
<tr><th>Usługa</th><td>${e(site.offer(o.offerKey).map(_.name(Lang.pl)).getOrElse("Usługa"))}</td></tr>
<tr><th>Kwota</th><td class=r><b>${e(Money.zl(o.amount))}</b></td></tr>
</table>
<p class=meta>Sprzedawca prowadzi działalność nierejestrowaną (art. 5 ust. 1 ustawy Prawo przedsiębiorców) i nie jest
podatnikiem VAT — rachunek bez VAT.</p>
<p class=meta>Zapłacono w całości. Dziękujemy!</p>
</body></html>
"""

  /** the words of these pages, in a language */
  final case class Words(l: Lang):
    private def w(ru: String, pl: String, en: String, uk: String) = T(ru, pl, en, uk)(l)
    def refPlaceholder = w("Номер заказа (напр. SZY-7K3F2Q)", "Numer zlecenia (np. SZY-7K3F2Q)",
      "Order number (e.g. SZY-7K3F2Q)", "Номер замовлення (напр. SZY-7K3F2Q)")
    def contactPlaceholder = w("Email или телефон из заказа", "Email lub telefon z zamówienia",
      "The email or phone of the order", "Email або телефон із замовлення")
    def check = w("Проверить", "Sprawdź", "Check", "Перевірити")
    def toSite = w("← На сайт", "← Na stronę", "← To the site", "← На сайт")
    def notFoundHint = w("Не найдено. Проверьте номер заказа и email или телефон — они в письме-подтверждении.",
      "Nie znaleziono. Sprawdź numer zlecenia i email lub telefon — są w potwierdzeniu.",
      "Not found. Check the order number and the email or phone — they are in the confirmation email.",
      "Не знайдено. Перевірте номер замовлення та email або телефон — вони в листі-підтвердженні.")
    def service = w("Изделие", "Usługa", "Service", "Послуга")
    def fromSite = w("Заявка с сайта", "Zgłoszenie ze strony", "A request from the site", "Заявка з сайту")
    def courier = w("📦 Курьер InPost", "📦 Kurier InPost", "📦 InPost courier", "📦 Кур'єр InPost")
    def inPerson = w("🚶 Личный визит", "🚶 Odbiór osobisty", "🚶 In person", "🚶 Особистий візит")
    def trackingNo = w("Трекинг", "Numer trackingu", "Tracking number", "Номер трекінгу")
    def sendHint = w("Отправьте вещь через InPost на согласованный адрес.",
      "Wyślij rzecz przez InPost na uzgodniony adres.", "Please send your item via InPost to the agreed address.",
      "Надішліть річ через InPost на узгоджену адресу.")
    def inpostHint = w("Следите за посылкой в InPost по трекинг-номеру выше.",
      "Śledź status w aplikacji InPost używając numeru trackingu powyżej.",
      "Track your parcel in the InPost app using the tracking number above.",
      "Відстежуйте посилку в InPost за номером трекінгу вище.")
    def receiptLink = w("Квитанция (скачать / печать) →", "Rachunek (pobierz / drukuj) →", "Receipt (download / " +
        "print) →",
      "Квитанція (завантажити / друк) →")
