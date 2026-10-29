package okay.shop.html

import okay.shop.model.*

/** THE PRIVACY POLICY (RODO/GDPR) of a site: binding in Polish, translated to English, Ukrainian and Russian.
  * The look is the old template's: back link, title, last-updated date, numbered sections, footer line. */
object Privacy:
  /** the policy's version: a date, stored with every consent */
  val version: String = "2026-10-28"

  /** where the controller is reached, by default `kontakt@` the site's own domain (its last two labels) */
  def email(site: Site): String = "kontakt@" + site.domain.split('.').takeRight(2).mkString(".")

  /** one language's texts: the title, the «last updated» words, the translation note, the sections */
  private final case class Texts(title: String, updated: String, note: Option[String],
                                 sections: Vector[(String, String)])

  /** the page for this site in this language: a whole HTML document */
  def page(site: Site, l: Lang, email: String = ""): String =
    val address = Html.escape(if email.nonEmpty then email else this.email(site))
    val contact = s"""<a href="mailto:$address">$address</a>"""
    val name = Html.escape(site.name)
    val accent = site.color
    val t = texts(l, site.clothing, name, contact)
    val note = t.note.fold("")(n => s"\n<p><em>$n</em></p>")
    // a shop that comes to its clients keeps their addresses: said after what is collected
    val visits = site.work.filter(_.has(Mode.AtClient)).fold(t.sections)(_ =>
      t.sections.take(2) ++ (visit(l) +: t.sections.drop(2)))
    val sections = visits.zipWithIndex.map { case ((h, body), i) => s"<h2>${i + 1}. $h</h2>\n$body" }
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

  private def texts(l: Lang, clothing: Boolean, name: String, contact: String): Texts = l match
    case Lang.pl => pl(clothing, name, contact)
    case Lang.en => en(clothing, name, contact)
    case Lang.uk => uk(clothing, name, contact)
    case Lang.ru => ru(clothing, name, contact)

  private def p(s: String): String = s"<p>$s</p>"

  /** the section of a shop that comes to its clients (specs/shop.md "How the work happens": privacy) */
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

  /** the recipients: Cloudflare (hosting, Turnstile), Brevo, and InPost for the atelier */
  private def recipients(intro: String, items: Vector[String], inpost: Option[String], outro: String): String =
    val all = items ++ inpost.toVector
    p(intro) + "\n<ul>\n" + all.map(i => s"<li>$i</li>").mkString("\n") + "\n</ul>\n" + p(outro)

  // ---- Polish: the binding text ----

  private def pl(clothing: Boolean, name: String, contact: String): Texts =
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
        s"Dane przechowujemy przez 5 lat od daty realizacji $order (wymóg przepisów podatkowych), " +
          "a następnie usuwamy."),
      "Twoje prawa" -> p(
        "Masz prawo do: dostępu do danych, sprostowania, usunięcia («prawo do bycia zapomnianym»), " +
          "ograniczenia przetwarzania, przenoszenia danych, wniesienia sprzeciwu. " +
          s"Aby skorzystać z tych praw, skontaktuj się z nami: $contact"),
      "Odbiorcy danych" -> recipients(
        "Twoje dane powierzamy wyłącznie podmiotom przetwarzającym, które pomagają nam prowadzić stronę " +
          s"i realizować $order, i tylko w niezbędnym zakresie:",
        Vector(
          "Cloudflare, Inc. — hosting strony i przechowywanie danych; dane mogą być przetwarzane w UE i w USA " +
            "na podstawie standardowych klauzul umownych zatwierdzonych przez Komisję Europejską;",
          "Cloudflare Turnstile (Cloudflare, Inc.) — ochrona formularza przed botami: przetwarza dane techniczne " +
            "przeglądarki (np. adres IP), aby odróżnić człowieka od bota;",
          "Sendinblue SAS (Brevo, Francja) — wysyłka wiadomości e-mail do klientów" + (if clothing then ";" else ".")),
        Option.when(clothing)("InPost S.A. — operator paczek, wyłącznie w zakresie niezbędnym do dostarczenia " +
          "przesyłki."),
        "Nie sprzedajemy danych osobom trzecim."),
      "Pliki cookie" -> p(
        "Strona używa wyłącznie technicznych plików cookie: <code>lang</code> zapamiętuje wybrany język, " +
          "a pracownicy obsługujący zgłoszenia po zalogowaniu otrzymują cookie sesji panelu administracyjnego. " +
          "Nie korzystamy z cookies reklamowych ani śledzących."),
      "Skargi" -> p(
        "Jeśli uważasz, że przetwarzamy Twoje dane niezgodnie z prawem, możesz wnieść skargę " +
          "do Prezesa Urzędu Ochrony Danych Osobowych (uodo.gov.pl).")))

  // ---- English ----

  private def en(clothing: Boolean, name: String, contact: String): Texts =
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
        "We keep your data for 5 years from the date the order was completed (as required by tax law), " +
          "after which it is deleted."),
      "Your rights" -> p(
        "You have the right to access your data, to have it rectified or erased (the “right to be forgotten”), " +
          "to restrict its processing, to data portability, and to object to processing. " +
          s"To exercise these rights, contact us: $contact"),
      "Recipients of data" -> recipients(
        "We entrust your data only to processors that help us run the website and fulfil orders, " +
          "and only to the extent necessary:",
        Vector(
          "Cloudflare, Inc. — website hosting and data storage; data may be processed in the EU and the US " +
            "under the standard contractual clauses approved by the European Commission;",
          "Cloudflare Turnstile (Cloudflare, Inc.) — protection of the form against bots: it processes technical " +
            "data of your browser (such as your IP address) to tell people from bots;",
          "Sendinblue SAS (Brevo, France) — sending e-mails to clients" + (if clothing then ";" else ".")),
        Option.when(clothing)("InPost S.A. — parcel operator, only to the extent necessary to deliver " +
          "the parcel."),
        "We do not sell your data to third parties."),
      "Cookies" -> p(
        "The website uses technical cookies only: <code>lang</code> remembers the language you chose, " +
          "and staff who handle requests receive a sign-in session cookie for the back office. " +
          "We do not use advertising or tracking cookies."),
      "Complaints" -> p(
        "If you believe that we process your data unlawfully, you may lodge a complaint with the President " +
          "of the Personal Data Protection Office of Poland (Prezes Urzędu Ochrony Danych Osobowych, " +
          "uodo.gov.pl).")))

  // ---- Ukrainian ----

  private def uk(clothing: Boolean, name: String, contact: String): Texts =
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
        "Ми зберігаємо дані протягом 5 років від дати виконання замовлення (вимога податкового " +
          "законодавства), після чого видаляємо їх."),
      "Ваші права" -> p(
        "Ви маєте право на доступ до своїх даних, їх виправлення, видалення («право бути забутим»), " +
          "обмеження обробки, перенесення даних, а також право заперечити проти обробки. " +
          s"Щоб скористатися цими правами, зв’яжіться з нами: $contact"),
      "Одержувачі даних" -> recipients(
        "Ми доручаємо обробку ваших даних лише постачальникам послуг, які допомагають нам підтримувати " +
          "роботу сайту й виконувати замовлення, і лише в необхідному обсязі:",
        Vector(
          "Cloudflare, Inc. — хостинг сайту та зберігання даних; дані можуть оброблятися в ЄС і США " +
            "на підставі стандартних договірних положень, затверджених Європейською комісією;",
          "Cloudflare Turnstile (Cloudflare, Inc.) — захист форми від ботів: обробляє технічні дані браузера " +
            "(наприклад, IP-адресу), щоб відрізнити людину від бота;",
          "Sendinblue SAS (Brevo, Франція) — надсилання електронних листів клієнтам" +
            (if clothing then ";" else ".")),
        Option.when(clothing)("InPost S.A. — оператор доставки посилок, лише в обсязі, необхідному " +
          "для доставки відправлення."),
        "Ми не продаємо ваші дані третім особам."),
      "Файли cookie" -> p(
        "Сайт використовує лише технічні файли cookie: <code>lang</code> запам’ятовує обрану мову, " +
          "а працівники, які опрацьовують заявки, після входу отримують сесійний cookie панелі адміністрування. " +
          "Ми не використовуємо рекламних чи відстежувальних cookie."),
      "Скарги" -> p(
        "Якщо ви вважаєте, що ми обробляємо ваші дані з порушенням закону, ви можете подати скаргу " +
          "Голові Управління з питань захисту персональних даних Польщі (Prezes Urzędu Ochrony Danych " +
          "Osobowych, uodo.gov.pl).")))

  // ---- Russian ----

  private def ru(clothing: Boolean, name: String, contact: String): Texts =
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
        "Мы храним данные в течение 5 лет с даты выполнения заказа (требование налогового законодательства), " +
          "после чего удаляем их."),
      "Ваши права" -> p(
        "Вы имеете право на доступ к своим данным, их исправление, удаление («право на забвение»), " +
          "ограничение обработки, переносимость данных, а также право возразить против обработки. " +
          s"Чтобы воспользоваться этими правами, свяжитесь с нами: $contact"),
      "Получатели данных" -> recipients(
        "Мы поручаем обработку ваших данных только поставщикам услуг, которые помогают нам обеспечивать " +
          "работу сайта и выполнять заказы, и только в необходимом объёме:",
        Vector(
          "Cloudflare, Inc. — хостинг сайта и хранение данных; данные могут обрабатываться в ЕС и США " +
            "на основании стандартных договорных положений, утверждённых Европейской комиссией;",
          "Cloudflare Turnstile (Cloudflare, Inc.) — защита формы от ботов: обрабатывает технические данные " +
            "браузера (например, IP-адрес), чтобы отличить человека от бота;",
          "Sendinblue SAS (Brevo, Франция) — отправка электронных писем клиентам" +
            (if clothing then ";" else ".")),
        Option.when(clothing)("InPost S.A. — оператор доставки посылок, только в объёме, необходимом " +
          "для доставки отправления."),
        "Мы не продаём ваши данные третьим лицам."),
      "Файлы cookie" -> p(
        "Сайт использует только технические файлы cookie: <code>lang</code> запоминает выбранный язык, " +
          "а сотрудники, обрабатывающие заявки, после входа получают сессионный cookie панели администрирования. " +
          "Мы не используем рекламные и отслеживающие cookie."),
      "Жалобы" -> p(
        "Если вы считаете, что мы обрабатываем ваши данные с нарушением закона, вы можете подать жалобу " +
          "Председателю Управления по защите персональных данных Польши (Prezes Urzędu Ochrony Danych " +
          "Osobowych, uodo.gov.pl).")))
