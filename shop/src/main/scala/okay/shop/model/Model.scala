package okay.shop.model

/** THE LANGUAGES a page is in: Polish, English, Ukrainian (shown UA, as before), Russian — in the switch's order */
enum Lang(val code: String, val shown: String, val locale: String):
  case pl extends Lang("pl", "PL", "pl_PL")
  case en extends Lang("en", "EN", "en_GB")
  case uk extends Lang("uk", "UA", "uk_UA")
  case ru extends Lang("ru", "RU", "ru_RU")

object Lang:
  def parse(s: String): Option[Lang] = values.find(_.code == s.trim.toLowerCase)
  /** the first of an `Accept-Language` we have */
  def accepted(header: String): Option[Lang] =
    header.split(',').iterator.map(_.trim.takeWhile(_ != ';').take(2)).flatMap(parse).nextOption()

/** A TEXT IN THE FOUR LANGUAGES — in busi's `i18nAttrs` order (ru, pl, en, uk), so its lines port as they are */
final case class T(ru: String, pl: String, en: String, uk: String):
  def apply(l: Lang): String = l match
    case Lang.ru => ru
    case Lang.pl => pl
    case Lang.en => en
    case Lang.uk => uk
  def map(f: String => String): T = T(f(ru), f(pl), f(en), f(uk))

object T:
  /** the same in every language */
  def all(s: String): T = T(s, s, s, s)

/** what a site sells: the atelier (things to mend, sent by InPost), IT work, or a salon's time (a barber's: booked) */
enum Kind:
  case Atelier, It, Salon

/** AN OFFER: its key, its name and description in the four languages, its price in grosze (0: quoted); whether it is
 * asked for a quote; a group class's seats and weekly starts (weekday 1–7, minutes from midnight); whether hidden
 * (specs/shop.md "The owner edits the content": not shown, nothing new made for it) */
final case class Offer(key: String, name: T, description: T, price: Long, order: Int, minutes: Int = 0,
                       quote: Boolean = false, seats: Int = 0, times: Vector[(Int, Int)] = Vector.empty,
                       hidden: Boolean = false):
  /** a group class (specs/shop.md "Group classes"): seats at weekly times */
  def isClass: Boolean = seats > 0 && times.nonEmpty && minutes > 0
  def priced: Boolean = price > 0
  /** booked for a time, not ordered: the service lasts `minutes` */
  def bookable: Boolean = minutes > 0
  /** «40 zł», as busi's `priceShort` */
  def priceShort: String = Money.zl(price)

/** A SITE: what its pages and letters say of it */
final case class Site(id: String, slug: String, name: String, tagline: T, lang: Lang, logoText: String,
                      color: String, contact: T, kind: Kind, refPrefix: String, domain: String,
                      offers: Vector[Offer], schedule: Option[Schedule] = None, work: Option[Work] = None,
                      masters: Vector[Master] = Vector.empty):
  def clothing: Boolean = kind == Kind.Atelier
  /** whether `o` is booked here: it lasts, and the shop has a schedule — or, by its work, a mode with hours */
  def books(o: Offer): Boolean = work match
    case None => o.bookable && (schedule.isDefined || o.isClass)
    case Some(_) => bookedModes(o).nonEmpty
  /** the modes `o` is booked in (specs/shop.md "How the work happens"): allowed, with a time, and hours somewhere */
  def bookedModes(o: Offer): Vector[Mode] = work.fold(Vector.empty[Mode])(w => w.modesOf(o.key).filter(m =>
    m.booked && places(m).exists(p => plan(o, m, p).isDefined)))
  /** the places a mode is met in: the locations' keys at the shop, else one unnamed */
  def places(m: Mode): Vector[String] =
    if m == Mode.AtShop then work.fold(Vector(""))(_.locations.map(_.key)) else Vector("")
  /** the schedule of `o` in mode `m` at place `p`: the shop's, its week the location's or the visits' own when
   * they have one; none when `o` does not last or nothing is open */
  def plan(o: Offer, m: Mode, p: String): Option[Schedule] =
    val base = schedule.getOrElse(Schedule(Map.empty, Map.empty))
    val own = m match
      case Mode.AtShop => work.flatMap(_.location(p)).map(_.hours)
      case Mode.AtClient => work.flatMap(_.area).map(_.hours)
      case _ => None
    val s = own.filter(_.nonEmpty).fold(base)(w => base.copy(week = w))
    Option.when(o.bookable && m.booked && (o.isClass || s.week.values.exists(_.nonEmpty)))(s)
  /** the masters who do `o` in mode `m` at place `p` (specs/shop.md "Masters"), in their order */
  def eligible(o: Offer, m: Option[Mode], p: String): Vector[Master] = masters.filter(_.does(o.key, m, p))
  /** the site with its work: a service with no minutes in the content takes the work's */
  def working(w: Work): Site = copy(work = Some(w), offers = offers.map(o => w.services.get(o.key).fold(o) { s =>
    val timed = if o.minutes == 0 && s.minutes > 0 then o.copy(minutes = s.minutes) else o
    // asked for a quote, or a class, when the work says so
    timed.copy(quote = timed.quote || s.quote, seats = if s.seats > 0 then s.seats else timed.seats,
      times = if s.times.nonEmpty then s.times else timed.times)
  }))
  def offer(key: String): Option[Offer] = offers.find(_.key == key)
  /** the offers its pages show: all but the hidden */
  def shown: Vector[Offer] = offers.filterNot(_.hidden)
  def origin: String = s"https://$domain"

