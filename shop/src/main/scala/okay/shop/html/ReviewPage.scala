package okay.shop.html

import okay.shop.model.*

/**
 * REVIEWS' PAGES (specs/shop.md "Reviews"): the client's form by its one-use link (`/{lang}/review?t=`), its thanks;
 * the storefront's block (the average, the count, the latest with the owner's replies) and its structured data; the
 * workshop's screen (`<desk>/reviews/`: every review, hidden ones apart; the owner replies, hides for a reason of the
 * list, shows again). Every value escaped
 */
object ReviewPage:
  private def e(s: String) = Html.escape(s)

  private val words: Map[String, T] = Map(
    "title" -> T("Отзыв", "Opinia", "Review", "Відгук"),
    "how" -> T("Как всё прошло?", "Jak było?", "How was it?", "Як усе пройшло?"),
    "rating" -> T("Ваша оценка", "Twoja ocena", "Your rating", "Ваша оцінка"),
    "text" -> T("Несколько слов (не обязательно)", "Kilka słów (opcjonalnie)", "A few words (optional)",
      "Кілька слів (не обовʼязково)"),
    "name" -> T("Имя для подписи (не обязательно) — покажем как «Анна К.»",
      "Imię do podpisu (opcjonalnie) — pokażemy jako „Anna K.”", "Your name (optional) — shown as “Anna K.”",
      "Імʼя для підпису (не обовʼязково) — покажемо як «Анна К.»"),
    "send" -> T("Отправить отзыв", "Wyślij opinię", "Send the review", "Надіслати відгук"),
    "public" -> T("Отзыв будет опубликован на странице. Не пишите в нём телефонов, адресов и чужих данных.",
      "Opinia zostanie opublikowana na stronie. Nie podawaj w niej telefonów, adresów ani cudzych danych.",
      "The review is published on the site. Please leave out phone numbers, addresses and others' details.",
      "Відгук буде опубліковано на сторінці. Не пишіть у ньому телефонів, адрес і чужих даних."),
    "thanks" -> T("Спасибо за отзыв!", "Dziękujemy za opinię!", "Thank you for your review!", "Дякуємо за відгук!"),
    "gone" -> T("Эта ссылка уже использована или устарела.", "Ten link został już użyty albo wygasł.",
      "This link has been used already or has expired.", "Це посилання вже використане або застаріле."),
    "back" -> T("На сайт", "Na stronę", "To the site", "На сайт"),
    "wrong.rating" -> T("Выберите оценку от 1 до 5.", "Wybierz ocenę od 1 do 5.", "Choose a rating from 1 to 5.",
      "Оберіть оцінку від 1 до 5."),
    "wrong.text" -> T("Текст слишком длинный (до 1000 знаков).", "Tekst jest za długi (do 1000 znaków).",
      "The text is too long (up to 1000 characters).", "Текст задовгий (до 1000 знаків)."),
    "reviews" -> T("Отзывы", "Opinie", "Reviews", "Відгуки"),
    "verified" -> T("Отзывы проверены: их оставляют только клиенты после визита или заказа — по ссылке из письма.",
      "Opinie zweryfikowane: wystawiają je tylko klienci po wizycie lub zamówieniu — przez link z wiadomości.",
      "Verified reviews: only clients after a visit or an order write them — by the link in their letter.",
      "Відгуки перевірені: їх залишають лише клієнти після візиту чи замовлення — за посиланням з листа."),
    "client" -> T("Клиент", "Klient", "A client", "Клієнт"),
    "reply" -> T("Ответ", "Odpowiedź", "Reply", "Відповідь"),
    "hidden" -> T("Скрыт", "Ukryta", "Hidden", "Приховано"),
    "hiddenCount" -> T("скрыто", "ukryte", "hidden", "приховано"),
    "spam" -> T("спам", "spam", "spam", "спам"),
    "personal" -> T("личные данные", "dane osobowe", "personal data", "особисті дані"),
    "offensive" -> T("оскорбительный", "obraźliwa", "offensive", "образливий"),
    "hide" -> T("Скрыть", "Ukryj", "Hide", "Приховати"),
    "show" -> T("Показать снова", "Pokaż ponownie", "Show again", "Показати знову"),
    "save" -> T("Ответить", "Odpowiedz", "Reply", "Відповісти"),
    "why" -> T("Скрыть можно только по причине из списка — не потому, что отзыв плохой. Скрытые не удаляются.",
      "Ukryć można tylko z powodu z listy — nie dlatego, że opinia jest negatywna. Ukryte nie są usuwane.",
      "A review is hidden only for a reason of the list — never because it is negative. Hidden ones are kept.",
      "Приховати можна лише з причини зі списку — не тому, що відгук поганий. Приховані не видаляються."),
    "ownerOnly" -> T("Отвечать и скрывать может только владелец.", "Odpowiadać i ukrywać może tylko właściciel.",
      "Only the owner replies and hides.", "Відповідати й приховувати може лише власник."),
    "none" -> T("Отзывов пока нет.", "Na razie brak opinii.", "No reviews yet.", "Відгуків поки немає."),
    "toWorkshop" -> T("← В мастерскую", "← Do panelu", "← Back to the workshop", "← До майстерні"),
    "failed" -> T("Не сохранено", "Nie zapisano", "Not saved", "Не збережено"))
  private def w(k: String)(using l: Lang): String = words.get(k).map(_(l)).getOrElse(k)

  /** ★★★★☆ for a rating (rounded) */
  def stars(r: Double): String =
    val n = math.round(r).toInt.max(0).min(5)
    "★" * n + "☆" * (5 - n)

  /** "12 opinii": the count with the word in the language's plural */
  def counted(n: Int)(using l: Lang): String =
    val few = n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14)
    val one = n % 10 == 1 && n % 100 != 11
    val word = l match
      case Lang.en => if n == 1 then "review" else "reviews"
      case Lang.pl => if n == 1 then "opinia" else if few then "opinie" else "opinii"
      case Lang.ru => if one then "отзыв" else if few then "отзыва" else "отзывов"
      case Lang.uk => if one then "відгук" else if few then "відгуки" else "відгуків"
    s"$n $word"

  // ---- THE CLIENT'S

  /** the form of a review: what it is of, the stars, a text, a name to sign with */
  def form(site: Site, l0: Lang, token: String, service: String, day: String, wrong: String = "",
           rating: String = "", text: String = "", name: String = ""): String =
    given l: Lang = l0
    val radios = (5 to 1 by -1).map(n => s"""<label class="star"><input type="radio" name="rating" value="$n"${
      if rating == n.toString then " checked" else ""} required> ${"★" * n}${"☆" * (5 - n)} <small>$n</small></label>"""
    ).mkString
    BookingPage.frame(site, l, w("title")) + s"""
<style>.star{display:block;font-size:20px;color:#e0a100;padding:4px 0;cursor:pointer}
.star small{color:#999;font-size:13px}
textarea,input[type=text]{font:inherit;width:100%;box-sizing:border-box;border:1px solid #d5c8b8;border-radius:10px;
padding:10px;margin:6px 0 12px}.wrong{color:#b42318;font-weight:600}</style>
<div class="card"><div class="status">${e(w("how"))}</div><div class="val">${e(service)}</div>
<div class="ref">${e(day)}</div></div>
<form class="card" method="post" action="/${l.code}/review"><input type="hidden" name="t" value="${e(token)}">
${if wrong.isEmpty then "" else s"""<p class="wrong" role="alert">${e(w("wrong." + wrong))}</p>"""}
<fieldset style="border:0;padding:0;margin:0"><legend class="label">${e(w("rating"))}</legend>$radios</fieldset>
<label class="label" for="text">${e(w("text"))}</label><textarea id="text" name="text" rows="5" maxlength="${
      Reviews.textMax}">${e(text)}</textarea>
<label class="label" for="name">${e(w("name"))}</label><input type="text" id="name" name="name" maxlength="${
      Reviews.nameMax * 2}" value="${e(name)}" autocomplete="name">
<p class="ref">${e(w("public"))}</p><button class="go" type="submit">${e(w("send"))}</button></form>
</div></body></html>
"""

  def thanks(site: Site, l0: Lang): String =
    given l: Lang = l0
    BookingPage.frame(site, l, w("title")) + s"""
<div class="card"><div class="status">${e(w("thanks"))}</div><p><a href="/${l.code}/">${e(w("back"))} →</a></p></div>
</div></body></html>
"""

  def gone(site: Site, l0: Lang): String =
    given l: Lang = l0
    BookingPage.frame(site, l, w("title")) + s"""
<div class="card"><p>${e(w("gone"))}</p><p><a href="/${l.code}/">${e(w("back"))} →</a></p></div>
</div></body></html>
"""

  // ---- THE STOREFRONT'S

  /** the storefront's block: nothing while nothing is shown (a shop without reviews: its page as before) */
  def block(site: Site)(using l: Lang): String = site.rated.filter(_.count > 0).fold("") { r =>
    val items = r.latest.map(x => s"""<div class="rv"><div class="rv-h"><span class="rv-s" aria-label="${x.rating}/5">${
      stars(x.rating)}</span> <b>${e(if x.name.isEmpty then w("client") else x.name)}</b> <small>${e(Day.of(x.at))}${
      site.offer(x.service).fold("")(o => " · " + e(o.name(l)))}</small></div>${
      if x.text.isEmpty then "" else s"""<p class="rv-t">${Html.lines(x.text)}</p>"""}${
      if x.reply.isEmpty then "" else s"""<p class="rv-r"><b>${e(w("reply"))}:</b> ${Html.lines(x.reply)}</p>"""
      }</div>""").mkString
    s"""
    <section class="reviews" id="reviews">
      <style>.rv-sum{font-size:20px}.rv-sum .rv-s,.rv-s{color:#e0a100;letter-spacing:1px}
      .rv{margin:14px 0;padding:12px 0;border-top:1px solid rgba(128,128,128,.25)}.rv-t{margin:6px 0 0}.rv-r{margin:8px 0 0 14px;opacity:.85}</style>
      <p class="label">${e(w("reviews"))}</p>
      <p class="body rv-sum"><span class="rv-s" aria-hidden="true">${stars(r.average)}</span> <b>${r.score}</b> / 5 · ${
      e(counted(r.count))}</p>
      <p class="offer-desc">${e(w("verified"))}</p>
      $items
    </section>"""
  }

  /** the structured data of the rating: only from `Reviews.structuredFrom` reviews on, else nothing */
  def structured(site: Site): String = site.rated.filter(_.count >= Reviews.structuredFrom).fold("") { r =>
    def s(x: String) = "\"" + Html.json(x) + "\""
    s"""<script type="application/ld+json">{"@context":"https://schema.org","@type":"LocalBusiness","@id":${
      s(site.origin + "/#business")},"name":${s(site.name)},"url":${s(site.origin + "/")},"aggregateRating":{""" +
      s""""@type":"AggregateRating","ratingValue":"${r.score}","reviewCount":${r.count},"bestRating":5,""" +
      s""""worstRating":1}}</script>"""
  }

  // ---- THE WORKSHOP'S

  private def langs(l: Lang): String = Lang.values.toVector.map(x => if x == l then s"<b>${x.code}</b>"
    else s"""<a href="?lang=${x.code}">${x.code}</a>""").mkString(" ")

  /** the screen: every review (newest first), the hidden ones marked; the owner's reply, hide and show */
  def desk(l0: Lang, c: Conf, all: Vector[Review], owner: Boolean): String =
    given l: Lang = l0
    val desk = c.desk.path
    val shown = all.filter(_.shown)
    val avg = if shown.isEmpty then "—" else Rated(shown.map(_.rating).sum.toDouble / shown.size, 0, 0, Vector.empty)
      .score
    val reasons = Reviews.reasons.map(x => s"""<option value="$x">${e(w(x))}</option>""").mkString
    def item(x: Review) = s"""<section class="panel${if x.shown then "" else " off"}"><h2><span class="st">${
      stars(x.rating)}</span> ${e(if x.name.isEmpty then w("client") else x.name)} <small>${e(Day.time(x.at))} · ${
      e(c.site.offer(x.service).fold(x.service)(_.name(l)))}${c.site.masters.find(_.key == x.master).fold("")(m =>
        " · " + e(m.name))} · ${e(x.ref)} · ${x.lang.code}</small></h2>${
      if x.shown then "" else s"""<p class="down"><b>${e(w("hidden"))}: ${e(w(x.hidden))}</b></p>"""}${
      if x.text.isEmpty then "" else s"<p>${Html.lines(x.text)}</p>"}${
      if x.reply.isEmpty then "" else s"""<p class="note"><b>${e(w("reply"))}</b> (${e(Day.time(x.replied))}): ${
        Html.lines(x.reply)}</p>"""}${if !owner then "" else s"""
