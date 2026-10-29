package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.*
import okay.shop.handlers.Codec
import okay.shop.html.{Conf, Html, Letters}
import okay.shop.model.*

/**
 * THE WORKSHOP'S API (specs/sites.md "The workshop"), in the site's object: sign-in by a one-use link mailed to the
 * staff list, a session by cookie, and the orders — listed, opened, moved as `Workflow` allows, their clients mailed.
 * Secrets are kept only as their SHA-256: a copy of the database signs nobody in
 */
object Workshop:
  val linkLife: Long = 15 * 60 * 1000L
  val sessionLife: Long = 30L * 24 * 60 * 60 * 1000
  val cookie = "__Host-ws"

  def answer[R[_]](c: Conf, r: Request, path: List[String])(using In[Store, R], In[Mail, R], In[Captcha, R],
    In[Ids, R], In[Clock, R]): Response ! R =
    (r.method, path) match
      case ("POST", List("link")) => link(c, r)
      case ("GET", List("enter")) => pure(enterPage(r.param("t").getOrElse("")))
      case ("POST", List("enter")) => enter(c, r)
      case (method, rest) =>
        if method == "POST" && !sameSite(r) then pure(refused("not from this site", 403))
        else Clock.now[R].flatMap(now => staff(c, r, now).flatMap {
          case None => pure(refused("sign in", 401))
          case Some(email) => signedIn(c, r, rest, email, now)
        })

  // ---- SIGN-IN

  def hash(secret: String): String = Sha256.hex(secret.getBytes("UTF-8"))

  private def link[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R],
    In[Clock, R]): Response ! R =
    val f = json(r)
    val email = f.getOrElse("email", "").trim.toLowerCase
    for
      now <- Clock.now[R]
      allowed <- Store.allow[R](s"link:${Api.ip(r)}", 5, Api.tenMinutes, now)
      human <- if allowed then Captcha.verify[R](f.getOrElse("turnstile", ""), Api.ip(r)) else pure(false)
      _ <- if allowed && human && c.staff(email) then mailLink(c, email, now) else unit[R]
    // the same answer for an email on the list and one not: the list is not told
    yield if !allowed then refused("limit", 429) else if !human then refused("captcha", 403)
      else ok(Json.obj("ok" -> Json.bool(true)))

  private def mailLink[R[_]](c: Conf, email: String, now: Long)(using In[Store, R], In[Mail, R], In[Ids, R])
    : Unit ! R =
    Ids.secret[R].flatMap { secret =>
      perform[Store](Store.KeepLink(hash(secret), Signin(email, now + linkLife))).flatMap(_ =>
        Mail.toStaff[R](email, Letters.signin(c, s"${c.origin}/workshop/api/enter?t=$secret")))
    }.map(_ => ())

  /** the link opened: one button that posts it, so a scanner opening links does not spend it */
  private def enterPage(secret: String): Response =
    Response.html(s"""<!doctype html><html lang="uk"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex">
<title>Майстерня</title><style>body{font:17px/1.5 system-ui,sans-serif;display:flex;align-items:center;
justify-content:center;min-height:100vh;margin:0;background:#faf5f0}button{font-size:17px;padding:14px 28px;
border:0;border-radius:12px;background:#2a1f14;color:#fff;cursor:pointer}</style></head><body>
<form method="post" action="/workshop/api/enter"><input type="hidden" name="t" value="${Html.escape(secret)}">
<button type="submit">Увійти до майстерні →</button></form></body></html>
""").header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")

  private def enter[R[_]](c: Conf, r: Request)(using In[Store, R], In[Ids, R], In[Clock, R]): Response ! R =
    val secret = Web.fields(r).getOrElse("t", "")
    for
      now <- Clock.now[R]
      used <- if secret.isEmpty then pure(None) else perform[Store](Store.UseLink(hash(secret), now))
      res <- used.filter(si => c.staff(si.email)) match
        case None => pure(Response.redirect("/workshop/#expired", 303))
        case Some(si) => Ids.secret[R].flatMap { session =>
          perform[Store](Store.KeepSession(hash(session), Signin(si.email, now + sessionLife))).map(_ =>
            Response.redirect("/workshop/", 303).header("Set-Cookie",
              s"$cookie=$session; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${sessionLife / 1000}"))
        }
    yield res

  /** the person signed in: a session kept and not past, its email on the list still */
  private def staff[R[_]](c: Conf, r: Request, now: Long)(using In[Store, R]): Option[String] ! R =
    Web.cookie(r, cookie).filter(_.nonEmpty) match
      case None => pure(None)
      case Some(s) => perform[Store](Store.Session(hash(s), now)).map(_.map(_.email).filter(c.staff))

  /** a POST of this site: JSON (a form elsewhere cannot send it without asking), and its own origin when said */
  private def sameSite(r: Request): Boolean =
    r.header("content-type").exists(_.startsWith("application/json")) &&
      r.header("origin").forall(_ == Web.origin(r))

  // ---- SIGNED IN

  private def signedIn[R[_]](c: Conf, r: Request, path: List[String], email: String, now: Long)(using In[Store, R],
    In[Mail, R]): Response ! R =
    (r.method, path) match
      case ("GET", List("me")) => pure(ok(Json.obj("email" -> Json.str(email))))
      case ("POST", List("out")) => perform[Store](Store.EndSession(hash(Web.cookie(r, cookie).getOrElse(""))))
          .map(_ => ok(Json.obj()).header("Set-Cookie", s"$cookie=; Path=/; HttpOnly; Secure; SameSite=Lax; " +
            "Max-Age=0"))
      case ("GET", List("orders")) => Store.orders[R](200).map(os =>
          ok(Json.obj("orders" -> Json.Arr(os.map(o => described(c, o))))))
      case ("GET", List("order")) => opened(c, r.param("ref").getOrElse(""), "").map(_.getOrElse(missing))
      case ("POST", List("move")) => move(c, json(r), now)
      case ("POST", List("email")) => letter(c, json(r), now)
      case ("GET", List("inquiries")) => Store.inquiries[R](200).map(is =>
          ok(Json.obj("inquiries" -> Json.Arr(is.map(Codec.inquiry)))))
      case ("GET", List("bookings")) => Store.bookings[R](now - day, now + 60 * day).map(bs =>
          ok(Json.obj("bookings" -> Json.Arr(bs.map(b => Json.Obj(Codec.booking(b).fields :+
            ("serviceName" -> Json.str(c.site.offer(b.service).map(_.name(Lang.pl)).getOrElse(b.service)))))))))
      case ("POST", List("booking", "cancel")) => Store.cancelBooking[R](json(r).getOrElse("ref", ""), now).flatMap {
          case None => pure(refused("no such booking", 404))
          case Some(b) => b.email.fold(pure(false))(to => Mail.toClient[R](to, Letters.unbooked(c, b)))
              .map(sent => ok(Json.obj("sent" -> Json.bool(sent))))
        }
      case _ => pure(Response.notFound())

  private val day = 86400000L

  /** an order as the workshop shows it: its fields, its service's name and price, where it may go next */
  def described(c: Conf, o: Order): Json.Obj =
    val offer = c.site.offer(o.offerKey)
    Json.Obj(Codec.order(o).fields ++ Vector(
      "service" -> Json.str(offer.map(_.name(Lang.pl)).getOrElse("")),
      "price" -> Json.str(offer.filter(_.priced).map(_.priceShort).getOrElse("")),
      "next" -> Json.Arr(Workflow.next(o).map(s => Json.str(s.key)))))

  private def opened[R[_]](c: Conf, ref: String, emailed: String)(using In[Store, R]): Option[Response] ! R =
    Store.find[R](ref).flatMap {
      case None => pure(None)
      case Some(o) => Store.history[R](ref).map(h => Some(ok(Json.obj("order" -> described(c, o),
        "history" -> Json.Arr(h.map((at, e) => Json.obj("at" -> Json.num(at), "event" -> Codec.event(e)))),
        "emailed" -> Json.str(emailed)))))
    }

  private def move[R[_]](c: Conf, f: Map[String, String], now: Long)(using In[Store, R], In[Mail, R])
    : Response ! R =
    val ref = f.getOrElse("ref", "")
    Store.find[R](ref).flatMap {
      case None => pure(missing)
      case Some(o) => Status.parse(f.getOrElse("status", "")) match
        case None => pure(refused("status"))
        case Some(to) if !Workflow.next(o).contains(to) => pure(refused(s"not from ${o.status.key}", 409))
        case Some(to) =>
          val amount = f.get("amount").flatMap(Money.parse).getOrElse(0L)
          val note = f.getOrElse("note", "").trim.take(1000)
          if to == Status.Paid && amount <= 0 then pure(refused("amount"))
          else if to == Status.Cancelled && note.isEmpty then pure(refused("note"))
          else
            Store.record[R](ref, Event.Moved(to, note, amount, f.getOrElse("tracking", "").trim.take(60)), now)
              .flatMap {
                case None => pure(None)
                case Some(moved) => (moved.email, Letters.moved(c, moved)) match
                  case (Some(to), Some(n)) => Mail.toClient[R](to, n).flatMap(sent =>
                      Store.record[R](ref, Event.Emailed(to, n.subject, sent), now)
                        .flatMap(_ => opened(c, ref, if sent then "sent" else "not sent")))
                  case _ => opened(c, ref, "")
              }.map(_.getOrElse(missing))
    }

  private def letter[R[_]](c: Conf, f: Map[String, String], now: Long)(using In[Store, R], In[Mail, R])
    : Response ! R =
    val subject = f.getOrElse("subject", "").trim.take(200)
    val body = f.getOrElse("body", "").trim.take(10000)
    Store.find[R](f.getOrElse("ref", "")).flatMap {
      case None => pure(missing)
      case Some(o) => o.email match
        case None => pure(refused("no email"))
        case Some(_) if subject.isEmpty || body.isEmpty => pure(refused("subject and body"))
        case Some(to) =>
          Mail.toClient[R](to, Letters.free(c, o, subject, body)).flatMap(sent =>
            Store.record[R](o.ref, Event.Emailed(to, subject, sent), now).map(_ =>
              ok(Json.obj("sent" -> Json.bool(sent)))))
    }

  private def json(r: Request): Map[String, String] = Web.fields(r)

  private def ok(j: Json): Response = Web.json(j)
  private def refused(why: String, status: Int = 400): Response = Web.refused(why, status)
  private val missing = refused("no such order", 404)
