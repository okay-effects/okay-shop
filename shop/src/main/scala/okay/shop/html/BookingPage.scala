package okay.shop.html

import okay.shop.model.*

/**
 * A BOOKING'S PAGE (the letter's link, `/{lang}/booking?ref=&k=`), in the track page's design: the time, the service,
 * the ref; cancel, or move to another free start (the slot picker, the pages' script). Every value escaped
 */
object BookingPage:
  private def e(s: String) = Html.escape(s)
  private def accent(site: Site) = if site.clothing then "#ff6f91" else site.color

  /** the frame of a client's own page (a booking's, an inquiry's): its head, its bar, the column opened */
  def frame(site: Site, l: Lang, title: String): String = head(site, l, title)

  private def head(site: Site, l: Lang, title: String): String =
    val a = accent(site)
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(title)} · ${e(site.logoText)}</title>
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
.ref{font-family:monospace;font-size:13px;color:#999;}
button,.day,.slot{font:inherit;border:1px solid #d5c8b8;background:#fff;border-radius:10px;padding:8px 12px;
  margin:3px;cursor:pointer;}
button.go{background:$a;color:#fff;border-color:$a;width:100%;padding:12px;margin:10px 0 0;}
.day.on,.slot.on{border-color:$a;box-shadow:inset 0 0 0 1px $a;}
.slots{margin:8px 0;}
</style></head><body>
<div class="top"><a href="/${l.code}/">← ${e(site.logoText)}</a><h1>${e(title)}</h1></div>
<div class="wrap">"""

  private val title = T("Ваша запись", "Twoja rezerwacja", "Your booking", "Ваш запис")

  /** a booking's page; `receipt` when it is paid (specs/shop.md "Money": the receipt linked) */
  def page(c: Conf, l: Lang, b: Booking, receipt: Boolean = false): String =
    def w(t: T) = e(t(l))
    val site = c.site
    val service = site.offer(b.service).map(_.name(l)).getOrElse(b.service)
    val status = if b.active then w(T("Записаны", "Zarezerwowane", "Booked", "Записано"))
      else if b.status == BookingStatus.NoShow then w(T("Неявка", "Nieobecność", "Not attended", "Неявка"))
      else w(T("Отменено", "Odwołane", "Cancelled", "Скасовано"))
    val paid = if !receipt then "" else s"""<div class="card"><a href="/${l.code}/receipt?ref=${e(b.ref)}&amp;k=${
      e(b.key)}">${w(T("Квитанция (скачать / печать) →", "Rachunek (pobierz / drukuj) →", "Receipt (download / " +
      "print) →", "Квитанція (завантажити / друк) →"))}</a></div>\n"""
    val picker = site.offer(b.service).fold("")(o => Pages.masterPicker(site, o, b.master)(using l))
    val manage = if !b.active then "" else
      s"""<div class="card"><div class="label">${w(T("Перенести", "Przenieś", "Move", "Перенести"))}</div>
<form data-send="booking/move" data-form="moveForm" data-done="moveDone" id="moveForm">
<input type="hidden" name="ref" value="${e(b.ref)}"><input type="hidden" name="k" value="${e(b.key)}">
<input type="hidden" name="lang" value="${l.code}"><input type="hidden" name="start" value="">${b.mode.fold("")(m =>
        s"""<input type="hidden" name="mode" value="${m.key}"><input type="hidden" name="place" value="${
          e(b.place)}">""")}
$picker<div class="slots" data-slots="${e(b.service)}"></div>
<button class="go send" type="submit">${w(T("Перенести на выбранное время", "Przenieś na wybrany termin",
        "Move to the time chosen", "Перенести на обраний час"))}</button></form>
<div id="moveDone" style="display:none"><p class="status">${w(T("Перенесено", "Przeniesiono", "Moved",
        "Перенесено"))}</p></div></div>
<div class="card"><form data-send="booking/cancel" data-form="cancelForm" data-done="cancelDone" id="cancelForm">
<input type="hidden" name="ref" value="${e(b.ref)}"><input type="hidden" name="k" value="${e(b.key)}">
<input type="hidden" name="lang" value="${l.code}">
<button class="send" type="submit">${w(T("Отменить запись", "Odwołaj rezerwację", "Cancel the booking",
        "Скасувати запис"))}</button></form>
<div id="cancelDone" style="display:none"><p class="status">${w(T("Отменено", "Odwołane", "Cancelled",
        "Скасовано"))}</p></div></div>"""
    head(site, l, title(l)) + s"""
  <div class="card">
    <div class="status">$status</div>
    <div class="label">${w(T("Когда", "Kiedy", "When", "Коли"))}</div><div class="val">${e(Day.time(b.start))}</div>
    <div class="label" style="margin-top:10px">${w(T("Услуга", "Usługa", "Service", "Послуга"))}</div>
    <div class="val">${e(service)} · ${b.minutes} min</div>${Letters.where(c, b, l).map((k, v) =>
      s"""\n    <div class="label" style="margin-top:10px">${e(k)}</div><div class="val">$v</div>""").mkString}
    <div class="ref" style="margin-top:4px">${e(b.ref)}</div>
  </div>
$paid$manage
</div><script src="/site.js" defer></script></body></html>
"""

  def missing(c: Conf, l: Lang): String =
    head(c.site, l, title(l)) + s"""<div class="card">${e(T("Запись не найдена — проверьте ссылку из письма.",
      "Nie znaleziono rezerwacji — sprawdź link z wiadomości.", "No such booking — check the link in the letter.",
      "Запис не знайдено — перевірте посилання з листа.")(l))}</div></div></body></html>
"""
