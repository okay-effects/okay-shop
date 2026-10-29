package okay.shop.workshop

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.http.Query
import okay.serverless.cloud.{Fetch, Request, Response, text}
import okay.shop.handlers.Codec
import okay.shop.model.*

/** `Staff` AS THE SITE'S WORKSHOP API (`sites.app.Workshop`): each operation a request to `/workshop/api/…` of
 * `base` (the page's own origin) over the cloud's `Fetch`, its JSON read back with the journal's own `Codec`. The
 * cookie is the browser's to send */
object StaffApi:
  val base = "/workshop/api/"

  def run[R[_], Rest[_], A](origin: String)(p: A ! R)(using Split[Staff, R, Rest], In[Fetch, Rest]): A ! Rest =
    handle(p, Handler.general[Staff, Rest, A, A](pure)([X] => (op: Staff[X], k: X => A ! Rest) =>
      answer[Rest, X](origin, op).flatMap(k)))

  private def answer[R[_]: Has[Fetch], X](origin: String, op: Staff[X]): X ! R =
    def get(path: String): Response ! R = Fetch[R](origin, Request.get(base + path))
    def post(path: String, fields: (String, String)*): Response ! R =
      Fetch[R](origin, Request.post(base + path, Json.print(Json.Obj(fields.toVector.map((k, v) => k -> Json.str(v))))))
    op match
      case Staff.Me() => get("me").map(r => if r.status == 200 then obj(r).map(str(_, "email")) else None)
      case Staff.Link(email, token) => post("link", "email" -> email, "turnstile" -> token).map(r =>
        if r.status == 200 then Right(()) else Left(error(r)))
      case Staff.Orders() => get("orders").map(r => read(r)(o => arr(o, "orders").collect { case j: Json.Obj => job(j)
        }))
      case Staff.Open(ref) => get("order?ref=" + Query.encode(ref)).map(r => read(r)(opened))
      case Staff.Moved(ref, m) => post("move", "ref" -> ref, "status" -> m.to.key, "amount" -> m.amount,
        "tracking" -> m.tracking, "note" -> m.note).map(r => read(r)(opened))
      case Staff.Letter(ref, subject, body) => post("email", "ref" -> ref, "subject" -> subject, "body" -> body)
        .map(r => read(r)(o => o.get("sent").contains(Json.Bool(true))))
      case Staff.Inquiries() => get("inquiries").map(r => read(r)(o =>
        arr(o, "inquiries").collect { case j: Json.Obj => Codec.inquiry(j) }))
      case Staff.Out() => post("out").map(_ => ())
      case Staff.Bookings() => get("bookings").map(r => read(r)(o => arr(o, "bookings").collect { case j: Json.Obj =>
        Codec.booking(j) -> str(j, "serviceName") }))
      case Staff.Unbook(ref) => post("booking/cancel", "ref" -> ref)
        .map(r => read(r)(o => o.get("sent").contains(Json.Bool(true))))
      case Staff.More() => get("more").map(r => read(r)(o => arr(o, "more").collect { case j: Json.Obj =>
        str(j, "text") -> str(j, "href") }.filter((t, h) => t.nonEmpty && h.nonEmpty)).getOrElse(Vector.empty))

  private def obj(r: Response): Option[Json.Obj] =
    Descent.parse(r.text).toOption.collect { case o: Json.Obj => o }
  private def str(o: Json.Obj, k: String): String = o.get(k).collect { case Json.Str(s) => s }.getOrElse("")
  private def arr(o: Json.Obj, k: String): Vector[Json] = o.get(k).collect { case Json.Arr(xs) => xs }
    .getOrElse(Vector.empty)
  private def error(r: Response): String =
    obj(r).map(str(_, "error")).filter(_.nonEmpty).getOrElse(if r.status == 0 then "no connection" else
      s"status ${r.status}")
  private def read[A](r: Response)(f: Json.Obj => A): Either[String, A] =
    if r.status != 200 then Left(error(r)) else obj(r).map(f).toRight("not an answer")

  private def job(j: Json.Obj): Job =
    Job(Codec.order(j), str(j, "service"), str(j, "price"), arr(j, "next").collect { case Json.Str(s) => s }
      .flatMap(Status.parse))

  private def opened(o: Json.Obj): Opened =
    val j = o.get("order").collect { case x: Json.Obj => job(x) }.get
    val history = arr(o, "history").collect { case h: Json.Obj =>
      val at = h.get("at").collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
      h.get("event").collect { case e: Json.Obj => Codec.eventText(Json.print(e)) }.flatten.map(at -> _)
    }.flatten
    Opened(j, history, str(o, "emailed"))