<form class="f" data-id="${e(x.id)}" data-action="reply"><textarea name="value" rows="2" maxlength="${
        Reviews.replyMax}">${e(x.reply)}</textarea><button type="submit">${e(w("save"))}</button></form>${
        if x.shown then s"""<form class="f" data-id="${e(x.id)}" data-action="hide"><select name="value">$reasons
</select><button class="ghost" type="submit">${e(w("hide"))}</button></form>"""
        else s"""<form class="f" data-id="${e(x.id)}" data-action="show"><input type="hidden" name="value" value="">
<button class="ghost" type="submit">${e(w("show"))}</button></form>"""}"""}${
      if x.log.isEmpty then "" else s"""<p class="note">${x.log.map(g => e(s"${Day.time(g.at)} ${g.who} ${g.what}"))
        .mkString("<br>")}</p>"""}</section>"""
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>${e(w("reviews"))} · ${e(c.site.name)}</title><link rel="icon" href="data:,"><style>${ReportsPage.style}
.st{color:#e0a100}.off{opacity:.7}.f{display:flex;gap:8px;align-items:flex-start;margin:8px 0}.f textarea{flex:1}
</style></head><body>
<header><h1>${e(w("reviews"))} · ${e(c.site.name)}</h1><span class="langs">${langs(l)}</span>
<a href="$desk/">${e(w("toWorkshop"))}</a></header><main>
<p class="period"><span class="st">${if shown.isEmpty then "" else stars(shown.map(_.rating).sum.toDouble /
      shown.size)}</span> <b>$avg</b> · ${e(counted(shown.size))} · ${all.size - shown.size} ${e(w("hiddenCount"))}</p>
<p class="note">${e(w(if owner then "why" else "ownerOnly"))}</p><p id="said" role="status"></p>
${if all.isEmpty then s"""<section class="panel"><p>${e(w("none"))}</p></section>""" else all.map(item).mkString}
</main><script>(function(){Array.prototype.forEach.call(document.querySelectorAll('form[data-id]'),function(f){
f.addEventListener('submit',function(ev){ev.preventDefault();fetch('$desk/api/review',{method:'POST',
headers:{'content-type':'application/json'},body:JSON.stringify({id:f.getAttribute('data-id'),
action:f.getAttribute('data-action'),value:f.elements.value.value})}).then(function(r){return r.json().then(
function(j){if(r.ok)location.reload();else document.getElementById('said').textContent="${Html.json(w("failed"))}"+
': '+(j.error||r.status)})})})})})();</script></body></html>
"""
