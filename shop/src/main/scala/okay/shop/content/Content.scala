package okay.shop.content

import okay.effects.json.Json
import okay.shop.model.*

/** A SERVICE AS ITS OWNER EDITS IT (specs/shop.md "The owner edits the content"): its key (stable; empty: a new one,
 * made from its name), name and description in the four languages, price (grosze), minutes, whether quoted, a
 * class's seats and weekly times, whether hidden */
final case class Service(key: String, name: T, description: T, price: Long, minutes: Int = 0, quote: Boolean = false,
                         seats: Int = 0, times: Vector[(Int, Int)] = Vector.empty, hidden: Boolean = false)

/**
 * A SHOP'S CONTENT AS ITS OWNER EDITS IT: the language its owner writes in (its text is `site.md`'s), its name and
 * logo text, its tagline and public contact text, its services in their order, and the texts machine-translated and
 * not yet read by a person (`<token>.<lang>`: `tagline.en`, `name.cut.uk`)
 */
final case class Content(lang: Lang, name: String, logoText: String, tagline: T, contact: T,
                         services: Vector[Service], auto: Set[String] = Set.empty):
  /** every text by its token: the dictionary's (`tagline`, `contact`, `name.<key>`, `desc.<key>`) */
  def texts: Vector[(String, T)] = Vector("tagline" -> tagline, "contact" -> contact) ++
    services.flatMap(s => Vector(s"name.${s.key}" -> s.name, s"desc.${s.key}" -> s.description))

