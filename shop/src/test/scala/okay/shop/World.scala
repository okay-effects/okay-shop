package okay.shop

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.Clock
import okay.http.Query
import okay.serverless.{Memory, Storage}
import okay.serverless.cloud.{Request, Response, headerOf, text}
import okay.shop.app.{Api, Bookings}
import okay.shop.effects.*
import okay.shop.handlers.StoreDurable
import okay.shop.html.Conf

/** A SITE IN A TEST: the program the Durable Object runs, its durable state in memory (okay-serverless' `Memory`,
 * which keeps `Storage`'s contract as the object's SQLite does), letters kept, Turnstile answered `human`, names from
 * a seed, a clock that moves only when told */
final class World(val id: String = "atelier", var human: Boolean = true,
                  more: String => Vector[(String, String)] = _ => Vector.empty,
                  desk: okay.shop.html.Desk = okay.shop.html.Desk()):
  val storage = Memory()
  var now: Long = 1793282400000L // 2026-10-29 14:00 UTC
  var letters: Vector[(String, Note)] = Vector.empty
  private var seed = 1L
  val site = Fixtures.site(id)
  val conf = Conf(site, s"https://preview-$id.example.workers.dev", "+48 600 000 000", "TST01A",
    "1x00000000000000000000AA", Set("staff@example.com"), "atelier@example.com", "2026-10-28",
    "CALENDAR-KEY-0123456789", more, desk)

  type W[X] = (Pure + Storage + Store + Ids + Captcha + Mail + Clock)[X]

  def ask(r: Request): Response =
    seed += 1
    val (kept, (_, res)) = run(storage.handler(StoreDurable.run(Ids.seeded(seed)(Captcha.always(human)(Mail.kept()(
      Clock.virtual(now)(Api.answer[W](conf, r))))))))
    letters ++= kept
    res

  /** the object's alarm, as the cloud fires it: cleared, then the reminders due */
  def alarm(): Int =
    storage.alarm = None
    seed += 1
    val (kept, (_, n)) = run(storage.handler(StoreDurable.run(Ids.seeded(seed)(Captcha.always(human)(Mail.kept()(
      Clock.virtual(now)(Bookings.remind[W](conf))))))))
    letters ++= kept
    n

  private def at(r: Request, headers: (String, String)*): Request =
    r.copy(headers = r.headers ++ (("host" -> conf.origin.stripPrefix("https://")) +: headers).map((k, v) =>
      k -> List(v)))
  def post(path: String, fields: (String, String)*): Response =
    ask(at(Request.post(path, Json.print(Json.Obj(fields.toVector.map((k, v) => k -> Json.str(v))))),
      "cf-connecting-ip" -> "1.2.3.4"))
  def form(path: String, fields: (String, String)*): Response =
    ask(at(Request.post(path, Query.print(fields.toMap.map((k, v) => k -> List(v))),
      "application/x-www-form-urlencoded"), "cf-connecting-ip" -> "1.2.3.4"))
  def get(path: String): Response = ask(at(Request.get(path)))

  def json(r: Response): Map[String, String] = Descent.parse(r.text).toOption.collect { case o: Json.Obj =>
    o.fields.collect { case (k, Json.Str(v)) => k -> v; case (k, Json.Bool(b)) => k -> b.toString }.toMap
  }.getOrElse(Map.empty)

  /** an order of `offer`, as the page posts it */
  def order(offer: String = "hem", contact: String = "anna@example.com", name: String = "Anna",
            extra: Seq[(String, String)] = Nil): Response =
    post("/api/order", (Seq("offerKey" -> offer, "name" -> name, "contact" -> contact, "need" -> "do piątku",
      "handover" -> "self", "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "token") ++ extra)*)

/** A BROWSER on a site: its cookie kept between requests, its origin said — the workshop's, in tests */
final class Browser(w: World):
  var cookie = ""
  def send(method: String, path: String, body: String = "", kind: String = "application/json",
           origin: Option[String] = None): Response =
    val headers = Map("content-type" -> kind, "cf-connecting-ip" -> "5.6.7.8",
      "host" -> w.conf.origin.stripPrefix("https://")) ++ origin.map("origin" -> _) ++
      (if cookie.isEmpty then Map.empty else Map("cookie" -> cookie))
    val r = w.ask(Request(method, path).copy(headers = headers.map((k, v) => k -> List(v)), body = body))
    r.headerOf("set-cookie").foreach(c => cookie = c.takeWhile(_ != ';'))
    r
  def post(path: String, fields: (String, String)*): Response =
    send("POST", path, Json.print(Json.Obj(fields.toVector.map((k, v) => k -> Json.str(v)))))
  def get(path: String): Response = send("GET", path)
  /** a page's request, as `fetch` sends it: its path on this site, the cookie and the origin the browser adds */
  def fetch(r: Request): Response =
    send(r.method, r.path + (if r.query.isEmpty then "" else "?" + Query.print(r.query)), r.text,
      r.header("content-type").getOrElse("text/plain"), if r.method == "POST" then Some(w.conf.origin) else None)
