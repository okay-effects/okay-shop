package okay.shop.commands.bench

import okay.frames.Laws
import okay.shop.commands.{Command, Commands, Computed}
import okay.shop.model.{Lang, Span}

/** THE GENERATED TEXTS (specs/commands.md A2), the part with no model: desks differ and are their seed's; every
 * canonical text's command is reachable by the options of its own words and parses back; the sealed set is read
 * whole and its shop is not among the desks */
class GenSuite extends munit.FunSuite:
  private val canon = Texts.canon(2000, 100000)

  test("a desk is its seed's, and desks differ"):
    assertEquals(Gen.desk(7), Gen.desk(7))
    val desks = (1L to 300L).map(Gen.desk)
    assert(desks.distinct.size > 295)
    assert(desks.forall(d => d.days.size >= 7 && d.times.size >= 6 && d.services.size >= 2))
    assert(desks.exists(_.masters.isEmpty) && desks.exists(_.bookings.isEmpty))
    assert(!desks.exists(d => d.services == Sealed.desk.services || d.bookings == Sealed.desk.bookings))

  test("every canonical text closes to its command over its own words, and parses to it"):
    for s <- canon do
      val c = s.command.getOrElse(fail(s"no command: ${s.json}"))
      assertEquals(Laws.reach(Commands)(s.desk, s.text, c), None, s.text)
      assertEquals(Commands.parse(s.desk, s.text, s.lang), Some(c), s.text)

  test("A2b: every canonical text's day and time are settled by the organ, its command still reachable"):
    for s <- canon; c <- s.command do
      assertEquals(Laws.reach(Computed)(s.desk, s.text, c), None, s.text)
    assert(Narrowing.of(canon).forall(_._3 == "settled"), Narrowing.of(canon).find(_._3 != "settled"))

  test("A2b: a move by a difference is a move of the desk's, by a start or a day the desk has"):
    val moves = (1L to 400L).flatMap(seed => Gen.relative(new scala.util.Random(seed), Gen.desk(Teach.heldFrom + seed))
      .map(Gen.desk(Teach.heldFrom + seed) -> _))
    assert(moves.size > 250, moves.size)
    for (desk, (c, note)) <- moves do
      assert(Commands.close(desk, "", Commands.path(desk, c)).contains(c), c)
      assert(note.contains("later") || note.contains("earlier") || note.contains("the same time"), note)
    assertEquals(Teach.heldQuota.values.sum, 75)

  private val weekdays = Map("Mon" -> "Monday", "Tue" -> "Tuesday", "Wed" -> "Wednesday", "Thu" -> "Thursday",
    "Fri" -> "Friday", "Sat" -> "Saturday", "Sun" -> "Sunday")

  test("A2c, the gate: a text the organ refutes is rejected, a reachable one kept"):
    // the teacher's slip of A2b: a bare weekday written for the same weekday a week later
    val desks = (1L to 60L).map(Gen.desk).filter(_.days.size >= 14)
    assert(desks.size > 20, desks.size)
    for desk <- desks; i <- Vector(2, 5) do
      val (service, name) = desk.services.head
      val text = s"Put Sophie Hall down for ${name.en} on ${weekdays(Asking.day(desk.days(i)).take(3))} at " +
        Span.label(desk.times.head)
      def book(day: Int, time: Int) = Command.Book("Sophie Hall", service, None, desk.days(day), desk.times(time))
      assertEquals(Teach.refused(desk, text, book(i, 0)), None, text)
      assertEquals(Teach.refused(desk, text, book(i + 7, 0)), Some("organ"), text)
      assertEquals(Teach.refused(desk, text, book(i, 1)), Some("organ"), text)
      assert(Texts.reachable(Commands)(desk, text, book(i + 7, 0)), text)
    // a canonical saying passes the gate, or says its booking's code; a booking said of another day does not pass
    for s <- canon.take(400); c <- s.command do
      val coded = s.path.exists(_.hole == "booking")
      assertEquals(Teach.refused(s.desk, s.text, c), if coded then Some("code") else None, s.text)
      c match
        case b: Command.Book =>
          val other = b.copy(day = s.desk.days.find(_ != b.day).get)
          assertEquals(Teach.refused(s.desk, s.text, other), Some("organ"), s.text)
        case _ =>
    // a client who will not come has no day and no time to refute
    val booked = (1L to 60L).map(Gen.desk).find(_.bookings.nonEmpty).get
    val b = booked.bookings.head
    assertEquals(Teach.refused(booked, s"${b.client} will not make it on Friday at 3", Command.Cancel(b.ref)), None)
    assertEquals(Teach.refused(booked, s"${b.client} (${b.ref}) will not come", Command.Cancel(b.ref)), Some("code"))

  test("A2c: every slice draws moves said indirectly in each language, their commands moves of the desk's"):
    for slice <- Vector(Teach.more, Teach.held3) do
      val asked = (0 until 6000).flatMap(Teach.draw(slice, _))
      for l <- Lang.values; k <- slice.quota.keys do
        val mine = asked.filter(a => a.lang == l && a.kind == k)
        assert(mine.size >= 2 * slice.quota(k), s"${slice.name} $k ${l.code}: ${mine.size}")
      for a <- asked do
        assert(a.seed >= slice.from && a.seed < slice.from + slice.span)
        assertEquals(a.desk, Gen.desk(a.seed))
        val path = Commands.path(a.desk, a.command)
        val words = Commands.say(a.desk, a.command, a.lang.code)
        assert(Commands.close(a.desk, words, path).contains(a.command), a.command)
        assertEquals(Teach.kind(Said(a.seed, a.lang.code, a.source, "", path)), a.kind)
        assertEquals(path.head.option, a.kind.takeWhile(_ != '-'), a.kind)
        assert(a.request.startsWith(s"Language: "), a.request)
      val indirect = asked.filter(a => a.kind == "move-ind" || a.kind == "move-rel-ind")
      for a <- indirect do
        val Command.Move(ref, day, time) = a.command: @unchecked
        val booking = a.desk.bookings.find(_.ref == ref).getOrElse(fail(s"no booking $ref"))
        assert(day != booking.day || time != booking.time, a.command)
        assert(a.desk.days.contains(day) && a.desk.times.contains(time), a.command)
        assert(a.request.contains(Gen.wish) || a.request.contains(Gen.refusal), a.request)
        // a move by a difference carries `Gen.relative`'s note of how to say it
        assertEquals(a.request.contains("Say the new"), a.kind == "move-rel-ind", a.request)
      // both ways of saying, in every language
      for l <- Lang.values; way <- Vector(Gen.wish, Gen.refusal) do
        assert(indirect.count(a => a.lang == l && a.request.contains(way)) > 20, s"${slice.name} ${l.code}")
      // the contrasts are asked for in their own way
      assert(asked.filter(_.kind == "cancel-ind").forall(_.request.contains(Gen.unwilling)))
      assert(asked.filter(_.kind == "book-ind").forall(_.request.contains(Gen.wished)))

  test("A2c: the quotas, and no two sources of texts draw the same desks"):
    assertEquals(Teach.held3.quota.values.sum, 90)
    assertEquals(Teach.held3.quota.keySet.map(_.takeWhile(_ != '-')), Commands.actions.toSet)
    assertEquals(Teach.held3.quota("move-ind") + Teach.held3.quota("move-rel-ind"), 20)
    assert(Vector("move-ind", "move-rel-ind", "cancel-ind", "list", "cancel").forall(Teach.more.quota.contains))
    for slice <- Vector(Teach.more, Teach.held3); (k, n) <- slice.quota do
      assertEquals(slice.turn.count(_ == k), n, s"${slice.name} $k")
    for Seq((a, from, until), (b, from2, until2)) <- Teach.seeds.combinations(2) do
      assert(from < until && from2 < until2 && (until <= from2 || until2 <= from), s"$a and $b meet")
    assertEquals(Teach.seeds.map(_._1).toSet, Set("teacher", "canon", "held", "more", "held3"))
    // the canonical texts of the training and of this suite, and A2's teacher, are inside their ranges
    def within(name: String, seed: Long) =
      Teach.seeds.exists((n, from, until) => n == name && seed >= from && seed < until)
    assert(canon.forall(s => within("canon", s.seed)))
    assert(within("teacher", 1000L + 4319 / 8) && within("held", Teach.heldFrom + 3999))

  test("the actions in the sealed set's shares, the languages in turn"):
    assertEquals(canon.groupBy(_.lang).view.mapValues(_.size).toMap, Lang.values.map(_.code -> 500).toMap)
    val actions = canon.groupBy(_.path.head.option).view.mapValues(_.size).toMap
    assertEquals(actions.keySet, Commands.actions.toSet)
    assert(actions("book") > 600 && actions("cannot") > 80, actions)

  test("the sealed set: 60 phrases, each closing over the sealed desk"):
    val sealedSet = Sealed.phrases
    assertEquals(sealedSet.size, 60)
    assert(sealedSet.forall(_.command.nonEmpty))

  test("the prompt, as the reader sees it"):
    val s = canon.find(_.path.head.option == "move").get
    val (rule, asked) = Asking.prompt(s.desk, s.text)
    println(rule + "\n\n" + asked + "\n→ " + ReadSet.line(s.path))
    assert(asked.endsWith("Phrase: " + s.text))
