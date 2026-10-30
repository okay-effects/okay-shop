package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.serverless.cloud.{Request, Response, text}
import okay.shop.effects.Note
import okay.shop.model.Sha256

/** A CLIENT'S LETTER AS RESEND'S API ASKS IT (`POST /emails` at `base`, `Authorization: Bearer <key>`) — a way to
 * reach a client, chosen by `MAIL_CLIENT=resend` with the secret `RESEND_API_KEY`; the sending domain is verified at
 * Resend by its DNS records (the site's). An `Idempotency-Key` per letter (the SHA-256 of its address, subject and
 * text): the same letter given again within Resend's 24 hours is sent once */
object Resend:
  val base = "https://api.resend.com"

  def request(key: String, from: String, fromName: String, to: String, n: Note): Request =
    val body = Json.Obj(Vector("from" -> Json.str(s"${display(fromName)} <$from>"), "to" -> Json.arr(Json.str(to)),
      "subject" -> Json.str(n.subject), "html" -> Json.str(n.html), "text" -> Json.str(n.text)) ++
      n.replyTo.map(r => "reply_to" -> Json.str(r)))
    Request.post("/emails", Json.print(body)).copy(headers = Map("content-type" -> List("application/json"),
      "authorization" -> List(s"Bearer $key"), "idempotency-key" -> List(idempotency(to, n))))

  /** the letter's own key: the same letter, the same key; at most 256 characters (Resend's limit) */
  def idempotency(to: String, n: Note): String =
    "okay-" + Sha256.hex(s"${to.trim.toLowerCase}\n${n.subject}\n${n.text}\n${n.html}".getBytes("UTF-8"))

  /** a display name as RFC 5322 wants it in `Name <address>`: quoted when it has a special character */
  def display(name: String): String =
    val n = name.replace("\"", "").replace("\\", "").replaceAll("[\\r\\n]+", " ").trim
    if n.exists(c => "()<>[]:;@,.".contains(c)) then "\"" + n + "\"" else n

  /** Resend took it: 200 with the letter's id */
  def sent(r: Response): Boolean = r.status >= 200 && r.status < 300

  /** why not, for the log: the status and Resend's error name — never an address or the letter's words */
  def why(r: Response): String =
    val name = Descent.parse(r.text).toOption.collect { case o: Json.Obj => o.get("name") }.flatten
      .collect { case Json.Str(s) => s.filter(c => c.isLetterOrDigit || c == '_').take(60) }.getOrElse("")
    val meaning = r.status match
      case 401 | 403 => "the key refused, or the sending domain not verified"
      case 422 => "the letter refused as invalid"
      case 429 => "a limit reached"
      case s if s >= 500 => "Resend failing"
      case _ => "refused"
    s"Resend answered ${r.status}${if name.isEmpty then "" else s" ($name)"}: $meaning"
