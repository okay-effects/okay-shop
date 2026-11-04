package okay.shop.page

import org.scalajs.dom
import org.scalajs.dom.HTMLElement

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSON

/**
 * THE ASSISTANT'S WIDGET (specs/shop.md "The assistant"), only where the page carries its marker (`#okay-chat`): a
 * button, a dialog with the conversation; each answer's values as chips (the value; its entity and source on hover
 * and focus), a slot's chip opening its booking card, a shop's card a link; "delete this conversation"; "what the
 * model wrote" hidden by default. In the page's language (pl, uk, ru, en); keyboard, Escape, announced
 */
object Chat:
  private final case class Words(placeholder: String, send: String, forget: String,
                                 forgotten: String, raw: String, close: String, name: String, contact: String,
                                 consent: String, privacy: String, book: String, go: String, moment: String,
                                 failed: String, hello: String, flagged: String, source: String,
                                 computed: String)

  private def words(l: String): Words = l match
    case "uk" => Words("Ваше запитання…", "Надіслати", "Видалити розмову",
      "Розмову видалено.", "Що написала модель", "Закрити", "Ім’я", "Телефон або e-mail",
      "Погоджуюся з політикою конфіденційності", "політика", "Записатися", "Перейти", "Зачекайте…",
      "Не вдалося. Спробуйте ще раз.", "Вітаю! Запитайте про послуги, ціни чи вільний час.",
      "Перевірте: відповідь позначено", "джерело", "обчислено з даних")
    case "ru" => Words("Ваш вопрос…", "Отправить", "Удалить разговор",
      "Разговор удалён.", "Что написала модель", "Закрыть", "Имя", "Телефон или e-mail",
      "Согласен с политикой конфиденциальности", "политика", "Записаться", "Перейти", "Подождите…",
      "Не удалось. Попробуйте ещё раз.", "Здравствуйте! Спросите об услугах, ценах или свободном времени.",
      "Проверьте: ответ помечен", "источник", "вычислено из данных")
    case "en" => Words("Your question…", "Send", "Delete this conversation",
      "The conversation was deleted.", "What the model wrote", "Close", "Name", "Phone or e-mail",
      "I accept the privacy policy", "policy", "Book", "Go", "One moment…", "That did not work. Please try again.",
      "Hello! Ask about the services, prices or free times.", "Please check: this answer is flagged", "source",
      "computed from the data")
    case _ => Words("Twoje pytanie…", "Wyślij", "Usuń rozmowę",
      "Rozmowa usunięta.", "Co napisał model", "Zamknij", "Imię", "Telefon lub e-mail",
      "Akceptuję politykę prywatności", "polityka", "Zarezerwuj", "Przejdź", "Chwileczkę…",
      "Nie udało się. Spróbuj ponownie.", "Dzień dobry! Zapytaj o usługi, ceny lub wolne terminy.",
      "Sprawdź: odpowiedź oznaczona", "źródło", "obliczone z danych")

  private val style = """
#okc-btn{position:fixed;right:18px;bottom:18px;z-index:2147483000;border:0;border-radius:999px;padding:12px 18px;
font:600 15px/1.2 system-ui,sans-serif;background:#1f2937;color:#fff;box-shadow:0 6px 24px rgba(0,0,0,.25);
cursor:pointer}
#okc-btn:focus-visible,#okc-panel button:focus-visible,#okc-panel a:focus-visible,#okc-panel input:focus-visible{
outline:3px solid #f59e0b;outline-offset:2px}
#okc-panel{position:fixed;right:18px;bottom:76px;z-index:2147483001;width:min(390px,calc(100vw - 24px));
height:min(620px,calc(100vh - 110px));display:flex;flex-direction:column;background:#fff;color:#111827;
border-radius:16px;box-shadow:0 12px 40px rgba(0,0,0,.3);font:15px/1.45 system-ui,sans-serif;overflow:hidden}
#okc-panel[hidden]{display:none}
.okc-head{display:flex;align-items:center;gap:8px;padding:10px 12px;background:#1f2937;color:#fff}
.okc-head h2{flex:1;margin:0;font-size:16px}
.okc-head button{background:transparent;color:#fff;border:1px solid rgba(255,255,255,.4);border-radius:8px;
padding:4px 8px;font-size:13px;cursor:pointer}
.okc-ai{margin:0;padding:8px 12px;font-size:12.5px;line-height:1.4;color:#374151;background:#fef3c7;
border-bottom:1px solid #fde68a}
.okc-ai a{color:#1d4ed8}
.okc-log{flex:1;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:8px}
.okc-msg{max-width:88%;padding:8px 11px;border-radius:12px;white-space:pre-wrap;word-wrap:break-word}
.okc-me{align-self:flex-end;background:#e0e7ff}
.okc-bot{align-self:flex-start;background:#f3f4f6}
.okc-note{align-self:center;font-size:13px;color:#6b7280}
.okc-chip{display:inline;padding:1px 6px;border-radius:6px;background:#fff;border:1px solid #d1d5db;font-weight:600;
color:inherit;font:inherit;font-weight:600;cursor:help}
button.okc-chip{cursor:pointer;border-color:#2563eb;color:#1d4ed8}
.okc-choices{display:flex;gap:6px;flex-wrap:wrap;margin-top:6px}
.okc-choice{border:1px solid #2563eb;color:#1d4ed8;background:#fff;border-radius:14px;padding:4px 10px;font:inherit;
cursor:pointer}
.okc-raw{display:none;margin-top:6px;font:12px/1.4 ui-monospace,monospace;color:#374151;background:#fff;
border:1px dashed #9ca3af;border-radius:6px;padding:6px;white-space:pre-wrap}
.okc-showraw .okc-raw{display:block}
.okc-card{border:1px solid #d1d5db;border-radius:10px;padding:10px;background:#fff;display:flex;
flex-direction:column;gap:6px}
.okc-card input[type=text]{border:1px solid #9ca3af;border-radius:8px;padding:7px;font:inherit}
.okc-card .okc-go,.okc-form button{align-self:flex-start;border:0;border-radius:8px;padding:8px 14px;
background:#2563eb;color:#fff;font:inherit;font-weight:600;cursor:pointer;text-decoration:none}
.okc-form{display:flex;gap:6px;padding:10px;border-top:1px solid #e5e7eb}
.okc-form input{flex:1;border:1px solid #9ca3af;border-radius:8px;padding:9px;font:inherit}
.okc-foot{padding:0 12px 8px;font-size:12px}
.okc-foot button{background:none;border:0;color:#6b7280;text-decoration:underline;cursor:pointer;font:inherit}
.okc-sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}
@media (max-width:600px){#okc-panel{right:0;bottom:0;width:100vw;height:100vh;border-radius:0}}
"""

  def start(marker: HTMLElement): Unit =
    val doc = dom.document
    val endpoint = marker.getAttribute("data-endpoint")
    val focus = marker.getAttribute("data-focus")
    val lang = Option(doc.documentElement.getAttribute("lang")).getOrElse("pl").take(2)
    val w = words(lang)
    // it says it is an AI (specs/shop.md "Told it is an AI"): the launcher, the title, the notice before the talk
    val ai = okay.shop.html.ChatWords.disclosure(okay.shop.model.Lang.parse(lang).getOrElse(okay.shop.model.Lang.pl))
    val privacyAt = Option(marker.getAttribute("data-privacy")).filter(_.nonEmpty).getOrElse(s"/$lang/privacy/#chat")
    val css = doc.createElement("style")
    css.textContent = style
    doc.head.appendChild(css)

    def el(tag: String, cls: String = "", text: String = ""): HTMLElement =
      val e = doc.createElement(tag).asInstanceOf[HTMLElement]
      if cls.nonEmpty then e.className = cls
      if text.nonEmpty then e.textContent = text
      e

    val button = el("button", "", ai.label)
    button.id = "okc-btn"
    button.setAttribute("aria-haspopup", "dialog")
    button.setAttribute("aria-expanded", "false")
    val panel = el("div")
    panel.id = "okc-panel"
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", ai.label)
    panel.setAttribute("aria-describedby", "okc-ai")
    panel.setAttribute("hidden", "")
    val head = el("div", "okc-head")
    head.appendChild(el("h2", "", ai.label))
    val forget = el("button", "", w.forget)
    val close = el("button", "", "×")
    close.setAttribute("aria-label", w.close)
    head.appendChild(forget)
    head.appendChild(close)
    // above the conversation, so deleting it keeps the notice
    val notice = el("p", "okc-ai", ai.notice + " ")
    notice.id = "okc-ai"
    notice.setAttribute("role", "note")
    val more = el("a", "", ai.privacy).asInstanceOf[dom.HTMLAnchorElement]
    more.href = privacyAt
    more.target = "_blank"
    more.rel = "noopener"
    notice.appendChild(more)
    val log = el("div", "okc-log")
    log.setAttribute("role", "log")
    log.setAttribute("aria-live", "polite")
    val form = el("form", "okc-form")
    val label = el("label", "okc-sr", w.placeholder)
    label.setAttribute("for", "okc-input")
    val input = el("input").asInstanceOf[dom.HTMLInputElement]
    input.id = "okc-input"
    input.`type` = "text"
    input.placeholder = w.placeholder
    input.maxLength = 600
    input.setAttribute("autocomplete", "off")
    val send = el("button", "", w.send)
    send.setAttribute("type", "submit")
    form.appendChild(label)
    form.appendChild(input)
    form.appendChild(send)
    val foot = el("div", "okc-foot")
    val rawToggle = el("button", "", w.raw)
    rawToggle.setAttribute("aria-pressed", "false")
    foot.appendChild(rawToggle)
    Seq(head, notice, log, form, foot).foreach(panel.appendChild)
    doc.body.appendChild(button)
    doc.body.appendChild(panel)

    var loaded = false
    /** THE CONVERSATION'S OBJECT WARMED before the first message (specs/shop.md "The assistant"): once the page is idle
     * and visible, at most once a minute in a session — the host answers with an id kept here only (no cookie: none
     * until the visitor chats) and its object warm; the first open or message brings it back */
    var warm: Option[String] = None
    /** the last question asked, in the user's words (a choice asks them again) */
    var lastAsked = ""
    def warmUp(): Unit =
      val now = js.Date.now()
      val last = try Option(dom.window.sessionStorage.getItem("okc-warm")).flatMap(_.toDoubleOption).getOrElse(0.0)
        catch case _: Throwable => 0.0
      if !loaded && doc.visibilityState.toString == "visible" && now - last > 60000 then
        try dom.window.sessionStorage.setItem("okc-warm", now.toString) catch case _: Throwable => ()
        call("/warm", js.Dynamic.literal()).foreach(r =>
          if !js.isUndefined(r.selectDynamic("warm")) then warm = Some(r.warm.toString))
    def scroll(): Unit = log.scrollTop = log.scrollHeight.toDouble
    def note(text: String): Unit = { log.appendChild(el("div", "okc-msg okc-note", text)); scroll() }
    def mine(text: String): Unit = { log.appendChild(el("div", "okc-msg okc-me", text)); scroll() }

    def call(path: String, body: js.Any): scala.concurrent.Future[js.Dynamic] =
      val init = js.Dynamic.literal(method = if body == null then "GET" else "POST",
        headers = js.Dictionary("content-type" -> "application/json"), credentials = "same-origin",
        body = if body == null then js.undefined else JSON.stringify(body))
      dom.fetch(endpoint + path, init.asInstanceOf[dom.RequestInit]).toFuture.flatMap(r =>
        r.json().toFuture.map(j => { j.asInstanceOf[js.Dynamic].updateDynamic("_status")(r.status); j
          .asInstanceOf[js.Dynamic] }))

    def chipOf(c: js.Dynamic, text: String): HTMLElement =
      val link = if js.isUndefined(c.selectDynamic("link")) then "" else c.link.asInstanceOf[String]
      val sources = c.sources.asInstanceOf[js.Array[js.Dynamic]].map(s =>
        s"${s.source}/${s.key}.${s.property} v${s.version}").mkString(", ")
      val entity = c.entity.asInstanceOf[String]
      // a value computed (a count, a comparison): what it is of, its sources when it read any
      val tip = (if entity.isEmpty then w.computed else entity) +
        (if sources.isEmpty then "" else s" · ${w.source}: $sources")
      val chip =
        if link.startsWith("book:") then
          val b = el("button", "okc-chip", text)
          b.setAttribute("type", "button")
          b.addEventListener("click", (_: dom.Event) => card(link.stripPrefix("book:")))
          b
        else if link.startsWith("https://") || link.startsWith("/") then
          val a = el("a", "okc-chip", text).asInstanceOf[dom.HTMLAnchorElement]
          a.href = link
          a
        else el("span", "okc-chip", text)
      chip.title = tip
      chip.setAttribute("tabindex", "0")
      chip.setAttribute("aria-label", s"$text — $tip")
      chip

    /** a question sent: its words, the intent a choice asked for, what the conversation shows of it */
    def ask(text: String, intent: Option[String], shown: String): Unit =
      lastAsked = text
      mine(shown)
      send.setAttribute("disabled", "")
      val waiting = el("div", "okc-msg okc-note", w.moment)
      log.appendChild(waiting)
      scroll()
      val body = js.Dynamic.literal(text = text, focus = focus, lang = lang)
      intent.foreach(i => body.updateDynamic("intent")(i))
      warm.foreach(x => body.updateDynamic("warm")(x))
      warm = None
      call("", body).recover { case _ =>
        js.Dynamic.literal(notice = w.failed) }.foreach { r =>
        waiting.remove()
        send.removeAttribute("disabled")
        answer(r)
        input.focus()
      }

    def answer(a: js.Dynamic): Unit =
      val box = el("div", "okc-msg okc-bot")
      if !js.isUndefined(a.selectDynamic("answer")) && a.answer != null then
        val chips = a.answer.chips.asInstanceOf[js.Array[js.Dynamic]].map(c => c.param.asInstanceOf[String] -> c).toMap
        a.answer.segments.asInstanceOf[js.Array[js.Dynamic]].foreach { s =>
          if js.isUndefined(s.selectDynamic("param")) then
            box.appendChild(doc.createTextNode(s.text.asInstanceOf[String]))
          else
            val text = s.text.asInstanceOf[String]
            chips.get(s.param.asInstanceOf[String]) match
              case Some(c) => box.appendChild(chipOf(c, text))
              case None => box.appendChild(doc.createTextNode(text))
        }
        if a.flagged.asInstanceOf[Any] == true then box.appendChild(el("div", "okc-note", w.flagged))
        // the lane that answered, when the host says it (the router — no model —, or the model): in the debug view only
        val lane = if js.isUndefined(a.selectDynamic("lane")) then "" else s"${a.lane} · "
        val raw = el("div", "okc-raw", lane + JSON.stringify(a.answer.raw,
          null: js.Function2[String, js.Any, js.Any], 1))
        box.appendChild(raw)
      else box.textContent = if js.isUndefined(a.selectDynamic("notice")) then w.failed else a.notice.toString
      // a question asked back (round 4): its answers as buttons — a click asks the same words for the one chosen
      if !js.isUndefined(a.selectDynamic("choices")) && a.choices != null then
        val row = el("div", "okc-choices")
        a.choices.asInstanceOf[js.Array[js.Dynamic]].foreach { c =>
          val b = el("button", "okc-choice", c.label.asInstanceOf[String])
          b.setAttribute("type", "button")
          val asked = lastAsked
          b.addEventListener("click", (_: dom.Event) => { row.remove(); ask(asked, Some(c.intent.asInstanceOf[String]),
            c.label.asInstanceOf[String]) })
          row.appendChild(b)
        }
        box.appendChild(row)
      log.appendChild(box)
      if !js.isUndefined(a.selectDynamic("cards")) then
        a.cards.asInstanceOf[js.Array[js.Dynamic]].foreach(showCard)
      scroll()

    def showCard(c: js.Dynamic): Unit =
      val box = el("div", "okc-card")
      box.appendChild(el("strong", "", c.title.asInstanceOf[String]))
      if c.kind.asInstanceOf[String] == "open" then
        val a = el("a", "okc-go", w.go).asInstanceOf[dom.HTMLAnchorElement]
        a.href = c.url.asInstanceOf[String]
        box.appendChild(a)
      else
        c.lines.asInstanceOf[js.Array[String]].foreach(l => box.appendChild(el("div", "", l)))
        val form = el("form")
        form.style.display = "flex"
        form.style.setProperty("flex-direction", "column")
        form.style.setProperty("gap", "6px")
        def field(label: String, kind: String): dom.HTMLInputElement =
          val l = el("label", "", label)
          val i = el("input").asInstanceOf[dom.HTMLInputElement]
          i.`type` = kind
          i.required = true
          i.maxLength = 120
          l.appendChild(el("br"))
          l.appendChild(i)
          form.appendChild(l)
          i
        val name = field(w.name, "text")
        name.setAttribute("autocomplete", "name")
        val contact = field(w.contact, "text")
        contact.setAttribute("autocomplete", "tel")
        val consentLabel = el("label")
        val consent = el("input").asInstanceOf[dom.HTMLInputElement]
        consent.`type` = "checkbox"
        consent.required = true
        consentLabel.appendChild(consent)
        consentLabel.appendChild(doc.createTextNode(" " + w.consent + " ("))
        val policy = el("a", "", w.privacy).asInstanceOf[dom.HTMLAnchorElement]
        policy.href = s"/$lang/privacy/"
        policy.target = "_blank"
        consentLabel.appendChild(policy)
        consentLabel.appendChild(doc.createTextNode(")"))
        form.appendChild(consentLabel)
        val go = el("button", "okc-go", w.book)
        go.setAttribute("type", "submit")
        form.appendChild(go)
        form.addEventListener("submit", (e: dom.Event) => {
          e.preventDefault()
          go.setAttribute("disabled", "")
          note(w.moment)
          call("/book", js.Dynamic.literal(token = c.token, name = name.value, contact = contact.value,
            consent = consent.checked, lang = lang)).foreach { r =>
            if r.ok.asInstanceOf[Any] == true then { box.remove(); answer(r) }
            else { go.removeAttribute("disabled"); note(if js.isUndefined(r.selectDynamic("message")) then w.failed
              else r.message.toString) }
          }
        })
        box.appendChild(form)
      log.appendChild(box)
      scroll()

    def card(handle: String): Unit =
      call("/card", js.Dynamic.literal(handle = handle, lang = lang)).foreach(r =>
        if !js.isUndefined(r.selectDynamic("card")) then showCard(r.card)
        else note(if js.isUndefined(r.selectDynamic("message")) then w.failed else r.message.toString))

    def history(): Unit =
      loaded = true
      call(s"/history?lang=$lang${warm.fold("")("&warm=" + _)}", null).foreach { r =>
        warm = None
        val turns = if js.isUndefined(r.selectDynamic("turns")) then js.Array[js.Dynamic]()
          else r.turns.asInstanceOf[js.Array[js.Dynamic]]
        if turns.isEmpty then note(w.hello)
        turns.foreach(t => if t.user != null && !js.isUndefined(t.user) then mine(t.user.toString) else answer(t))
      }

    def toggle(open: Boolean): Unit =
      if open then
        panel.removeAttribute("hidden")
        button.setAttribute("aria-expanded", "true")
        if !loaded then history()
        input.focus()
      else
        panel.setAttribute("hidden", "")
        button.setAttribute("aria-expanded", "false")
        button.focus()

    button.addEventListener("click", (_: dom.Event) => toggle(panel.hasAttribute("hidden")))
    close.addEventListener("click", (_: dom.Event) => toggle(false))
    panel.addEventListener("keydown", (e: dom.KeyboardEvent) => if e.key == "Escape" then toggle(false))
    rawToggle.addEventListener("click", (_: dom.Event) =>
      val on = !panel.classList.contains("okc-showraw")
      panel.classList.toggle("okc-showraw")
      rawToggle.setAttribute("aria-pressed", on.toString))
    forget.addEventListener("click", (_: dom.Event) =>
      call("/forget", js.Dynamic.literal()).foreach { _ =>
        log.innerHTML = ""
        note(w.forgotten)
      })
    form.addEventListener("submit", (e: dom.Event) => {
      e.preventDefault()
      val text = input.value.trim
      if text.nonEmpty then
        input.value = ""
        ask(text, None, text)
    })
    // the warming when the page is idle (a browser without `requestIdleCallback`: 1.5 s after the start)
    // (the window's property: a browser without it — Safari — gets undefined, never a ReferenceError)
    val dw = dom.window.asInstanceOf[js.Dynamic]
    if js.typeOf(dw.selectDynamic("requestIdleCallback")) == "function" then
      dw.requestIdleCallback((_: js.Any) => warmUp(), js.Dynamic.literal(timeout = 3000))
    else js.timers.setTimeout(1500)(warmUp())
