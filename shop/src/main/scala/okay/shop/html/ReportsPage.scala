package okay.shop.html

import okay.shop.model.*

/**
 * THE OWNER'S REPORTS (specs/shop.md "Reports"), `<desk>/reports/` in pl/uk/ru/en: a period chosen (today, this week,
 * this month, last month, any range), its totals beside the previous period's, a day-by-day chart (inline SVG), the
 * breakdowns, the payments; the exports (CSV, a printable page, the month's "ewidencja" helper); manual income added
 * and removed. Drawn by the server from the report; its little script only posts the manual income
 */
object ReportsPage:
  private def e(s: String) = Html.escape(s)

  private val words: Map[String, T] = Map(
    "title" -> T("Отчёты", "Raporty", "Reports", "Звіти"),
    "back" -> T("← В мастерскую", "← Do panelu", "← Back to the workshop", "← До майстерні"),
    "today" -> T("Сегодня", "Dziś", "Today", "Сьогодні"),
    "week" -> T("Эта неделя", "Ten tydzień", "This week", "Цей тиждень"),
    "month" -> T("Этот месяц", "Ten miesiąc", "This month", "Цей місяць"),
    "lastMonth" -> T("Прошлый месяц", "Poprzedni miesiąc", "Last month", "Минулий місяць"),
    "from" -> T("С", "Od", "From", "З"), "to" -> T("По", "Do", "To", "По"),
    "show" -> T("Показать", "Pokaż", "Show", "Показати"),
    "income" -> T("Получено", "Wpłynęło", "Income paid", "Отримано"),
    "net" -> T("Чистыми (минус возвраты)", "Netto (minus zwroty)", "Net (less refunds)", "Чисто (мінус повернення)"),
    "refunds" -> T("возвраты", "zwroty", "refunds", "повернення"),
    "unpaid" -> T("Сделано, не оплачено", "Wykonane, nieopłacone", "Done, not paid", "Зроблено, не оплачено"),
    "expected" -> T("Ожидается (записи впереди)", "Oczekiwane (rezerwacje przed nami)", "Expected (bookings ahead)",
      "Очікується (записи попереду)"),
    "average" -> T("Средний чек", "Średni rachunek", "Average ticket", "Середній чек"),
    "orders" -> T("Заказы", "Zamówienia", "Orders", "Замовлення"),
    "bookings" -> T("Записи", "Rezerwacje", "Bookings", "Записи"),
    "visits" -> T("Визиты (прошли)", "Wizyty (odbyte)", "Visits (done)", "Візити (відбулися)"),
    "cancelled" -> T("Отмены", "Odwołane", "Cancellations", "Скасування"),
    "noShows" -> T("Неявки", "Nieobecności", "No-shows", "Неявки"),
    "payments" -> T("платежей", "płatności", "payments", "платежів"),
    "before" -> T("пред. период", "poprz. okres", "previous period", "попер. період"),
    "byDay" -> T("По дням", "Dzień po dniu", "Day by day", "По днях"),
    "byService" -> T("По услугам", "Wg usług", "By service", "За послугами"),
    "byMaster" -> T("По мастерам", "Wg mistrzów", "By master", "За майстрами"),
    "byMode" -> T("По способу", "Wg sposobu", "By way", "За способом"),
    "byMethod" -> T("По оплате", "Wg płatności", "By payment method", "За оплатою"),
    "entries" -> T("Платежи", "Płatności", "Payments", "Платежі"),
    "day" -> T("Дата", "Data", "Date", "Дата"), "receipt" -> T("Документ", "Dokument", "Document", "Документ"),
    "description" -> T("Описание", "Opis", "Description", "Опис"), "method" -> T("Оплата", "Płatność", "Method",
      "Оплата"), "amount" -> T("Сумма", "Kwota", "Amount", "Сума"), "count" -> T("Кол-во", "Ile", "Count", "К-сть"),
    "share" -> T("Доля", "Udział", "Share", "Частка"),
    "refunded" -> T("возврат", "zwrot", "refunded", "повернено"),
    "none" -> T("Ничего за этот период.", "Nic w tym okresie.", "Nothing in this period.", "Нічого за цей період."),
    "exports" -> T("Для бухгалтера", "Dla księgowej", "For the accountant", "Для бухгалтера"),
    "csv" -> T("CSV", "CSV", "CSV", "CSV"),
    "csvPl" -> T("CSV (Excel, запятая)", "CSV (Excel, przecinek)", "CSV (Excel, decimal comma)",
      "CSV (Excel, кома)"),
    "print" -> T("Печать / PDF", "Drukuj / PDF", "Print / PDF", "Друк / PDF"),
    "ewidencja" -> T("Ewidencja przychodów (помощник)", "Ewidencja przychodów (pomocnik)",
      "Ewidencja przychodów (a helper)", "Ewidencja przychodów (помічник)"),
    "manual" -> T("Доход вне системы", "Przychód spoza systemu", "Income outside the system", "Дохід поза системою"),
    "manualNote" -> T("Работа, оплаченная не через сайт: добавьте её, чтобы отчёт был полным.", "Praca opłacona " +
      "poza stroną: dodaj ją, by raport był pełny.", "Work paid outside the site: add it so the report is whole.",
      "Робота, оплачена не через сайт: додайте її, щоб звіт був повним."),
    "add" -> T("Добавить", "Dodaj", "Add", "Додати"),
    "remove" -> T("Убрать", "Usuń", "Remove", "Прибрати"),
    "service" -> T("Услуга (необязательно)", "Usługa (opcjonalnie)", "Service (optional)",
      "Послуга (необов'язково)"),
    "master" -> T("Мастер (необязательно)", "Mistrz (opcjonalnie)", "Master (optional)", "Майстер (необов'язково)"),
    "note" -> T("Заметка", "Notatka", "Note", "Нотатка"),
    "failed" -> T("Не добавлено, проверьте поле", "Nie dodano, sprawdź pole", "Not added, check the field",
      "Не додано, перевірте поле"),
    "order" -> T("Заказ", "Zamówienie", "Order", "Замовлення"),
    "booking" -> T("Запись", "Rezerwacja", "Booking", "Запис"),
    "other" -> T("Другое", "Inne", "Other", "Інше"),
    "ownerOnly" -> T("Отчёты видит только владелец магазина.", "Raporty widzi tylko właściciel sklepu.",
      "Only the shop's owner sees the reports.", "Звіти бачить лише власник магазину."))

  private def w(k: String)(using l: Lang): String = words.get(k).map(_(l)).getOrElse(k)
  private def zl(g: Long): String = Money.zl(g)
  private def label(key: String)(using Lang): String = Mode.parse(key).map(_.label(summon[Lang]))
    .orElse(Method.parse(key).map(_.label(summon[Lang]))).getOrElse(if words.contains(key) then w(key) else key)

  /** the change from the previous period: `+12 %`, `−5 %`, `—` when there was nothing */
  private def delta(now: Long, before: Long): String =
    if before == 0 then (if now == 0 then "±0" else "—")
    else
      val d = Math.round((now - before) * 100.0 / Math.abs(before))
      if d > 0 then s"+$d %" else if d < 0 then s"−${-d} %" else "±0 %"

  private val style = """:root{--ink:#1f2328;--mist:#59636e;--line:#d8dee4;--card:#fff;--bg:#f4f5f7;--acc:#1f6feb;
--good:#1a7f37;--bad:#b42318;--sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
*{box-sizing:border-box}body{margin:0;font:15px/1.5 var(--sans);color:var(--ink);background:var(--bg)}
header{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;padding:14px 20px;
background:#fff;border-bottom:1px solid var(--line)}header h1{font-size:18px;margin:0}header a{color:var(--acc);
text-decoration:none}.langs{font-size:13px;color:var(--mist)}.langs a{color:var(--mist);margin:0 2px}
main{max-width:1180px;margin:0 auto;padding:18px 16px 60px}h2{font-size:16px;margin:0 0 10px}
.bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:0 0 16px}.bar a.q{padding:7px 12px;
border:1px solid var(--line);border-radius:999px;background:#fff;color:var(--ink);text-decoration:none;font-size:14px}
.bar a.q.on{background:var(--ink);color:#fff;border-color:var(--ink)}.bar form{display:flex;gap:6px;
align-items:center;flex-wrap:wrap;font-size:14px}.bar input{font:inherit;padding:6px 8px;border:1px solid var(--line);
border-radius:8px}button,.btn{font:600 14px var(--sans);padding:8px 14px;border:0;border-radius:9px;
background:var(--acc);color:#fff;cursor:pointer;text-decoration:none;display:inline-block}
.ghost{background:#fff;color:var(--ink);border:1px solid var(--line);font-weight:500}
.period{color:var(--mist);margin:-6px 0 14px;font-size:14px}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:12px;margin:0 0 18px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px 14px}
.card .k{font-size:12.5px;color:var(--mist)}.card .v{font-size:22px;font-weight:700;margin:2px 0}
.card .d{font-size:12.5px;color:var(--mist)}.up{color:var(--good)}.down{color:var(--bad)}
.card.main{border-color:var(--acc);box-shadow:inset 0 0 0 1px var(--acc)}
.panel{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:0 0 16px}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px}.chart svg{width:100%;height:auto;display:block}
table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:7px 6px;border-bottom:1px solid #eef1f4;
text-align:left;vertical-align:top}th{font-weight:600;color:var(--mist);font-size:12.5px}td.r,th.r{text-align:right;
white-space:nowrap}.wrapx{overflow-x:auto}.exports{display:flex;gap:8px;flex-wrap:wrap}
.f{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:10px;align-items:end}
.f label{display:flex;flex-direction:column;gap:4px;font-size:13px;color:var(--mist)}.f input,.f select{font:inherit;
font-size:15px;color:var(--ink);padding:7px 9px;border:1px solid var(--line);border-radius:8px;background:#fff}
#said{font-size:14px;color:var(--bad);margin:8px 0 0}.note{color:var(--mist);font-size:14px;margin:0 0 10px}
@media (max-width:760px){.grid2{grid-template-columns:1fr}.card .v{font-size:19px}}
@media print{header .langs,header a,.bar,.exports,.manual,button{display:none!important}body{background:#fff}
.panel,.card{break-inside:avoid;border-color:#bbb}main{padding:0}}"""

  /** the chart: a bar a day, the highest labelled; inline SVG */
  def chart(days: Vector[(String, Long)])(using Lang): String =
    val top = days.map(_._2).maxOption.filter(_ > 0).getOrElse(1L)
    val n = days.size max 1
    val (w0, h0, pad) = (720.0, 200.0, 24.0)
    val bw = (w0 - pad) / n
    val bars = days.zipWithIndex.map { case ((d, a), i) =>
      val h = if a <= 0 then 0.0 else (a.toDouble / top * (h0 - 40)).max(2.0)
      f"""<rect x="${pad + i * bw + bw * 0.12}%.1f" y="${h0 - 20 - h}%.1f" width="${(bw * 0.76).max(1.0)}%.1f" """ +
        f"""height="$h%.1f" rx="2" fill="#1f6feb"><title>${e(d)}: ${e(zl(a))}</title></rect>"""
    }.mkString
    val ticks = days.zipWithIndex.filter((_, i) => i == 0 || i == n - 1 || (n > 10 && i % (n / 6 max 1) == 0))
      .map { case ((d, _), i) => f"""<text x="${pad + i * bw + bw / 2}%.1f" y="${h0 - 4}%.1f" font-size="11" """ +
        s"""text-anchor="middle" fill="#59636e">${e(d.drop(5))}</text>""" }.mkString
    s"""<svg viewBox="0 0 ${w0.toInt} ${h0.toInt}" role="img" aria-label="${e(w("byDay"))}">
<line x1="$pad" y1="${h0 - 20}" x2="$w0" y2="${h0 - 20}" stroke="#d8dee4"/>
<text x="2" y="14" font-size="11" fill="#59636e">${e(zl(top))}</text>$bars$ticks</svg>"""

  private def breakdown(title: String, ls: Vector[Line], total: Long)(using Lang): String =
    s"""<section class="panel"><h2>${e(title)}</h2><table><tr><th></th><th class="r">${e(w("count"))}</th>
<th class="r">${e(w("amount"))}</th><th class="r">${e(w("share"))}</th></tr>${ls.map(x => s"""<tr><td>${
      e(label(x.label))}</td><td class="r">${x.count}</td><td class="r">${e(zl(x.amount))}</td><td class="r">${
      if total > 0 then Math.round(x.amount * 100.0 / total) else 0} %</td></tr>""").mkString}${
      if ls.isEmpty then s"""<tr><td colspan="4">${e(w("none"))}</td></tr>""" else ""}</table></section>"""

  private def card(k: String, v: String, d: String, main: Boolean = false, cls: String = ""): String =
    s"""<div class="card${if main then " main" else ""}"><div class="k">${e(k)}</div><div class="v">${e(v)}</div>
<div class="d $cls">${d}</div></div>"""

  /** the report's body: the totals and their changes, the chart, the breakdowns, the payments */
  def body(r: Report, b: Report, manual: Boolean)(using l: Lang): String =
    def vs(now: Long, before: Long, money: Boolean = true) =
      val cls = if now > before then "up" else if now < before then "down" else ""
      s"""<span class="$cls">${e(delta(now, before))}</span> · ${e(w("before"))}: ${
        e(if money then zl(before) else before.toString)}"""
    val cards = Vector(
      card(w("income"), zl(r.income), s"${r.paidCount} ${e(w("payments"))} · " + vs(r.income, b.income), main = true),
      card(w("net"), zl(r.net), s"${e(w("refunds"))}: ${e(zl(r.refunds))} · " + vs(r.net, b.net)),
      card(w("unpaid"), zl(r.unpaid), s"${r.unpaidCount} · " + vs(r.unpaid, b.unpaid)),
      card(w("expected"), zl(r.expected), s"${r.expectedCount}"),
      card(w("average"), zl(r.average), vs(r.average, b.average)),
      card(w("orders"), r.orders.toString, vs(r.orders, b.orders, money = false)),
      card(w("bookings"), r.bookings.toString, vs(r.bookings, b.bookings, money = false)),
      card(w("visits"), r.visits.toString, vs(r.visits, b.visits, money = false)),
      card(w("cancelled"), r.cancelled.toString, vs(r.cancelled, b.cancelled, money = false)),
      card(w("noShows"), r.noShows.toString, vs(r.noShows, b.noShows, money = false))).mkString
    val rows = r.entries.map(x => s"""<tr><td>${e(x.day)}</td><td>${e(x.receipt)}</td><td>${e(x.description)}${
      if x.state == PayState.Refunded then s""" <b class="down">(${e(w("refunded"))})</b>""" else ""}</td><td>${
      e(x.method.label(l))}</td><td class="r">${e(zl(x.amount))}</td>${if manual then s"""<td>${
      if x.of == Of.Manual && x.state == PayState.Paid then s"""<button class="ghost" data-remove="${e(x.ref)}">${
        e(w("remove"))}</button>""" else ""}</td>""" else ""}</tr>""").mkString
    s"""<div class="cards">$cards</div>
<section class="panel chart"><h2>${e(w("byDay"))}</h2>${chart(r.days)}</section>
<div class="grid2">${breakdown(w("byService"), r.byService, r.income)}${breakdown(w("byMaster"), r.byMaster,
      r.income)}${breakdown(w("byMode"), r.byMode, r.income)}${breakdown(w("byMethod"), r.byMethod, r.income)}</div>
<section class="panel"><h2>${e(w("entries"))}</h2><div class="wrapx"><table><tr><th>${e(w("day"))}</th><th>${
      e(w("receipt"))}</th><th>${e(w("description"))}</th><th>${e(w("method"))}</th><th class="r">${e(w("amount"))}
</th>${if manual then "<th></th>" else ""}</tr>$rows${if r.entries.isEmpty then s"""<tr><td colspan="5">${
      e(w("none"))}</td></tr>""" else ""}</table></div></section>"""

  private def langs(l: Lang, query: String): String = Lang.values.toVector.map(x => if x == l then s"<b>${x.code}</b>"
    else s"""<a href="?${query}lang=${x.code}">${x.code}</a>""").mkString(" ")

  private def head(title: String, l: Lang): String =
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(title)}</title><link rel="icon" href="data:,"><style>$style</style></head><body>"""

  /** THE SCREEN: the period's report, its controls, its exports, the manual income */
  def page(l0: Lang, c: Conf, today: String, r: Report, b: Report): String =
    given l: Lang = l0
    val p = r.period
    val desk = c.desk.path
    val monday = Reports.plus(today, -((Reports.epochDay(today) + 3) % 7).toInt)
    val month = Reports.month(today)
    val last = Reports.month(Reports.plus(month.from, -1))
    def q(key: String, x: Period) = s"""<a class="q${if x == p then " on" else ""}" href="?from=${x.from}&amp;to=${
      x.to}&amp;lang=${l.code}">${e(w(key))}</a>"""
    val query = s"from=${p.from}&amp;to=${p.to}&amp;"
    val services = c.site.offers.map(o => s"""<option value="${e(o.key)}">${e(o.name(l))}</option>""").mkString
    val masters = c.site.masters.map(m => s"""<option value="${e(m.key)}">${e(m.name)}</option>""").mkString
    val methods = Method.values.map(m => s"""<option value="${m.key}">${e(m.label(l))}</option>""").mkString
    head(s"${w("title")} · ${c.site.name}", l) + s"""
<header><h1>${e(w("title"))} · ${e(c.site.name)}</h1><span class="langs">${langs(l, query)}</span>
<a href="$desk/">${e(w("back"))}</a></header><main>
<div class="bar">${q("today", Period(today, today))}${q("week", Period(monday, today))}${q("month", month)}${
      q("lastMonth", last)}<form method="get"><label>${e(w("from"))} <input type="date" name="from" value="${p.from}">
</label><label>${e(w("to"))} <input type="date" name="to" value="${p.to}"></label><input type="hidden" name="lang"
value="${l.code}"><button class="ghost" type="submit">${e(w("show"))}</button></form></div>
<p class="period">${e(p.from)} — ${e(p.to)} · ${e(w("before"))}: ${e(p.previous.from)} — ${e(p.previous.to)}</p>
${body(r, b, manual = true)}
<section class="panel"><h2>${e(w("exports"))}</h2><div class="exports">
<a class="btn" href="$desk/api/report.csv?from=${p.from}&amp;to=${p.to}" download>${e(w("csv"))}</a>
<a class="btn" href="$desk/api/report.csv?from=${p.from}&amp;to=${p.to}&amp;comma=1" download>${e(w("csvPl"))}</a>
<a class="btn ghost" href="$desk/reports/print?from=${p.from}&amp;to=${p.to}&amp;lang=${l.code}">${e(w("print"))}</a>
<a class="btn ghost" href="$desk/reports/ewidencja?month=${p.to.take(7)}">${e(w("ewidencja"))} ${e(p.to.take(7))}</a>
</div></section>
<section class="panel manual"><h2>${e(w("manual"))}</h2><p class="note">${e(w("manualNote"))}</p>
<form id="income" class="f"><label>${e(w("amount"))}, zł<input name="amount" inputmode="decimal" required></label>
<label>${e(w("day"))}<input type="date" name="day" value="$today" max="$today" required></label>
<label>${e(w("method"))}<select name="method">$methods</select></label>
<label>${e(w("description"))}<input name="description" maxlength="200" required></label>
<label>${e(w("service"))}<select name="service"><option value=""></option>$services</select></label>
${if masters.isEmpty then "" else s"""<label>${e(w("master"))}<select name="master"><option value=""></option>$masters
</select></label>"""}<label>${e(w("note"))}<input name="note" maxlength="300"></label>
<button type="submit">${e(w("add"))}</button></form><p id="said" role="status"></p></section>
</main><script>(function(){var J={'content-type':'application/json'};
function post(path,body){return fetch('$desk/api/'+path,{method:'POST',headers:J,body:JSON.stringify(body)})}
document.getElementById('income').addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target,b={};
Array.prototype.forEach.call(f.elements,function(x){if(x.name)b[x.name]=x.value});post('income',b).then(function(r){
return r.json().then(function(j){if(r.ok)location.reload();else document.getElementById('said').textContent=
"${Html.json(w("failed"))}"+': '+(j.error||r.status)})})});
Array.prototype.forEach.call(document.querySelectorAll('[data-remove]'),function(x){x.addEventListener('click',
function(){post('pay',{of:'manual',ref:x.getAttribute('data-remove'),action:'unpaid'}).then(function(){
location.reload()})})})})();</script></body></html>
"""

  /** THE PRINTABLE PAGE: the report alone, for the accountant (a PDF by the browser's print) */
  def print(l0: Lang, c: Conf, r: Report, b: Report): String =
    given l: Lang = l0
    head(s"${w("title")} · ${c.site.name} · ${r.period.from} — ${r.period.to}", l) + s"""
<header><h1>${e(w("title"))} · ${e(c.site.name)}</h1><span>${e(r.period.from)} — ${e(r.period.to)}</span>
<button onclick="print()">${e(w("print"))}</button></header><main>
<p class="period">${e(w("before"))}: ${e(r.period.previous.from)} — ${e(r.period.previous.to)}</p>
${body(r, b, manual = false)}</main></body></html>
"""

  /** THE MONTH'S "EWIDENCJA PRZYCHODÓW" HELPER: in Polish, its rows numbered, the total; NOT the official record */
  def ewidencja(c: Conf, month: Period, rows: Vector[(Int, String, String, String, Long)]): String =
    head(s"Ewidencja przychodów · ${c.site.name} · ${month.from.take(7)}", Lang.pl) + s"""
<header><h1>Ewidencja przychodów — pomocnik · ${e(c.site.name)} · ${e(month.from.take(7))}</h1>
<span class="exports"><a class="btn ghost" href="?month=${month.from.take(7)}&amp;format=csv">CSV</a>
<button onclick="print()">Drukuj / PDF</button></span></header><main>
<p class="note"><b>To nie jest oficjalna ewidencja przychodów.</b> To zestawienie pomocnicze z płatności zapisanych
w panelu (data, numer dokumentu, opis, kwota). Ewidencja na ryczałcie ma od 2026 r. własne kolumny (m.in. stawki
ryczałtu, numer KSeF, NIP kontrahenta) i terminy wpisów — sprawdź z księgową, jak z niego korzystać.</p>
<section class="panel"><div class="wrapx"><table><tr><th>Lp.</th><th>Data uzyskania przychodu</th><th>Nr dowodu</th>
<th>Opis</th><th class="r">Kwota</th></tr>${rows.map((n, d, doc, what, a) => s"""<tr><td>$n</td><td>${e(d)}</td><td>${
      e(doc)}</td><td>${e(what)}</td><td class="r">${e(Reports.amount(a, comma = true))} zł</td></tr>""").mkString}
<tr><th colspan="4">Razem</th><th class="r">${e(Reports.amount(rows.map(_._5).sum, comma = true))} zł</th></tr>
</table></div></section></main></body></html>
"""

  def notOwner(l: Lang, desk: String): String =
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(words("title")(l))}</title></head><body style="font:16px/1.5 system-ui,sans-serif;max-width:460px;
margin:20vh auto;padding:0 20px"><p>${e(words("ownerOnly")(l))}</p><p><a href="$desk/">${e(words("back")(l))}</a>
</p></body></html>
"""
