package okay.shop.commands

import okay.shop.model.*

/**
 * THE MASTER'S COMMANDS IN WORDS (specs/commands.md): what a master says is read into a COMMAND whose every value
 * is chosen among the options the shop itself offers; the command is said back in canonical words and run only
 * after the master confirms. This file is the exact part — the command, the options of each of its lines, its
 * canonical words in the four languages and their parser. The reader that chooses is not here, and no choice of it
 * is ever an action by itself.
 */

/** a booking as a command names it: its day `2026-11-17`, its time in minutes from midnight */
final case class Booked(ref: String, client: String, service: String, master: String, day: String, time: Int)

/** THE SHOP AS A COMMAND SEES IT — all the options come from here: the days of the horizon (today first), the
 * starts of a day, the services' and the masters' keys and names, the bookings still to come */
final case class Desk(days: Vector[String], times: Vector[Int], services: Vector[(String, T)],
                      masters: Vector[(String, String)], bookings: Vector[Booked])

enum Command:
  /** `master`: none is any master */
  case Book(client: String, service: String, master: Option[String], day: String, time: Int)
  case Move(booking: String, day: String, time: Int)
  case Cancel(booking: String)
  case DayOff(master: String, day: String)
  /** `amount` in grosze */
  case Paid(booking: String, amount: Long, way: Method)
  case Show(day: String, master: Option[String])
  case Cannot

/** a line of the frame: one decision */
enum Hole:
  case Client, Service, Master, Day, Time, Booking, Amount, Way
  def key: String = toString.toLowerCase

object Frames:
  val Any = "any"
  val actions: Vector[String] = Vector("book", "move", "cancel", "dayoff", "paid", "list", "cannot")

  /** the lines of an action, in the order they are decided */
  def holes(action: String): Vector[Hole] = action match
    case "book"   => Vector(Hole.Client, Hole.Service, Hole.Master, Hole.Day, Hole.Time)
    case "move"   => Vector(Hole.Booking, Hole.Day, Hole.Time)
    case "cancel" => Vector(Hole.Booking)
    case "dayoff" => Vector(Hole.Master, Hole.Day)
    case "paid"   => Vector(Hole.Booking, Hole.Amount, Hole.Way)
    case "list"   => Vector(Hole.Day, Hole.Master)
    case _        => Vector.empty

  private val edge = ",.;:!?\"«»()".toSet
  private def trimmed(w: String): String = w.dropWhile(edge).reverse.dropWhile(edge).reverse

  /** the text's own spans of one to three words, none across a punctuation mark: what a client's name can be */
  def spans(text: String): Vector[String] =
    val words = text.trim.split("\\s+").toVector.filter(_.nonEmpty)
    val out = for
      i <- words.indices.toVector
      n <- 1 to 3 if i + n <= words.size
      inner = words.slice(i, i + n - 1) if inner.forall(w => !edge(w.last))
      span = words.slice(i, i + n).map(trimmed) if span.forall(_.nonEmpty)
    yield span.mkString(" ")
    out.distinct

  /** an amount as a line writes it: `80`, `70.50` */
  def amount(grosze: Long): String =
    if grosze % 100 == 0 then (grosze / 100).toString else f"${grosze / 100}.${grosze % 100}%02d"

  /** the text's own numbers, as amounts */
  def numbers(text: String): Vector[String] =
    val found = Vector.newBuilder[String]
    var i = 0
    while i < text.length do
      if text(i).isDigit then
        var j = i
        while j < text.length && text(j).isDigit do j += 1
        if j + 1 < text.length && (text(j) == '.' || text(j) == ',') && text(j + 1).isDigit then
          var k = j + 1
          while k < text.length && text(k).isDigit do k += 1
          if k - j - 1 <= 2 then j = k
        Money.parse(text.substring(i, j)).foreach(g => found += amount(g))
        i = j
      else i += 1
    found.result().distinct

  /** THE OPTIONS of a line: finite, from the shop's data or the text's own words — nothing else can be chosen */
  def options(desk: Desk, text: String, action: String, hole: Hole): Vector[String] = hole match
    case Hole.Client  => spans(text)
    case Hole.Service => desk.services.map(_._1)
    case Hole.Master  => desk.masters.map(_._1) ++ (if action == "dayoff" then Vector.empty else Vector(Any))
    case Hole.Day     => desk.days
    case Hole.Time    => desk.times.map(Span.label)
    case Hole.Booking => desk.bookings.map(_.ref)
    case Hole.Amount  => numbers(text)
    case Hole.Way     => Method.values.toVector.map(_.key)

  /** a command's lines, as they were chosen */
  def lines(c: Command): (String, Map[Hole, String]) =
    def m(o: Option[String]) = o.getOrElse(Any)
    c match
      case Command.Book(cl, s, ma, d, t) => "book" -> Map(Hole.Client -> cl, Hole.Service -> s, Hole.Master -> m(ma),
        Hole.Day -> d, Hole.Time -> Span.label(t))
      case Command.Move(b, d, t) => "move" -> Map(Hole.Booking -> b, Hole.Day -> d, Hole.Time -> Span.label(t))
      case Command.Cancel(b)     => "cancel" -> Map(Hole.Booking -> b)
      case Command.DayOff(ma, d) => "dayoff" -> Map(Hole.Master -> ma, Hole.Day -> d)
      case Command.Paid(b, a, w) => "paid" -> Map(Hole.Booking -> b, Hole.Amount -> amount(a), Hole.Way -> w.key)
      case Command.Show(d, ma)   => "list" -> Map(Hole.Day -> d, Hole.Master -> m(ma))
      case Command.Cannot        => "cannot" -> Map.empty

  /** THE COMMAND of the lines chosen — none when a line is missing or its value is not one of its options */
  def close(desk: Desk, text: String, action: String, chosen: Map[Hole, String]): Option[Command] =
    val hs = holes(action)
    val ok = actions.contains(action) && chosen.keySet == hs.toSet &&
      hs.forall(h => options(desk, text, action, h).contains(chosen(h)))
    def master = Some(chosen(Hole.Master)).filter(_ != Any)
    def time = Span.minutes(chosen(Hole.Time))
    if !ok then None
    else action match
      case "book"   => time.map(Command.Book(chosen(Hole.Client), chosen(Hole.Service), master, chosen(Hole.Day), _))
      case "move"   => time.map(Command.Move(chosen(Hole.Booking), chosen(Hole.Day), _))
      case "cancel" => Some(Command.Cancel(chosen(Hole.Booking)))
      case "dayoff" => Some(Command.DayOff(chosen(Hole.Master), chosen(Hole.Day)))
      case "paid"   => for a <- Money.parse(chosen(Hole.Amount)); w <- Method.values.find(_.key == chosen(Hole.Way))
        yield Command.Paid(chosen(Hole.Booking), a, w)
      case "list"   => Some(Command.Show(chosen(Hole.Day), master))
      case _        => Some(Command.Cannot)

