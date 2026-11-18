package okay.shop.commands

import okay.frames.{Choice, Hole, Space}
import okay.shop.model.{Civil, Reports, Span}

/** WHAT THE ORGAN SAYS OF A LINE'S OPTIONS: nothing (they stay as they are), the ones the phrase says, or that
 * the phrase says a day or a time the desk does not offer — never a guess, and never a wider choice */
enum Narrowed:
  case Unsaid
  case Among(options: Vector[String])
  case Outside(said: Vector[String])

/**
 * DAYS AND TIMES COMPUTED, NOT READ (specs/commands.md A2b): what a phrase says exactly about a day and a time,
 * in Russian, Ukrainian, Polish and English — no model, no effect, both platforms. A phrase is folded and cut
 * into words, numbers and marks; each language it may be written in (`Tongues.of`) hears it by its own words
 * (`Tongue`), left to right: a move by a difference first, then a day, then a time. What is heard is still
 * before any calendar (`Heard`: "tomorrow", "a Friday", "half past five, morning or evening"); `days` and `times`
 * put it on a desk — today, the horizon, the starts — and answer a `Narrowed`.
 *
 * The calendar is the engine's own (`Reports.epochDay`, `Civil.ymd`): no date arithmetic is written here.
 */
object When:
  /** a part of the day, which settles a time said by a twelve-hour clock */
  enum Part { case Morning, Day, Evening, Night, Am, Pm }
  /** the word before a day or a time: `from` it, `until` it, or any other */
  enum Role { case Plain, From, Until, Instead }
  enum Mode { case Bare, This, Next }

  /** a day as a phrase says it */
  enum Day:
    /** so many days from today — more than one when the words mean either (`через день`) */
    case In(days: Vector[Int])
    /** Monday is 0 */
    case Weekday(weekday: Int, mode: Mode)
    case Date(day: Int, month: Option[Int])

  enum What:
    /** `led`: by a word of its own (`on`, `from`), so it is no part of the day said just before it */
    case OnDay(day: Day, role: Role, led: Boolean)
    /** `next week` (1), `this week` (0): the week of the weekdays said, or its days when none is */
    case InWeek(weeks: Int)
    /** `twelve`: said by a twelve-hour clock, so the hour is this or twelve more */
    case At(hour: Int, minute: Int, twelve: Boolean, part: Option[Part], role: Role)
    /** `10.11`: the tenth of November, or eleven past ten — a date when the horizon has it */
    case Dotted(a: Int, b: Int, clock: Boolean, twelve: Boolean, role: Role, led: Boolean)
    case PartOfDay(part: Part)
    /** a move by a difference: minutes (0: the same time), days (0: the same day) */
    case Later(minutes: Int)
    case DaysLater(days: Int)

  /** one thing heard, and the words it was heard in: from a place to before another */
  final case class Heard(what: What, at: Int, end: Int)

  private[commands] enum Tok:
    case Word(s: String)
    /** digits: `15`, `15:30`, `20.11`, `20/11` (`sep`, `m`); what is written on to them (`pm`, `го`, `th`);
     * whether the hour has a leading zero, how many digits it has, whether `m` has two */
    case Num(n: Int, sep: Char, m: Int, tail: String, zero: Boolean, width: Int, two: Boolean)
    case Mark(c: Char)

  private val marks = Map('ą' -> 'a', 'ć' -> 'c', 'ę' -> 'e', 'ł' -> 'l', 'ń' -> 'n', 'ó' -> 'o', 'ś' -> 's',
    'ź' -> 'z', 'ż' -> 'z', 'ё' -> 'е')
  private val apostrophes = "'’ʼ`"

  /** a phrase as its words are looked up: lower case, letters without their marks, no apostrophe and no hyphen
   * inside a word */
  def fold(text: String): String =
    val s = text.toLowerCase.map(c => marks.getOrElse(c, c))
    def letter(i: Int) = i >= 0 && i < s.length && s(i).isLetter
    s.indices.flatMap { i =>
      if letter(i - 1) && letter(i + 1) then
        if apostrophes.contains(s(i)) then None else if s(i) == '-' then Some(' ') else Some(s(i))
      else Some(s(i))
    }.mkString

  private[commands] def tokens(text: String): Vector[Tok] =
    val s = fold(text)
    val out = Vector.newBuilder[Tok]
    def letter(i: Int) = i < s.length && s(i).isLetter
    def digit(i: Int) = i < s.length && s(i).isDigit
    def run(i: Int, of: Int => Boolean): Int = if of(i) then run(i + 1, of) else i
    var i = 0
    while i < s.length do
      if letter(i) then
        val j = run(i, letter)
        // a reference is one word: letters with digits on to them (`b12`, `R-17`, `QXB-7H2K9P`)
        val k = if digit(j) || (j < s.length && s(j) == '-' && digit(j + 1)) then
          run(j + 1, x => letter(x) || digit(x) || (x < s.length && s(x) == '-' && (letter(x + 1) || digit(x + 1))))
        else j
        out += Tok.Word(s.substring(i, k)); i = k
      else if digit(i) then
        val j = run(i, digit)
        if j - i > 4 then { out += Tok.Word(s.substring(i, j)); i = j }
        else
          val n = s.substring(i, j).toInt
          val k = if j - i <= 2 && j < s.length && ":./".contains(s(j)) then run(j + 1, digit) else j
          val split = k > j + 1 && k - j - 1 <= 2
          // a year after a date: `20.11.2026`
          val past = if split && s(j) == '.' && k < s.length && s(k) == '.' then
            val y = run(k + 1, digit)
            if y - k - 1 == 4 || y - k - 1 == 2 then y else k
          else if split then k else j
          val from = if past < s.length && s(past) == '-' && letter(past + 1) then past + 1 else past
          val to = run(from, letter)
          out += (if split then Tok.Num(n, s(j), s.substring(j + 1, k).toInt, s.substring(from, to), s(i) == '0',
            j - i, k - j - 1 == 2)
          else Tok.Num(n, ' ', 0, s.substring(from, to), s(i) == '0' && j - i == 2, j - i, false))
          i = to
      else
        if !s(i).isWhitespace then out += Tok.Mark(s(i))
        i += 1
    // `a.m.` and `p.m.` are one word; a name quoted as the canonical words quote it (`«Jan Sob»`) says no day
    def merged(ts: List[Tok]): List[Tok] = ts match
      case Tok.Mark('«') :: rest if rest.contains(Tok.Mark('»')) =>
        Tok.Mark('«') :: merged(rest.dropWhile(_ != Tok.Mark('»')))
      case Tok.Word(a) :: Tok.Mark('.') :: Tok.Word("m") :: rest if a == "a" || a == "p" =>
        Tok.Word(a + "m") :: merged(rest match { case Tok.Mark('.') :: more => more; case _ => rest })
      case head :: rest => head :: merged(rest)
      case Nil => Nil
    merged(out.result().toList).toVector

  /** A PHRASE HEARD BY ONE LANGUAGE'S WORDS */
  private[commands] final class Hearing(toks: Vector[Tok], t: Tongue):
    def word(i: Int): Option[String] = toks.lift(i).collect { case Tok.Word(s) => s }
    def is(i: Int, of: Set[String]): Boolean = word(i).exists(of)
    /** the place after the longest of the phrases said at a place */
    def said(i: Int, of: Vector[Vector[String]]): Option[Int] =
      of.filter(p => p.indices.forall(k => word(i + k).contains(p(k)))).map(i + _.size).maxOption
    private def plain(i: Int): Option[Int] = toks.lift(i).collect { case Tok.Num(n, ' ', _, "", _, _, _) => n }
    /** a number in words — `двадцать пять` is one */
    private def named(i: Int, of: Map[String, Int]): Option[(Int, Int)] = word(i).flatMap(of.get).map { n =>
      val unit = if n >= 20 && n % 10 == 0 then word(i + 1).flatMap(of.get).filter(u => u >= 1 && u <= 9) else None
      unit.fold(n -> (i + 1))(u => (n + u) -> (i + 2))
    }
    /** a count, in digits or in words, and the place after */
    def count(i: Int): Option[(Int, Int)] = plain(i).map(_ -> (i + 1)).orElse(named(i, t.numbers))
      .orElse(if is(i, t.one) then Some(1 -> (i + 1)) else None)
    /** an hour, in plain digits or in the language's words for one (`двадцать один`, `dwudziesta pierwsza`) */
    def clock(i: Int): Option[(Int, Int)] = plain(i).filter(_ <= 23).map(_ -> (i + 1)).orElse(
      word(i).flatMap(t.hours.get).map { n =>
        val unit = if n == 20 then word(i + 1).flatMap(t.hours.get).filter(_ <= 3) else None
        unit.fold(n -> (i + 1))(u => (n + u) -> (i + 2))
      })

    private def role(i: Int): Role = if is(i, Tongues.instead) then Role.Instead
      else if is(i, t.from) then Role.From else if is(i, t.until) then Role.Until
      else Role.Plain

    /** a number that is money: a currency after it, a word of paying before it */
    private def amount(i: Int): Boolean =
      def sign(j: Int) = toks.lift(j).exists { case Tok.Mark(c) => c == '€' || c == '$'; case _ => false }
      toks.lift(i).exists {
        case Tok.Num(_, _, _, tail, _, _, _) => t.currency(tail) || is(i + 1, t.currency) || sign(i + 1) ||
          sign(i - 1) || is(i - 1, Tongues.paying) || is(i - 2, Tongues.paying)
        case _ => false
      }

    /** what makes the number before it no time: people, minutes, days, money — but `дня` after an hour is the
     * afternoon */
    private val counted: Set[String] =
      Tongues.counting.filterNot(u => t.partAfter.exists(_._1.contains(Vector(u))))

    private def part(i: Int): Option[(Part, Int)] =
      (t.partAfter ++ t.partAnywhere).flatMap((p, part) => said(i, p).map(part -> _)).maxByOption(_._2)

    // ---- a move by a difference: `an hour later`, `позже на полчаса`, `the same time` ----
    private def shift(i: Int): Option[Heard] =
      /** whether days, how many (minutes or days), the place after */
      def duration(a: Int): Option[(Boolean, Int, Int)] =
        said(a, t.hourAndHalf).map(n => (false, 90, n)).orElse(said(a, t.halfHour).map(n => (false, 30, n)))
          .orElse {
            val (n, j) = count(a).getOrElse(1 -> a)
            if is(j, t.hourUnit) then Some((false, 60 * n, j + 1))
            else if j > a && is(j, t.minuteUnit) then Some((false, n, j + 1))
            else if is(j, t.dayUnit) then Some((true, n, j + 1))
            else if is(j, t.weekUnit) then Some((true, 7 * n, j + 1))
            else None
          }
      def sign(j: Int) = if is(j, t.later) then Some(1) else if is(j, t.earlier) then Some(-1) else None
      def what(days: Boolean, n: Int) = if days then What.DaysLater(n) else What.Later(n)
      said(i, t.sameTime).map(n => Heard(What.Later(0), i, n))
        .orElse(said(i, t.sameDay).map(n => Heard(What.DaysLater(0), i, n)))
        .orElse(for (days, n, j) <- duration(if is(i, t.by) then i + 1 else i); s <- sign(j)
          yield Heard(what(days, s * n), i, j + 1))
        .orElse(for s <- sign(i) if is(i + 1, t.by); (days, n, j) <- duration(i + 2)
          yield Heard(what(days, s * n), i, j))

    // ---- a day ----
    private def weekday(i: Int): Option[Int] =
      word(i).map(w => t.weekdays.indexWhere(_(w))).filter(_ >= 0)
    private def month(i: Int): Option[Int] =
      word(i).map(w => t.months.indexWhere(_(w)) + 1).filter(_ > 0)

    private def day(i: Int): Option[Heard] =
      val as = role(i)
      val led = as != Role.Plain || is(i, t.on)
      val a0 = if led then i + 1 else i
      val a = if is(a0, t.article) then a0 + 1 else a0
      def heard(d: Day, end: Int) = Some(Heard(What.OnDay(d, as, led), i, end))
      def near = t.near.flatMap((p, days) => said(a, p).map(days -> _)).maxByOption(_._2)
        .flatMap((days, n) => heard(Day.In(days), n))
      // `in three days`, `через неделю`
      def ahead = if led || !is(i, t.in) then None else
        val (n, j) = count(i + 1).getOrElse(1 -> (i + 1))
        if is(j, t.dayUnit) then heard(Day.In(if j == i + 1 && t.dayOrTwo then Vector(1, 2) else Vector(n)), j + 1)
        else if is(j, t.weekUnit) then heard(Day.In(Vector(7 * n)), j + 1)
        else None
      def marked(mode: Mode, of: Vector[Vector[String]]) = said(a, of).flatMap { n =>
        weekday(n) match
          case Some(w) => heard(Day.Weekday(w, mode), n + 1)
          case None => if is(n, t.week) then Some(Heard(What.InWeek(if mode == Mode.Next then 1 else 0), i, n + 1))
            else None
      }
      def bare = weekday(a).flatMap(w => heard(Day.Weekday(w, Mode.Bare), a + 1))
      def numbered(n: Int) = n >= 1 && n <= 31
      def date: Option[Heard] = toks.lift(a) match
        case Some(Tok.Num(n, ' ', _, tail, _, _, _)) if numbered(n) && (tail.isEmpty || t.dateTail(tail)) &&
            !amount(a) =>
          val j = if is(a + 1, t.article) then a + 2 else a + 1
          month(j) match
            case Some(m) => heard(Day.Date(n, Some(m)), j + 1)
            case None => if tail.nonEmpty then heard(Day.Date(n, None), a + 1)
              else if is(a + 1, t.dateWord) then heard(Day.Date(n, None), a + 2)
              else None
        case Some(Tok.Num(n, '/', m, "", _, _, _)) if numbered(n) && m >= 1 && m <= 12 && !amount(a) =>
          heard(Day.Date(n, Some(m)), a + 1)
        case Some(Tok.Num(n, '.', m, "", zero, _, two)) if numbered(n) && m >= 1 && m <= 12 && !amount(a) =>
          Some(Heard(What.Dotted(n, m, two && n <= 23, !zero && n <= 12, as, led), i, a + 1))
        case Some(Tok.Word(w)) =>
          // a date in words: `двадцать первого`, `the twentieth of March`
          val tens = t.numbers.get(w).orElse(t.dates.get(w)).filter(n => n == 20 || n == 30)
          val ordinal = tens.flatMap(n => word(a + 1).flatMap(t.dates.get).filter(_ <= 9).map(u => (n + u) -> (a + 2)))
            .orElse(t.dates.get(w).map(_ -> (a + 1))).filter((n, _) => numbered(n))
          ordinal.flatMap { (n, j0) =>
            val j = if is(j0, t.article) then j0 + 1 else j0
            month(j) match
              case Some(m) => heard(Day.Date(n, Some(m)), j + 1)
              case None => if is(j0, t.dateWord) then heard(Day.Date(n, None), j0 + 1)
                else if led || n >= 11 then heard(Day.Date(n, None), j0)
                else None
          }.orElse(
            // `November 20th`
            if !t.monthFirst then None else month(a).flatMap(m => toks.lift(a + 1) match
              case Some(Tok.Num(n, ' ', _, tail, _, _, _)) if numbered(n) && (tail.isEmpty || t.dateTail(tail)) &&
                  !is(a + 2, t.hourAfter) && part(a + 2).isEmpty => heard(Day.Date(n, Some(m)), a + 2)
              case _ => None))
        case _ => None
      near.orElse(ahead).orElse(marked(Mode.Next, t.nextOne)).orElse(marked(Mode.This, t.thisOne)).orElse(bare)
        .orElse(date)

    // ---- a time ----
    private final case class Core(hour: Int, minute: Int, twelve: Boolean, sure: Boolean, part: Option[Part],
                                  next: Int)

    private def time(i: Int): Option[Heard] =
      val as = role(i)
      val led = as != Role.Plain || is(i, t.at)
      val a = if led then i + 1 else i
      val named = is(a, t.hourBefore)
      val b = if !named then a else if toks.lift(a + 1).contains(Tok.Mark('.')) then a + 2 else a + 1
      def open(hour: Int) = hour >= 1 && hour <= 12
      def digits: Option[Core] = toks.lift(b).collect { case n: Tok.Num => n }.filterNot(_ => amount(b)).flatMap { d =>
        val known = d.tail.isEmpty || t.tails.contains(d.tail)
        val tailed = t.tails.get(d.tail).flatten
        if !known || d.n > 23 then None
        else d.sep match
          case ':' | '.' =>
            if d.two && d.m <= 59 then Some(Core(d.n, d.m, !d.zero && open(d.n), true, tailed, b + 1)) else None
          case ' ' if is(b + 1, counted) || is(b + 2, t.counted) => None
          case ' ' =>
            // `в 15 30`
            val minute = if !(led || named) || d.tail.nonEmpty then None else toks.lift(b + 1).collect {
              case Tok.Num(m, ' ', _, "", _, 2, _) if m <= 59 && m % 5 == 0 && !amount(b + 1) => m }
            Some(Core(d.n, minute.getOrElse(0), !d.zero && open(d.n), d.tail.nonEmpty, tailed,
              b + (if minute.isEmpty then 1 else 2)))
          case _ => None
      }
      // an hour by its own word — or by a plain number's, when minutes follow (`piętnaście piętnaście`)
      val number = word(b).flatMap(t.numbers.get).filter(n => n >= 1 && n <= 23).map(_ -> (b + 1))
      def words: Option[Core] = clock(b).filter(_ => word(b).nonEmpty).orElse(number).flatMap { (hour, n) =>
        val minute = said(n, t.zero).map(0 -> _).orElse(
          named0(n).filter((m, _) => m >= 10 && m <= 59 && m % 5 == 0))
        val end = minute.fold(n)(_._2)
        if is(end, counted) || (minute.isEmpty && !word(b).exists(t.hours.contains)) then None
        else Some(Core(hour, minute.fold(0)(_._1), open(hour), minute.nonEmpty, None, end))
      }
      val core = t.idiom(this, b, as).map((hour, minute, n) => Core(hour, minute, open(hour), true, None, n))
        .orElse(said(b, t.noon).map(n => Core(12, 0, false, true, None, n)))
        .orElse(said(b, t.midnight).map(n => Core(0, 0, false, true, None, n)))
        .orElse(digits).orElse(words)
      core.flatMap { c0 =>
        val k0 = if is(c0.next, t.hourAfter) then c0.next + 1 else c0.next
        // `о сьомій годині тридцять хвилин`
        val more = if k0 == c0.next || c0.minute != 0 then None
          else count(k0).filter((m, j) => m > 0 && m < 60 && is(j, t.minuteUnit))
        val c = more.fold(c0)((m, _) => c0.copy(minute = m))
        val k = more.fold(k0)(_._2 + 1)
        val after = part(k)
        // `три часа` alone may be how long, `three o'clock` may not
        val evident = c.sure || led || named || after.nonEmpty || (k > c.next && !is(c.next, t.hourUnit))
        val soft = word(b).exists(t.soft) && !c.sure && !(led && is(i, t.softAt)) && after.isEmpty
        if !evident || soft then None
        else Some(Heard(What.At(c.hour, c.minute, c.twelve, c.part.orElse(after.map(_._1)), as), i,
          after.fold(k)(_._2)))
      }

    private def named0(i: Int): Option[(Int, Int)] = named(i, t.numbers)

    private def adverb(i: Int): Vector[Heard] =
      t.partAnywhere.flatMap((p, part) => said(i, p).map(part -> _)).maxByOption(_._2).toVector.flatMap {
        (part, n) => Heard(What.PartOfDay(part), i, n) +: (
          if said(i, t.todays).contains(n) then Vector(Heard(What.OnDay(Day.In(Vector(0)), Role.Plain, false), i, n))
          else Vector.empty)
      }

    /** everything heard, left to right: at a place a difference before a day, a day before a time */
    def all: Vector[Heard] =
      val out = Vector.newBuilder[Heard]
      var i = 0
      while i < toks.size do
        val heard = shift(i).orElse(day(i)).orElse(time(i)).fold(adverb(i))(Vector(_))
        out ++= heard
        i = heard.map(_.end).maxOption.getOrElse(i + 1)
      out.result()

  private def heard(text: String): (Vector[Tok], Vector[Heard]) =
    val toks = tokens(text)
    val all = Tongues.of(text).flatMap(t => Hearing(toks, t).all).distinct
    // of two languages' hearings of the same words, the one that heard more of them
    (toks, all.filterNot(h => all.exists(o => o.at <= h.at && o.end >= h.end && o.end - o.at > h.end - h.at))
      .sortBy(_.at))

  /** WHAT A PHRASE SAYS of days and times, before any desk */
  def hear(text: String): Vector[Heard] = heard(text)._2

  /** the starts a time said may be, by the clock it was said by */
  private def minutes(hour: Int, minute: Int, twelve: Boolean, part: Option[Part]): Vector[Int] =
    val hours =
      if !twelve || hour < 1 || hour > 12 then Vector(hour)
      else part match
        case Some(Part.Am)      => Vector(hour % 12)
        case Some(Part.Pm)      => Vector(hour % 12 + 12)
        case Some(Part.Morning) => Vector(hour)
        case Some(Part.Day)     => Vector(if hour <= 6 then hour + 12 else hour)
        case Some(Part.Evening) => Vector(if hour == 12 then 0 else hour + 12)
        case Some(Part.Night)   => Vector(if hour == 12 then 0 else if hour >= 9 then hour + 12 else hour)
        case None               => Vector(hour, (hour + 12) % 24)
    hours.map(_ * 60 + minute)

  /** the first day from a day on with this number (and month) */
  private def dated(today: Long, day: Int, month: Option[Int]): Option[Long] =
    (today until today + 366).find { e => val (_, m, d) = Civil.ymd(e); d == day && month.forall(_ == m) }

  /** what the phrase says, of the desk's options: the sets said, each with the word before it */
  private final class Read(desk: Desk, text: String):
    private val (toks, all) = heard(text)
    private val today = Reports.epochDay(desk.days.headOption.getOrElse("1970-01-01"))
    private val horizon = desk.days.map(Reports.epochDay).toSet
    private def weekdayOf(e: Long): Int = Math.floorMod(e + 3, 7L).toInt
    private val monday = today - weekdayOf(today)

    /** a `10.11` is a date when the horizon has it, else a time when the desk starts then, else a date */
    private val dotted: Vector[Heard] = all.collect { case h @ Heard(What.Dotted(a, b, clock, twelve, as, led), _, _) =>
      val isDate = dated(today, a, Some(b)).exists(horizon)
      val isTime = clock && minutes(a, b, twelve, None).exists(desk.times.contains)
      if !isDate && isTime then h.copy(what = What.At(a, b, twelve, None, as))
      else h.copy(what = What.OnDay(Day.Date(a, Some(b)), as, led))
    }
    private val known: Vector[Heard] = (all.filterNot(_.what.isInstanceOf[What.Dotted]) ++ dotted).sortBy(_.at)

    private val weeks: Option[Int] = known.collect { case Heard(What.InWeek(n), _, _) => n }.distinct match
      case Vector(one) => Some(one)
      case _           => None

    private def one(d: Day): Vector[Long] = d match
      case Day.In(days) => days.map(today + _)
      case Day.Date(day, month) => dated(today, day, month).toVector
      case Day.Weekday(w, mode) =>
        val next = today + Math.floorMod(w - weekdayOf(today) + 6, 7) + 1
        weeks match
          case Some(n) => Vector(monday + 7 * n + w)
          case None => mode match
            case Mode.Bare => if weekdayOf(today) == w then Vector(today, next) else Vector(next)
            case Mode.This => Vector(if weekdayOf(today) == w then today else next)
            case Mode.Next => Vector(next, monday + 7 + w).distinct

    /** days said one after another are one day (`Friday 20.11`, `завтра, в среду`) when they agree */
    private def together(g: Vector[What.OnDay]): Vector[Long] =
      val fixed = g.map(_.day).filterNot(_.isInstanceOf[Day.Weekday]).map(one)
      val named = g.map(_.day).collect { case Day.Weekday(w, _) => w }
      val strict = if fixed.isEmpty then Vector.empty
        else fixed.reduce(_ intersect _).filter(e => named.forall(_ == weekdayOf(e)))
      if strict.nonEmpty then strict else g.flatMap(d => one(d.day)).distinct

    private val dayGroups: Vector[(Role, Vector[String])] =
      val said = known.collect { case h @ Heard(d: What.OnDay, _, _) => (h, d) }
      def glued(a: Heard, b: (Heard, What.OnDay)) = !b._2.led && b._1.at >= a.end &&
        toks.slice(a.end, b._1.at).forall(_ == Tok.Mark(','))
      val groups = said.foldLeft(Vector.empty[Vector[(Heard, What.OnDay)]]) { (gs, m) =>
        gs.lastOption match
          case Some(g) if glued(g.last._1, m) => gs.init :+ (g :+ m)
          case _ => gs :+ Vector(m)
      }
      val named = groups.map(g => g.head._2.role -> together(g.map(_._2)))
      // a week said with no weekday is its days
      val week = if said.exists(_._2.day.isInstanceOf[Day.Weekday]) then None
        else weeks.map(n => Role.Plain -> (0 to 6).toVector.map(monday + 7 * n + _).filter(_ >= today))
      (named ++ week).map((as, days) => as -> days.map(Reports.ofEpoch))

    private val part: Option[Part] = known.collect { case Heard(What.PartOfDay(p), _, _) => p }.distinct match
      case Vector(one) => Some(one)
      case _           => None

    private val timeGroups: Vector[(Role, Vector[Int])] = known.collect {
      case Heard(What.At(hour, minute, twelve, own, as), _, _) => as -> minutes(hour, minute, twelve, own.orElse(part))
    }

    private def narrowed[A](sets: Vector[(Role, Vector[A])], options: Vector[A], action: String, own: Option[A],
                            shifted: Option[A], show: A => String): Narrowed =
      if sets.isEmpty then shifted.fold(Narrowed.Unsaid) { s =>
        if options.contains(s) then Narrowed.Among(Vector(show(s))) else Narrowed.Outside(Vector(show(s)))
      }
      else
        def unless(of: Vector[(Role, Vector[A])], drop: ((Role, Vector[A])) => Boolean) =
          val rest = of.filterNot(drop)
          if rest.isEmpty then of else rest
        // what is said `instead of` is not the one meant
        val live = unless(sets.map((as, s) => as -> s.filter(options.contains)).filter(_._2.nonEmpty),
          _._1 == Role.Instead)
        val kept =
          // a move goes FROM what the booking has, which the phrase may say, to the rest
          if action == "move" then
            val to = unless(live, _._1 == Role.From)
            own.fold(to)(o => unless(to, _._2 == Vector(o)))
          // elsewhere a `from` is the beginning, and an `until` after it the end
          else if live.exists(_._1 == Role.From) then unless(live, _._1 == Role.Until)
          else live
        val found = kept.flatMap(_._2).toSet
        if found.isEmpty then Narrowed.Outside(sets.flatMap(_._2).distinct.map(show))
        else Narrowed.Among(options.filter(found).map(show))

    private def moved(action: String, booking: Option[Booked]): Option[Booked] =
      if action == "move" then booking else None

    def days(action: String, booking: Option[Booked]): Narrowed =
      val own = moved(action, booking)
      val shift = known.collect { case Heard(What.DaysLater(n), _, _) => n }.distinct match
        case Vector(n) => own.map(b => Reports.plus(b.day, n))
        case _         => None
      narrowed(dayGroups, desk.days, action, own.map(_.day), shift, identity)

    def times(action: String, booking: Option[Booked]): Narrowed =
      val own = moved(action, booking)
      val shift = known.collect { case Heard(What.Later(n), _, _) => n }.distinct match
        case Vector(n) => own.map(_.time + n)
        case _         => None
      narrowed(timeGroups, desk.times, action, own.map(_.time), shift, Span.label)

  /** THE DAYS a phrase says, among the desk's: for an action's day line; `booking` is the one a move moves */
  def days(desk: Desk, text: String, action: String, booking: Option[Booked] = None): Narrowed =
    if desk.days.isEmpty then Narrowed.Unsaid else Read(desk, text).days(action, booking)

  /** THE TIMES a phrase says, among the desk's starts (as `09:30`) */
  def times(desk: Desk, text: String, action: String, booking: Option[Booked] = None): Narrowed =
    if desk.days.isEmpty then Narrowed.Unsaid else Read(desk, text).times(action, booking)

