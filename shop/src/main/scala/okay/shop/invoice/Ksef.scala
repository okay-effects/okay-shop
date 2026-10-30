package okay.shop.invoice

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Bytes, Crypto, Fetch, Request, Response, text}

import java.util.Base64

/** an invoice taken by KSeF: the interactive session's reference and the invoice's */
final case class KsefSent(session: String, reference: String)

/** where a sent invoice stands (KSeF's codes: 100 accepted, 150 processing, 200 done — its KSeF number —, 4xx a
 * refusal), its description and details */
final case class KsefStatus(code: Int, description: String, number: String = ""):
  def done: Boolean = code == 200 && number.nonEmpty
  def waiting: Boolean = code == 100 || code == 150
  def refused: Boolean = !done && !waiting

/**
 * KSeF, THE MINISTRY OF FINANCE'S E-INVOICES, AS AN EFFECT (specs/shop.md "KSeF"): an FA(3) invoice sent, where it
 * stands, its UPO. A refusal of the call (no connection, a wrong token, KSeF down) is a `Left` with why, never a
 * failure: the program queues the invoice and the object's alarm tries again. Handlers: `KsefHttp` (KSeF 2.0's API
 * over `Fetch` and `Crypto`), `KsefFake` (memory, for tests), `Ksef.off` (a shop not connected)
 */
enum Ksef[X]:
  case Send(xml: String) extends Ksef[Either[String, KsefSent]]
  case Status(session: String, reference: String) extends Ksef[Either[String, KsefStatus]]
  case Upo(session: String, number: String) extends Ksef[Either[String, String]]

object Ksef:
  def send[R[_]](xml: String)(using In[Ksef, R]): Either[String, KsefSent] ! R = perform[Ksef](Send(xml))
  def status[R[_]](session: String, reference: String)(using In[Ksef, R]): Either[String, KsefStatus] ! R =
    perform[Ksef](Status(session, reference))
  def upo[R[_]](session: String, number: String)(using In[Ksef, R]): Either[String, String] ! R =
    perform[Ksef](Upo(session, number))

  /** a shop not connected to KSeF: every call answered so */
  def off[R[_], Rest[_], A](p: A ! R)(using Split[Ksef, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Ksef, Rest, A, A](pure)([X] => (op: Ksef[X]) => (op match
      case Send(_) => Left("not connected")
      case Status(_, _) => Left("not connected")
      case Upo(_, _) => Left("not connected")).asInstanceOf[X]))

/** KSeF's environments: the API's base, the QR codes' base (specs/shop.md "KSeF") */
enum KsefEnv(val key: String, val api: String, val qr: String):
  case Test extends KsefEnv("test", "https://api-test.ksef.mf.gov.pl", "https://qr-test.ksef.mf.gov.pl")
  case Demo extends KsefEnv("demo", "https://api-demo.ksef.mf.gov.pl", "https://qr-demo.ksef.mf.gov.pl")
  case Production extends KsefEnv("production", "https://api.ksef.mf.gov.pl", "https://qr.ksef.mf.gov.pl")

object KsefEnv:
  def parse(s: String): Option[KsefEnv] = values.find(_.key == s.trim)

/** a shop's connection to KSeF: the environment, the seller's NIP (the context), its KSeF token */
final case class KsefAccount(env: KsefEnv, nip: String, token: String)

/**
 * `Ksef` ON KSeF 2.0's API (api-test.ksef.mf.gov.pl/docs/v2): each call signs in first by the KSeF token — the
 * challenge, the token and the challenge's time in ms encrypted by RSA-OAEP (SHA-256) to the Ministry's
 * `KsefTokenEncryption` key, the sign-in polled until done, its token redeemed for an access token —, then:
 *
 *  - Send: an interactive session opened for FA (3) with a fresh AES-256 key (RSA-OAEP to the
 *    `SymmetricKeyEncryption` key) and IV, the invoice encrypted (AES-256-CBC, PKCS#7) with its and the
 *    ciphertext's SHA-256 and sizes, the session closed (its UPO made);
 *  - Status: `GET /v2/sessions/{session}/invoices/{reference}` — its code, and the KSeF number when done;
 *  - Upo: `GET /v2/sessions/{session}/invoices/ksef/{number}/upo`.
 *
 * Every answer but the expected one is a `Left` with KSeF's own words (its exception code and description). The
 * token is never logged nor put in a `Left`
 */
