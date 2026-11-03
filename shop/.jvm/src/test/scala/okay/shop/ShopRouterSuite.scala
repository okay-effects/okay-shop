package okay.shop

import okay.facts.{Audit, Card, Conversation, Facts, Icu, Lang as FLang, Level, Setup, Validator}
import okay.facts.router.Table
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.Storage
import okay.shop.assistant.{About, ShopFacts, ShopRouter}
import okay.shop.assistant.ShopRouter.Decision
import okay.shop.effects.*
import okay.shop.handlers.StoreDurable


/** THE ROUTER LANE (specs/shop.md "The assistant") on the fictional crew's data: no model — the intent, the slots, an
 * authored answer over the handles found, rendered by okay-facts from the data; what it does not understand declined */
class ShopRouterSuite extends munit.FunSuite:
  type A[X] = (Pure + Storage + Store + Clock + Facts + Audit)[X]
  val setup: Setup = Setup(ShopFacts.schema, "You are the assistant of a fictional shop.", Level.L3)
  lazy val lane: ShopRouter.Lane = ShopRouter.lane(Table(getClass.getResourceAsStream(ShopRouter.resource)
    .readAllBytes))

  def turn(w: World, text: String, l: FLang = FLang.pl, c: Conversation = Conversation()): Decision =
    val program = Audit.ignore(ShopRouter.turn[A](lane, setup, c, text, l, "shop:crew"))
    run(w.storage.handler(StoreDurable.run(Clock.virtual(w.now)(ShopFacts.facts(w.conf, "crew", About())(
      program)))))._2

  def said(d: Decision): String = d match
    case Decision.Answered(_, r, _, _) => r.rendered.map(_.text).getOrElse("(none)")
    case Decision.Declined(route, why, _) => s"(declined: $why, ${route.intent} ${route.by} ${route.p})"

  def conv(d: Decision): Conversation = d match
    case Decision.Answered(c, _, _, _) => c
    case _ => fail(s"not answered: ${said(d)}")

  test("every authored answer: an ICU message, no digit, no comparison in words outside a select, in four languages"):
    val a = ShopRouter.answers
    for (key, byLang) <- a.heads; l <- FLang.values do
      val tpl = byLang.getOrElse(l, fail(s"$key has no $l"))
      val items = a.items.getOrElse(key, Map.empty).keys.toVector
      val full = if items.isEmpty then tpl else a.list(key, l, items ++ items)
      Icu.parse(full) match
        case Left(e) => fail(s"$key/$l: $e in $full")
        case Right(parts) =>
          assert(!Icu.texts(parts).exists(_.exists(_.isDigit)), s"$key/$l has a digit: $full")
          assertEquals(Icu.topTexts(parts).flatMap(Validator.comparative), Vector.empty, s"$key/$l: $full")

  test("a price in four languages: the service by meaning, its price and duration rendered from the data"):
    val w = World("crew")
    assertEquals(said(turn(w, "Ile kosztuje strzyżenie?")), "Strzyżenie: 60,00 zł, czas trwania 45 minut.")
    assert(said(turn(w, "Скільки коштує стрижка?", FLang.uk)).contains("60,00 zł"))
    assert(said(turn(w, "Сколько стоит стрижка?", FLang.ru)).contains("60,00 zł"))
    // the crew has no English names: an English word of its own is not guessed into a Polish name
    assert(said(turn(w, "How much is a haircut?", FLang.en)).startsWith("Our services: Strzyżenie — 60.00 zł;"))
    // a quote has no price: said so, invented never
    assertEquals(said(turn(w, "Ile kosztuje hydraulik?")), "Hydraulik: czas trwania 1 godzina, cena ustalana " +
      "indywidualnie.")

  test("a follow-up takes the conversation's service; no service named: the price list"):
    val w = World("crew")
    val first = turn(w, "Ile kosztuje konsultacja?")
    assertEquals(said(first), "Konsultacja: 50,00 zł, czas trwania 30 minut.")
    assertEquals(said(turn(w, "a ile trwa?", c = conv(first))), "Konsultacja: 50,00 zł, czas trwania 30 minut.")
    assert(said(turn(w, "Jakie są wasze ceny?")).startsWith("Nasze usługi: Strzyżenie — 60,00 zł;"))

  test("free times: tomorrow after 17, the masters' names in their case when known, else a construction needing none"):
    val w = World("crew")
    val d = turn(w, "Macie jutro wolne na strzyżenie po 16?")
    assert(said(d).startsWith("Strzyżenie, piątek, 30 października — wolne terminy: 16:00"), said(d))
    // "Piotr Próbny" (two words) is not declined: the nominative, in the construction that needs no case
    assert(said(d).contains("(mistrz: Piotr Próbny)"), said(d))
    val kasia = said(turn(w, "Kiedy jest wolny termin na konsultację u Kasi?"))
    assert(kasia.contains("u Kasi"), kasia)

  test("a booking: a time asked gives the existing card — never booked by itself"):
    val w = World("crew")
    turn(w, "Zapisz mnie jutro na strzyżenie o 16") match
      case Decision.Answered(_, r, route, slots) =>
        assertEquals(route.intent, "book")
        assertEquals(slots.get("at"), Some("16:00"))
        assert(r.cards.exists(_.isInstanceOf[Card.Book]), r.cards)
        assert(r.rendered.get.text.startsWith("Potwierdź rezerwację w karcie poniżej: Strzyżenie, piątek"))
      case d => fail(said(d))

  test("hours, address, the area (yes, not there), the modes, the masters, a human, help, greetings"):
    val w = World("crew")
    assert(said(turn(w, "Do której jesteście otwarci?")).startsWith("Godziny otwarcia: pon 10:00–18:00"))
    assert(said(turn(w, "Jaki jest wasz adres?")).startsWith("Adres: ul. Testowa 1"))
    assertEquals(said(turn(w, "Czy dojeżdżacie do Piaseczna?")), "Tak, dojeżdżamy: Piaseczno. Cały obszar: " +
      "Warszawa, Piaseczno.")
    assert(said(turn(w, "Czy dojeżdżacie do Radomia?")).startsWith("Tego miejsca nie ma na naszej liście"))
    assert(said(turn(w, "Czy można wysłać paczką?")).startsWith("Jak pracujemy: U nas, Dojazd do klienta"))
    assertEquals(said(turn(w, "Kto u was pracuje?")), "U nas pracują: Ola Testowa, Piotr Próbny, Kasia.")
    assertEquals(said(turn(w, "Jak się z wami skontaktować?")), "Napisz do nas: atelier@example.com.")
    assert(said(turn(w, "Co umiesz?")).startsWith("Podam ceny"))
    assertEquals(said(turn(w, "Dzięki!")), "Proszę bardzo! Gdyby coś jeszcze — pisz.")
    assertEquals(said(turn(w, "Привіт", FLang.uk)), "Добрий день! Чим можу допомогти?")

  test("a comparison is the resolver's: the select's words chosen by its boolean"):
    val w = World("crew")
    val first = turn(w, "Ile kosztuje strzyżenie?")
    assertEquals(said(turn(w, "a konsultacja jest tańsza?", c = conv(first))), "Konsultacja: 50,00 zł, " +
      "Strzyżenie: 60,00 zł — taniej jest Konsultacja.")

  test("what it does not understand goes to the model lane"):
    val w = World("crew")
    for t <- Seq("Jaką fryzurę polecasz na wesele?", "qwrtzx", "Czy mogę zapłacić kartą?") do
      assert(turn(w, t).isInstanceOf[Decision.Declined], s"$t: ${said(turn(w, t))}")
