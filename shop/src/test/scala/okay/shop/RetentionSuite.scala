package okay.shop

import okay.scala3.*
import okay.serverless.{Memory, Storage}
import okay.shop.effects.{Expired, Store}
import okay.shop.handlers.StoreDurable
import okay.shop.model.*

/** RETENTION (specs/shop.md "Retention"): the store on memory, a clock that is a number — rate limits by a keyed
 * hash and their ended windows, the old raw keys, the sign-ins past, the clients' records five years after their
 * last activity, the accounting records kept; once in 20 hours unless forced */
class RetentionSuite extends munit.FunSuite:
  type S[X] = (Pure + Storage + Store)[X]
  private val t0 = 1793282400000L // 2026-10-29 14:00 UTC
  private val day = 86400000L

  private def run[A](m: Memory, rateKey: String = "")(p: A ! S): A =
    okay.scala3.run(m.handler(StoreDurable.run(p, rateKey)))

  private def order(ref: String, at: Long) = Order(ref, "atelier", "k", "fix", "Test Client", "client@example.com",
    "", "", "", "", "", "", Lang.pl, Consent(at, "2026-10-28"), at)

  test("a rate limit's key is kept as its HMAC under the rate key, never the IP; ended windows go") {
    val m = Memory()
    assert(run(m, "secret")(Store.allow[S]("order:203.0.113.7", 5, 600000L, t0)))
    val keys = m.keys("site/hit/")
    assertEquals(keys.size, 1)
    assert(!keys.head.contains("203.0.113.7"), keys.head)
    assertEquals(keys.head, s"site/hit/${StoreDurable.hashed("order:203.0.113.7", "secret")}/600000/${t0 / 600000L}")
    // another key, another hash; no key: the plain SHA-256, still not the IP
    assertNotEquals(StoreDurable.hashed("order:203.0.113.7", "other"), StoreDurable.hashed("order:203.0.113.7",
      "secret"))
    assert(!StoreDurable.hashed("order:203.0.113.7", "").contains("203"))
    // an old raw key (before 2026-11-05) and the window still on: the old one goes, the live one stays
    m.write("site/hit/order:198.51.100.1/2988804", "3")
    val e = run(m)(Store.expire[S](t0 + 1000))
    assertEquals((e.ran, e.hits), (true, 1))
    assertEquals(m.keys("site/hit/"), keys)
    // the window ended: gone at the next run
    assertEquals(run(m)(Store.expire[S](t0 + 600000L, force = true)).hits, 1)
    assertEquals(m.keys("site/hit/"), Nil)
  }

  test("the limit still counts within its window") {
    val m = Memory()
    val allowed = (1 to 6).map(_ => run(m, "k")(Store.allow[S]("book:203.0.113.7", 5, 600000L, t0)))
    assertEquals(allowed.count(identity), 5)
    assert(run(m, "k")(Store.allow[S]("book:203.0.113.7", 5, 600000L, t0 + 600000L)))
  }

  test("five years after the last activity a client's records go; accounting records stay") {
    val m = Memory()
    run(m)(Store.place[S](order("TST-OLD", t0)).andThen(Store.place[S](order("TST-NEW", t0 + 400 * day))))
    m.write("site/pay/order/TST-OLD", """{"amount":4000}""")
    m.write("site/buyer/order/TST-OLD", """{"name":"Test Buyer"}""")
    m.write("site/invoice/000001", """{"id":"000001"}""")
    m.write("site/session/abc", s"""{"email":"staff@example.com","until":${t0 + 30 * day}}""")
    // a day short of five years: kept
    val early = run(m)(Store.expire[S](t0 + Expired.keep - day))
    assertEquals((early.orders, early.signins), (0, 1))
    assert(m.keys("site/order/").contains("site/order/TST-OLD"))
    val e = run(m)(Store.expire[S](t0 + Expired.keep + day, force = true))
    assertEquals((e.orders, e.records), (1, 1))
    assertEquals(m.keys("site/order/"), List("site/order/TST-NEW"))
    assertEquals(m.keys("site/event/TST-OLD/"), Nil)
    assert(m.keys("site/event/TST-NEW/").nonEmpty)
    assertEquals(m.keys("site/pay/") ++ m.keys("site/buyer/") ++ m.keys("site/invoice/"),
      List("site/pay/order/TST-OLD", "site/buyer/order/TST-OLD", "site/invoice/000001"))
  }

  test("an order's last event counts, not its placing") {
    val m = Memory()
    run(m)(Store.place[S](order("TST-1", t0)).andThen(Store.record[S]("TST-1",
      Event.Moved(Status.Paid, "", 0, ""), t0 + 100 * day)).as(()))
    assertEquals(run(m)(Store.expire[S](t0 + Expired.keep + day)).orders, 0)
    assertEquals(run(m)(Store.expire[S](t0 + Expired.keep + 101 * day, force = true)).orders, 1)
  }

  test("once in 20 hours unless forced") {
    val m = Memory()
    assert(run(m)(Store.expire[S](t0)).ran)
    assert(!run(m)(Store.expire[S](t0 + 19 * 3600000L)).ran)
    assert(run(m)(Store.expire[S](t0 + 19 * 3600000L, force = true)).ran)
    assert(run(m)(Store.expire[S](t0 + 40 * 3600000L)).ran)
  }