object KsefHttp:
  private val json = "application/json"
  private def b64(b: Bytes) = Base64.getEncoder.encodeToString(b.toArray)

  def run[R[_], Rest[_], A](account: KsefAccount)(p: A ! R)(using Split[Ksef, R, Rest], In[Fetch, Rest],
    In[Crypto, Rest], In[Clock, Rest]): A ! Rest =
    val k = KsefCalls[Rest](account)
    handle(p, Handler.general[Ksef, Rest, A, A](pure)([X] => (op: Ksef[X], next: X => A ! Rest) => (op match
      case Ksef.Send(xml) => k.send(xml)
      case Ksef.Status(s, r) => k.status(s, r)
      case Ksef.Upo(s, n) => k.upo(s, n)).asInstanceOf[X ! Rest].flatMap(next)))

  /** KSeF's words for a refusal: its exception's code and description, else the status */
  def why(r: Response): String =
    val described = Descent.parse(r.text).toOption.flatMap(j => scala.util.Try {
      val d = j("exception")("exceptionDetailList").list.head
      s"${d("exceptionCode").int} ${d("exceptionDescription").string}"
    }.toOption.orElse(scala.util.Try(j("title").string).toOption))
    s"HTTP ${r.status}" + described.fold("")(" " + _)

  /** the calls, on one sign-in at most per handler */
  final class KsefCalls[R[_]](a: KsefAccount)(using In[Fetch, R], In[Crypto, R], In[Clock, R]):
    private var access: Option[String] = None

    private def call(method: String, path: String, body: Option[Json], bearer: Option[String]): Response ! R =
      val base = Request(method, "/v2" + path).copy(headers = Map("accept" -> List(json)) ++
        bearer.map(t => "authorization" -> List(s"Bearer $t")))
      Fetch[R](a.env.api, body.fold(base)(j => base.copy(body = Json.print(j), headers = base.headers +
        ("content-type" -> List(json)))))

    private def parsed(r: Response): Option[Json] = Descent.parse(r.text).toOption

    /** the Ministry's certificate for a use, its public key (SubjectPublicKeyInfo) */
    private def key(use: String): Either[String, Bytes] ! R = call("GET", "/security/public-key-certificates", None,
      None).map { r =>
      if r.status != 200 then Left(why(r))
      else parsed(r).toRight("certificates").flatMap(j => scala.util.Try(j.list.filter(c =>
        c("usage").list.exists(_.string == use)).maxBy(_("validFrom").string)).toOption.toRight("no " + use))
        .flatMap(c => Crypto.spki(Bytes(Base64.getDecoder.decode(c("certificate").string))).toRight("certificate"))
    }

    /** signed in: an access token */
    private def signIn: Either[String, String] ! R = access match
      case Some(t) => pure(Right(t))
      case None =>
        def poll(ref: String, auth: String, left: Int): Either[String, Unit] ! R =
          call("GET", s"/auth/$ref", None, Some(auth)).flatMap { r =>
            val code = parsed(r).flatMap(j => scala.util.Try(j("status")("code").int).toOption)
            if r.status != 200 then pure(Left(why(r)))
            else if code.contains(200) then pure(Right(()))
            else if code.contains(100) && left > 0 then Clock.sleep[R](1000).flatMap(_ => poll(ref, auth, left - 1))
            else pure(Left(s"sign-in ${code.getOrElse(0)} " + parsed(r).flatMap(j => scala.util.Try(
              j("status")("description").string).toOption).getOrElse("")))
          }
        call("POST", "/auth/challenge", None, None).flatMap { ch =>
          parsed(ch).filter(_ => ch.status == 200).flatMap(j => scala.util.Try((j("challenge").string,
            j("timestampMs").long)).toOption) match
            case None => pure(Left(why(ch)))
            case Some((challenge, ms)) => key("KsefTokenEncryption").flatMap {
                case Left(w) => pure(Left(w))
                case Right(spki) => Crypto.rsaOaep[R](spki, Bytes.of(s"${a.token}|$ms")).flatMap(boxedToken =>
                    call("POST", "/auth/ksef-token", Some(Json.obj("challenge" -> Json.str(challenge),
                      "contextIdentifier" -> Json.obj("type" -> Json.str("Nip"), "value" -> Json.str(a.nip)),
                      "encryptedToken" -> Json.str(b64(boxedToken)))), None)).flatMap { r =>
                    parsed(r).filter(_ => r.status == 202).flatMap(j => scala.util.Try((j("referenceNumber").string,
                      j("authenticationToken")("token").string)).toOption) match
                      case None => pure(Left(why(r)))
                      case Some((ref, auth)) => poll(ref, auth, 20).flatMap {
                          case Left(w) => pure(Left(w))
                          case Right(()) => call("POST", "/auth/token/redeem", None, Some(auth)).map { t =>
                              parsed(t).filter(_ => t.status == 200).flatMap(j => scala.util.Try(
                                j("accessToken")("token").string).toOption).toRight(why(t))
                            }.map { got =>
                              got.foreach(t => access = Some(t))
                              got
                            }
                        }
                  }
              }
        }

    def send(xml: String): Either[String, KsefSent] ! R = signIn.flatMap {
      case Left(w) => pure(Left(w))
      case Right(token) => key("SymmetricKeyEncryption").flatMap {
          case Left(w) => pure(Left(w))
          case Right(spki) =>
            val plain = Bytes.of(xml)
            for
              aes <- Crypto.random[R](32)
              iv <- Crypto.random[R](16)
              boxedKey <- Crypto.rsaOaep[R](spki, aes)
              opened <- call("POST", "/sessions/online", Some(Json.obj(
                "formCode" -> Json.obj("systemCode" -> Json.str("FA (3)"), "schemaVersion" -> Json.str("1-0E"),
                  "value" -> Json.str("FA")),
                "encryption" -> Json.obj("encryptedSymmetricKey" -> Json.str(b64(boxedKey)),
                  "initializationVector" -> Json.str(b64(iv))))), Some(token))
              res <- parsed(opened).filter(_ => opened.status == 201).flatMap(j => scala.util.Try(
                j("referenceNumber").string).toOption) match
                case None => pure(Left(why(opened)))
                case Some(session) =>
                  for
                    cipher <- Crypto.aesCbc[R](aes, iv, plain)
                    plainHash <- Crypto.sha256[R](plain)
                    cipherHash <- Crypto.sha256[R](cipher)
                    sent <- call("POST", s"/sessions/online/$session/invoices", Some(Json.obj(
                      "invoiceHash" -> Json.str(b64(plainHash)), "invoiceSize" -> Json.num(plain.size),
                      "encryptedInvoiceHash" -> Json.str(b64(cipherHash)),
                      "encryptedInvoiceSize" -> Json.num(cipher.size),
                      "encryptedInvoiceContent" -> Json.str(b64(cipher)), "offlineMode" -> Json.bool(false))),
                      Some(token))
                    // closed either way: its UPO is made, or nothing is left open
                    _ <- call("POST", s"/sessions/online/$session/close", None, Some(token))
                  yield parsed(sent).filter(_ => sent.status == 202).flatMap(j => scala.util.Try(
                    j("referenceNumber").string).toOption).map(KsefSent(session, _)).toRight(why(sent))
            yield res
        }
    }

    def status(session: String, reference: String): Either[String, KsefStatus] ! R = signIn.flatMap {
      case Left(w) => pure(Left(w))
      case Right(token) => call("GET", s"/sessions/$session/invoices/$reference", None, Some(token)).map { r =>
          parsed(r).filter(_ => r.status == 200).flatMap(j => scala.util.Try {
            val st = j("status")
            val details = scala.util.Try(st("details").list.map(_.string).mkString("; ")).getOrElse("")
            KsefStatus(st("code").int, (st("description").string + (if details.nonEmpty then s" — $details" else ""))
              .take(500), scala.util.Try(j("ksefNumber").string).getOrElse(""))
          }.toOption).toRight(why(r))
        }
    }

    def upo(session: String, number: String): Either[String, String] ! R = signIn.flatMap {
      case Left(w) => pure(Left(w))
      case Right(token) => Fetch[R](a.env.api, Request("GET", s"/v2/sessions/$session/invoices/ksef/$number/upo")
          .copy(headers = Map("accept" -> List("application/xml"), "authorization" -> List(s"Bearer $token"))))
          .map(r => if r.status == 200 && r.text.contains("<") then Right(r.text) else Left(why(r)))
    }

