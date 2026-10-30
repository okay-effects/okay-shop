package okay.shop.html

import okay.shop.effects.Note
import okay.shop.model.*

/** THE CONFIGURATION a site's program needs beyond its content: where it is answered from (the request's origin —
 * a preview's workers.dev, or the domain), the BLIK phone, the paczkomat, the Turnstile key, who may sign in; the
 * seller's data for invoices and the KSeF environment it is connected to (specs/shop.md "Invoices", "KSeF") */
final case class Conf(site: Site, origin: String, blik: String, paczkomat: String, sitekey: String,
                      staff: Set[String], contact: String, policy: String, calendarKey: String = "",
                      more: String => Vector[(String, String)] = _ => Vector.empty, desk: Desk = Desk(),
                      owner: String => Boolean = _ => true,
                      seller: Option[okay.shop.invoice.Seller] = None,
                      ksef: Option[okay.shop.invoice.KsefEnv] = None)

/** where a workshop's sign-in lives (specs/shop.md "The host's own workshop pages"): its path — its API under
 * `<path>/api/`, its page `<path>/` — and its session's cookie. A host may sign its own people in elsewhere */
final case class Desk(path: String = "/workshop", cookie: String = "__Host-ws")

/**
 * THE LETTERS, as busi wrote them (`orders.ssc`, `email.ssc`, `hub.ssc`), fixed: the client's confirmation shows
 * the ref the track page takes, and its link is to this site's track page with the order's key. Every value escaped
 */