object Money:
  /** grosze as «40 zł» or «40,50 zł» */
  def zl(grosze: Long): String =
    val z = grosze / 100
    val g = grosze % 100
    if g == 0 then s"$z zł" else f"$z,$g%02d zł"
  /** «40», «40.5», «40,50» as grosze */
  def parse(s: String): Option[Long] =
    val t = s.trim.replace(',', '.').replace("zł", "").replace(" ", "")
    t.toDoubleOption.filter(d => d >= 0 && d < 1e9).map(d => math.round(d * 100))

/** THE STATUSES of an order, in the order they come */
enum Status(val key: String, val label: T):
  case Received extends Status("received", T("Принято — оцениваем", "Przyjęto — wyceniamy", "Received — quoting",
    "Прийнято — оцінюємо"))
  case Quoted extends Status("quoted", T("Оценено — ждём вашего подтверждения",
    "Wycenione — czekamy na potwierdzenie", "Quoted — waiting for your confirmation",
    "Оцінено — чекаємо на ваше підтвердження"))
  case Accepted extends Status("accepted", T("Подтверждено — отправьте посылку", "Potwierdzone — wyślij paczkę",
    "Confirmed — please send us your parcel", "Підтверджено — надішліть посилку"))
  case ParcelIn extends Status("parcel_in", T("Посылка получена — работаем", "Paczka dotarła — w realizacji",
    "Parcel received — working on it", "Посилка отримана — в роботі"))
  case InWork extends Status("in_work", T("В работе", "W realizacji", "In progress", "В роботі"))
  case Ready extends Status("ready", T("Готово — отправляем обратно", "Gotowe — wysyłamy", "Ready — shipping back",
    "Готово — відправляємо"))
  case ParcelOut extends Status("parcel_out", T("Отправлено — проверьте InPost", "Wysłane — sprawdź InPost",
    "Shipped — check InPost", "Відправлено — перевірте InPost"))
  case Delivered extends Status("delivered", T("Доставлено — ждём оплату", "Dostarczone — czekamy na płatność",
    "Delivered — waiting for payment", "Доставлено — чекаємо на оплату"))
  case Paid extends Status("paid", T("Оплачено — спасибо!", "Opłacone — dziękujemy!", "Paid — thank you!",
    "Оплачено — дякуємо!"))
  case Cancelled extends Status("cancelled", T("Отменено", "Anulowane", "Cancelled", "Скасовано"))

object Status:
  def parse(s: String): Option[Status] = values.find(_.key == s)
  def open(s: Status): Boolean = s != Paid && s != Cancelled

/** the consent to the privacy policy, as given: when, to which version */
final case class Consent(at: Long, policy: String)

/** AN ORDER, as the client gave it and as it moved since */
final case class Order(ref: String, site: String, key: String, offerKey: String, name: String, contact: String,
                       need: String, handover: String, deliveryMethod: String, paymentMethod: String,
                       itemValue: String, fabric: String, lang: Lang, consent: Consent, placed: Long,
                       status: Status = Status.Received, tracking: String = "", amount: Long = 0,
                       paidAt: Long = 0, note: String = ""):
  def email: Option[String] = Contact.email(contact)
  def courier: Boolean = deliveryMethod == "courier"

/** AN INQUIRY of the IT site's /products */
final case class Inquiry(ref: String, site: String, name: String, contact: String, subject: String, kind: String,
                         body: String, confidential: Boolean, lang: Lang, consent: Consent, at: Long)

/** what happens to an order after it is placed: the workshop's moves and letters */
enum Event:
  case Placed(order: Order)
  case Moved(status: Status, note: String, amount: Long, tracking: String)
  /** a letter to the client: sent, or not (no way to send it, or it failed) — either way it is known */
  case Emailed(to: String, subject: String, sent: Boolean)
  case Inquired(inquiry: Inquiry)

