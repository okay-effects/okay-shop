package okay.shop.html

import okay.shop.model.*

/** where a page is: its site, its language, its path after the language (`""`, `offer/hem/`, `privacy/`), and the
 * Turnstile site key its forms carry */
final case class At(site: Site, lang: Lang, path: String, sitekey: String):
  def url(l: Lang): String = s"${site.origin}/${l.code}/$path"

/**
 * THE PAGES, as busi's storefront drew them (`storefront.ssc`): the same markup and CSS, each language's text
 * rendered in rather than swapped by a script. The language switch is links; the forms are posted by the pages'
 * script (`site.js`), with Turnstile. Pure: an `At` in, the page's HTML out
 */
object Pages:
  /** a text of the page's language, escaped */
  def t(ru: String, pl: String, en: String, uk: String)(using l: Lang): String =
    Html.escape(T(ru, pl, en, uk)(l))
  def tt(x: T)(using l: Lang): String = Html.escape(x(l))
  def e(s: String): String = Html.escape(s)

  /** the head: language, title, description, the alternates of the four languages, the style */
  def head(at: At, title: String, description: String, style: String, extra: String = ""): String =
    val alternates = Lang.values.map(l => s"""<link rel="alternate" hreflang="${l.code}" href="${at.url(l)}">""")
      .mkString("\n") + s"""\n<link rel="alternate" hreflang="x-default" href="${at.url(at.site.lang)}">"""
    s"""<!doctype html><html lang="${at.lang.code}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${e(title)}</title>
<meta name="description" content="${e(description)}">
<meta property="og:title" content="${e(title)}">
<meta property="og:description" content="${e(description)}">
<meta property="og:type" content="website">
<meta property="og:locale" content="${at.lang.locale}">
<link rel="canonical" href="${at.url(at.lang)}">
$alternates
$extra
<style>
$style
$switchStyle
</style></head><body>"""

  /** the language switch: links styled as busi's buttons, the page's own `.on` */
  def switch(at: At): String =
    "<span class=\"lang\">" + Lang.values.map(l =>
      s"""<a data-l="${l.code}" href="/${l.code}/${at.path}"${if l == at.lang then " class=\"on\"" else ""}>""" +
        s"""${l.shown}</a>""").mkString + "</span>"

  /** the switch's links as busi's buttons were */
  val switchStyle: String =
    """.lang a { font-family: var(--mono); font-size: 11px; letter-spacing: .08em; color: var(--mist);
    padding: 4px 7px; border-radius: 6px; text-decoration: none; line-height: normal; }
  .lang a.on { color: var(--ink); background: rgba(142,162,190,.14); }
  .lang a:hover { color: var(--ink); }"""

  /** Turnstile's widget, seen only when Cloudflare wants an interaction */
  def turnstile(sitekey: String): String =
    s"""<div class="cf-turnstile" data-sitekey="${e(sitekey)}" data-appearance="interaction-only"></div>"""

  /** the scripts at the end of every page with a form */
  def scripts: String =
    """<script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
<script src="/site.js" defer></script>"""

  def foot(site: Site, look: Look)(using l: Lang): String =
    s"""<p class="foot">${e(site.name)}${look.footLinks} · <span>${t("сделано в busi", "zrobione w busi", "made " +
        "with busi",
      "зроблено в busi")}</span>${if look.staffLink then staff else ""}</p>"""

  /** the workshop, for its staff: one muted word at the end of a foot, in its colour and size — not followed */
  def staff(using l: Lang): String =
    s""" · <a href="/workshop/" rel="nofollow" style="color:inherit;opacity:.65;text-decoration:none">${
      t("панель", "panel", "staff", "панель")}</a>"""

  def serviceCard(o: Offer)(using l: Lang): String =
    val desc = if o.description(l).isEmpty then "" else s"""<span class="offer-desc">${tt(o.description)}</span>"""
    s"""<a class="offer" href="/${l.code}/offer/${o.key}/"><span class="offer-main"><span class="offer-name">""" +
      s"""${tt(o.name)}</span>$desc</span><span class="offer-go">→</span></a>"""

  // ---- THE STOREFRONT

  def storefront(at: At, look: Look): String =
    given l: Lang = at.lang
    val site = at.site
    val clothing = site.clothing
    val ordered = site.offers
    val cards = if ordered.isEmpty then s"""<p class="offer-desc">${t("Скоро.", "Wkrótce.", "Coming soon.",
      "Незабаром.")}</p>""" else ordered.map(serviceCard).mkString
    val seo = look.seo(site)
    s"""${head(at, seo.title, seo.description, storefrontStyle(look.theme, site.color), seo.head)}
  <div class="bg"><i class="seam s1"></i><i class="seam s2"></i><i class="seam s3"></i></div>
  <div class="glow"></div>
  <div class="wrap">
    <div class="top"><span class="mark">${e(site.logoText)}</span>${switch(at)}</div>
    <section class="hero">
      ${look.kicker}
      <h1 class="headline">${tt(site.tagline)}</h1>
      <p class="lead">${t("Или выберите готовый оффер ниже.", "Albo wybierz gotową ofertę poniżej.",
        "Or pick a ready-made offer below.", "Або оберіть готову пропозицію нижче.")}</p>
      <button class="buy" data-open="buyModal">${t("Купить", "Kup", "Buy", "Купити")}</button>
      ${slaNote(clothing)}
    </section>
    <section class="offers">
      <p class="label">${t("Готовые офферы", "Gotowe oferty", "Ready-made offers", "Готові пропозиції")}</p>
      $cards
    </section>
    ${look.sections(at)}
    <section class="contact" id="contact">
      <p class="label">${t("Связаться", "Kontakt", "Contact", "Звʼязатися")}</p>
      <p class="body">${tt(site.contact)}</p>
      <button class="cta" data-open="buyModal">${t("Написать →", "Napisz →", "Get in touch →", "Написати →")}</button>
      ${slaNote(clothing)}
    </section>
    ${foot(site, look)}
  </div>
  <div class="modal" id="buyModal">
    <div class="sheet">
      <button class="x" data-close="buyModal" aria-label="×">×</button>
      <div id="buyForm">
        <h2>${t("Расскажите, что нужно", "Powiedz, czego potrzebujesz", "Tell us what you need",
          "Розкажіть, що потрібно")}</h2>
        <p class="sub">${t(
          "Опишите задачу — отвечу в течение рабочего дня: уточним детали, цену и срок. Без звонков, переписка по " +
              "ссылке.",
          "Opisz zadanie — odpowiem w ciągu dnia roboczego: doprecyzujemy szczegóły, cenę i termin. Bez telefonów, " +
            "rozmowa pod linkiem.",
          "Describe it — I reply within a business day: we'll confirm the details, price and timing. No phone calls, " +
            "the conversation stays on a link.",
          "Опишіть задачу — відповім протягом робочого дня: уточнимо деталі, ціну й термін. Без дзвінків, " +
            "листування за посиланням.")}</p>
        <form data-send="order" data-form="buyForm" data-done="buyDone">
          <input type="hidden" name="lang" value="${l.code}">
          ${intakeFields(clothing, site.slug, at.sitekey)}
        </form>
      </div>
      <div class="done" id="buyDone" style="display:none">
        <div class="big">${t("Спасибо!", "Dziękujemy!", "Thank you!", "Дякуємо!")}</div>
        <p>${t("Заявка принята — отвечу в течение рабочего дня. Без звонков.",
          "Zgłoszenie przyjęte — odpowiem w ciągu dnia roboczego. Bez telefonów.",
          "Got it — I reply within a business day. No phone calls.",
          "Заявку прийнято — відповім протягом робочого дня. Без дзвінків.")}</p>
        ${if clothing then liabilityNote else ""}
      </div>
    </div>
  </div>
$scripts
</body></html>
"""

  // ---- AN OFFER'S PAGE: the offer and its form

  def offer(at: At, o: Offer, look: Look): String =
    given l: Lang = at.lang
    val site = at.site
    val clothing = site.clothing
    val desc = if o.description(l).isEmpty then "" else s"""<p class="lead">${tt(o.description)}</p>"""
    val pn = o.priceShort
    val books = site.books(o)
    val priceChip =
      if site.kind == Kind.It then s"""<span class="chip accent">${t("Цена договорная", "Cena do uzgodnienia",
        "Price by agreement", "Ціна договірна")}</span>"""
      else if o.priced then s"""<span class="chip accent">${t("от " + pn, "od " + pn, "from " + pn, "від " +
          "" + pn)}</span>"""
      else s"""<span class="chip accent">${t("Цена по осмотру", "Cena po obejrzeniu", "Price once we see it",
        "Ціна після огляду")}</span>"""
    val howChip = if books then s"""<span class="chip">${t(s"${o.minutes} мин · запись онлайн",
        s"${o.minutes} min · rezerwacja online", s"${o.minutes} min · book online",
        s"${o.minutes} хв · запис онлайн")}</span>"""
      else if clothing then s"""<span class="chip">${t("Принимаем, оцениваем, согласуем — и в работу",
        "Przyjmujemy, wyceniamy, uzgadniamy — i do pracy", "We take it in, assess, agree — then start",
        "Приймаємо, оцінюємо, узгоджуємо — і в роботу")}</span>"""
      else s"""<span class="chip">${t("Обсудим объём и сроки до старта", "Omówimy zakres i terminy przed startem",
        "We agree scope and timing before we start", "Обговоримо обсяг і терміни до старту")}</span>"""
    val title = s"${o.name(l)} · ${site.name}"
    s"""${head(at, title, if o.description(l).nonEmpty then o.description(l) else site.tagline(l),
      storefrontStyle(look.theme, site.color))}
  <div class="bg"><i class="seam s1"></i><i class="seam s2"></i><i class="seam s3"></i></div>
  <div class="glow"></div>
  <div class="wrap">
    <div class="top"><a class="mark" href="/${l.code}/">← ${e(site.logoText)}</a>${switch(at)}</div>
    <section class="hero" style="padding: 12vh 0 5vh">
      <p class="label">${t("Готовое предложение", "Gotowa oferta", "Ready-made offer", "Готова пропозиція")}</p>
      <h1 class="headline">${tt(o.name)}</h1>
      $desc
      <div class="chips">$priceChip$howChip</div>
    </section>
    <section class="offers" style="margin-top: 1vh">
      <div class="formcard">
      ${if books then bookingForm(at, o) else s"""<div id="offForm">
        <p class="label">${t("Оформить заказ", "Złóż zamówienie", "Place the order", "Оформити замовлення")}</p>
        <form data-send="order" data-form="offForm" data-done="offDone">
          <input type="hidden" name="offerKey" value="${e(o.key)}">
          <input type="hidden" name="lang" value="${l.code}">
          ${offerFields(clothing, site.slug, o.key, at.sitekey)}
        </form>
        ${slaNote(clothing)}
      </div>"""}
      <div class="done" id="offDone" style="display:none; text-align:left; padding:10px 0">
        <div class="big">${t("Спасибо!", "Dziękujemy!", "Thank you!", "Дякуємо!")}</div>
        <p>${t("Заявка принята — отвечу в течение рабочего дня. Без звонков.",
          "Zgłoszenie przyjęte — odpowiem w ciągu dnia roboczego. Bez telefonów.",
          "Got it — I reply within a business day. No phone calls.",
          "Заявку прийнято — відповім протягом робочого дня. Без дзвінків.")}</p>
        <p style="margin-top:14px"><a class="cta" href="/${l.code}/">${t("← На главную", "← Strona główna",
          "← Back home", "← На головну")}</a></p>
      </div>
      </div>
    </section>
    ${foot(site, look)}
  </div>
$scripts
</body></html>
"""

  /** a bookable service's form: the free starts by day (filled by the pages' script from `/api/slots`), who, the
   * consent, Turnstile — posted to `/api/book` */
  def bookingForm(at: At, o: Offer)(using l: Lang): String =
    s"""<style>.slots .days,.slots .times{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0}
.slots .day,.slots .slot{font:inherit;font-size:14px;padding:7px 11px;border-radius:9px;cursor:pointer;
background:transparent;color:inherit;border:1px solid rgba(127,127,127,.4)}
.slots .on{border-color:currentColor;box-shadow:inset 0 0 0 1px currentColor}</style>
      <div id="offForm">
        <p class="label">${t("Выберите время", "Wybierz termin", "Choose a time", "Оберіть час")}</p>
        <form data-send="book" data-form="offForm" data-done="offDone">
          <input type="hidden" name="service" value="${e(o.key)}">
          <input type="hidden" name="lang" value="${l.code}">
          <input type="hidden" name="start" value="">
          <div class="fld slots" data-slots="${e(o.key)}"><span>${t("Загружаем свободное время…",
            "Wczytujemy wolne terminy…", "Loading the free times…", "Завантажуємо вільний час…")}</span></div>
          $contactFields
          ${rodoConsent(at.site.slug)}
          ${turnstile(at.sitekey)}
          <button class="send" type="submit">${t("Записаться", "Zarezerwuj", "Book", "Записатися")}</button>
        </form>
      </div>"""

  // ---- NOT FOUND: in the site's look, the four languages' way home

  def missing(site: Site, look: Look): String =
    val at = At(site, site.lang, "", "")
    s"""${head(at, "404 · " + site.name, site.tagline(site.lang), storefrontStyle(look.theme, site.color),
      """<meta name="robots" content="noindex">""")}
  <div class="bg"><i class="seam s1"></i><i class="seam s2"></i><i class="seam s3"></i></div>
  <div class="glow"></div>
  <div class="wrap">
    <div class="top"><a class="mark" href="/">${e(site.logoText)}</a></div>
    <section class="hero">
      <h1 class="headline">404</h1>
      <p class="lead">${Lang.values.map(x => s"""<a href="/${x.code}/" style="color:inherit">""" +
        s"""${Html.escape(T("На главную", "Strona główna", "Home", "На головну")(x))}</a>""").mkString(" · ")}</p>
    </section>
  </div>
</body></html>
"""

  // ---- THE WORKSHOP'S PAGE: a frame for okay-ui's screens (okay.shop.workshop), the Turnstile widget of its sign-in

  def workshop(site: Site, sitekey: String): String =
    s"""<!doctype html><html lang="uk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Майстерня · ${e(site.name)}</title><link rel="icon" href="data:,"></head><body>
<header><div class="bar"><span class="brand">Майстерня</span><span class="tag">${e(site.name)}</span></div></header>
<main id="app" data-name="${e(site.name)}" data-accent="${e(site.color)}"
  data-deep="${if site.clothing then "#3a2a22" else "#0c1420"}"${if site.schedule.isDefined then
    " data-books=\"yes\"" else ""}></main>
<div class="cf-turnstile" data-sitekey="${e(sitekey)}" data-appearance="interaction-only"
  style="display:flex;justify-content:center;margin:12px 0"></div>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
<script src="/workshop/workshop.js" defer></script>
</body></html>
"""

  // ---- busi's parts, as they were (storefront.ssc), each language rendered in

  /** the storefront's stylesheet: busi's rules, the site's theme (its colours, its background, what it adds), the
   * accent */
  def storefrontStyle(theme: Theme, color: String): String =
    s""":root {
      --accent: ${color};
      ${theme.root}
      --serif: ui-serif, "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
      --mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
      --sans: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    }
    * { box-sizing: border-box; }
    body { margin: 0; color: var(--ink); font-family: var(--sans); font-size: 17px; line-height: 1.6;
      background: var(--void); min-height: 100vh; overflow-x: hidden; }
    ${theme.background}
    .glow { position: fixed; inset: 0; z-index: 0; pointer-events: none;
      background: radial-gradient(44% 30% at 50% 13%, color-mix(in oklab, var(--accent) 66%, transparent), transparent
          70%);
      opacity: .13; filter: blur(58px); animation: breathe 13s ease-in-out infinite; }
    .wrap { position: relative; z-index: 1; max-width: 760px; margin: 0 auto; padding: 22px 22px 64px; }
    .top { display: flex; justify-content: space-between; align-items: baseline;
      font-family: var(--mono); font-size: 12.5px; letter-spacing: .14em; text-transform: uppercase; }
    .mark { color: var(--ink); text-decoration: none; }
    .lang { display: inline-flex; gap: 2px; }
    .lang button { font-family: var(--mono); font-size: 11px; letter-spacing: .08em; background: none;
      border: 0; color: var(--mist); padding: 4px 7px; cursor: pointer; border-radius: 6px; }
    .lang button.on { color: var(--ink); background: rgba(142,162,190,.14); }
    .lang button:hover { color: var(--ink); }
    .hero { padding: 17vh 0 12vh; position: relative; }
    .headline { font-family: var(--serif); font-weight: 500; font-size: clamp(32px, 7.6vw, 60px);
      line-height: 1.06; letter-spacing: -.01em; margin: 0; max-width: 18ch;
      text-shadow: var(--hsh); }
    .lead { color: var(--mist); font-size: 18px; margin: 22px 0 30px; max-width: 46ch; }
    .buy { display: inline-block; font-family: var(--mono); font-size: 14px; letter-spacing: .14em;
      text-transform: uppercase; text-decoration: none; color: #0a0d13; background: var(--accent);
      border: 0; cursor: pointer; border-radius: 999px; padding: 15px 36px; font-weight: 600;
      transition: box-shadow .3s ease, transform .2s ease; }
    .buy:hover, .buy:focus-visible { box-shadow: 0 0 32px 2px var(--accent); transform: translateY(-1px); outline:
        none; }
    .label { font-family: var(--mono); font-size: 13px; letter-spacing: .28em; text-transform: uppercase;
      color: var(--accent-text); margin: 0 0 4px; }
    .offers { margin-top: 9vh; }
    .offer { display: flex; align-items: center; justify-content: space-between; gap: 18px;
      padding: 20px 12px 20px 14px; border-top: 1px solid var(--line); text-decoration: none; color: inherit;
      border-radius: 12px; transition: background .25s ease, transform .25s ease, box-shadow .25s ease; }
    .offers .offer:last-child { border-bottom: 1px solid var(--line); }
    .offer-name { font-family: var(--serif); font-size: 20px; color: var(--ink); display: block; }
    .offer-desc { color: var(--mist); font-size: 15px; display: block; margin-top: 3px; }
    .offer-go { font-family: var(--mono); font-size: 18px; color: var(--accent-text); opacity: .6;
      transition: opacity .25s ease, transform .25s ease; }
    .offer:hover, .offer:focus-visible { background: linear-gradient(90deg, rgba(142,162,190,.07), transparent);
      transform: translateX(3px); box-shadow: inset 2px 0 0 var(--accent); outline: none; }
    .offer:hover .offer-go, .offer:focus-visible .offer-go { opacity: 1; transform: translateX(4px); }
    .contact { margin-top: 9vh; padding-top: 24px; border-top: 1px solid var(--line); }
    .contact .body { color: var(--mist); font-size: 17px; margin: 6px 0 16px; }
    .cta { display: inline-block; font-family: var(--mono); font-size: 13px; letter-spacing: .1em;
      text-transform: uppercase; text-decoration: none; color: var(--ink); background: transparent;
      border: 1px solid var(--accent); border-radius: 999px; padding: 12px 22px; cursor: pointer;
      transition: box-shadow .25s ease, background .25s ease; }
    .cta:hover, .cta:focus-visible { background: rgba(142,162,190,.10); box-shadow: 0 0 22px 0 var(--accent); outline:
        none; }
    /* the async/SLA expectation set right at the call-to-action — no phone, reply within a business day */
    .sla { font: 500 14px var(--mono); letter-spacing: .02em; color: var(--mist); margin: 14px 0 0; }
    .sla::before { content: "•"; color: var(--accent-text); margin-right: 8px; }
    .foot { font-family: var(--mono); font-size: 13px; letter-spacing: .12em; color: #4e5666;
      margin-top: 8vh; text-transform: uppercase; }
    a:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; }
    /* intake: «Купить» popup + the ready-made-offer screen form (organic, theme-aware) */
    .modal { position: fixed; inset: 0; z-index: 50; display: none; align-items: flex-end; justify-content: center;
      background: var(--scrim); backdrop-filter: blur(7px); -webkit-backdrop-filter: blur(7px); }
    .modal.open { display: flex; }
    .sheet { position: relative; z-index: 1; width: 100%; max-width: 540px; max-height: 92vh; overflow-y: auto;
      background: var(--surface); border: 1px solid var(--line); border-radius: 22px 22px 0 0;
      padding: 30px 22px 34px; box-shadow: 0 -14px 70px rgba(0,0,0,.45); }
    @media (min-width: 600px) { .modal { align-items: center; } .sheet { border-radius: 22px; } }
    .sheet h2 { font-family: var(--serif); font-weight: 500; font-size: 27px; margin: 0 0 4px; color: var(--ink); }
    .sheet .sub { color: var(--mist); font-size: 14.5px; margin: 0 0 20px; }
    .x { position: absolute; top: 14px; right: 16px; background: none; border: 0; color: var(--mist);
      font-size: 26px; line-height: 1; cursor: pointer; }
    .fld { display: block; margin: 0 0 15px; }
    .fld > span { display: block; font-family: var(--mono); font-size: 10.5px; letter-spacing: .12em;
      text-transform: uppercase; color: var(--mist); margin: 0 0 6px; }
    .fld input, .fld textarea { width: 100%; box-sizing: border-box; font-family: var(--sans); font-size: 16px;
      background: var(--field); color: var(--ink); border: 1px solid var(--line); border-radius: 12px; padding: 12px
          14px; }
    .fld textarea { min-height: 82px; resize: vertical; }
    .fld input:focus, .fld textarea:focus { outline: none; border-color: var(--accent); }
    .opts { display: flex; gap: 8px; flex-wrap: wrap; }
    .opt { flex: 1; min-width: 120px; position: relative; cursor: pointer; margin: 0; }
    .opt input { position: absolute; opacity: 0; pointer-events: none; }
    .opt span { display: block; text-align: center; font-size: 14px; color: var(--ink); background: var(--field);
      border: 1px solid var(--line); border-radius: 12px; padding: 11px 8px; transition: border-color .2s ease,
          box-shadow .2s ease; }
    .opt input:checked + span { border-color: var(--accent); box-shadow: inset 0 0 0 1px var(--accent); }
    .consent { display: flex; gap: 9px; align-items: flex-start; font-size: 13px; color: var(--mist); margin: 6px 0
        20px; }
    .consent input { margin-top: 3px; flex: 0 0 auto; }
    .send { width: 100%; font-family: var(--mono); font-size: 14px; letter-spacing: .14em; text-transform: uppercase;
      color: #0a0d13; background: var(--accent); border: 0; border-radius: 999px; padding: 16px; font-weight: 600;
      cursor: pointer; transition: box-shadow .3s ease; }
    .send:hover { box-shadow: 0 0 28px 1px var(--accent); }
    .done { text-align: center; padding: 26px 8px 8px; }
    .done .big { font-family: var(--serif); font-size: 27px; color: var(--ink); margin-bottom: 8px; }
    .done p { color: var(--mist); margin: 0; }
    .formcard { background: var(--surface); border: 1px solid var(--line); border-radius: 18px; padding: 24px 20px
        28px; }
    .chips { display: flex; gap: 8px; flex-wrap: wrap; margin: 18px 0 0; }
    .chip { font-family: var(--mono); font-size: 12px; letter-spacing: .04em; color: var(--ink);
      background: var(--field); border: 1px solid var(--line); border-radius: 999px; padding: 8px 15px; }
    .chip.accent { color: #0a0d13; background: var(--accent); border-color: var(--accent); font-weight: 600; }
    @keyframes rush { 0% { transform: translateX(-14%) rotate(-1.5deg); } 100% { transform: translateX(14%)
        rotate(-1.5deg); } }
    @keyframes breathe { 0%, 100% { opacity: .09; transform: scale(1); } 50% { opacity: .17; transform: scale(1.06); } }
    /* product cards — the «Существующие решения» section (shared by /products and the IT storefront) */
    .product { position: relative; z-index: 2; border: 1px solid var(--line); background: var(--surface);
        border-radius: 16px; padding: 22px 22px 18px; margin: 14px 0; box-shadow: var(--hsh); }
    .product h2 { font: 600 23px var(--sans); margin: 0 0 4px; color: var(--ink); letter-spacing: -.01em; }
    .product .pt { color: var(--accent); font: 600 12px var(--mono); text-transform: uppercase; letter-spacing: .09em;
        margin: 0 0 13px; }
    .product p.pd { color: var(--mist); margin: 0 0 16px; line-height: 1.6; }
    .pviz { display: block; width: 132px; height: 70px; margin: 0 0 12px; }
    .plinks { display: flex; flex-wrap: wrap; gap: 10px; }
    .plink { color: var(--ink); text-decoration: none; border: 1px solid var(--line); border-radius: 10px; padding: 9px
        15px; font: 500 14px var(--sans); transition: border-color .15s, color .15s; }
    .plink:hover { color: var(--accent); border-color: var(--accent); }
    .plink.primary { background: var(--accent); color: #04221e; border-color: var(--accent); font-weight: 600; }
    ${theme.extra}
    @media (prefers-reduced-motion: reduce) { .bg::before, .bg::after, .glow, .headline, .buy { animation: none; } }
    .how-courier { margin: 8vh 0 6vh; }
    .steps { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin: 16px 0 12px; }
    .step { background: rgba(255,255,255,0.07); border-radius: 12px; padding: 14px 14px 12px; display: flex;
        flex-direction: column; gap: 4px; }
    .snum { font-family: var(--mono); font-size: 18px; font-weight: 700; color: var(--accent); }
    .stxt { font-weight: 600; font-size: 14px; color: var(--ink); }
    .sdesc { font-size: 12px; color: var(--mist); }
    .min-order { font-size: 12px; color: var(--mist); background: rgba(255,255,255,.06); border-radius: 8px; padding:
        8px 12px; margin-bottom: 10px; }
    .track-link { font-family: var(--mono); font-size: 12px; color: var(--accent); text-decoration: none;
        letter-spacing: .06em; }
    .track-link:hover { text-decoration: underline; }
    .warn { background: transparent; }
    .trk { font-family: var(--mono); font-size: 12px; color: var(--mist); }
    .done-ref { font-family: var(--mono); font-size: 13px; color: var(--mist); margin-top: 8px; word-break: break-all; }
    .consent { display: flex; gap: 10px; align-items: flex-start; font-size: 13px; color: var(--mist); cursor: pointer;
        }
    .consent input[type=checkbox] { width: auto; min-width: 16px; margin-top: 3px; accent-color: var(--accent); }
    .consent a { color: var(--accent); }
    @media (max-width: 400px) { .steps { grid-template-columns: 1fr; } }"""


  // «как передать вещь» — only for clothing (garment); IT has nothing physical to hand over

  def handoverField(clothing: Boolean)(using l: Lang): String =
    if !clothing then "" else
      s"""<label class="fld"><span>${t("Как передать вещь?", "Jak przekazać rzecz?", "How to hand over the item?",
           "Як передати річ?")}</span>
        <div class="opts">
          <label class="opt"><input type="radio" name="handover" value="self" checked><span>${t("Принесу сам(а)",
               "Przyniosę sam(a)", "I'll bring it", "Принесу сам(а)")}</span></label>
          <label class="opt"><input type="radio" name="handover" value="visit"><span>${t("Визит мастера", "Wizyta " +
              "mistrza", "Master's visit", "Візит майстра")}</span></label>
          <label class="opt"><input type="radio" name="handover" value="delivery"><span>${t("Доставка", "Dostawa",
               "Delivery", "Доставка")}</span></label>
        </div></label>"""

  // shared bottom of every form: who you are (the single RODO consent with the policy link is
  // appended by each form via rodoConsent — the old duplicate short checkbox was removed)
  def contactFields(using l: Lang): String =
    // the label's look inline, as busi had it: these two spans are outside `.fld > span`
    val look = "display:block;font-family:var(--mono);font-size:10.5px;letter-spacing:.12em;" +
      "text-transform:uppercase;color:var(--mist);margin:0 0 6px"
    s"""<div class="fld" style="display:flex;gap:10px"><label style="flex:1"><span style="$look">${
      t("Ваше имя", "Imię", "Your name", "Ваше імʼя")}</span><input name="name" required></label>
        <label style="flex:1"><span style="$look">${t("Телефон или email", "Telefon lub email", "Phone or email",
      "Телефон або email")}</span><input name="contact" required></label></div>"""

  // courier delivery + payment choice — atelier intake only
  def courierFields(using l: Lang): String =
    s"""<label class="fld"><span>${t("Как отправить вещь?", "Jak wysłać rzecz?", "How to send the item?", "Як " +
        "надіслати річ?")}</span>
      <div class="opts">
        <label class="opt"><input type="radio" name="deliveryMethod" value="courier" checked><span>${t("📦 По почте " +
            "(InPost)", "📦 Pocztą (InPost)", "📦 By post (InPost)", "📦 Поштою (InPost)")}</span></label>
        <label class="opt"><input type="radio" name="deliveryMethod" value="in_person"><span>${t("🚶 Принесу сам(а)",
             "🚶 Przyniosę sam(a)", "🚶 I'll bring it", "🚶 Принесу сам(а)")}</span></label>
      </div></label>
    <label class="fld"><span>${t("Оплата", "Płatność", "Payment", "Оплата")}</span>
      <div class="opts">
        <label class="opt"><input type="radio" name="paymentMethod" value="transfer" checked><span>${t("💳 Переводом " +
            "(Blik)", "💳 Przelew (Blik)", "💳 Bank transfer (Blik)", "💳 Переказом (Blik)")}</span></label>
        <label class="opt"><input type="radio" name="paymentMethod" value="cod"><span>${t("💵 Наложенный платёж", "💵 " +
            "Za pobraniem", "💵 Cash on delivery", "💵 Накладений платіж")}</span></label>
      </div></label>"""

  // GENERIC «Купить» popup — the need is OPEN, so we ask what it is
  // NB: the language JS swaps textContent, so translations must be TEXT-ONLY —
  // HTML inside a data-* attr renders as literal markup (the uk RODO garbage,
  // owner-reported). The link is a real <a> whose label translates separately;
  // ${slug} interpolates in the outer s-string, never inside translation args.
  def rodoConsent(slug: String)(using l: Lang): String =
    s"""<label class="fld consent"><input type="checkbox" name="rodo_consent" required>
      <span><span>${t("Согласен(а) на обработку персональных данных согласно", "Wyrażam zgodę na przetwarzanie " +
          "moich danych osobowych zgodnie z", "I consent to processing of my personal data per the", "Погоджуюсь на " +
          "обробку персональних даних згідно з")}</span> <a href="/${l.code}/privacy/"
          target="_blank">${t("Политикой конфиденциальности", "Polityką Prywatności", "Privacy Policy", "Політикою " +
          "конфіденційності")}</a></span></label>"""

  def intakeFields(clothing: Boolean, slug: String, sitekey: String)(using l: Lang): String =
    val whatLabel = if clothing then t("Что нужно сделать?", "Co trzeba zrobić?", "What do you need?", "Що потрібно " +
        "зробити?")
                    else t("Опишите задачу", "Opisz zadanie", "Describe the task", "Опишіть задачу")
    val whatPh = if clothing then t("напр.: заменить молнию на куртке, ушить платье", "np.: wymienić zamek w " +
        "kurtce, zwęzić sukienkę", "e.g.: replace a jacket zipper, take in a dress", "напр.: замінити блискавку на " +
        "куртці, звузити сукню")
                  else t("напр.: не работает форма оплаты, нужна новая функция", "np.: nie działa formularz " +
                      "płatności, potrzebna nowa funkcja", "e.g.: the payment form is broken, need a new feature",
                       "напр.: не працює форма оплати, потрібна нова функція")
    val itemValueField = if clothing then
      s"""<label class="fld"><span>${t("Примерная стоимость вещи (zł)", "Szacunkowa wartość rzeczy (zł)",
           "Approximate item value (PLN)", "Орієнтовна вартість речі (zł)")}</span><input type="number"
          name="itemValue" min="0" step="1" placeholder="${t("напр.: 200", "np.: 200", "e.g.: 200", "напр.: " +
          "200")}"></label>"""
      else ""
    s"""<label class="fld"><span>${whatLabel}</span><textarea name="need" placeholder="${whatPh}"></textarea></label>
      ${itemValueField}
      ${if clothing then courierFields else ""}
      ${contactFields}
      ${rodoConsent(slug)}
      ${turnstile(sitekey)}
      <button class="send" type="submit">${t("Отправить заявку", "Wyślij zgłoszenie", "Send request", "Надіслати " +
          "заявку")}</button>"""

  // READY-MADE OFFER — the need is ALREADY defined (= the offer), so we don't re-ask it; we ORDER this one.
  // Only the specifics that ordering this offer needs: how to hand it over, optional notes, contact.
  def offerFields(clothing: Boolean, slug: String, key: String, sitekey: String)(using l: Lang): String =
    if key == "bespoke" then
      s"""<label class="fld"><span>${t("Опишите желаемый образ", "Opisz wymarzony strój", "Describe your ideal outfit",
           "Опишіть бажаний образ")}</span><textarea name="need" placeholder="${t("напр.: приталенное платье миди, " +
          "тёмно-синее, без рукавов", "np.: sukienka midi dopasowana, granatowa, bez rękawów", "e.g.: fitted midi " +
          "dress, navy blue, sleeveless", "напр.: приталена сукня міді, темно-синя, без рукавів")}"></textarea></label>
      <label class="fld"><span>${t("Ткань / материал (необязательно)", "Tkanina / materiał (opcjonalnie)", "Fabric " +
          "/ material (optional)", "Тканина / матеріал (необовʼязково)")}</span><input type="text" name="fabric"
          placeholder="${t("напр.: хлопок, лён, трикотаж", "np.: bawełna, len, dzianina", "e.g.: cotton, linen, " +
          "jersey", "напр.: бавовна, льон, трикотаж")}"></label>
      <p class="sla">${t("После заявки пришлите 2 фото в полный рост (спереди + сбоку) — мастер свяжется и откроет " +
          "ваш проект.", "Po zgłoszeniu wyślij 2 zdjęcia w pełnej postaci (przód + bok) — krawcowa napisze i " +
          "otworzy Twój projekt.", "After submitting send 2 full-body photos (front + side) — the seamstress will " +
          "contact you and open your project.", "Після заявки надішліть 2 фото на повний зріст (спереду + збоку) — " +
          "майстер напише і відкриє ваш проект.")}</p>
      ${contactFields}
      ${rodoConsent(slug)}
      ${turnstile(sitekey)}
      <button class="send" type="submit">${t("Начать →", "Zacznijmy →", "Let's start →", "Почнімо →")}</button>"""
    else
      val notePh = if clothing then t("напр.: к пятнице; любимая вещь — аккуратно", "np.: na piątek; ulubiona rzecz " +
          "— ostrożnie", "e.g.: by Friday; a favourite piece — handle with care", "напр.: до пʼятниці; улюблена річ " +
          "— обережно")
                    else t("напр.: сроки, детали, ссылки", "np.: terminy, szczegóły, linki", "e.g.: deadlines, " +
                        "details, links", "напр.: терміни, деталі, посилання")
      val offerItemValueField = if clothing then
        s"""<label class="fld"><span>${t("Примерная стоимость вещи (zł)", "Szacunkowa wartość rzeczy (zł)",
             "Approximate item value (PLN)", "Орієнтовна вартість речі (zł)")}</span><input type="number"
            name="itemValue" min="0" step="1" placeholder="${t("напр.: 200", "np.: 200", "e.g.: 200", "напр.: " +
            "200")}"></label>"""
        else ""
      s"""${handoverField(clothing)}
      ${offerItemValueField}
      <label class="fld"><span>${t("Пожелания (необязательно)", "Uwagi (opcjonalnie)", "Notes (optional)",
           "Побажання (необовʼязково)")}</span><textarea name="need" placeholder="${notePh}"></textarea></label>
      ${contactFields}
      ${rodoConsent(slug)}
      ${turnstile(sitekey)}
      <button class="send" type="submit">${t("Заказать", "Zamów", "Order", "Замовити")}</button>"""

  def liabilityNote(using l: Lang): String =
    s"""<p class="sla" style="margin-top:8px">${t("Принимаем ответственность за вашу вещь до возврата.",
         "Przyjmujemy odpowiedzialność za Twoją rzecz do momentu zwrotu.", "We take responsibility for your item " +
        "until returned.", "Приймаємо відповідальність за вашу річ до повернення.")}</p>"""


  // the async/SLA expectation shown next to every public CTA («Купить» / «Написать» / «Обсудить
  // проект»): it tells the client up front that this is asynchronous — no phone calls, a reply within
  // a business day, the conversation stays on the request link. Shared by the storefront + /products.

  def slaNote(clothing: Boolean = false)(using l: Lang): String =
    if clothing then
      s"""<p class="sla">${t("Без звонков — отвечаем в течение рабочего дня.", "Bez telefonów — odpisujemy w ciągu " +
          "dnia roboczego.", "No phone calls — we reply within one business day.", "Без дзвінків — відповідаємо " +
          "протягом робочого дня.")}</p>"""
    else
      s"""<p class="sla">${t("Асинхронно, без звонков — отвечаю в течение одного рабочего дня; переписка по " +
          "ссылке-заявке.", "Asynchronicznie, bez telefonów — odpowiadam w ciągu jednego dnia roboczego; rozmowa " +
          "pod linkiem zgłoszenia.", "Async, no phone calls — I reply within one business day; the conversation " +
          "stays on your request link.", "Асинхронно, без дзвінків — відповідаю протягом одного робочого дня; " +
          "листування за посиланням-заявкою.")}</p>"""

