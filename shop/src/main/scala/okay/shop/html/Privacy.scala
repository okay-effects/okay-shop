package okay.shop.html

import okay.shop.model.*

/** THE PRIVACY POLICY (RODO/GDPR) of a site: binding in Polish, translated to English, Ukrainian and Russian.
  * The look is the old template's: back link, title, last-updated date, numbered sections, footer line. */
object Privacy:
  /** the policy's version: a date, stored with every consent */
  val version: String = "2026-11-05"

  /** where the controller is reached, by default `kontakt@` the site's own domain (its last two labels) */
  def email(site: Site): String = "kontakt@" + site.domain.split('.').takeRight(2).mkString(".")

  /** one language's texts: the title, the «last updated» words, the translation note, the sections */
  private final case class Texts(title: String, updated: String, note: Option[String],
                                 sections: Vector[(String, String)])

  /** the page for this site in this language: a whole HTML document. `host`: the items its host (a platform the
   * site runs on: its processor) adds to the recipients, in `l`, said first — HTML of the host's (specs/shop.md
   * "The site's host in its privacy policy"); none: the page as it always was */
  def page(site: Site, l: Lang, email: String = "", host: Vector[String] = Vector.empty, chat: Int = 0,
           mail: Mail = Mail.Cloudflare, geocoding: Boolean = false, cookies: Vector[String] = Vector.empty): String =
    val address = Html.escape(if email.nonEmpty then email else this.email(site))
    val contact = s"""<a href="mailto:$address">$address</a>"""
    val name = Html.escape(site.name)
    val accent = site.color
    // the processors and cookies the site's config uses (specs/shop.md "The privacy policy from the config")
    val u = Uses(mail, chat > 0, geocoding && site.work.flatMap(_.area).exists(_.radius > 0), cookies)
    val t = texts(l, site.clothing, name, contact, host, u)
    val note = t.note.fold("")(n => s"\n<p><em>$n</em></p>")
    // a shop that comes to its clients keeps their addresses: said after what is collected
    val visits0 = site.work.filter(_.has(Mode.AtClient)).fold(t.sections)(_ =>
      t.sections.take(2) ++ (visit(l) +: t.sections.drop(2)))
    // the assistant's chat (specs/shop.md "The assistant"): what it keeps and for how long, after what is collected
    val visits1 = if chat <= 0 then visits0 else visits0.take(2) ++ (assistant(l, chat) +: visits0.drop(2))
    // a map of its place (specs/shop.md "Places on the map"): what the map's third parties receive; none: as before
    val mapped = site.work.exists(_.locations.exists(x => Geo.current(x).isDefined))
    val visits = if !mapped then visits1 else visits1.take(2) ++ (maps(l) +: visits1.drop(2))
    // the assistant's section is the chat's link target (specs/shop.md "Told it is an AI"): `id="chat"`
    val chatHead = Option.when(chat > 0)(assistant(l, chat)._1)
    val sections = visits.zipWithIndex.map { case ((h, body), i) =>
      s"<h2${if chatHead.contains(h) then " id=\"chat\"" else ""}>${i + 1}. $h</h2>\n$body" }
    s"""<!doctype html><html lang="${l.code}"><head><meta charset="utf-8">""" +
      """<meta name="viewport" content="width=device-width,initial-scale=1">""" + "\n" +
      s"<title>${t.title} · $name</title>\n" +
      "<style>body{font:16px/1.7 system-ui,sans-serif;margin:0;background:#faf5f0;color:#2a1f14;}\n" +
      ".wrap{max-width:660px;margin:0 auto;padding:40px 20px;}\n" +
      s"h1{font-size:24px;color:$accent;margin:0 0 8px;}\n" +
      "h2{font-size:17px;margin:28px 0 6px;}\n" +
      s"a{color:$accent;}\n" +
      ".back{display:inline-block;margin-bottom:24px;font-size:14px;" +
      s"color:$accent;text-decoration:none;}\n" +
      "</style></head>\n" +
      """<body><div class="wrap">""" + "\n" +
      s"""<a class="back" href="/${l.code}/">← $name</a>""" + "\n" +
      s"<h1>${t.title}</h1>\n" +
      s"<p><strong>$name</strong> — ${t.updated}: $version</p>$note\n" +
      sections.mkString("\n") + "\n" +
      s"""<p style="margin-top:32px;font-size:13px;color:#999">$name · $version${Pages.staff(using l)}</p>""" + "\n" +
      "</div></body></html>\n"

  private def texts(l: Lang, clothing: Boolean, name: String, contact: String, host: Vector[String],
                    u: Uses): Texts =
    l match
      case Lang.pl => pl(clothing, name, contact, host, u)
      case Lang.en => en(clothing, name, contact, host, u)
      case Lang.uk => uk(clothing, name, contact, host, u)
      case Lang.ru => ru(clothing, name, contact, host, u)

  private def p(s: String): String = s"<p>$s</p>"

  /** the section of a shop that comes to its clients (specs/shop.md "How the work happens": privacy) */
  /** the map's section: OpenStreetMap's tiles and the map's script, fetched only when the map is shown */
  private def maps(l: Lang): (String, String) = l match
    case Lang.pl => "Mapa" -> p("Mapa naszego adresu wczytuje się dopiero, gdy jest widoczna lub kliknięta: Twoja " +
        "przeglądarka pobiera wtedy kafelki OpenStreetMap (OpenStreetMap Foundation) i skrypt mapy z cdnjs " +
        "(Cloudflare), przekazując im swój adres IP i adres strony. Nie zbieramy przy tym Twojego położenia.")
    case Lang.en => "The map" -> p("The map of our address loads only when it is in view or clicked: your browser " +
        "then fetches OpenStreetMap's tiles (the OpenStreetMap Foundation) and the map's script from cdnjs " +
        "(Cloudflare), giving them its IP address and the page's address. We do not collect your location for it.")
    case Lang.uk => "Мапа" -> p("Мапа нашої адреси завантажується лише тоді, коли вона видима або натиснута: ваш " +
        "браузер тоді завантажує плитки OpenStreetMap (OpenStreetMap Foundation) і скрипт мапи з cdnjs " +
        "(Cloudflare), передаючи їм свою IP-адресу та адресу сторінки. Вашого розташування ми при цьому не збираємо.")
    case Lang.ru => "Карта" -> p("Карта нашего адреса загружается, только когда она видна или нажата: ваш браузер " +
        "тогда загружает плитки OpenStreetMap (OpenStreetMap Foundation) и скрипт карты с cdnjs (Cloudflare), " +
        "передавая им свой IP-адрес и адрес страницы. Ваше местоположение мы при этом не собираем.")

  /** the assistant's section: its purpose, what it keeps, for `days` days of quiet, deletion in the chat, the AI */
  private def assistant(l: Lang, days: Int): (String, String) = l match
    case Lang.pl => "Asystent (czat)" -> p("Asystent jest systemem sztucznej inteligencji (AI) — nie " +
        "rozmawiasz z człowiekiem; mówi o tym też sam czat. " +
        "Czat na stronie odpowiada na pytania o nasze usługi, ceny, wolne " +
        "terminy i pomaga zarezerwować wizytę. Przechowujemy rozmowę (Twoje wiadomości i odpowiedzi) oraz dane, " +
        "które podasz przy rezerwacji (imię, kontakt), by prowadzić rozmowę i zrealizować rezerwację; podstawą " +
        "jest Twoje żądanie (art. 6 ust. 1 lit. b RODO). Odpowiedzi tworzy model językowy Cloudflare Workers AI, " +
        "który nie otrzymuje naszych cen ani terminów — te wstawiamy sami. Rozmowa jest usuwana po " +
        s"$days dniach bez aktywności, a w każdej chwili możesz ją usunąć przyciskiem w czacie.")
    case Lang.en => "The assistant (chat)" -> p("The assistant is an artificial intelligence (AI) system — " +
        "you are not talking to a person; the chat itself says so too. " +
        "The chat on the site answers questions about our services, " +
        "prices and free times, and helps to book. We keep the conversation (your messages and the answers) and " +
        "what you give for a booking (name, contact) to hold the conversation and make the booking, at your request " +
        "(art. 6(1)(b) GDPR). The answers are written by a language model of Cloudflare Workers AI, which never " +
        "receives our prices or times — we put them in ourselves. The conversation is deleted after " +
        s"$days days without activity, and you can delete it at any time with the button in the chat.")
    case Lang.uk => "Асистент (чат)" -> p("Асистент — це система штучного інтелекту (AI): " +
        "ви розмовляєте не з людиною; про це каже й сам чат. " +
        "Чат на сайті відповідає на запитання про наші послуги, ціни, вільний " +
        "час і допомагає записатися. Ми зберігаємо розмову (ваші повідомлення та відповіді) і дані, які ви надаєте " +
        "для запису (ім’я, контакт), щоб вести розмову та виконати запис, на ваш запит (ст. 6 п. 1 б) GDPR). " +
        "Відповіді пише мовна модель Cloudflare Workers AI, яка не отримує наших цін і часу — їх вставляємо ми. " +
        s"Розмова видаляється через $days днів без активності, а видалити її можна будь-коли кнопкою в чаті.")
    case Lang.ru => "Ассистент (чат)" -> p("Ассистент — это система искусственного интеллекта (ИИ): " +
        "вы разговариваете не с человеком; об этом говорит и сам чат. " +
        "Чат на сайте отвечает на вопросы о наших услугах, ценах, свободном " +
        "времени и помогает записаться. Мы храним разговор (ваши сообщения и ответы) и данные, которые вы даёте " +
        "для записи (имя, контакт), чтобы вести разговор и выполнить запись, по вашему запросу (ст. 6 п. 1 б) " +
        "GDPR). Ответы пишет языковая модель Cloudflare Workers AI, которая не получает наших цен и времени — их " +
        s"вставляем мы. Разговор удаляется через $days дней без активности, а удалить его можно в любой момент " +
        "кнопкой в чате.")

  private def visit(l: Lang): (String, String) = l match
    case Lang.pl => "Adres wizyty" -> p("Gdy rezerwujesz wizytę u siebie, podajesz adres (ulica, kod, miasto, " +
        "uwagi o wejściu). Używamy go wyłącznie, by dojechać na umówioną wizytę: widzą go tylko nasi pracownicy " +
        "i Ty pod linkiem z potwierdzenia; nigdy nie jest publikowany. Przechowujemy go z rezerwacją.")
    case Lang.en => "The visit's address" -> p("When you book a visit at your place, you give its address (street, " +
        "postcode, city, notes on the entrance). We use it only to come to the visit booked: only our staff see it, " +
        "and you, by your confirmation's link; it is never published. We keep it with the booking.")
    case Lang.uk => "Адреса візиту" -> p("Коли ви бронюєте візит до себе, ви вказуєте адресу (вулиця, індекс, " +
        "місто, примітки щодо входу). Ми використовуємо її лише, щоб приїхати на домовлений візит: її бачать " +
        "тільки наші працівники і ви за посиланням з підтвердження; вона ніколи не публікується. Ми зберігаємо " +
        "її разом із записом.")
    case Lang.ru => "Адрес визита" -> p("Когда вы бронируете визит к себе, вы указываете адрес (улица, индекс, " +
        "город, заметки о входе). Мы используем его только, чтобы приехать на назначенный визит: его видят только " +
        "наши сотрудники и вы по ссылке из подтверждения; он никогда не публикуется. Мы храним его вместе с " +
        "записью.")

  /** the recipients: the host's items, the engine's (each ending ";"), InPost for the atelier; the last ends "." */
  private def recipients(intro: String, items: Vector[String], inpost: Option[String], outro: String,
                         host: Vector[String]): String =
    val engine = if inpost.isDefined then items
      else items.dropRight(1) ++ items.lastOption.map(_.stripSuffix(";") + ".")
    val all = host ++ engine ++ inpost.toVector
    p(intro) + "\n<ul>\n" + all.map(i => s"<li>$i</li>").mkString("\n") + "\n</ul>\n" + p(outro)

  /** HOW THE SITE'S CLIENTS' LETTERS GO, as its Worker's config says — the way `MailCloud` takes */
  enum Mail:
    case Cloudflare, Brevo, Resend
    /** an SMTP relay of its own (not one named here) */
    case Smtp(host: String)
    /** none set up: no letters to clients */
    case Off

  object Mail:
    /** from `MAIL_CLIENT` and `SMTP_HOST` as `Settings` reads them: `smtp` (or empty) by the relay's host */
    def of(client: String, smtpHost: String): Mail = client.trim.toLowerCase match
      case "cloudflare" => Cloudflare
      case "brevo" => Brevo
      case "resend" => Resend
      case _ =>
        val h = smtpHost.trim.toLowerCase
        if h.isEmpty then Off else if h.endsWith("brevo.com") || h.endsWith("sendinblue.com") then Brevo else Smtp(h)

  /** what the site uses that its policy names: the clients' mail, the assistant's model, the geocoding of a visit's
   * address (a travel radius, on a host that geocodes), the host's cookies (HTML items in the page's language) */
  final case class Uses(mail: Mail = Mail.Cloudflare, ai: Boolean = false, geo: Boolean = false,
                        cookies: Vector[String] = Vector.empty)

  /** THE ENGINE'S PROCESSORS for this config, in `l` (docs of the mall: podmioty-przetwarzajace.md) — Cloudflare's
   * transfer basis the EU-US Data Privacy Framework, the standard contractual clauses its fallback */
  private def items(l: Lang, u: Uses): Vector[String] =
    val (cf, turnstile, mailCf, brevo, resend, ai, geo) = l match
      case Lang.pl => ("Cloudflare, Inc. (USA) — hosting strony i przechowywanie danych; dane mogą być " +
          "przetwarzane w UE i w USA — przekazanie do USA na podstawie EU-US Data Privacy Framework (decyzja Komisji " +
          "Europejskiej stwierdzająca odpowiedni stopień ochrony; Cloudflare, Inc. ma certyfikację), a gdyby ta " +
          "podstawa przestała obowiązywać — standardowych klauzul umownych zatwierdzonych przez Komisję Europejską;",
        "Cloudflare Turnstile (Cloudflare, Inc.) — ochrona formularza przed botami: przetwarza dane techniczne " +
          "przeglądarki (np. adres IP), aby odróżnić człowieka od bota;",
        "Cloudflare Email Service (Cloudflare, Inc.) — wysyłka wiadomości e-mail do klientów;",
        "Sendinblue SAS (Brevo, Francja) — wysyłka wiadomości e-mail do klientów;",
        "Resend (USA) — wysyłka wiadomości e-mail do klientów, na podstawie standardowych klauzul umownych;",
        "Cloudflare Workers AI (Cloudflare, Inc.) — model językowy asystenta: otrzymuje treść wiadomości do " +
          "asystenta, nie używa jej do trenowania modeli;",
        "Główny Urząd Geodezji i Kartografii (Polska) oraz OpenStreetMap Foundation (Wielka Brytania — decyzja " +
          "Komisji Europejskiej o odpowiednim stopniu ochrony; Nominatim, gdy GUGiK nie znajdzie adresu) — zamiana " +
          "adresu wizyty na współrzędne, by sprawdzić odległość dojazdu: otrzymują tylko adres, bez imienia i " +
          "kontaktu;")
      case Lang.en => ("Cloudflare, Inc. (USA) — website hosting and data storage; data may be processed in the " +
          "EU and the US — the transfer to the US under the EU-US Data Privacy Framework (the European Commission's " +
          "adequacy decision; Cloudflare, Inc. is certified), and should that basis cease to apply, under the " +
          "standard contractual clauses approved by the European Commission;",
        "Cloudflare Turnstile (Cloudflare, Inc.) — protection of the form against bots: it processes technical " +
          "data of your browser (such as your IP address) to tell people from bots;",
        "Cloudflare Email Service (Cloudflare, Inc.) — sending e-mails to clients;",
        "Sendinblue SAS (Brevo, France) — sending e-mails to clients;",
        "Resend (USA) — sending e-mails to clients, under the standard contractual clauses;",
        "Cloudflare Workers AI (Cloudflare, Inc.) — the assistant's language model: it receives the messages to " +
          "the assistant and does not use them to train models;",
        "Główny Urząd Geodezji i Kartografii (Poland's surveying office) and the OpenStreetMap Foundation (the " +
          "United Kingdom — the European Commission's adequacy decision; Nominatim, when GUGiK does not find the " +
          "address) — turning a visit's address into coordinates to check the travel distance: they receive the " +
          "address only, never a name or a contact;")
      case Lang.uk => ("Cloudflare, Inc. (США) — хостинг сайту та зберігання даних; дані можуть оброблятися в ЄС " +
          "і США — передання до США на підставі EU-US Data Privacy Framework (рішення Європейської комісії про " +
          "належний рівень захисту; Cloudflare, Inc. має сертифікацію), а якщо ця підстава перестане діяти — " +
          "стандартних договірних положень, затверджених Європейською комісією;",
        "Cloudflare Turnstile (Cloudflare, Inc.) — захист форми від ботів: обробляє технічні дані браузера " +
          "(наприклад, IP-адресу), щоб відрізнити людину від бота;",
        "Cloudflare Email Service (Cloudflare, Inc.) — надсилання електронних листів клієнтам;",
        "Sendinblue SAS (Brevo, Франція) — надсилання електронних листів клієнтам;",
        "Resend (США) — надсилання електронних листів клієнтам, на підставі стандартних договірних положень;",
        "Cloudflare Workers AI (Cloudflare, Inc.) — мовна модель асистента: отримує текст повідомлень асистенту, " +
          "не використовує його для навчання моделей;",
        "Główny Urząd Geodezji i Kartografii (геодезична служба Польщі) та OpenStreetMap Foundation (Велика " +
          "Британія — рішення Європейської комісії про належний рівень захисту; Nominatim, коли GUGiK не знаходить " +
          "адресу) — перетворення адреси візиту на координати, щоб перевірити відстань виїзду: отримують лише " +
          "адресу, без імені та контакту;")
      case Lang.ru => ("Cloudflare, Inc. (США) — хостинг сайта и хранение данных; данные могут обрабатываться в ЕС " +
          "и США — передача в США на основании EU-US Data Privacy Framework (решение Европейской комиссии о " +
          "надлежащем уровне защиты; Cloudflare, Inc. сертифицирована), а если это основание перестанет действовать, " +
          "— стандартных договорных положений, утверждённых Европейской комиссией;",
        "Cloudflare Turnstile (Cloudflare, Inc.) — защита формы от ботов: обрабатывает технические данные " +
          "браузера (например, IP-адрес), чтобы отличить человека от бота;",
        "Cloudflare Email Service (Cloudflare, Inc.) — отправка электронных писем клиентам;",
        "Sendinblue SAS (Brevo, Франция) — отправка электронных писем клиентам;",
        "Resend (США) — отправка электронных писем клиентам, на основании стандартных договорных положений;",
        "Cloudflare Workers AI (Cloudflare, Inc.) — языковая модель ассистента: получает текст сообщений " +
          "ассистенту, не использует его для обучения моделей;",
        "Główny Urząd Geodezji i Kartografii (геодезическая служба Польши) и OpenStreetMap Foundation " +
          "(Великобритания — решение Европейской комиссии о надлежащем уровне защиты; Nominatim, когда GUGiK не " +
          "находит адрес) — преобразование адреса визита в координаты, чтобы проверить расстояние выезда: " +
          "получают только адрес, без имени и контакта;")
    val relay = (h: String) => l match
      case Lang.pl => s"dostawca serwera poczty ${Html.escape(h)} — wysyłka wiadomości e-mail do klientów;"
      case Lang.en => s"the provider of the mail server ${Html.escape(h)} — sending e-mails to clients;"
      case Lang.uk => s"постачальник поштового сервера ${Html.escape(h)} — надсилання електронних листів клієнтам;"
      case Lang.ru => s"поставщик почтового сервера ${Html.escape(h)} — отправка электронных писем клиентам;"
    val mailing = u.mail match
      case Mail.Cloudflare => Vector(mailCf)
      case Mail.Brevo => Vector(brevo)
      case Mail.Resend => Vector(resend)
      case Mail.Smtp(h) => Vector(relay(h))
      case Mail.Off => Vector.empty
    Vector(cf, turnstile) ++ mailing ++ Option.when(u.ai)(ai) ++ Option.when(u.geo)(geo)

  /** THE COOKIES the engine sets, with their purpose, lifetime and kind, then the host's; whether consent is needed */
  private def cookies(l: Lang, u: Uses): String =
    val (intro, lang, session, warm, outro) = l match
      case Lang.pl => ("Strona używa wyłącznie plików cookie i pamięci przeglądarki, które są niezbędne do jej " +
          "działania albo uruchamiasz je swoim działaniem:",
        "<code>lang</code> — zapamiętuje język wybrany kliknięciem; 1 rok; funkcjonalny, ustawiany na Twoje żądanie;",
        "<code>__Host-ws</code> — sesja panelu dla zalogowanych pracowników; 30 dni lub do wylogowania; niezbędny " +
          "(HttpOnly, Secure);",
        "<code>okc-warm</code> (pamięć sesji przeglądarki, nie cookie) — czas ostatniego przygotowania asystenta, " +
          "by nie robić tego częściej niż raz na minutę; do zamknięcia karty; techniczny;",
        "Nie używamy plików cookie reklamowych, analitycznych ani śledzących. Ponieważ używane pliki są niezbędne " +
          "do świadczenia usługi, o którą prosisz, nie prosimy o zgodę na nie.")
      case Lang.en => ("The website uses only cookies and browser storage that it needs to work or that you start " +
          "by your own action:",
        "<code>lang</code> — remembers the language you chose by a click; 1 year; functional, set at your request;",
        "<code>__Host-ws</code> — the back office's session for signed-in staff; 30 days or until sign-out; " +
          "strictly necessary (HttpOnly, Secure);",
        "<code>okc-warm</code> (the browser's session storage, not a cookie) — when the assistant was last " +
          "prepared, so it is not done more than once a minute; until the tab is closed; technical;",
        "We do not use advertising, analytics or tracking cookies. As those used are needed for the service you " +
          "ask for, we do not ask for consent to them.")
      case Lang.uk => ("Сайт використовує лише файли cookie та сховище браузера, необхідні для його роботи або " +
          "увімкнені вашою дією:",
        "<code>lang</code> — запам’ятовує мову, обрану кліком; 1 рік; функціональний, встановлюється на ваш запит;",
        "<code>__Host-ws</code> — сесія панелі для працівників, що увійшли; 30 днів або до виходу; необхідний " +
          "(HttpOnly, Secure);",
        "<code>okc-warm</code> (сесійне сховище браузера, не cookie) — час останньої підготовки асистента, щоб " +
          "не робити її частіше ніж раз на хвилину; до закриття вкладки; технічний;",
        "Ми не використовуємо рекламних, аналітичних чи відстежувальних cookie. Оскільки використовувані файли " +
          "необхідні для послуги, якої ви просите, ми не просимо згоди на них.")
      case Lang.ru => ("Сайт использует только файлы cookie и хранилище браузера, необходимые для его работы или " +
          "включаемые вашим действием:",
        "<code>lang</code> — запоминает язык, выбранный щелчком; 1 год; функциональный, ставится по вашему запросу;",
        "<code>__Host-ws</code> — сессия панели для вошедших сотрудников; 30 дней или до выхода; необходимый " +
          "(HttpOnly, Secure);",
        "<code>okc-warm</code> (сессионное хранилище браузера, не cookie) — время последней подготовки " +
          "ассистента, чтобы не делать её чаще раза в минуту; до закрытия вкладки; технический;",
        "Мы не используем рекламные, аналитические и отслеживающие cookie. Поскольку используемые файлы необходимы " +
          "для услуги, которую вы запрашиваете, мы не просим согласия на них.")
    val all = Vector(lang, session) ++ Option.when(u.ai)(warm) ++ u.cookies
    p(intro) + "\n<ul>\n" + all.map(i => s"<li>$i</li>").mkString("\n") + "\n</ul>\n" + p(outro)

  // ---- Polish: the binding text ----

  private def pl(clothing: Boolean, name: String, contact: String, host: Vector[String], u: Uses): Texts =
    val order = if clothing then "zamówienia" else "zlecenia"
    val collected =
      if clothing then "opis zlecenia (rodzaj i stan odzieży), dane dostawy (jeśli korzystasz z kuriera InPost)."
      else "opis zadania (czego dotyczy zlecenie)."
    val service = if clothing then "usługi krawieckie/przeróbki" else "usługi IT i tworzenie oprogramowania"
    Texts("Polityka Prywatności", "data ostatniej aktualizacji", None, Vector(
      "Administrator danych" -> p(
        s"Administratorem Twoich danych osobowych jest właściciel firmy <strong>$name</strong>, " +
          s"prowadzącej działalność gospodarczą w Polsce (JDG). Kontakt: $contact"),
      "Jakie dane zbieramy" -> p(
        "W formularzu zgłoszeniowym zbieramy: imię i nazwisko lub pseudonim, adres e-mail i/lub numer telefonu, " +
          collected + " Przy wysyłaniu formularza mechanizm ochrony przed botami przetwarza dane techniczne " +
          "przeglądarki (np. adres IP)."),
      "Cel przetwarzania" -> p(
        s"Twoje dane przetwarzamy wyłącznie w celu realizacji $order ($service) oraz komunikacji w jego sprawie, " +
          "a także w celu ochrony formularza przed spamem i automatycznymi zgłoszeniami. " +
          "Nie używamy danych do celów marketingowych bez Twojej zgody."),
      "Podstawa prawna" -> p(
        "Art. 6 ust. 1 lit. b) RODO — przetwarzanie niezbędne do wykonania umowy, " +
          "art. 6 ust. 1 lit. a) RODO — Twoja zgoda wyrażona w formularzu, " +
          "oraz art. 6 ust. 1 lit. f) RODO — nasz prawnie uzasadniony interes w ochronie formularza " +
          "przed spamem i botami. Zgodę zapisujemy wraz z datą i godziną jej wyrażenia oraz wersją niniejszej " +
          "polityki, aby móc wykazać jej udzielenie (art. 7 ust. 1 RODO). Zgodę możesz w każdej chwili wycofać; " +
          "nie wpływa to na zgodność z prawem przetwarzania dokonanego przed jej wycofaniem."),
      "Czas przechowywania" -> p(
        s"Dane $order, rezerwacji, zapytania i opinii przechowujemy przez 5 lat od ostatniej czynności w sprawie " +
          s"(np. realizacji $order lub wizyty), a następnie usuwamy je automatycznie. Dokumenty rozliczeniowe " +
          "(faktury, rachunki, płatności) przechowujemy tak długo, jak wymagają tego przepisy podatkowe."),
      "Twoje prawa" -> p(
        "Masz prawo do: dostępu do danych, sprostowania, usunięcia («prawo do bycia zapomnianym»), " +
          "ograniczenia przetwarzania, przenoszenia danych, wniesienia sprzeciwu. " +
          s"Aby skorzystać z tych praw, skontaktuj się z nami: $contact"),
      "Odbiorcy danych" -> recipients(
        "Twoje dane powierzamy wyłącznie podmiotom przetwarzającym, które pomagają nam prowadzić stronę " +
          s"i realizować $order, i tylko w niezbędnym zakresie:",
        items(Lang.pl, u),
        Option.when(clothing)("InPost S.A. — operator paczek, wyłącznie w zakresie niezbędnym do dostarczenia " +
          "przesyłki."),
        "Nie sprzedajemy danych osobom trzecim.", host),
      "Pliki cookie" -> cookies(Lang.pl, u),
      "Skargi" -> p(
        "Jeśli uważasz, że przetwarzamy Twoje dane niezgodnie z prawem, możesz wnieść skargę " +
          "do Prezesa Urzędu Ochrony Danych Osobowych (uodo.gov.pl).")))

  // ---- English ----

  private def en(clothing: Boolean, name: String, contact: String, host: Vector[String], u: Uses): Texts =
    val collected =
      if clothing then "a description of the order (type and condition of the garments), and delivery details " +
        "(if you use the InPost courier)."
      else "and a description of the task."
    val service = if clothing then "tailoring and alterations" else "IT services and software development"
    Texts("Privacy Policy", "last updated", Some("This is a translation; the Polish version is binding."), Vector(
      "Data controller" -> p(
        s"The controller of your personal data is the owner of <strong>$name</strong>, " +
          s"a sole proprietorship (JDG) operating in Poland. Contact: $contact"),
      "What data we collect" -> p(
        "Through the request form we collect: your full name or a nickname, your e-mail address and/or " +
          s"phone number, $collected When you submit the form, the bot-protection service processes " +
          "technical data of your browser (such as your IP address)."),
      "Purpose of processing" -> p(
        s"We process your data solely to carry out your order ($service) and to communicate with you about it, " +
          "and to protect the form against spam and automated submissions. " +
          "We do not use your data for marketing purposes without your consent."),
      "Legal basis" -> p(
        "Article 6(1)(b) GDPR — processing necessary for the performance of a contract; " +
          "Article 6(1)(a) GDPR — your consent given in the form; and Article 6(1)(f) GDPR — our legitimate " +
          "interest in protecting the form against spam and bots. We store your consent together with the date " +
          "and time it was given and the version of this policy, so that we can demonstrate it " +
          "(Article 7(1) GDPR). You may withdraw your consent at any time; this does not affect the lawfulness " +
          "of processing carried out before its withdrawal."),
      "Retention period" -> p(
        "We keep the data of an order, a booking, an inquiry and a review for 5 years from the last action in the " +
          "matter (such as the order's completion or the visit), after which it is deleted automatically. Accounting " +
          "records (invoices, receipts, payments) are kept for as long as tax law requires."),
      "Your rights" -> p(
        "You have the right to access your data, to have it rectified or erased (the “right to be forgotten”), " +
          "to restrict its processing, to data portability, and to object to processing. " +
          s"To exercise these rights, contact us: $contact"),
      "Recipients of data" -> recipients(
        "We entrust your data only to processors that help us run the website and fulfil orders, " +
          "and only to the extent necessary:",
        items(Lang.en, u),
        Option.when(clothing)("InPost S.A. — parcel operator, only to the extent necessary to deliver " +
          "the parcel."),
        "We do not sell your data to third parties.", host),
      "Cookies" -> cookies(Lang.en, u),
      "Complaints" -> p(
        "If you believe that we process your data unlawfully, you may lodge a complaint with the President " +
          "of the Personal Data Protection Office of Poland (Prezes Urzędu Ochrony Danych Osobowych, " +
          "uodo.gov.pl).")))

  // ---- Ukrainian ----

  private def uk(clothing: Boolean, name: String, contact: String, host: Vector[String], u: Uses): Texts =
    val collected =
      if clothing then "опис замовлення (вид і стан одягу), дані для доставки (якщо ви користуєтеся " +
        "кур’єром InPost)."
      else "опис завдання."
    val service = if clothing then "кравецькі послуги та переробка одягу" else "ІТ-послуги та розробка програм"
    Texts("Політика конфіденційності", "дата останнього оновлення",
      Some("Це переклад; юридично обов’язковою є польська версія."), Vector(
      "Адміністратор даних" -> p(
        s"Адміністратором ваших персональних даних є власник <strong>$name</strong>, який провадить " +
          s"підприємницьку діяльність у Польщі як фізична особа-підприємець (JDG). Контакт: $contact"),
      "Які дані ми збираємо" -> p(
        "У формі заявки ми збираємо: ім’я та прізвище або псевдонім, адресу електронної пошти та/або " +
          s"номер телефону, $collected Під час надсилання форми служба захисту від ботів обробляє " +
          "технічні дані вашого браузера (наприклад, IP-адресу)."),
      "Мета обробки" -> p(
        s"Ми обробляємо ваші дані виключно для виконання замовлення ($service) і зв’язку з вами щодо нього, " +
          "а також для захисту форми від спаму та автоматичних заявок. " +
          "Без вашої згоди ми не використовуємо дані в маркетингових цілях."),
      "Правова підстава" -> p(
        "Ст. 6(1)(b) GDPR (RODO) — обробка, необхідна для виконання договору; " +
          "ст. 6(1)(a) GDPR — ваша згода, надана у формі; ст. 6(1)(f) GDPR — наш законний інтерес " +
          "у захисті форми від спаму та ботів. Згоду ми зберігаємо разом із датою й часом її надання " +
          "та версією цієї політики, щоб мати змогу її підтвердити (ст. 7(1) GDPR). Ви можете будь-коли " +
          "відкликати згоду; це не впливає на законність обробки, здійсненої до її відкликання."),
      "Строк зберігання" -> p(
        "Дані замовлення, запису, запиту та відгуку ми зберігаємо протягом 5 років від останньої дії у справі " +
          "(наприклад, виконання замовлення або візиту), після чого автоматично видаляємо їх. Облікові документи " +
          "(рахунки, чеки, платежі) зберігаємо стільки, скільки вимагає податкове законодавство."),
      "Ваші права" -> p(
        "Ви маєте право на доступ до своїх даних, їх виправлення, видалення («право бути забутим»), " +
          "обмеження обробки, перенесення даних, а також право заперечити проти обробки. " +
          s"Щоб скористатися цими правами, зв’яжіться з нами: $contact"),
      "Одержувачі даних" -> recipients(
        "Ми доручаємо обробку ваших даних лише постачальникам послуг, які допомагають нам підтримувати " +
          "роботу сайту й виконувати замовлення, і лише в необхідному обсязі:",
        items(Lang.uk, u),
        Option.when(clothing)("InPost S.A. — оператор доставки посилок, лише в обсязі, необхідному " +
          "для доставки відправлення."),
        "Ми не продаємо ваші дані третім особам.", host),
      "Файли cookie" -> cookies(Lang.uk, u),
      "Скарги" -> p(
        "Якщо ви вважаєте, що ми обробляємо ваші дані з порушенням закону, ви можете подати скаргу " +
          "Голові Управління з питань захисту персональних даних Польщі (Prezes Urzędu Ochrony Danych " +
          "Osobowych, uodo.gov.pl).")))

  // ---- Russian ----

  private def ru(clothing: Boolean, name: String, contact: String, host: Vector[String], u: Uses): Texts =
    val collected =
      if clothing then "описание заказа (вид и состояние одежды), данные для доставки (если вы пользуетесь " +
        "курьером InPost)."
      else "описание задачи."
    val service = if clothing then "пошив и переделка одежды" else "ИТ-услуги и разработка программ"
    Texts("Политика конфиденциальности", "дата последнего обновления",
      Some("Это перевод; юридически обязательной является польская версия."), Vector(
      "Администратор данных" -> p(
        s"Администратором ваших персональных данных является владелец <strong>$name</strong>, ведущий " +
          s"предпринимательскую деятельность в Польше как индивидуальный предприниматель (JDG). " +
          s"Контакт: $contact"),
      "Какие данные мы собираем" -> p(
        "В форме заявки мы собираем: имя и фамилию или псевдоним, адрес электронной почты и/или " +
          s"номер телефона, $collected При отправке формы служба защиты от ботов обрабатывает " +
          "технические данные вашего браузера (например, IP-адрес)."),
      "Цель обработки" -> p(
        s"Мы обрабатываем ваши данные исключительно для выполнения заказа ($service) и связи с вами по нему, " +
          "а также для защиты формы от спама и автоматических заявок. " +
          "Без вашего согласия мы не используем данные в маркетинговых целях."),
      "Правовое основание" -> p(
        "Ст. 6(1)(b) GDPR (RODO) — обработка, необходимая для исполнения договора; " +
          "ст. 6(1)(a) GDPR — ваше согласие, выраженное в форме; ст. 6(1)(f) GDPR — наш законный интерес " +
          "в защите формы от спама и ботов. Согласие мы сохраняем вместе с датой и временем его " +
          "предоставления и версией настоящей политики, чтобы иметь возможность его подтвердить " +
          "(ст. 7(1) GDPR). Вы можете в любой момент отозвать согласие; это не влияет на законность " +
          "обработки, осуществлённой до его отзыва."),
      "Срок хранения" -> p(
        "Данные заказа, записи, запроса и отзыва мы храним в течение 5 лет с последнего действия по делу " +
          "(например, выполнения заказа или визита), после чего автоматически удаляем их. Учётные документы " +
          "(счета, чеки, платежи) храним столько, сколько требует налоговое законодательство."),
      "Ваши права" -> p(
        "Вы имеете право на доступ к своим данным, их исправление, удаление («право на забвение»), " +
          "ограничение обработки, переносимость данных, а также право возразить против обработки. " +
          s"Чтобы воспользоваться этими правами, свяжитесь с нами: $contact"),
      "Получатели данных" -> recipients(
        "Мы поручаем обработку ваших данных только поставщикам услуг, которые помогают нам обеспечивать " +
          "работу сайта и выполнять заказы, и только в необходимом объёме:",
        items(Lang.ru, u),
        Option.when(clothing)("InPost S.A. — оператор доставки посылок, только в объёме, необходимом " +
          "для доставки отправления."),
        "Мы не продаём ваши данные третьим лицам.", host),
      "Файлы cookie" -> cookies(Lang.ru, u),
      "Жалобы" -> p(
        "Если вы считаете, что мы обрабатываем ваши данные с нарушением закона, вы можете подать жалобу " +
          "Председателю Управления по защите персональных данных Польши (Prezes Urzędu Ochrony Danych " +
          "Osobowych, uodo.gov.pl).")))
