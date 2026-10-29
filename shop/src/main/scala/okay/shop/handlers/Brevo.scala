package okay.shop.handlers

import okay.effects.json.Json
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.Note

/** A CLIENT'S LETTER AS BREVO'S TRANSACTIONAL API ASKS IT (`POST /v3/smtp/email` at `base`, the key in `api-key`) —
 * a way to reach a client, chosen by `MAIL_CLIENT=brevo`; the sending domain's Brevo records are the site's */
object Brevo:
  val base = "https://api.brevo.com"

  def request(key: String, from: String, fromName: String, to: String, n: Note): Request =
    val body = Json.Obj(Vector("sender" -> Json.obj("email" -> Json.str(from), "name" -> Json.str(fromName)),
      "to" -> Json.arr(Json.obj("email" -> Json.str(to))), "subject" -> Json.str(n.subject),
      "htmlContent" -> Json.str(n.html), "textContent" -> Json.str(n.text)) ++
      n.replyTo.map(r => "replyTo" -> Json.obj("email" -> Json.str(r))))
    Request.post("/v3/smtp/email", Json.print(body)).copy(headers = Map("content-type" -> List("application/json"),
      "accept" -> List("application/json"), "api-key" -> List(key)))

  /** Brevo took it: 201 with a message id */
  def sent(r: Response): Boolean = r.status == 201 || r.status == 200
