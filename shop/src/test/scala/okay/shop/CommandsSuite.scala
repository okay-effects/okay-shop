package okay.shop

import okay.shop.commands.*
import okay.shop.model.*

/** THE MASTER'S COMMANDS, THE EXACT PART (specs/commands.md A1): the options, the words and their parser, over the
 * fictional shop and the 60 sealed phrases of scripts/commands/phrases.tsv (`Fixtures.phrases`, by the build) */
class CommandsSuite extends munit.FunSuite:
  private val desk = Desk(
    days = (16 to 29).toVector.map(d => s"2026-11-$d"),
    times = (540 until 1140 by 30).toVector,
    services = Vector("haircut" -> T("Стрижка", "Strzyżenie", "Haircut", "Стрижка"),
      "beard" -> T("Борода", "Broda", "Beard", "Борода"),
      "color" -> T("Окрашивание", "Koloryzacja", "Colouring", "Фарбування"),
      "manicure" -> T("Маникюр", "Manicure", "Manicure", "Манікюр")),
    masters = Vector("olena" -> "Olena", "marek" -> "Marek"),
    bookings = Vector(Booked("b1", "Anna Kowalska", "haircut", "olena", "2026-11-17", 600),
      Booked("b2", "Piotr Nowak", "beard", "marek", "2026-11-17", 840),
      Booked("b3", "Oksana Melnyk", "color", "olena", "2026-11-18", 720),
      Booked("b4", "Ivan Petrenko", "haircut", "marek", "2026-11-19", 990),
      Booked("b5", "Maria Wiśniewska", "manicure", "olena", "2026-11-20", 570)))

  /** a phrase: its language, its text, the action and the lines written by hand */
  private val phrases: Vector[(String, String, String, Map[Hole, String])] =
    Fixtures.phrases.linesIterator.filter(_.nonEmpty).map(_.split("\t", -1)).collect {
      case Array(lang, text, action, gold) =>
        val lines = gold.split(";").filter(_.nonEmpty).map { kv =>
          val Array(k, v) = kv.split("=", 2); Hole.values.find(_.key == k).get -> v }.toMap
        (lang, text, action, lines)
    }.toVector

  private val golds: Vector[Command] =
    phrases.flatMap((_, text, action, lines) => Frames.close(desk, text, action, lines))

  /** every command of the desk worth saying: the phrases' own, and each kind over the shop's options */
  private val commands: Vector[Command] =
    val masters = None +: desk.masters.map(m => Some(m._1))
    val days = Vector(desk.days.head, desk.days(4), desk.days.last)
    val times = Vector(desk.times.head, 870, desk.times.last)
    val refs = desk.bookings.map(_.ref)
    (golds ++
      (for c <- Vector("Emma", "Олю Иванову", "Ewa Lis"); (s, _) <- desk.services; m <- masters; d <- days
           t <- times yield Command.Book(c, s, m, d, t)) ++
      (for b <- refs; d <- days; t <- times yield Command.Move(b, d, t)) ++ refs.map(Command.Cancel(_)) ++
      (for (m, _) <- desk.masters; d <- desk.days yield Command.DayOff(m, d)) ++
      (for b <- refs; a <- Vector(8000L, 7050L, 100L); w <- Method.values.toVector yield Command.Paid(b, a, w)) ++
      (for d <- desk.days; m <- masters yield Command.Show(d, m)) :+ Command.Cannot).distinct

  test("the sealed set is whole: 60 phrases, 15 a language"):
    assertEquals(phrases.size, 60)
    assertEquals(phrases.groupBy(_._1).view.mapValues(_.size).toMap,
      Map("ru" -> 15, "uk" -> 15, "pl" -> 15, "en" -> 15))

  test("L1: every line's options are finite and there, for every phrase and action"):
    for (_, text, _, _) <- phrases; action <- Frames.actions; hole <- Frames.holes(action)
        if hole != Hole.Amount do
      assert(Frames.options(desk, text, action, hole).nonEmpty, s"$action ${hole.key}: $text")
    assert(!Frames.options(desk, "x", "dayoff", Hole.Master).contains(Frames.Any))

  test("L2: every phrase's hand-written command is reachable by the options"):
    for (lang, text, action, lines) <- phrases do
      assertEquals(lines.keySet, Frames.holes(action).toSet, s"$lang: $text")
      for (hole, value) <- lines do
        assert(Frames.options(desk, text, action, hole).contains(value), s"${hole.key}=$value not offered: $text")
      assert(Frames.close(desk, text, action, lines).nonEmpty, s"not closed: $text")
    assertEquals(golds.size, 60)

  test("a value outside the options closes nothing"):
    val text = "Book Emma for a haircut on Friday at 3 with Marek"
    val lines = Map(Hole.Client -> "Emma", Hole.Service -> "haircut", Hole.Master -> "marek",
      Hole.Day -> "2026-11-20", Hole.Time -> "15:00")
    assert(Frames.close(desk, text, "book", lines).nonEmpty)
    assertEquals(Frames.close(desk, text, "book", lines.updated(Hole.Client, "Sophie")), None)
    assertEquals(Frames.close(desk, text, "book", lines.updated(Hole.Service, "massage")), None)
    assertEquals(Frames.close(desk, text, "book", lines.updated(Hole.Day, "2027-01-01")), None)
    assertEquals(Frames.close(desk, text, "book", lines - Hole.Time), None)

  test("the lines of a command close to it again"):
    for (_, text, action, lines) <- phrases; c <- Frames.close(desk, text, action, lines) do
      assertEquals(Frames.lines(c), action -> lines)

  test("L5: the words of a command parse to it, in every language"):
    for l <- Lang.values; c <- commands do
      val words = Words.say(desk, c, l)
      assertEquals(Words.parse(desk, words, l), Some(c), s"${l.code}: $words")

  test("two commands never say the same, and other words are no command"):
    for l <- Lang.values do
      assertEquals(commands.map(Words.say(desk, _, l)).distinct.size, commands.size, l.code)
      assertEquals(Words.parse(desk, "Book Emma for a haircut on Friday", l), None)
      assertEquals(Words.parse(desk, Words.say(desk, Command.Cancel("b1"), l) + " ", l), None)

  test("the words, as a master reads them"):
    assertEquals(Words.say(desk, Command.Book("Emma", "haircut", Some("marek"), "2026-11-20", 900), Lang.en),
      "Book: «Emma» — Haircut, Marek, Friday 20.11 at 15:00.")
    assertEquals(Words.say(desk, Command.Move("b1", "2026-11-18", 750), Lang.pl),
      "Przeniesienie: b1 «Anna Kowalska» (Strzyżenie, Olena, wtorek 17.11, godz. 10:00) — na środa 18.11, " +
        "godz. 12:30.")
    assertEquals(Words.say(desk, Command.Paid("b2", 8000, Method.Card), Lang.ru),
      "Оплачено: b2 «Piotr Nowak» (Борода, Marek, вторник 17.11 в 14:00) — 80 zł, Карта.")
    assertEquals(Words.say(desk, Command.Show("2026-11-18", None), Lang.uk),
      "Показати записи: середа 18.11, усі майстри.")
