package okay.shop.html

import okay.shop.model.*

/**
 * AN INQUIRY'S PAGE (the letters' link, `/{lang}/quote?ref=&k=`), in the booking page's design: the job, its status,
 * the shop's quote, and — when quoted — accept or decline. Every value escaped
 */
object QuotePage:
  private def e(s: String) = Html.escape(s)
  private val title = T("Ваш запрос", "Twoje zapytanie", "Your inquiry", "Ваш запит")

  def page(c: Conf, l: Lang, q: Quote): String =
    def w(t: T) = e(t(l))
    val service = c.site.offer(q.service).map(_.name(l)).getOrElse(q.service)
    def row(k: T, v: String) = if v.isEmpty then "" else
      s"""<div class="label" style="margin-top:10px">${w(k)}</div><div class="val">${Html.lines(v)}</div>"""
    def form(kind: String, label: T, go: Boolean) =
      s"""<form data-send="quote/$kind" data-form="${kind}Form" data-done="${kind}Done" id="${kind}Form"
style="display:inline"><input type="hidden" name="ref" value="${e(q.ref)}"><input type="hidden" name="k"
value="${e(q.key)}"><input type="hidden" name="lang" value="${l.code}"><button class="${if go then "go " else ""}send"
type="submit">${w(label)}</button></form><div id="${kind}Done" style="display:none"><p class="status">${w(label)
      } ✓</p></div>"""
    val decide = if q.status != QuoteStatus.Quoted then "" else
      s"""<div class="card">${form("accept", T("Принять", "Przyjmuję", "Accept", "Прийняти"), go = true)}
${form("decline", T("Отклонить", "Odrzucam", "Decline", "Відхилити"), go = false)}</div>"""
    BookingPage.frame(c.site, l, title(l)) + s"""
  <div class="card">
    <div class="status">${w(q.status.label)}</div>
    <div class="label">${w(T("Услуга", "Usługa", "Service", "Послуга"))}</div><div class="val">${e(service)}</div>
    ${row(T("Что сделать", "Co zrobić", "The job", "Що зробити"), q.description)}
    ${row(T("Где", "Gdzie", "Where", "Де"), q.address.fold("")(_.line))}
    ${row(T("Цена", "Cena", "Price", "Ціна"), q.priced)}
    ${row(T("Когда", "Kiedy", "When", "Коли"), if q.start > 0 then Day.time(q.start) else "")}
    ${row(T("Комментарий", "Uwagi", "Note", "Коментар"), q.note)}
    <div class="ref" style="margin-top:8px">${e(q.ref)}</div>
  </div>
$decide
</div><script src="/site.js" defer></script></body></html>
"""

  def missing(c: Conf, l: Lang): String =
    BookingPage.frame(c.site, l, title(l)) + s"""<div class="card">${e(T("Запрос не найден — проверьте ссылку из " +
      "письма.", "Nie znaleziono zapytania — sprawdź link z wiadomości.", "No such inquiry — check the link in the " +
      "letter.", "Запит не знайдено — перевірте посилання з листа.")(l))}</div></div></body></html>
"""
