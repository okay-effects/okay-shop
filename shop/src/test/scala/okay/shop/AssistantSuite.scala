package okay.shop

import okay.facts.{Audit, Bind, Card, Conversation, Emit, Facts, Lang as FLang, Level, Model, Reply, Setup}
import okay.llm.Llm
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.Storage
import okay.shop.assistant.{About, ShopFacts}
import okay.shop.effects.*
import okay.shop.handlers.StoreDurable
import okay.shop.html.{Chat, Look, Pages, StaticPages}

/** THE ASSISTANT (specs/shop.md "The assistant") on the fictional crew's data, its store in memory, the model a fake
 * replaying fixed outputs — okay-facts' design §12 end to end: found, rendered from the data, booked, confirmed */
class AssistantSuite extends munit.FunSuite:
  type A[X] = (Pure + Storage + Store + Clock + Facts + Llm + Audit + Emit)[X]
  val setup: Setup = Setup(ShopFacts.schema, "You are the assistant of a fictional shop.", Level.L3)

  def turn(w: World, c: Conversation, outputs: Vector[String], text: String): (Conversation, Reply) =
    val program = Emit.none(Audit.ignore(Bind.turn[A](setup, c, text, FLang.pl, "shop:crew")))
    run(w.storage.handler(StoreDurable.run(Clock.virtual(w.now)(ShopFacts.facts(w.conf, "crew", About())(
      Model.replay(outputs)(program))))))._2

  def confirm(w: World, token: String, name: String = "Anna Test", contact: String = "anna@example.com",
              conversation: String = "conv-1") =
    type C[X] = (Pure + Storage + Store + Ids + Mail + Clock + Notify + Geocoder)[X]
    val (kept, (_, out)) = run(w.storage.handler(StoreDurable.run(Ids.seeded(7)(Mail.kept()(Clock.virtual(w.now)(
      Notify.kept(w.notices)(Geocoder.none(ShopFacts.confirm[C](w.conf, "crew", token, name, contact, consent = true,
        FLang.pl, "1.2.3.4", conversation)))))))))
    w.letters ++= kept
    out

  val findCut: String = Model.call("find", """{"class": "Service", "text": "strzyżenie"}""")
  val findSlots: String = Model.call("find",
    """{"class": "Slot", "where": ["service = #v1", "date = tomorrow", "time >= 17:00"]}""")
  val answer: String = """{"params": {"s": "name(#v1)", "p": "price(#v1)", "d": "duration(#v1)", "t1": "time(#s1)",
    "m1": "name(master(#s1)) as nom", "t2": "time(#s2)"}, "template": "Tak, {s} kosztuje {p} i trwa {d}.
    Jutro wolne: {t1}
    ({m1}) albo {t2}. Zapisać?"}""".replace("\n    ", " ")

  test("§12's dialogue end to end on Memory: found, rendered from the data, booked by the card, confirmed"):
    val w = World("crew")
    val (c1, r1) = turn(w, Conversation(), Vector(findCut, findSlots, answer), "Ile kosztuje strzyżenie i kiedy " +
      "jutro po 17?")
    assertEquals(r1.rendered.map(_.text), Some("Tak, Strzyżenie kosztuje 60,00 zł i trwa 45 minut. Jutro wolne: " +
      "17:00 (Piotr Próbny) albo 17:15. Zapisać?"))
    // the model saw handles and names only: no price, no time, no master's name
    val seen = c1.history.map(_.render).mkString
    for v <- Seq("60", "17:00", "17:15", "45", "Piotr") do assert(!seen.contains(v), s"the model saw $v")
    val (c2, r2) = turn(w, c1, Vector(Model.call("book", """{"slot": "#s1"}"""),
      """{"params": {"t": "time(#s1)"}, "template": "Potwierdź rezerwację na {t} w karcie poniżej."}"""),
      "Tak, 17:00. Anna, anna@example.com")
    assertEquals(r2.rendered.map(_.text), Some("Potwierdź rezerwację na 17:00 w karcie poniżej."))
    val card = r2.cards.collectFirst { case b: Card.Book => b }.get
    assertEquals(card.title, "Strzyżenie")
    assertEquals(card.lines, Vector("piątek, 30 października, 17:00", "45 minut", "60,00 zł", "Piotr Próbny"))
    // the client's data never went to the model: the card takes it
    assert(!c2.history.map(_.render).mkString.contains("Anna Test"))
    confirm(w, card.token) match
      case Left(why) => fail(why.toString)
      case Right((b, acted, rendered)) =>
        assertEquals(b.master, "piotr")
        assert(rendered.text.startsWith("Zarezerwowano: Strzyżenie, piątek, 30 października, 17:00 — Piotr Próbny, " +
          s"60,00 zł. Numer rezerwacji: ${b.ref}. Zmiana lub odwołanie: strona rezerwacji"), rendered.text)
        assert(rendered.chips.exists(_.link.exists(_.contains("/pl/booking?ref="))))
        assertEquals(acted.pinned("price").value, okay.facts.Value.Money(6000, "PLN"))
        assertEquals(w.letters.size, 2)
        assert(w.notices.exists(_.isInstanceOf[Notice.Booked]))
    // the slot is taken now: a second card for it is refused, the same start again too
    val (_, r3) = turn(w, c2, Vector(Model.call("book", """{"slot": "#s1"}"""),
      """{"params": {}, "template": "Ten termin jest już zajęty."}"""), "Jeszcze raz 17:00")
    assertEquals(r3.cards, Vector.empty)
    assertEquals(confirm(w, card.token, "Ewa Test", "ewa@example.com").left.map(_._1), Left("taken"))

  test("a booking from the chat: the form's checks, and its limits — 3 a conversation a day"):
    val w = World("crew")
    val (_, r) = turn(w, Conversation(), Vector(findCut, findSlots, Model.call("book", """{"slot": "#s2"}"""),
      """{"params": {}, "template": "Proszę potwierdzić."}"""), "?")
    val token = r.cards.collectFirst { case b: Card.Book => b.token }.get
    assertEquals(confirm(w, token, name = "").left.map(_._1), Left("name"))
    assertEquals(confirm(w, token, contact = "nope").left.map(_._1), Left("contact"))
    val starts = Vector(10, 11, 12, 13).map(h => token.split('|') match
      case Array(s, _, t) => s"$s|piotr|${t.toLong - (17 * 60 + 15 - h * 60) * 60000L}")
    val made = starts.map(t => confirm(w, t, contact = s"c$t@example.com").left.map(_._1))
    assertEquals(made.map(_.isRight), Vector(true, true, true, false))
    assertEquals(made.last, Left("limit"))

  test("the facts the model may see: names and words only; a hidden service is not there"):
    val w = World("crew", edit = s => s.copy(offers = s.offers.map(o => if o.key == "consult" then
      o.copy(hidden = true) else o)))
    type F[X] = (Pure + Storage + Store + Clock + Facts)[X]
    val found = run(w.storage.handler(StoreDurable.run(Clock.virtual(w.now)(ShopFacts.facts(w.conf, "crew", About())(
      Facts.find[F](okay.facts.Query("Service", limit = 10)))))))._2
    assert(!found.exists(_.ent.key == "consult"))
    assert(found.forall(f => f.visible.forall((k, v) => Set("name", "description")(k) && !v.exists(_.isDigit))))

  test("the widget's marker only where the look enables the chat: every other page as before"):
    val site = Fixtures.site("crew")
    val plain = new Look:
      def theme = okay.shop.html.Theme(":root{}", "#fff")
      def seo(s: okay.shop.model.Site)(using okay.shop.model.Lang) = okay.shop.html.Seo(s.name, s.name)
    val chatty = new Look:
      def theme = plain.theme
      def seo(s: okay.shop.model.Site)(using okay.shop.model.Lang) = plain.seo(s)
      override def chat = Some(Chat("/_chat", "shop:crew"))
    val before = StaticPages.of(site, plain, "key").toMap
    val after = StaticPages.of(site, chatty, "key").toMap
    val changed = before.keys.filter(k => before(k) != after(k)).toVector.sorted
    assert(changed.nonEmpty && changed.forall(k => k.endsWith("/index.html") && !k.contains("privacy")), changed)
    assert(after("pl/index.html").contains("""<div id="okay-chat" data-endpoint="/_chat" data-focus="shop:crew" """ +
      "hidden></div>"))
    assertEquals(after("pl/index.html").replace(Pages.chat(chatty), ""), before("pl/index.html"))

  test("the privacy policy's assistant section only with the chat"):
    val site = Fixtures.site("crew")
    assertEquals(okay.shop.html.Privacy.page(site, okay.shop.model.Lang.pl, chat = 0),
      okay.shop.html.Privacy.page(site, okay.shop.model.Lang.pl))
    assert(okay.shop.html.Privacy.page(site, okay.shop.model.Lang.pl, chat = 30).contains("po 30 dniach bez " +
      "aktywności"))
