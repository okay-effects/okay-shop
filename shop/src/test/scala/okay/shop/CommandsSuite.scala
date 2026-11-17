package okay.shop

import okay.frames.{Choice, Hole, Laws, Measure, Reading}
import okay.shop.commands.*
import okay.shop.model.*

/** THE MASTER'S COMMANDS, THE EXACT PART (specs/commands.md A1): the space `Commands` under okay-frames' laws, its
 * words and their parser, over the fictional shop and the 60 sealed phrases of scripts/commands/phrases.tsv
 * (`Fixtures.phrases`, by the build) */
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
  private val languages = Lang.values.toVector.map(_.code)

  /** a phrase: its language, its text, and the choices written by hand, in the space's order */
  private val phrases: Vector[(String, String, Vector[Choice])] =
    Fixtures.phrases.linesIterator.filter(_.nonEmpty).map(_.split("\t", -1)).collect {
      case Array(lang, text, action, gold) =>
        val lines = gold.split(";").filter(_.nonEmpty).map { kv => val Array(k, v) = kv.split("=", 2); k -> v }.toMap
        assertEquals(lines.keySet, Commands.lines(action).toSet, text)
        (lang, text, Choice(Commands.Action, action) +: Commands.lines(action).map(l => Choice(l, lines(l))))
    }.toVector

  private val golds: Vector[(String, Command)] =
    phrases.flatMap((_, text, path) => Commands.close(desk, text, path).map(text -> _))

  /** every command of the desk worth saying: the phrases' own, and each kind over the shop's options */
  private val commands: Vector[Command] =
    val masters = None +: desk.masters.map(m => Some(m._1))
    val days = Vector(desk.days.head, desk.days(4), desk.days.last)
    val times = Vector(desk.times.head, 870, desk.times.last)
    val refs = desk.bookings.map(_.ref)
    (golds.map(_._2) ++
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
    for (_, text, _) <- phrases; action <- Commands.actions; line <- Commands.lines(action) if line != "amount" do
      assert(Commands.options(desk, text, action, line).nonEmpty, s"$action $line: $text")
    assert(!Commands.options(desk, "x", "dayoff", "master").contains(Commands.Any))
    assertEquals(Commands.next(desk, "x", Vector.empty), Some(Hole(Commands.Action, Commands.actions)))

  test("L2: every phrase's hand-written command is reachable by the options"):
    assertEquals(golds.size, 60, "every hand-written path closes")
    for (text, command) <- golds do assertEquals(Laws.reach(Commands)(desk, text, command), None, text)

  test("a value outside the options closes nothing"):
    val text = "Book Emma for a haircut on Friday at 3 with Marek"
    val path = Vector(Choice("action", "book"), Choice("client", "Emma"), Choice("service", "haircut"),
      Choice("master", "marek"), Choice("day", "2026-11-20"), Choice("time", "15:00"))
    assert(Commands.close(desk, text, path).nonEmpty)
    assertEquals(Commands.close(desk, text, path.updated(1, Choice("client", "Sophie"))), None)
    assertEquals(Commands.close(desk, text, path.updated(2, Choice("service", "massage"))), None)
    assertEquals(Commands.close(desk, text, path.updated(4, Choice("day", "2027-01-01"))), None)
    assertEquals(Commands.close(desk, text, path.dropRight(1)), None)
    assertEquals(Commands.close(desk, text, path.updated(0, Choice("action", "refund"))), None)

  test("L5: the words of a command parse to it, in every language"):
    for c <- commands do assertEquals(Laws.roundTrip(Commands)(desk, c, languages), None)

  test("two commands never say the same, and other words are no command"):
    for l <- languages do
      assertEquals(Laws.distinct(Commands)(desk, commands, l), None)
      assertEquals(Commands.parse(desk, "Book Emma for a haircut on Friday", l), None)
      assertEquals(Commands.parse(desk, Commands.say(desk, Command.Cancel("b1"), l) + " ", l), None)
    assertEquals(Commands.parse(desk, Commands.say(desk, Command.Cannot, "de"), "de"), None, "no such language")

  test("the words, as a master reads them"):
    assertEquals(Commands.say(desk, Command.Book("Emma", "haircut", Some("marek"), "2026-11-20", 900), "en"),
      "Book: «Emma» — Haircut, Marek, Friday 20.11 at 15:00.")
    assertEquals(Commands.say(desk, Command.Move("b1", "2026-11-18", 750), "pl"),
      "Przeniesienie: b1 «Anna Kowalska» (Strzyżenie, Olena, wtorek 17.11, godz. 10:00) — na środa 18.11, " +
        "godz. 12:30.")
    assertEquals(Commands.say(desk, Command.Paid("b2", 8000, Method.Card), "ru"),
      "Оплачено: b2 «Piotr Nowak» (Борода, Marek, вторник 17.11 в 14:00) — 80 zł, Карта.")
    assertEquals(Commands.say(desk, Command.Show("2026-11-18", None), "uk"),
      "Показати записи: середа 18.11, усі майстри.")

  test("a reading by an oracle reads all 60; a weigher with no opinion is asked 60 times and never wrong"):
    def oracle(r: Reading[Desk, Command], h: Hole): Vector[Double] =
      val wanted = Commands.path(desk, golds.find(_._1 == r.text).get._2).lift(r.chosen.size).map(_.option)
      h.options.map(o => if wanted.contains(o) then 1.0 else 0.0)
    val exact = Measure.of(Commands)(desk, golds, 0.5)(oracle)
    assertEquals((exact.right, exact.asked, exact.unread, exact.wrong), (60, 0, 0, 0), exact.said)
    // equal scores: every choice has a zero margin, so everything is asked and nothing is answered wrongly
    val flat = Measure.of(Commands)(desk, golds, 0.5)((_, h) => h.options.map(_ => 0.0))
    assertEquals((flat.right, flat.wrong, flat.asked), (0, 0, 60), flat.said)
