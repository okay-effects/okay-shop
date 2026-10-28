package okay.shop.app

import okay.effects.json.Json
import okay.http.Query
import okay.serverless.cloud.{Request, Response, text}

/** WHAT THE SITE'S PROGRAMS SAY OF HTTP beyond okay-http's model: a request's host, cookies, form and JSON fields;
 * the responses they answer — JSON, a page kept out of caches and search, a refusal */
object Web:
  /** the host the request came to, without its port */
  def host(r: Request): String = r.header("host").getOrElse("").takeWhile(_ != ':')

  /** where the request came: `https://<host>`, or `http://` on the local machine (wrangler dev) */
  def origin(r: Request): String =
    val h = r.header("host").getOrElse("localhost")
    val local = h.startsWith("localhost") || h.startsWith("127.0.0.1")
    s"${if local then "http" else "https"}://$h"

  def cookie(r: Request, name: String): Option[String] =
    r.headers.getOrElse("cookie", Nil).flatMap(_.split(';')).map(_.trim).collectFirst {
      case c if c.startsWith(name + "=") => Query.decode(c.drop(name.length + 1))
    }

  def ip(r: Request): String = r.header("cf-connecting-ip").getOrElse("")

  /** a form's or a JSON object's fields, as the request's content type says: texts as they are, a flag as `on` */
  def fields(r: Request): Map[String, String] =
    if r.header("content-type").exists(_.contains("json")) then
      okay.effects.json.Descent.parse(r.text).toOption.collect { case o: Json.Obj => o.fields.collect {
        case (k, Json.Str(v)) => k -> v
        case (k, Json.Bool(b)) => k -> (if b then "on" else "")
        case (k, Json.Num(n)) => k -> n.toString
      }.toMap }.getOrElse(Map.empty)
    else Query.parse(r.text).map((k, vs) => k -> vs.headOption.getOrElse(""))

  private val private_ = Map("Cache-Control" -> "no-store")
  def json(j: Json, status: Int = 200): Response =
    Response(status, Map("Content-Type" -> "application/json; charset=utf-8") ++ private_, Json.print(j))
  def refused(why: String, status: Int = 400): Response = json(Json.obj("error" -> Json.str(why)), status)
  /** a page for one person — a status, a receipt: never cached, never indexed */
  def page(html: String, status: Int = 200): Response =
    Response(status, Map("Content-Type" -> "text/html; charset=utf-8", "X-Robots-Tag" -> "noindex") ++ private_, html)
