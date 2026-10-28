package okay.shop

import okay.scala3.*
import okay.serverless.Storage
import okay.serverless.cloud.text
import okay.shop.effects.Store
import okay.shop.handlers.StoreDurable
import okay.shop.model.*

class ApiSuite extends munit.FunSuite:
  test("an order placed: its ref and key, BLIK with the price, the owner's and the client's letters") {
    val w = World()
    val r = w.order("hem", extra = Seq("deliveryMethod" -> "courier"))
    assertEquals(r.status, 200)
    val j = w.json(r)
    assert(j("ref").matches("SZY-[0-9A-HJKMNP-TV-Z]{6}"), j)
    assertEquals(j("key").length, 24)
    assertEquals(j.get("price"), Some("40 zł"))
    assertEquals(j.get("blik"), Some("+48 790 869 655"))
    assertEquals(j.get("paczkomat"), Some("WRO06L"))
    assertEquals(w.letters.map(_._1), Vector("owner", "anna@example.com"))
    val client = w.letters(1)._2
    // the letter's ref is the one the track page takes, its link this site's, with the key
    assert(client.html.contains(j("ref")))
    assert(client.html.contains(s"${w.conf.origin}/pl/track?ref=${j("ref")}&amp;k=${j("key")}"), client.html)
    assert(client.subject.startsWith("Potwierdzenie zlecenia"))
    assert(client.text.contains("Przedpłata 40 zł"))
    // the text part keeps the link: the track page, with the ref and the key
    assert(client.text.contains(s"/pl/track?ref=${j("ref")}&k=${j("key")}"), client.text)
    assert(w.letters.head._2.html.contains("do piątku"))
  }

  test("the client's language decides the letter's") {
    val w = World()
    w.order("fit", extra = Seq("lang" -> "uk"))
    assert(w.letters(1)._2.subject.startsWith("Підтвердження замовлення"), w.letters(1)._2.subject)
  }

  test("a quoted offer: BLIK with the amount to be confirmed; a phone gets no letter") {
    val w = World()
    val j = w.json(w.order("bespoke", contact = "+48 600 100 200", extra = Seq("fabric" -> "len")))
    assertEquals(j.get("price"), None)
    assertEquals(w.letters.map(_._1), Vector("owner"))
    assert(w.letters.head._2.html.contains("len"))
  }

  test("what is refused: no name, no contact, no consent, an unknown offer, a bot, too many") {
    val w = World()
    assertEquals(w.order(name = "").status, 400)
    assertEquals(w.order(contact = "nobody").status, 400)
    assertEquals(w.post("/api/order", "name" -> "A", "contact" -> "a@b.pl").text, """{"error":"consent"}""")
    assertEquals(w.order("nothing").status, 400)
    w.human = false
    assertEquals(w.order().status, 403)
    w.human = true
    (1 to 4).foreach(_ => assertEquals(w.order().status, 200))
    assertEquals(w.order().status, 429) // the bot's try counted too: five in ten minutes
    w.now += 11 * 60 * 1000L
    assertEquals(w.order().status, 200)
    assert(w.letters.forall(_._1 != "nobody"))
  }

  test("what the client typed is escaped in every letter") {
    val w = World()
    w.order(name = "<script>alert(1)</script>")
    assert(w.letters.forall(!_._2.html.contains("<script>")))
    assert(w.letters.head._2.html.contains("&lt;script&gt;"))
  }

  test("track: the letter's link shows the status; the form needs the same email or phone; nothing else tells") {
    val w = World()
    val j = w.json(w.order())
    val ok = w.get(s"/pl/track?ref=${j("ref")}&k=${j("key")}")
    assert(ok.text.contains("Przyjęto — wyceniamy"), ok.text)
    assert(ok.text.contains(j("ref")))
    val wrongKey = w.get(s"/pl/track?ref=${j("ref")}&k=AAAAAAAAAAAAAAAAAAAAAAAA").text
    assert(wrongKey.contains("Nie znaleziono"))
    assert(w.form("/en/track", "ref" -> j("ref").toLowerCase, "contact" -> "Anna@Example.com",
      "cf-turnstile-response" -> "t").text.contains("Received — quoting"))
    val stranger = w.form("/en/track", "ref" -> j("ref"), "contact" -> "eve@example.com").text
    val nothing = w.form("/en/track", "ref" -> "SZY-000000", "contact" -> "eve@example.com").text
    assertEquals(stranger.replace(j("ref"), "SZY-000000"), nothing)
    assert(w.get("/ru/track").text.contains("Статус заказа"))
  }

  test("an order's events: placed, emailed — and the receipt only when paid") {
    val w = World()
    val j = w.json(w.order())
    assertEquals(w.get(s"/pl/receipt?ref=${j("ref")}&k=${j("key")}").status, 404)
    val history = run(w.storage.handler(StoreDurable.run(Store.history[Pure + Storage + Store](j("ref")))))
    assertEquals(history.map(_._2) match
      case Vector(Event.Placed(o), Event.Emailed(to, _, sent)) => (o.ref, to, sent)
      case other => other, (j("ref"), "anna@example.com", true))
  }

  test("an inquiry of /products: kept, the owner told, its ref answered") {
    val w = World("it")
    val r = w.post("/api/inquiry", "name" -> "Jan", "contact" -> "jan@example.com", "subject" -> "CI",
      "kind" -> "task", "body" -> "Need CI", "rodo_consent" -> "on", "cf-turnstile-response" -> "t")
    assertEquals(r.status, 200, r.text)
    assert(w.json(r)("ref").startsWith("ITCQ-"))
    assertEquals(w.letters.map(_._1), Vector("owner"))
    assert(w.letters.head._2.html.contains("Need CI"))
  }