/** `Ksef` IN MEMORY, for tests: what was sent kept; each invoice done at once with a KSeF number in KSeF's form, or
 * refused while `down` (the network) or when the XML lacks the seller's NIP */
final class KsefFake(nip: String):
  var down: Boolean = false
  var sent: Vector[String] = Vector.empty
  /** answered `150` this many times before done */
  var slow: Int = 0

  def run[R[_], Rest[_], A](p: A ! R)(using Split[Ksef, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Ksef, Rest, A, A](pure)([X] => (op: Ksef[X]) => (op match
      case Ksef.Send(xml) =>
        if down then Left("HTTP 503")
        else if !xml.contains(s"<NIP>$nip</NIP>") then Left("HTTP 400 21405 nip")
        else
          sent = sent :+ xml
          Right(KsefSent("20261030-SO-FAKE", f"20261030-EE-${sent.size}%010d"))
      case Ksef.Status(_, ref) =>
        if down then Left("HTTP 503")
        else if slow > 0 then
          slow -= 1
          Right(KsefStatus(150, "Trwa przetwarzanie"))
        else Right(KsefStatus(200, "Sukces", KsefFake.number(nip, "20261030", ref.takeRight(10))))
      case Ksef.Upo(_, number) =>
        if down then Left("HTTP 503") else Right(s"<Potwierdzenie><NumerKSeFDokumentu>$number</NumerKSeFDokumentu>" +
          "</Potwierdzenie>")).asInstanceOf[X]))

object KsefFake:
  /** a KSeF number: `NIP-YYYYMMDD-XXXXXXXXXXXX-CC`, CC the CRC-8 (polynomial 0x07) of the rest, in hex */
  def number(nip: String, day: String, id: String): String =
    val head = s"$nip-$day-${("0" * 12 + id).takeRight(12).toUpperCase}"
    f"$head-${crc8(head)}%02X"
  def crc8(s: String): Int = s.getBytes("UTF-8").foldLeft(0) { (c0, b) =>
    (0 until 8).foldLeft((c0 ^ (b & 0xff)) & 0xff)((c, _) => if (c & 0x80) != 0 then ((c << 1) ^ 0x07) & 0xff
      else (c << 1) & 0xff)
  }
