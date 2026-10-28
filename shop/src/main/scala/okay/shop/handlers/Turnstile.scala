package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.http.Query
import okay.scala3.*
import okay.serverless.cloud.{Fetch, Request, Response, text}
import okay.shop.effects.Captcha

/** `Captcha` AS TURNSTILE'S SITEVERIFY: a token and the visitor's IP posted with the secret over the cloud's `Fetch`;
 * a person when Cloudflare says `success`. No token (or no secret) is no person, and nothing is asked */
object Turnstile:
  val base = "https://challenges.cloudflare.com"

  def run[R[_], Rest[_], A](secret: String)(p: A ! R)(using Split[Captcha, R, Rest], In[Fetch, Rest]): A ! Rest =
    handle(p, Handler.general[Captcha, Rest, A, A](pure)([X] => (op: Captcha[X], k: X => A ! Rest) => op match
      case Captcha.Verify(token, ip) =>
        if token.isEmpty || secret.isEmpty then k(false)
        else Fetch[Rest](base, siteverify(secret, token, ip)).flatMap(r => k(verified(r)))))

  def siteverify(secret: String, token: String, ip: String): Request =
    val form = Map("secret" -> List(secret), "response" -> List(token)) ++
      (if ip.nonEmpty then Map("remoteip" -> List(ip)) else Map.empty)
    Request.post("/turnstile/v0/siteverify", Query.print(form), "application/x-www-form-urlencoded")

  def verified(r: Response): Boolean =
    r.status == 200 && Descent.parse(r.text).toOption.exists {
      case o: Json.Obj => o.get("success").contains(Json.Bool(true))
      case _ => false
    }