object Letters:
  private def e(s: String) = Html.escape(s)
  private def page(accent: String, body: String) =
    s"<html><body style='font-family:sans-serif;color:#222;max-width:520px;margin:0 auto;padding:24px'>$body" +
      "</body></html>"
  /** the text of a letter: its HTML without tags */
  def text(html: String): String =
    // a link keeps its address in the text: a reader of the text alone can still follow it
    html.replaceAll("(?i)<a [^>]*href='([^']*)'[^>]*>([^<]*)</a>", "$2 $1").replaceAll("(?i)<br\\s*/?>", "\n")
      .replaceAll("(?i)</(p|h2|tr|div)>", "\n").replaceAll("<[^>]+>", "")
      .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
      .linesIterator.map(_.trim).filter(_.nonEmpty).mkString("\n")
  private def note(subject: String, html: String, replyTo: Option[String] = None) =
    Note(subject, html, text(html), replyTo)
  private def accent(site: Site) = if site.clothing then "#ff6f91" else site.color

  def trackLink(c: Conf, o: Order): String =
    s"${c.origin}/${o.lang.code}/track?ref=${okay.http.Query.encode(o.ref)}&k=${okay.http.Query.encode(o.key)}"

  private def handover(h: String) = h match
    case "self" => "клієнт принесе сам"
    case "visit" => "візит майстра"
    case "delivery" => "доставка"
    case _ => h

  /** to the owner, on each order: every field (in Ukrainian, as busi wrote it) */
  def owner(c: Conf, o: Order): Note =
    val site = c.site
    val item = site.offer(o.offerKey).map(_.name(Lang.pl)).getOrElse("Заявка з сайту")
    def row(k: String, v: String) = if v.isEmpty then "" else s"<p><b>${e(k)}:</b> ${Html.lines(v)}</p>"
    note(s"🧵 Нове замовлення ${o.ref} — ${site.name}", page(accent(site),
      s"<h2 style='color:${accent(site)}'>🧵 Нове замовлення — ${e(site.name)}</h2>" +
        row("Номер", o.ref) + row("Послуга", item) +
        row("Ціна", site.offer(o.offerKey).filter(_.priced).map(_.priceShort).getOrElse("")) +
        row("Клієнт", o.name) + row("Контакт", o.contact) + row("Деталі", o.need) + row("Тканина", o.fabric) +
        row("Передача", handover(o.handover)) + row("Доставка", o.deliveryMethod) + row("Оплата", o.paymentMethod) +
        row("Вартість речі", if o.itemValue.isEmpty then "" else o.itemValue + " zł") + row("Мова", o.lang.code) +
        row("Згода RODO", s"${Day.time(o.consent.at)} · політика ${o.consent.policy}") +
        s"<p><a href='${e(trackLink(c, o))}'>${e(trackLink(c, o))}</a></p>"),
      o.email)

  /** to the client whose contact is an email: the confirmation, in the order's language */
  def confirmation(c: Conf, o: Order): Note =
    val site = c.site
    val l = o.lang
    def w(ru: String, pl: String, en: String, uk: String) = T(ru, pl, en, uk)(l)
    val offer = site.offer(o.offerKey)
    val service = offer.map(_.name(l)).getOrElse(w("Заявка с сайта", "Zgłoszenie ze strony", "A request from the site",
      "Заявка з сайту"))
    val price = offer.filter(_.priced).map(_.priceShort)
    val blik = if c.blik.nonEmpty && site.clothing then
      "<p style='background:#f0faf0;border-radius:8px;padding:12px 16px;margin:16px 0'>" + (price match
        case Some(p) => s"<b>${e(w("Предоплата ", "Przedpłata ", "Prepayment ", "Передоплата "))}${e(p)}</b> — BLIK: " +
            s"<b>${e(c.blik)}</b>"
        case None => e(w(s"Оплата BLIK: ${c.blik} · сумму подтвердит мастер",
          s"Płatność BLIK: ${c.blik} · kwotę potwierdzi krawcowa",
          s"Pay via BLIK: ${c.blik} · amount confirmed by the seamstress",
          s"Оплата BLIK: ${c.blik} · суму підтвердить майстер"))) + "</p>"
      else ""
    val inpost = if c.paczkomat.nonEmpty && o.courier then
      "<p style='background:#f0f4ff;border-radius:8px;padding:12px 16px;margin:16px 0'>" +
        e(w("Отправьте через InPost — Paczkomat: ", "Wyślij przez InPost — Paczkomat: ", "Send via InPost — " +
            "Paczkomat: ",
          "Надішліть через InPost — Paczkomat: ")) + s"<b style='letter-spacing:.06em'>${e(c.paczkomat)}</b></p>"
      else ""
    val a = accent(site)
    def tr(k: String, v: String) =
      s"<tr><td style='padding:6px 0;color:#666'>${e(k)}</td><td style='padding:6px 0'>$v</td></tr>"
    note(w("Подтверждение заказа ", "Potwierdzenie zlecenia ", "Order confirmation ", "Підтвердження замовлення ") +
      s"${o.ref} — ${site.name}", page(a,
      s"<h2 style='color:$a'>${e(site.name)}</h2>" +
        s"<p>${e(w("Спасибо! Ваш заказ принят.", "Dziękujemy! Twoje zlecenie zostało przyjęte.",
          "Thank you! Your order has been received.", "Дякуємо! Ваше замовлення прийнято."))}</p>" +
        "<table style='border-collapse:collapse;width:100%;margin:16px 0'>" +
        tr(w("Номер заказа", "Numer zlecenia", "Order number", "Номер замовлення"), s"<b>${e(o.ref)}</b>") +
        tr(w("Услуга", "Usługa", "Service", "Послуга"), e(service)) +
        price.map(p => tr(w("Цена", "Cena", "Price", "Ціна"), e(p))).getOrElse("") + "</table>" + blik + inpost +
        s"<p style='margin-top:20px'><a href='${e(trackLink(c, o))}' style='background:$a;color:#fff;" +
        "padding:10px 20px;border-radius:8px;text-decoration:none;font-size:14px'>" +
        e(w("Проверить статус →", "Sprawdź status zlecenia →", "Check order status →", "Перевірити статус →")) +
        "</a></p>" + questions(c, l)),
      Some(c.contact))

  private def questions(c: Conf, l: Lang) =
    s"<p style='color:#888;font-size:13px;margin-top:24px'>${e(T("По вопросам пишите на ", "W razie pytań pisz na ",
      "For questions write to ", "З питань пишіть на ")(l))}<a href='mailto:${e(c.contact)}'>${e(c.contact)}</a></p>"

  /** to the client, when the order moved: ready, sent with its tracking number — busi's `jobNotificationHtml` */
  def moved(c: Conf, o: Order): Option[Note] =
    val l = o.lang
    def w(ru: String, pl: String, en: String, uk: String) = T(ru, pl, en, uk)(l)
    val site = c.site
    val trk = if o.tracking.nonEmpty then s"<br><b style='letter-spacing:.05em'>${e(o.tracking)}</b>" else ""
    val said: Option[(String, String)] = o.status match
      case Status.Ready if !site.clothing => Some((w("Готово — ", "Gotowe — ", "Ready — ", "Готово — "),
        e(w("Ваш заказ готов.", "Twoje zlecenie jest gotowe.", "Your order is ready.", "Ваше замовлення готове."))))
      case Status.Ready if !o.courier => Some((w("Готово! Можно забирать — ", "Gotowe! Możesz odebrać — ",
        "Ready! You can pick it up — ", "Готово! Можна забирати — "), e(w("Ваша вещь готова — можно забирать.",
        "Twoja rzecz jest gotowa — możesz ją odebrać.", "Your item is ready — you can pick it up.",
        "Ваша річ готова — можна забирати."))))
      case Status.Ready => Some((w("Готово! Отправляем — ", "Gotowe! Wysyłamy Twoją rzecz — ", "Ready! Shipping " +
          "your item — ",
        "Готово! Відправляємо — "), e(w("Ваша вещь готова — отправляем через InPost!",
        "Twoja rzecz jest gotowa — wysyłamy ją do Ciebie przez InPost!",
        "Your item is ready — we're shipping it back via InPost!", "Ваша річ готова — відправляємо через InPost!"))))
      case Status.ParcelOut => Some((w("Отправлено! Ваша вещь в пути — ", "Wysłano! Twoja rzecz jedzie do Ciebie — ",
        "Shipped! Your item is on its way — ", "Відправлено! Ваша річ їде до вас — "),
        e(w("Ваша вещь отправлена через InPost!", "Twoja rzecz została wysłana przez InPost!",
          "Your item has been shipped via InPost!", "Ваша річ відправлена через InPost!")) + trk +
          "<br><small style='color:#888'>" + e(w("Отслеживайте в приложении InPost.", "Śledź paczkę w aplikacji " +
              "InPost.",
          "Track your parcel in the InPost app.", "Відстежуйте посилку в InPost.")) + "</small>"))
      case _ => None
    said.map((subject, message) => notice(c, o, subject + site.name, message))

  /** to the client: the workshop's own words */
  def free(c: Conf, o: Order, subject: String, body: String): Note =
    notice(c, o, subject, Html.lines(body))

  private def notice(c: Conf, o: Order, subject: String, messageHtml: String): Note =
    val site = c.site
    val l = o.lang
    val a = accent(site)
    val item = site.offer(o.offerKey).map(_.name(l)).getOrElse("")
    note(subject, page(a, s"<h2 style='color:$a'>${e(site.name)}</h2><p style='font-size:15px'>$messageHtml</p>" +
      s"<p style='color:#888;font-size:13px;margin:4px 0 16px'>${e(item)} · ${e(o.ref)}</p>" +
      s"<p><a href='${e(trackLink(c, o))}' style='background:$a;color:#fff;padding:10px 20px;border-radius:8px;" +
      "text-decoration:none;font-size:14px'>" + e(T("Проверить статус →", "Sprawdź status zlecenia →",
      "Check order status →", "Перевірити статус →")(l)) + "</a></p>" + questions(c, l)), Some(c.contact))

  /** to the owner, on an inquiry of /products */
  def inquiry(c: Conf, i: Inquiry): Note =
    def row(k: String, v: String) = if v.isEmpty then "" else s"<p><b>${e(k)}:</b> ${Html.lines(v)}</p>"
    note(s"✉️ Новая заявка ${i.ref}${if i.confidential then " (конфиденциально)" else ""} — ${c.site.name}",
      page(c.site.color, s"<h2 style='color:${c.site.color}'>✉️ Новая заявка — ${e(c.site.name)}</h2>" +
        row("Номер", i.ref) + row("Имя", i.name) + row("Контакт", i.contact) + row("Тема", i.subject) +
        row("Вид", i.kind) + row("Текст", i.body) + row("Конфиденциально", if i.confidential then "да" else "") +
        row("Язык", i.lang.code) + row("Согласие RODO", s"${Day.time(i.consent.at)} · политика ${i.consent.policy}")),
      Contact.email(i.contact))

  /** to a staff member: the workshop's sign-in link */
  def signin(c: Conf, link: String): Note =
    note(s"Вхід до майстерні · ${c.site.name}", page(accent(c.site),
      s"<h2 style='color:${accent(c.site)}'>✂️ ${e(c.site.name)}</h2>" +
        "<p>Натисніть кнопку нижче, щоб увійти до майстерні (посилання діє 15 хвилин, один раз):</p>" +
        s"<p><a href='${e(link)}' style='display:inline-block;background:${accent(c.site)};color:#fff;" +
        "padding:12px 24px;border-radius:10px;text-decoration:none;font-size:15px;font-weight:600'>" +
        "Увійти до майстерні →</a></p>" +
        "<p style='color:#aaa;font-size:12px;margin-top:24px'>Якщо ви не робили цей запит — просто проігноруйте " +
            "лист.</p>"))

  // ---- BOOKINGS (specs/shop.md "Booking")

  def manageLink(c: Conf, b: Booking): String =
    s"${c.origin}/${b.lang.code}/booking?ref=${okay.http.Query.encode(b.ref)}&k=${okay.http.Query.encode(b.key)}"

  private def service(c: Conf, b: Booking, l: Lang) = c.site.offer(b.service).map(_.name(l)).getOrElse(b.service)

  /** a letter to the client of a booking: what happened, the time, the service, the link to manage it */
  private def bookingNote(c: Conf, b: Booking, subject: T, said: T): Note =
    val l = b.lang
    val a = accent(c.site)
    def w(t: T) = t(l)
    note(s"${w(subject)} — ${c.site.name}", page(a, s"<h2 style='color:$a'>${e(c.site.name)}</h2>" +
      s"<p>${e(w(said))}</p><table style='border-collapse:collapse;width:100%;margin:16px 0'>" +
      s"<tr><td style='padding:6px 0;color:#666'>${e(w(T("Когда", "Kiedy", "When", "Коли")))}</td>" +
      s"<td style='padding:6px 0'><b>${e(Day.time(b.start))}</b> (${b.minutes} min)</td></tr>" +
      s"<tr><td style='padding:6px 0;color:#666'>${e(w(T("Услуга", "Usługa", "Service", "Послуга")))}</td>" +
      s"<td style='padding:6px 0'>${e(service(c, b, l))}</td></tr>" +
      where(c, b, l).map((k, v) => s"<tr><td style='padding:6px 0;color:#666'>${e(k)}</td>" +
        s"<td style='padding:6px 0'>$v</td></tr>").mkString +
      s"<tr><td style='padding:6px 0;color:#666'>№</td><td style='padding:6px 0'>${e(b.ref)}</td></tr></table>" +
      (if b.active then s"<p><a href='${e(manageLink(c, b))}' style='background:$a;color:#fff;padding:10px 20px;" +
        "border-radius:8px;text-decoration:none;font-size:14px'>" + e(w(T("Изменить или отменить →",
        "Zmień lub odwołaj →", "Change or cancel →", "Змінити або скасувати →"))) + "</a></p>" else "") +
      questions(c, l)), Some(c.contact))

  /** how and where a booking meets, for its letters and its page (none before the modes): the mode, the address
   * with its map link, its notes, the online call's details, the price — each a label and its HTML */
  def where(c: Conf, b: Booking, l: Lang): Vector[(String, String)] =
    master(c, b).toVector.map(n => T("Мастер", "Specjalista", "Master", "Майстер")(l) -> e(n)) ++ how(c, b, l)

  /** the name of a booking's master, when the shop has masters (specs/shop.md "Masters") */
  def master(c: Conf, b: Booking): Option[String] = c.site.masters.find(_.key == b.master).map(_.name)

  private def how(c: Conf, b: Booking, l: Lang): Vector[(String, String)] = b.mode.fold(Vector.empty) { m =>
    def w(t: T) = t(l)
    val online = c.site.work.flatMap(_.online).filter(_ => m == Mode.Online)
    Vector(w(T("Как", "Jak", "How", "Як")) -> e(m.label(l) + online.fold("")(o => " · " + (if o.how == "phone"
      then w(T("по телефону", "telefonicznie", "by phone", "телефоном")) else w(T("видеозвонок", "rozmowa wideo",
      "video call", "відеодзвінок")))))) ++
      b.address.map(a => w(T("Где", "Gdzie", "Where", "Де")) -> (s"<a href='${e(a.mapLink)}'>${e(a.line)}</a>" +
        (if a.notes.isEmpty then "" else s"<br><small>${e(a.notes)}</small>"))) ++
      online.filter(_.details.nonEmpty).map(o => w(T("Связь", "Połączenie", "The call", "Зв'язок")) ->
        Html.lines(o.details)) ++
      Option.when(b.price > 0)(w(T("Цена", "Cena", "Price", "Ціна")) -> e(Money.zl(b.price)))
  }

  def booked(c: Conf, b: Booking): Note = bookingNote(c, b,
    T("Запись подтверждена", "Rezerwacja potwierdzona", "Booking confirmed", "Запис підтверджено"),
    T("Спасибо! Ждём вас.", "Dziękujemy! Czekamy na Ciebie.", "Thank you! See you then.", "Дякуємо! Чекаємо на вас."))
  def rebooked(c: Conf, b: Booking): Note = bookingNote(c, b,
    T("Запись перенесена", "Rezerwacja przeniesiona", "Booking moved", "Запис перенесено"),
    T("Новое время вашей записи:", "Nowy termin Twojej rezerwacji:", "Your booking's new time:",
      "Новий час вашого запису:"))
  def unbooked(c: Conf, b: Booking): Note = bookingNote(c, b,
    T("Запись отменена", "Rezerwacja odwołana", "Booking cancelled", "Запис скасовано"),
    T("Ваша запись отменена.", "Twoja rezerwacja została odwołana.", "Your booking is cancelled.",
      "Ваш запис скасовано."))
  def reminder(c: Conf, b: Booking): Note = bookingNote(c, b,
    T("Напоминание: завтра", "Przypomnienie: jutro", "A reminder: tomorrow", "Нагадування: завтра"),
    T("Напоминаем о вашей записи:", "Przypominamy o Twojej rezerwacji:", "A reminder of your booking:",
      "Нагадуємо про ваш запис:"))

  /** to the owner: a booking taken, moved, cancelled (in Ukrainian, as the owner's letters are) */
  private def ownerNote(c: Conf, b: Booking, what: String): Note =
    def row(k: String, v: String) = if v.isEmpty then "" else s"<p><b>${e(k)}:</b> ${Html.lines(v)}</p>"
    note(s"📅 $what ${b.ref}: ${Day.time(b.start)} — ${c.site.name}", page(accent(c.site),
      s"<h2 style='color:${accent(c.site)}'>📅 $what — ${e(c.site.name)}</h2>" + row("Коли", Day.time(b.start)) +
        row("Послуга", service(c, b, Lang.uk)) + master(c, b).fold("")(row("Майстер", _)) +
        b.mode.fold("")(m => row("Як", m.label(Lang.uk))) +
        b.address.fold("")(a => row("Адреса", a.line) + row("Примітки", a.notes) +
          s"<p><a href='${e(a.mapLink)}'>${e(a.mapLink)}</a></p>") +
        (if b.price > 0 then row("Ціна", Money.zl(b.price)) else "") +
        row("Клієнт", b.name) + row("Контакт", b.contact) +
        row("Номер", b.ref) + row("Згода RODO", s"${Day.time(b.consent.at)} · політика ${b.consent.policy}")),
      b.email)
  def bookedOwner(c: Conf, b: Booking): Note = ownerNote(c, b, "Новий запис")
  def rebookedOwner(c: Conf, b: Booking): Note = ownerNote(c, b, "Запис перенесено")
  def unbookedOwner(c: Conf, b: Booking): Note = ownerNote(c, b, "Запис скасовано")

  // ---- QUOTES (specs/shop.md "Inquiry and quote")

  private def quoteNote(c: Conf, q: Quote, subject: T, said: T, more: String): Note =
    val l = q.lang
    val a = accent(c.site)
    val service = c.site.offer(q.service).map(_.name(l)).getOrElse(q.service)
    note(s"${subject(l)} ${q.ref} — ${c.site.name}", page(a, s"<h2 style='color:$a'>${e(c.site.name)}</h2>" +
      s"<p>${e(said(l))}</p><p style='color:#666'>${e(service)} · ${e(q.ref)}</p>" + more +
      s"<p><a href='${e(okay.shop.app.Quotes.manageLink(c, q))}' style='background:$a;color:#fff;padding:10px 20px;" +
      "border-radius:8px;text-decoration:none;font-size:14px'>" + e(T("Открыть запрос →", "Otwórz zapytanie →",
      "Open the inquiry →", "Відкрити запит →")(l)) + "</a></p>" + questions(c, l)), Some(c.contact))

  /** to the client: the inquiry taken, the link to follow it */
  def quoteAsked(c: Conf, q: Quote): Note = quoteNote(c, q, T("Запрос принят", "Zapytanie przyjęte",
    "Inquiry received", "Запит прийнято"), T("Спасибо! Оценим работу и ответим с ценой.",
    "Dziękujemy! Wycenimy pracę i odpiszemy z ceną.", "Thank you! We will price the job and reply.",
    "Дякуємо! Оцінимо роботу й відповімо з ціною."), s"<p>${Html.lines(q.description)}</p>")

  /** to the client: the shop's quote — the price or range, the time proposed, the note — to accept or decline */
  def quoted(c: Conf, q: Quote): Note =
    val l = q.lang
    def row(k: T, v: String) = if v.isEmpty then "" else s"<p><b>${e(k(l))}:</b> ${Html.lines(v)}</p>"
    quoteNote(c, q, T("Оценка", "Wycena", "Quote", "Оцінка"), T("Мы оценили вашу работу. Примите или отклоните по " +
      "ссылке.", "Wyceniliśmy Twoją pracę. Przyjmij lub odrzuć pod linkiem.", "We have priced your job. Accept " +
      "or decline by the link.", "Ми оцінили вашу роботу. Прийміть або відхиліть за посиланням."),
      row(T("Цена", "Cena", "Price", "Ціна"), q.priced) + row(T("Когда", "Kiedy", "When", "Коли"),
        if q.start > 0 then Day.time(q.start) else "") + row(T("Комментарий", "Uwagi", "Note", "Коментар"), q.note))

  /** to the owner: an inquiry asked, accepted or declined (in Ukrainian, as the owner's letters are) */
  def quoteOwner(c: Conf, q: Quote, what: String): Note =
    def row(k: String, v: String) = if v.isEmpty then "" else s"<p><b>${e(k)}:</b> ${Html.lines(v)}</p>"
    note(s"💬 $what ${q.ref} — ${c.site.name}", page(accent(c.site),
      s"<h2 style='color:${accent(c.site)}'>💬 $what — ${e(c.site.name)}</h2>" + row("Номер", q.ref) +
        row("Послуга", c.site.offer(q.service).map(_.name(Lang.uk)).getOrElse(q.service)) + row("Опис", q.description) +
        row("Бажані дати", q.preferred) + q.mode.fold("")(m => row("Як", m.label(Lang.uk))) +
        q.address.fold("")(a => row("Адреса", a.line) + row("Примітки", a.notes)) + row("Ціна", q.priced) +
        row("Клієнт", q.name) + row("Контакт", q.contact) +
        row("Згода RODO", s"${Day.time(q.consent.at)} · політика ${q.consent.policy}")), q.email)

  // ---- REVIEWS (specs/shop.md "Reviews")

  /** to the client after a visit or an order done: "how was it?", the one-use link to the review, in their language */
  def reviewAsk(c: Conf, l: Lang, service: String, link: String): Note =
    val a = accent(c.site)
    def w(t: T) = t(l)
    note(s"${w(T("Как всё прошло?", "Jak było?", "How was it?", "Як усе пройшло?"))} — ${c.site.name}",
      page(a, s"<h2 style='color:$a'>${e(c.site.name)}</h2>" +
        s"<p>${e(w(T("Спасибо, что выбрали нас! Как вам ", "Dziękujemy, że nas wybrałeś! Jak oceniasz: ",
          "Thank you for choosing us! How was ", "Дякуємо, що обрали нас! Як вам ")))}<b>${e(service)}</b>?</p>" +
        s"<p>${e(w(T("Оцените нас — это займёт минуту. Отзыв увидят другие клиенты.",
          "Oceń nas — to zajmie minutę. Opinię zobaczą inni klienci.",
          "Rate us — it takes a minute. Other clients will see your review.",
          "Оцініть нас — це займе хвилину. Відгук побачать інші клієнти.")))}</p>" +
        s"<p><a href='${e(link)}' style='background:$a;color:#fff;padding:10px 20px;border-radius:8px;" +
        "text-decoration:none;font-size:14px'>" + e(w(T("★ Оставить отзыв →", "★ Wystaw opinię →",
          "★ Write a review →", "★ Залишити відгук →"))) + "</a></p>" +
        s"<p style='color:#888;font-size:13px'>${e(w(T("Ссылка одноразовая, действует 60 дней.",
          "Link jest jednorazowy i ważny 60 dni.", "The link works once, for 60 days.",
          "Посилання одноразове, діє 60 днів.")))}</p>" + questions(c, l)), Some(c.contact))
