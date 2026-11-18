package okay.shop.commands.bench

import okay.frames.Laws
import okay.shop.commands.Commands
import okay.shop.model.Lang

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