/** A CLIENT'S CONTACT: an email or a phone, and whether a second factor matches it */
object Contact:
  def email(s: String): Option[String] =
    val t = s.trim
    Option(t).filter(e => e.count(_ == '@') == 1 && !e.exists(_.isWhitespace) && e.indexOf('@') > 0 &&
      e.substring(e.indexOf('@') + 1).contains('.') && e.length <= 200)
  def phone(s: String): Option[String] =
    val t = s.trim.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
    val digits = t.stripPrefix("+")
    Option(t).filter(_ => digits.nonEmpty && digits.forall(_.isDigit) && digits.length >= 9 && digits.length <= 15)
  def valid(s: String): Boolean = email(s).isDefined || phone(s).isDefined
  /** the second factor of a lookup: an email the same in any case, a phone by its last 9 digits */
  def matches(offered: String, kept: String): Boolean =
    (email(offered), email(kept)) match
      case (Some(a), Some(b)) => a.equalsIgnoreCase(b)
      case _ => (phone(offered), phone(kept)) match
        case (Some(a), Some(b)) => a.takeRight(9) == b.takeRight(9)
        case _ => false

/** a day of an instant, in Poland's time (UTC+1, +2 in summer: the last Sunday of March to that of October) */
object Day:
  def of(millis: Long): String = Civil.iso(millis + offset(millis)).take(10)
  def offset(millis: Long): Long =
    val (y, _, _) = Civil.ymd(Math.floorDiv(millis, 86400000L))
    def lastSunday(month: Int): Long =
      val first = days(y, month + 1, 1)
      val d = first - 1
      d - Math.floorMod(d + 4, 7L) // 1970-01-01 was a Thursday: (d + 4) % 7 is the weekday, 0 Sunday
    val start = lastSunday(3) * 86400000L + 3600000L
    val end = lastSunday(10) * 86400000L + 3600000L
    if millis >= start && millis < end then 7200000L else 3600000L
  /** days since 1970-01-01 of a civil date */
  def days(y0: Int, m0: Int, d: Int): Long =
    val (y, m) = if m0 > 12 then (y0 + 1, m0 - 12) else (y0, m0)
    val yy = if m <= 2 then y - 1 else y
    val era = Math.floorDiv(yy.toLong, 400L)
    val yoe = yy - era * 400
    val doy = (153 * (if m > 2 then m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468
  /** `2026-11-03 14:00`, Poland's time, as the instant it is */
  def parse(s: String): Option[Long] = s.trim.replace('T', ' ').split(' ') match
    case Array(d, t) => (d.split('-'), Span.minutes(t)) match
      case (Array(y, m, dd), Some(mins)) => for yy <- y.toIntOption; mm <- m.toIntOption; x <- dd.toIntOption
        if mm >= 1 && mm <= 12 && x >= 1 && x <= 31
        yield
          val local = days(yy, mm, x) * 86400000L + mins * 60000L
          local - offset(local - offset(local))
      case _ => None
    case _ => None
  /** a moment for a person: `2026-10-28 14:03` in Poland's time */
  def time(millis: Long): String = Civil.iso(millis + offset(millis)).take(16).replace('T', ' ')

/** AN ORDER MOVED BY AN EVENT: the journal's one rule, pure */
object Orders:
  def apply(o: Order, e: Event, at: Long): Order = e match
    case Event.Moved(st, note, amount, tracking) => o.copy(status = st,
      tracking = if tracking.nonEmpty then tracking else o.tracking,
      amount = if amount > 0 then amount else o.amount,
      paidAt = if st == Status.Paid then at else o.paidAt,
      note = if note.nonEmpty then note else o.note)
    case _ => o

/** WHERE AN ORDER MAY GO NEXT — the workshop's buttons and the API's rule: the atelier's path through the parcels, the
 * IT site's without them; an open order may always be cancelled */
object Workflow:
  def next(o: Order): Vector[Status] =
    import Status.*
    val forward: Vector[Status] = o.status match
      case Received | Quoted => Vector(Accepted)
      case Accepted => if o.courier then Vector(ParcelIn) else Vector(InWork)
      case ParcelIn => Vector(InWork)
      case InWork => Vector(Ready)
      case Ready => if o.courier then Vector(ParcelOut) else Vector(Delivered)
      case ParcelOut => Vector(Delivered)
      case Delivered => Vector(Paid)
      case Paid | Cancelled => Vector.empty
    if Status.open(o.status) then forward :+ Cancelled else forward

/** days since 1970-01-01 as a civil date, an instant as ISO 8601 in UTC — no java.time, which Scala.js has not
 * (Howard Hinnant's algorithm) */
object Civil:
  def ymd(days: Long): (Int, Int, Int) =
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m = (if mp < 10 then mp + 3 else mp - 9).toInt
    ((yoe + era * 400 + (if m <= 2 then 1 else 0)).toInt, m, d)

  /** `2026-10-28T14:03:00Z` */
  def iso(millis: Long): String =
    val (y, m, d) = ymd(Math.floorDiv(millis, 86400000L))
    val secs = Math.floorMod(millis / 1000, 86400L)
    f"$y%04d-$m%02d-$d%02dT${secs / 3600}%02d:${secs / 60 % 60}%02d:${secs % 60}%02dZ"

/** a span of a day, in minutes from its midnight (Poland's time) */
final case class Span(from: Int, to: Int)

object Span:
  /** `10:00` as minutes */
  def minutes(hhmm: String): Option[Int] = hhmm.trim.split(':') match
    case Array(h, m) => for hh <- h.toIntOption; mm <- m.toIntOption if hh >= 0 && hh <= 24 && mm >= 0 && mm < 60
      yield hh * 60 + mm
    case _ => None
  def label(minutes: Int): String = f"${minutes / 60}%02d:${minutes % 60}%02d"

/**
 * WHEN A SHOP TAKES BOOKINGS: its week (each day's spans, 1 Monday … 7 Sunday), its exceptions by date (`2026-12-24`:
 * no span is closed), the minutes between starts, how many days ahead, how soon from now; its masters (one in v1)
 */
final case class Schedule(week: Map[Int, Vector[Span]], exceptions: Map[String, Vector[Span]], step: Int = 15,
                          horizon: Int = 21, lead: Int = 60, masters: Vector[String] = Vector("main")):
  def master: String = masters.head
  /** the spans of a date (`2026-11-03`), its weekday 1–7 */
  def spans(date: String, weekday: Int): Vector[Span] =
    exceptions.getOrElse(date, week.getOrElse(weekday, Vector.empty))

enum BookingStatus(val key: String):
  case Booked extends BookingStatus("booked")
  case Cancelled extends BookingStatus("cancelled")

object BookingStatus:
  def parse(s: String): Option[BookingStatus] = values.find(_.key == s)

enum QuoteStatus(val key: String, val label: T):
  case Asked extends QuoteStatus("asked", T("Ждёт оценки", "Czeka na wycenę", "Waiting for a quote",
    "Чекає на оцінку"))
  case Quoted extends QuoteStatus("quoted", T("Оценено — ждём вашего решения", "Wycenione — czekamy na decyzję",
    "Quoted — waiting for your decision", "Оцінено — чекаємо на ваше рішення"))
  case Accepted extends QuoteStatus("accepted", T("Принято", "Przyjęte", "Accepted", "Прийнято"))
  case Declined extends QuoteStatus("declined", T("Отклонено", "Odrzucone", "Declined", "Відхилено"))

object QuoteStatus:
  def parse(s: String): Option[QuoteStatus] = values.find(_.key == s)

/** AN INQUIRY FOR A QUOTE (specs/shop.md "Inquiry and quote"): the job described, how and where, the dates the client
 * prefers; then the shop's answer — a price or a range, a proposed time (0: none), a note — and the client's decision,
 * with the ref of the booking or the order it became */
final case class Quote(ref: String, key: String, service: String, name: String, contact: String,
                       description: String, mode: Option[Mode], address: Option[Address], preferred: String,
                       lang: Lang, consent: Consent, placed: Long, status: QuoteStatus = QuoteStatus.Asked,
                       price: Long = 0, priceTo: Long = 0, start: Long = 0, note: String = "", result: String = ""):
  def email: Option[String] = Contact.email(contact)
  /** `120 zł` or `120 zł – 200 zł` */
  def priced: String = if price <= 0 then "" else if priceTo > price then s"${Money.zl(price)} – ${Money.zl(priceTo)}"
    else Money.zl(price)

/** A BOOKING: a service of a master from `start` (a UTC instant) for `minutes`; who, how reached, the consent, its
 * secret key (the manage link's), whether reminded; how it meets (none: before the modes) — at which location, at
 * which address (the location's, copied; or the client's), the minutes kept free before and after it (the master's
 * travel), its price (grosze; 0: not said) */
final case class Booking(ref: String, key: String, service: String, master: String, start: Long, minutes: Int,
                         name: String, contact: String, lang: Lang, consent: Consent, placed: Long,
                         status: BookingStatus = BookingStatus.Booked, reminded: Boolean = false,
                         mode: Option[Mode] = None, place: String = "", address: Option[Address] = None,
                         before: Int = 0, after: Int = 0, price: Long = 0):
  def end: Long = start + minutes * 60000L
  def active: Boolean = status == BookingStatus.Booked
  /** whether its time, with its buffers, meets `from`–`until` */
  def overlaps(from: Long, until: Long): Boolean =
    active && start - before * 60000L < until && from < end + after * 60000L
  def email: Option[String] = Contact.email(contact)