object Content:
  // ---- READ

  def read(siteMd: String, i18nMd: String): Content =
    val f = Parse.frontMatter(siteMd)
    val dict = Sites.dictionary(i18nMd)
    val lang = f.get("langDefault").flatMap(Lang.parse).getOrElse(Lang.pl)
    // a text's own language is the content's text, whatever its row says
    def words(token: String, text: String): T = own(Sites.words(dict, token, text), lang, text)
    def auto(token: String, text: String): Set[String] = dict.get(token).filter(_.get("src").contains(text))
      .flatMap(_.get("auto")).fold(Set.empty)(a => a.split("[ ,]+").toSet.flatMap(Lang.parse).filter(_ != lang)
        .map(l => s"$token.${l.code}"))
    val rows = Parse.block(siteMd, "services")
    val services = rows.map { m =>
      val key = m.getOrElse("key", "")
      val name = m.getOrElse("name", "")
      val description = m.getOrElse("description", "")
      (m.get("order").flatMap(_.toIntOption).getOrElse(0), Service(key, words(s"name.$key", name),
        words(s"desc.$key", description), Money.parse(m.getOrElse("price", "0")).getOrElse(0L),
        m.get("minutes").flatMap(_.toIntOption).getOrElse(0), m.get("quote").contains("true"),
        m.get("seats").flatMap(_.toIntOption).getOrElse(0), m.get("times").flatMap(Work.times).getOrElse(Vector.empty),
        m.get("hidden").contains("true")), auto(s"name.$key", name) ++ auto(s"desc.$key", description))
    }.sortBy(_._1)
    val name = f.getOrElse("name", "")
    val tagline = f.getOrElse("tagline", "")
    val contact = f.getOrElse("contact", "")
    Content(lang, name, f.getOrElse("logoText", name), words("tagline", tagline), words("contact", contact),
      services.map(_._2), auto("tagline", tagline) ++ auto("contact", contact) ++ services.flatMap(_._3))

  private def own(t: T, l: Lang, text: String): T = l match
    case Lang.ru => t.copy(ru = text)
    case Lang.pl => t.copy(pl = text)
    case Lang.en => t.copy(en = text)
    case Lang.uk => t.copy(uk = text)

  // ---- CHECKED

  /** a text of one line: whitespace runs one space, trimmed */
  def line(s: String): String = s.replaceAll("\\s+", " ").trim

  val maxServices = 50
  private val keyForm = "[a-z0-9][a-z0-9-]{0,29}"

  /** the content with its texts made one line, checked: the name of its first wrong field, or the content */
  def check(c0: Content): Either[String, Content] =
    // a language left empty takes the text of the shop's own, marked as not translated
    val empties = c0.texts.flatMap((token, t) => Lang.values.toVector.filter(l => l != c0.lang &&
      line(t(l)).isEmpty && line(t(c0.lang)).nonEmpty).map(l => s"$token.${l.code}"))
    val c = c0.copy(auto = c0.auto ++ empties)
    def lines(t: T) =
      val own = line(t(c.lang))
      t.map(line).map(v => if v.isEmpty then own else v)
    val x = c.copy(name = line(c.name), logoText = line(c.logoText), tagline = lines(c.tagline),
      contact = lines(c.contact), services = c.services.map(s => s.copy(key = s.key.trim, name = lines(s.name),
        description = lines(s.description))))
    def within(t: T, lo: Int, hi: Int) = Lang.values.forall(l => t(l).length >= lo && t(l).length <= hi)
    val keys = x.services.map(_.key).filter(_.nonEmpty)
    if x.name.isEmpty || x.name.length > 80 then Left("name")
    else if x.logoText.isEmpty || x.logoText.length > 40 then Left("logoText")
    else if !within(x.tagline, 1, 160) then Left("tagline")
    else if !within(x.contact, 0, 300) then Left("contact")
    else if x.services.size > maxServices then Left("services")
    else if keys.exists(!_.matches(keyForm)) || keys.distinct.size != keys.size then Left("service.key")
    else x.services.collectFirst(Function.unlift(problem)).toLeft(x)

  /** what is wrong with a service: its field's name */
  private def problem(s: Service): Option[String] =
    def within(t: T, lo: Int, hi: Int) = Lang.values.forall(l => t(l).length >= lo && t(l).length <= hi)
    if !within(s.name, 1, 80) then Some("service.name")
    else if !within(s.description, 0, 300) then Some("service.description")
    else if s.price < 0 || s.price > 10000000L then Some("service.price")
    else if s.minutes != 0 && (s.minutes < 5 || s.minutes > 480) then Some("service.minutes")
    else if s.seats < 0 || s.seats > 200 then Some("service.seats")
    else if s.seats > 0 && (s.times.isEmpty || s.minutes == 0) then Some("service.times")
    else if s.times.size > 28 then Some("service.times")
    else None

  // ---- KEYS, AND WHAT A REMOVAL KEEPS

  /** a key from a name: ASCII letters and digits, hyphens between; `service` when nothing is left */
  def slug(name: String): String =
    val folded = name.toLowerCase.map(c => fold.getOrElse(c, c))
    Some(folded.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").take(26).stripSuffix("-")).filter(_.nonEmpty)
      .getOrElse("service")
  private val fold: Map[Char, Char] = Map('ą' -> 'a', 'ć' -> 'c', 'ę' -> 'e', 'ł' -> 'l', 'ń' -> 'n', 'ó' -> 'o',
    'ś' -> 's', 'ź' -> 'z', 'ż' -> 'z', 'ä' -> 'a', 'ö' -> 'o', 'ü' -> 'u', 'é' -> 'e', 'è' -> 'e', 'á' -> 'a')

  /** a new service's key from its name: none of `taken`, and none another service has — a renamed one keeps its own */
  def key(name: String, taken: Set[String]): String =
    val base = slug(name)
    LazyList.from(1).map(n => if n == 1 then base else s"$base-$n").find(!taken(_)).get

  /** the services named by what the shop has made: orders, bookings, inquiries for a quote */
  def used(orders: Vector[Order], bookings: Vector[Booking], quotes: Vector[Quote]): Set[String] =
    orders.map(_.offerKey).toSet ++ bookings.map(_.service) ++ quotes.map(_.service)

  /** `after` with every service removed from `before` that something names kept, hidden, at the end; and their keys */
  def keeping(before: Content, after: Content, used: Set[String]): (Content, Vector[String]) =
    val kept = before.services.filter(s => used(s.key) && !after.services.exists(_.key == s.key))
    (after.copy(services = after.services ++ kept.map(_.copy(hidden = true)),
      auto = after.auto ++ before.auto.filter(a => kept.exists(s => a.startsWith(s"name.${s.key}.") ||
        a.startsWith(s"desc.${s.key}.")))), kept.map(_.key))

  // ---- WRITTEN: the files patched, everything else as it was

  /** `siteMd` and `i18nMd` with `c` written into them */
  def write(c: Content, siteMd: String, i18nMd: String): (String, String) =
    val old = read(siteMd, i18nMd)
    (writeSite(c, old, siteMd), writeDictionary(c, old, i18nMd))

  private def price(grosze: Long): String = (BigDecimal(grosze) / 100).setScale(2).toString

  /** a line's field, as the content's reader takes it: the text before its first colon */
  private def field(line: String): Option[String] =
    val t = if line.startsWith("- ") then line.drop(2) else line.trim
    t.indexOf(':') match
      case -1 => None
      case i => Some(t.substring(0, i).trim)

  /** lines with `key`'s value set (`None`: its line removed); a new field at `insert` (the end by default) */
  private def set(lines: Vector[String], key: String, value: Option[String], indent: String): Vector[String] =
    lines.indexWhere(l => !l.trim.startsWith("#") && field(l).contains(key)) match
      case -1 => value.fold(lines)(v => lines :+ rtrim(s"$indent$key: $v"))
      case i =>
        val first = lines(i).startsWith("- ")
        value match
          case Some(v) => lines.updated(i, rtrim(s"${if first then "- " else indent}$key: $v"))
          case None =>
            val rest = lines.patch(i, Nil, 1)
            // the row's first line gone: the next one starts it
            if first && i < rest.size then rest.updated(i, "- " + rest(i).trim) else rest

  private def lines(text: String): Vector[String] = text.split("\n", -1).toVector
  private def rtrim(s: String): String = s.reverse.dropWhile(_ == ' ').reverse

  private def writeSite(c: Content, old: Content, siteMd: String): String =
    val all = lines(siteMd)
    // the front matter: its own, or a new one
    val start = all.indexWhere(_.trim == "---")
    val withFront = if start >= 0 && all.indexWhere(_.trim == "---", start + 1) > start then all
      else Vector("---", "---") ++ all
    val s0 = withFront.indexWhere(_.trim == "---")
    val e0 = withFront.indexWhere(_.trim == "---", s0 + 1)
    val front = withFront.slice(s0 + 1, e0)
    val changes = Vector("name" -> Option.when(c.name != old.name)(c.name),
      "logoText" -> Option.when(c.logoText != old.logoText)(c.logoText),
      "tagline" -> Option.when(c.tagline(c.lang) != old.tagline(old.lang))(c.tagline(c.lang)),
      "contact" -> Option.when(c.contact(c.lang) != old.contact(old.lang))(c.contact(c.lang)))
    val front1 = changes.foldLeft(front) { case (f, (k, v)) => v.fold(f)(x => set(f, k, Some(x), "")) }
    val text = withFront.take(s0 + 1) ++ front1 ++ withFront.drop(e0)
    services(c, old, text).mkString("\n")

  /** the `yaml services` block written: rows patched, added, removed, in the new order when it changed */
  private def services(c: Content, old: Content, all: Vector[String]): Vector[String] =
    val start = all.indexWhere(_.trim == "```yaml services")
    if start < 0 && c.services.isEmpty then all
    else
      val (head, body, tail) =
        if start < 0 then (all.reverse.dropWhile(_.isEmpty).reverse ++ Vector("", "```yaml services"), Vector.empty,
          Vector("```", ""))
        else
          val end = all.indexWhere(_.trim == "```", start + 1) match
            case -1 => all.size
            case i => i
          (all.take(start + 1), all.slice(start + 1, end), all.drop(end))
      // the rows: what comes before the first, then each from its `- ` line
      val pre = body.takeWhile(!_.startsWith("- "))
      val rows = body.drop(pre.size).foldLeft(Vector.empty[Vector[String]])((acc, l) =>
        if l.startsWith("- ") || acc.isEmpty then acc :+ Vector(l) else acc.init :+ (acc.last :+ l))
      def keyOf(row: Vector[String]) = row.collectFirst { case l if field(l).contains("key") =>
        l.drop(l.indexOf(':') + 1).trim }.getOrElse("")
      val byKey = rows.map(r => keyOf(r) -> r).toMap
      val same = c.services.map(_.key) == old.services.map(_.key)
      val written = if same then rows.map(r => c.services.find(_.key == keyOf(r)).zip(old.services.find(_.key ==
        keyOf(r))).fold(r)((now, was) => patch(r, was, now, c.lang)))
      else c.services.zipWithIndex.map { (s, i) =>
        val row = byKey.get(s.key).zip(old.services.find(_.key == s.key)).fold(fresh(s, c.lang))((r, was) =>
          patch(r, was, s, c.lang))
        set(row, "order", Some((i + 1).toString), "  ")
      } ++ rows.filter(r => keyOf(r).isEmpty)
      head ++ pre ++ written.flatten ++ tail

  /** a new service's row */
  private def fresh(s: Service, l: Lang): Vector[String] =
    Vector(s"- key: ${s.key}", s"  name: ${s.name(l)}", rtrim(s"  description: ${s.description(l)}"),
      s"  price: ${price(s.price)}", "  currency: PLN") ++ Option.when(s.minutes > 0)(s"  minutes: ${s.minutes}") ++
      Option.when(s.quote)("  quote: true") ++ Option.when(s.seats > 0)(s"  seats: ${s.seats}") ++
      Option.when(s.times.nonEmpty)(s"  times: ${Work.timesText(s.times)}") ++ Option.when(s.hidden)("  hidden: true")

  /** a service's row with the fields that changed, and only those, written */
  private def patch(row: Vector[String], was: Service, now: Service, l: Lang): Vector[String] =
    val changes = Vector(
      "name" -> Option.when(now.name(l) != was.name(l))(Some(now.name(l))),
      "description" -> Option.when(now.description(l) != was.description(l))(Some(now.description(l))),
      "price" -> Option.when(now.price != was.price)(Some(price(now.price))),
      "minutes" -> Option.when(now.minutes != was.minutes)(Option.when(now.minutes > 0)(now.minutes.toString)),
      "quote" -> Option.when(now.quote != was.quote)(Option.when(now.quote)("true")),
      "seats" -> Option.when(now.seats != was.seats)(Option.when(now.seats > 0)(now.seats.toString)),
      "times" -> Option.when(now.times != was.times)(Option.when(now.times.nonEmpty)(Work.timesText(now.times))),
      "hidden" -> Option.when(now.hidden != was.hidden)(Option.when(now.hidden)("true")))
    changes.foldLeft(row) { case (r, (k, change)) => change.fold(r)(v => set(r, k, v, "  ")) }

  /** the `yaml i18n` block written: the rows of the texts that changed, new rows for new texts */
  private def writeDictionary(c: Content, old: Content, i18nMd: String): String =
    val was = old.texts.toMap
    val changed = c.texts.filter { (token, t) =>
      !was.get(token).contains(t) || autoOf(c, token) != autoOf(old, token)
    }
    if changed.isEmpty then i18nMd
    else
      val all = lines(i18nMd)
      val start = all.indexWhere(_.trim == "```yaml i18n")
      val (head, body, tail) =
        if start < 0 then (all.reverse.dropWhile(_.isEmpty).reverse ++ Vector("", "```yaml i18n"), Vector.empty,
          Vector("```", ""))
        else
          val end = all.indexWhere(_.trim == "```", start + 1) match
            case -1 => all.size
            case i => i
          (all.take(start + 1), all.slice(start + 1, end), all.drop(end))
      val pre = body.takeWhile(!_.startsWith("- "))
      val rows = body.drop(pre.size).foldLeft(Vector.empty[Vector[String]])((acc, l) =>
        if l.startsWith("- ") || acc.isEmpty then acc :+ Vector(l) else acc.init :+ (acc.last :+ l))
      def tokenOf(row: Vector[String]) = row.collectFirst { case l if field(l).contains("token") =>
        l.drop(l.indexOf(':') + 1).trim }.getOrElse("")
      def values(row: Vector[String], token: String, t: T): Vector[String] =
        val auto = autoOf(c, token)
        val fields = Vector("src" -> Some(t(c.lang)), "ru" -> Some(t.ru), "pl" -> Some(t.pl), "en" -> Some(t.en),
          "uk" -> Some(t.uk), "auto" -> Option.when(auto.nonEmpty)(auto.map(_.code).mkString(" ")))
        fields.foldLeft(row) { case (r, (k, v)) => set(r, k, v, "  ") }
      val patched = rows.map(r => changed.find(_._1 == tokenOf(r)).fold(r)((token, t) => values(r, token, t)))
      // a new text gets a row when it says more than its own words in every language
      val added = changed.filter((token, t) => !rows.exists(r => tokenOf(r) == token) && t(c.lang).nonEmpty &&
        (Lang.values.exists(l => t(l) != t(c.lang)) || autoOf(c, token).nonEmpty))
        .map((token, t) => values(Vector(s"- token: $token"), token, t))
      (head ++ pre ++ patched.flatten ++ added.flatten ++ tail).mkString("\n")

  /** the languages of a text machine-made, not yet read */
  private def autoOf(c: Content, token: String): Vector[Lang] =
    Lang.values.toVector.filter(l => l != c.lang && c.auto(s"$token.${l.code}"))

  // ---- AS JSON: what a host's screen reads and sends

  private def tj(t: T): Json = Json.obj(Lang.values.toVector.map(l => l.code -> Json.str(t(l)))*)
  private def zl(grosze: Long): String = if grosze % 100 == 0 then (grosze / 100).toString else price(grosze)

  /** a service's machine-made texts as a screen says them: `name.en`, `desc.uk` */
  private def autoOf(c: Content, s: Service): Vector[String] = c.auto.toVector.sorted.collect {
    case a if a.startsWith(s"name.${s.key}.") => "name." + a.drop(s"name.${s.key}.".length)
    case a if a.startsWith(s"desc.${s.key}.") => "desc." + a.drop(s"desc.${s.key}.".length)
  }

  def toJson(c: Content): Json.Obj = Json.obj("lang" -> Json.str(c.lang.code), "name" -> Json.str(c.name),
    "logoText" -> Json.str(c.logoText), "tagline" -> tj(c.tagline), "contact" -> tj(c.contact),
    "services" -> Json.Arr(c.services.map(s => Json.obj("key" -> Json.str(s.key), "name" -> tj(s.name),
      "description" -> tj(s.description), "price" -> Json.str(zl(s.price)), "minutes" -> Json.num(s.minutes),
      "quote" -> Json.bool(s.quote), "seats" -> Json.num(s.seats), "times" -> Json.str(Work.timesText(s.times)),
      "hidden" -> Json.bool(s.hidden), "auto" -> Json.Arr(autoOf(c, s).map(Json.str))))),
    "auto" -> Json.Arr(c.auto.toVector.sorted.filter(a => a.startsWith("tagline.") || a.startsWith("contact."))
      .map(Json.str)))

  /** a content from a screen's JSON, its language `lang` (the shop's: not the screen's to change), a new service
   * (no key) keyed from its name — none of `taken` (the stored content's keys); the name of the first field not of
   * its form */
  def fromJson(j: Json, lang: Lang, taken: Set[String] = Set.empty): Either[String, Content] = j match
    case o: Json.Obj =>
      def str(x: Json.Obj, k: String) = x.get(k).collect { case Json.Str(v) => v; case Json.Num(n) =>
        if n.isWhole then n.toLong.toString else n.toString }.getOrElse("")
      def t(x: Json.Obj, k: String): T = x.get(k) match
        case Some(w: Json.Obj) =>
          def l(c: String) = w.get(c).collect { case Json.Str(v) => v }.getOrElse("")
          T(l("ru"), l("pl"), l("en"), l("uk"))
        case Some(Json.Str(v)) => T.all(v)
        case _ => T.all("")
      def strs(x: Json.Obj, k: String) = x.get(k).collect { case Json.Arr(xs) => xs.collect { case Json.Str(v) => v } }
        .getOrElse(Vector.empty)
      def n(x: Json.Obj, k: String): Option[Int] =
        if str(x, k).isEmpty then Some(0) else str(x, k).toDoubleOption.filter(_.isWhole).map(_.toInt)
      val services = o.get("services").collect { case Json.Arr(xs) => xs }.getOrElse(Vector.empty).map {
        case x: Json.Obj => for
            price <- Money.parse(if str(x, "price").isEmpty then "0" else str(x, "price")).toRight("service.price")
            minutes <- n(x, "minutes").toRight("service.minutes")
            seats <- n(x, "seats").toRight("service.seats")
            times <- Work.times(str(x, "times")).toRight("service.times")
          yield Service(str(x, "key").trim, t(x, "name"), t(x, "description"), price, minutes,
            x.get("quote").contains(Json.Bool(true)), seats, times, x.get("hidden").contains(Json.Bool(true))) ->
            strs(x, "auto")
        case _ => Left("service")
      }
      val own = strs(o, "auto").filter(a => a.startsWith("tagline.") || a.startsWith("contact.")).toSet
      services.collectFirst { case Left(why) => why }.toLeft {
        val raw = services.collect { case Right((s, _)) => s }
        // the new ones keyed, none twice, none of the stored ones
        val keyed = raw.foldLeft(Vector.empty[Service]) { (acc, s) =>
          if s.key.nonEmpty then acc :+ s
          else acc :+ s.copy(key = key(s.name(lang), taken ++ raw.map(_.key) ++ acc.map(_.key)))
        }
        val marks = keyed.zip(services.collect { case Right((_, a)) => a }).flatMap((s, a) => a.collect {
          case m if m.startsWith("name.") => s"name.${s.key}.${m.drop(5)}"
          case m if m.startsWith("desc.") => s"desc.${s.key}.${m.drop(5)}"
        })
        Content(lang, str(o, "name"), str(o, "logoText"), t(o, "tagline"), t(o, "contact"), keyed, own ++ marks)
      }
    case _ => Left("not an object")