/**
 * THE SPACE OF COMMANDS WITH ITS DAYS AND TIMES COMPUTED: `Commands`, but a day's and a time's options are only
 * those the phrase says (`When`), when it says any. A line the phrase says nothing of keeps all its options; a
 * line the phrase settles has one option and is not weighed by any reader; a day or a time the desk does not
 * offer leaves the line no option, and the phrase is not read. The command, its path and its words are
 * `Commands`' own.
 */
object Computed extends Space[Desk, Command]:
  val name = "commands, days and times computed"

  def next(desk: Desk, text: String, chosen: Vector[Choice]): Option[Hole] =
    Commands.next(desk, text, chosen).map { hole =>
      def narrowed(by: (Desk, String, String, Option[Booked]) => Narrowed) =
        val booking = chosen.collectFirst { case Choice("booking", ref) => ref }
          .flatMap(ref => desk.bookings.find(_.ref == ref))
        by(desk, text, chosen.head.option, booking) match
          case Narrowed.Unsaid         => hole
          case Narrowed.Among(options) => Hole(hole.name, hole.options.filter(options.toSet))
          case Narrowed.Outside(_)     => Hole(hole.name, Vector.empty)
      hole.name match
        case "day"  => narrowed(When.days)
        case "time" => narrowed(When.times)
        case _      => hole
    }

  def assemble(desk: Desk, chosen: Vector[Choice]): Option[Command] = Commands.assemble(desk, chosen)
  def path(desk: Desk, c: Command): Vector[Choice] = Commands.path(desk, c)
  def say(desk: Desk, c: Command, language: String): String = Commands.say(desk, c, language)
  def parse(desk: Desk, words: String, language: String): Option[Command] = Commands.parse(desk, words, language)