/**
 * THE CANONICAL WORDS of a command and their parser. The law (`CommandsSuite`): `parse(say(c)) == c` for every
 * command of a desk, in every language — what the master reads and confirms is what will run. The words are
 * labelled lines, not prose: no case of a name is ever needed, so a name is shown as the shop wrote it.
 */
object Words:
  private final case class Forms(book: String, move: String, cancel: String, dayOff: String, paid: String,
                                 show: String, cannot: String, any: String, all: String, booking: String,
                                 when: String, week: Vector[String])

  private val forms: Map[Lang, Forms] = Map(
    Lang.en -> Forms("Book: «{client}» — {service}, {master}, {when}.", "Move: {booking} — to {when}.",
      "Cancel: {booking}.", "Day off: {master}, {day}.", "Paid: {booking} — {amount}, {way}.",
      "Show bookings: {day}, {master}.", "I cannot do this here.", "any master", "all masters",
      "{ref} «{client}» ({service}, {master}, {when})", "{day} at {time}",
      Vector("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")),
    Lang.pl -> Forms("Zapis: «{client}» — {service}, {master}, {when}.", "Przeniesienie: {booking} — na {when}.",
      "Odwołanie: {booking}.", "Dzień wolny: {master}, {day}.", "Zapłacono: {booking} — {amount}, {way}.",
      "Pokaż zapisy: {day}, {master}.", "Tego tutaj nie zrobię.", "dowolny mistrz", "wszyscy mistrzowie",
      "{ref} «{client}» ({service}, {master}, {when})", "{day}, godz. {time}",
      Vector("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")),
    Lang.ru -> Forms("Запись: «{client}» — {service}, {master}, {when}.", "Перенос: {booking} — на {when}.",
      "Отмена: {booking}.", "Выходной: {master}, {day}.", "Оплачено: {booking} — {amount}, {way}.",
      "Показать записи: {day}, {master}.", "Этого я здесь не сделаю.", "любой мастер", "все мастера",
      "{ref} «{client}» ({service}, {master}, {when})", "{day} в {time}",
      Vector("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")),
    Lang.uk -> Forms("Запис: «{client}» — {service}, {master}, {when}.", "Перенесення: {booking} — на {when}.",
      "Скасування: {booking}.", "Вихідний: {master}, {day}.", "Сплачено: {booking} — {amount}, {way}.",
      "Показати записи: {day}, {master}.", "Цього я тут не зроблю.", "будь-який майстер", "усі майстри",
      "{ref} «{client}» ({service}, {master}, {when})", "{day} о {time}",
      Vector("понеділок", "вівторок", "середа", "четвер", "п'ятниця", "субота", "неділя")))

  /** a template's literal parts and the names between them */
  private def parts(template: String): (Vector[String], Vector[String]) =
    val pieces = template.split("[{}]", -1).toVector
    (pieces.zipWithIndex.collect { case (p, i) if i % 2 == 0 => p },
      pieces.zipWithIndex.collect { case (p, i) if i % 2 == 1 => p })

  private def fill(template: String, values: Map[String, String]): String =
    val (lits, names) = parts(template)
    lits.head + names.zip(lits.tail).map((n, l) => values(n) + l).mkString

  /** the values a text has at a template's names — each value up to the next literal's first place */
  private def read(template: String, text: String): Option[Map[String, String]] =
    val (lits, names) = parts(template)
    if !text.startsWith(lits.head) then None
    else
      var at = lits.head.length
      var out = Map.empty[String, String]
      var ok = true
      for ((n, l), i) <- names.zip(lits.tail).zipWithIndex if ok do
        // the last literal is the text's end (a day's `20.11` has the full stop a sentence ends with)
        val end = if i == names.size - 1 then (if text.endsWith(l) then text.length - l.length else -1)
          else text.indexOf(l, at)
        if end < at then ok = false
        else
          out += n -> text.substring(at, end)
          at = end + l.length
      if ok && at == text.length then Some(out) else None

  /** `Friday 20.11` */
  def day(date: String, l: Lang): String = date.split('-') match
    case Array(y, m, d) =>
      val weekday = Math.floorMod(Day.days(y.toInt, m.toInt, d.toInt) + 3, 7L).toInt
      s"${forms(l).week(weekday)} $d.$m"
    case _ => date

  private def when(date: String, time: Int, l: Lang): String =
    fill(forms(l).when, Map("day" -> day(date, l), "time" -> Span.label(time)))

  private def service(desk: Desk, key: String, l: Lang): String =
    desk.services.collectFirst { case (`key`, name) => name(l) }.getOrElse(key)

  private def master(desk: Desk, key: String): String =
    desk.masters.collectFirst { case (`key`, name) => name }.getOrElse(key)

  /** a booking in words: its reference first, so no two bookings ever read alike */
  def booking(desk: Desk, ref: String, l: Lang): String = desk.bookings.find(_.ref == ref) match
    case Some(e) => fill(forms(l).booking, Map("ref" -> e.ref, "client" -> e.client,
      "service" -> service(desk, e.service, l), "master" -> master(desk, e.master), "when" -> when(e.day, e.time, l)))
    case None => ref

  def say(desk: Desk, c: Command, l: Lang): String =
    val f = forms(l)
    c match
      case Command.Book(client, s, m, d, t) => fill(f.book, Map("client" -> client, "service" -> service(desk, s, l),
        "master" -> m.fold(f.any)(master(desk, _)), "when" -> when(d, t, l)))
      case Command.Move(b, d, t) => fill(f.move, Map("booking" -> booking(desk, b, l), "when" -> when(d, t, l)))
      case Command.Cancel(b)     => fill(f.cancel, Map("booking" -> booking(desk, b, l)))
      case Command.DayOff(m, d)  => fill(f.dayOff, Map("master" -> master(desk, m), "day" -> day(d, l)))
      case Command.Paid(b, a, w) => fill(f.paid, Map("booking" -> booking(desk, b, l), "amount" -> Money.zl(a),
        "way" -> w.label(l)))
      case Command.Show(d, m)    => fill(f.show, Map("day" -> day(d, l), "master" -> m.fold(f.all)(master(desk, _))))
      case Command.Cannot        => f.cannot

  /** THE COMMAND of canonical words — none for any other words. Whatever it answers says back to the same words */
  def parse(desk: Desk, words: String, l: Lang): Option[Command] =
    val f = forms(l)
    def serviceOf(s: String) = desk.services.collectFirst { case (k, name) if name(l) == s => k }
    def masterOf(s: String) = desk.masters.collectFirst { case (k, name) if name == s => k }
    def dayOf(s: String) = desk.days.find(day(_, l) == s)
    def bookingOf(s: String) = desk.bookings.collectFirst { case e if booking(desk, e.ref, l) == s => e.ref }
    def whenOf(s: String) = for
      v <- read(f.when, s); d <- dayOf(v("day")); t <- Span.minutes(v("time"))
    yield (d, t)
    def some(s: String, none: String) = if s == none then Some(None) else masterOf(s).map(Some(_))
    val candidates: Vector[Option[Command]] = Vector(
      if words == f.cannot then Some(Command.Cannot) else None,
      for v <- read(f.book, words); s <- serviceOf(v("service")); m <- some(v("master"), f.any)
        (d, t) <- whenOf(v("when")) yield Command.Book(v("client"), s, m, d, t),
      for v <- read(f.move, words); b <- bookingOf(v("booking")); (d, t) <- whenOf(v("when"))
        yield Command.Move(b, d, t),
      for v <- read(f.cancel, words); b <- bookingOf(v("booking")) yield Command.Cancel(b),
      for v <- read(f.dayOff, words); m <- masterOf(v("master")); d <- dayOf(v("day")) yield Command.DayOff(m, d),
      for v <- read(f.paid, words); b <- bookingOf(v("booking")); a <- Money.parse(v("amount"))
        w <- Method.values.find(_.label(l) == v("way")) yield Command.Paid(b, a, w),
      for v <- read(f.show, words); d <- dayOf(v("day")); m <- some(v("master"), f.all) yield Command.Show(d, m))
    candidates.flatten.find(c => say(desk, c, l) == words)
