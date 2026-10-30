package okay.shop

import okay.scala3.*
import okay.scala3.std.{Clock, Level, Log}
import okay.serverless.cloud.{Conn, Fetch, Mail as Letter, Mails, Request, Response, SmtpServer, Tcp, Tls, text}
import okay.shop.effects.{Mail, Note}
import okay.shop.handlers.{MailCloud, Mailing}

/** `Mail` on the cloud's effects: which binding each letter takes, Brevo's request, and a letter that does not go
 * answered `false` with the reason logged — never a failure */
class MailCloudSuite extends munit.FunSuite:
  type C[X] = (Pure + Log + Fetch + Tcp + Clock + Mails + Mail)[X]

  /** a relay of the test's, as `Tcp` answers: what a relay says to each line, the letters it took */
  final class Relay:
    var letters = Vector.empty[(String, String, String)]
    var said = Vector.empty[String]
    private var out = "220 relay ESMTP\r\n"
    private var inData = false
    private var data = ""
    private var from = ""
    private var to = ""
    private var auth = 0
    def receive(text: String): Unit = text.split("\r\n", -1).dropRight(1).foreach { line =>
      if inData then
        if line == "." then { letters :+= (from, to, data); inData = false; out += "250 queued\r\n" }
        else data += line + "\r\n"
      else if auth == 1 then { auth = 2; out += "334 UGFzc3dvcmQ6\r\n" }
      else if auth == 2 then { auth = 0; out += "235 ok\r\n" }
      else
        said :+= line
        line.takeWhile(_ != ' ') match
          case "EHLO" => out += "250-relay\r\n250-STARTTLS\r\n250 AUTH LOGIN\r\n"
          case "STARTTLS" => out += "220 go\r\n"
          case "AUTH" => auth = 1; out += "334 VXNlcm5hbWU6\r\n"
          case "MAIL" => from = line.drop(11).stripSuffix(">"); out += "250 ok\r\n"
          case "RCPT" => to = line.drop(9).stripSuffix(">"); out += "250 ok\r\n"
          case "DATA" => inData = true; data = ""; out += "354 go\r\n"
          case _ => out += "221 bye\r\n"
    }
    def answer(): Option[String] = { val o = out; out = ""; Option.when(o.nonEmpty)(o) }
  private val note = Note("Potwierdzenie TST-1", "<p>Dziękujemy</p>", "Dziękujemy", Some("atelier@example.com"))

  /** the letters each binding took (`CLIENT_MAIL` refusing: an unverified destination), Brevo's requests, the log */
  private def sending(m: Mailing, relay: Relay = Relay(), reply: Response = Response(201, Map.empty, "{}"))(
    p: Boolean ! C)
    : (Boolean, Vector[(String, Letter)], Vector[Request], List[String]) =
    var letters = Vector.empty[(String, Letter)]
    var asked = Vector.empty[Request]
    val mails = handle(MailCloud.run(m)(p), Handler.tail[Mails, Pure + Log + Fetch + Tcp + Clock, Boolean, Boolean](
      pure)(
      [X] => (op: Mails[X]) => op match
        case Mails.Deliver(b, l) => letters :+= b -> l; if b == "CLIENT_MAIL" then Left("not verified") else Right(())
        case Mails.Send(b, l) => letters :+= b -> l; ()))
    val timed = handle(mails, Handler.tail[Clock, Pure + Log + Fetch + Tcp, Boolean, Boolean](pure)(
      [X] => (op: Clock[X]) => op match
        case Clock.Now() => 1793282400000L
        case Clock.Sleep(_) => ()))
    val connected = handle(timed, Handler.tail[Tcp, Pure + Log + Fetch, Boolean, Boolean](pure)(
      [X] => (op: Tcp[X]) => op match
        case Tcp.Connect(host, _, _) => if host == "relay.test" then Right(Conn("1")) else Left("refused")
        case Tcp.Write(_, text) => Right(relay.receive(text))
        case Tcp.Read(_) => Right(relay.answer())
        case Tcp.Upgrade(c) => Right(c)
        case Tcp.Close(_) => ()))
    val fetched = handle(connected, Handler.tail[Fetch, Pure + Log, Boolean, Boolean](pure)([X] => (op: Fetch[X]) =>
      op match
        case okay.http.Fetch.Send(_, r, answer) => asked :+= r; answer(reply)
        case okay.http.Fetch.Call(_, _, _, _, answer) => answer(Response(500, Map.empty, ""))))
    val (log, sent) = run(Log.collect[Pure + Log, Pure, Boolean](Level.Debug)(fetched))
    (sent, letters, asked, log.map(_.toString))

  private val cloudflare = Mailing("atelier@example.com", "Test Atelier", "owner@example.com", "cloudflare", None)
  private val smtp = cloudflare.copy(client = "smtp",
    smtp = Some(SmtpServer("relay.test", 587, Tls.StartTls, "atelier@example.com", "secret", "atelier.example")))

  test("a client by the SMTP relay: STARTTLS, signed in, the letter taken; no relay set: not sent, said why") {
    val relay = Relay()
    val (sent, letters, _, _) = sending(smtp, relay)(Mail.toClient[C]("anna@example.com", note))
    assert(sent)
    assert(letters.isEmpty, "no send_email for a client")
    assertEquals(relay.said.take(3), Vector("EHLO atelier.example", "STARTTLS", "EHLO atelier.example"))
    val (from, to, data) = relay.letters.head
    assertEquals((from, to), ("atelier@example.com", "anna@example.com"))
    assert(data.contains("Reply-To: atelier@example.com") && data.contains("text/html"), data)
    val (none, _, _, log) = sending(smtp.copy(smtp = None))(Mail.toClient[C]("anna@example.com", note))
    assert(!none)
    assert(log.exists(_.contains("no SMTP relay")), log)
    val (refused, _, _, why) = sending(smtp.copy(smtp = smtp.smtp.map(_.copy(host = "down.test"))))(
      Mail.toClient[C]("anna@example.com", note))
    assert(!refused)
    assert(why.exists(_.contains("connect: refused")), why)
  }

  test("the owner by MAIL, with the HTML, the reply-to, the name") {
    val (sent, letters, _, _) = sending(cloudflare)(Mail.toOwner[C](note))
    assert(sent)
    val (binding, l) = letters.head
    assertEquals(binding, "MAIL")
    assertEquals(l, Letter("atelier@example.com", "owner@example.com", note.subject, note.text, Some(note.html),
      note.replyTo, Some("Test Atelier")))
  }

  test("a client by Email Service's binding; refused there: not sent, said why in the log") {
    val (sent, letters, _, log) = sending(cloudflare)(Mail.toClient[C]("anna@example.com", note))
    assert(!sent)
    assertEquals(letters.map(_._1), Vector("CLIENT_MAIL"))
    assert(log.exists(_.contains("not verified")), log)
  }

  test("a client by Brevo: its API asked with the key; no key: not sent, said why") {
    val (sent, letters, asked, _) = sending(cloudflare.copy(client = "brevo", brevoKey = Some("k")))(
      Mail.toClient[C]("anna@example.com", note))
    assert(sent)
    assert(letters.isEmpty)
    assertEquals(asked.map(r => (r.path, r.header("api-key"))), Vector(("/v3/smtp/email", Some("k"))))
    assert(asked.head.text.contains("\"htmlContent\":\"<p>Dziękujemy</p>\""))
    val (none, _, _, log) = sending(cloudflare.copy(client = "brevo"))(Mail.toClient[C]("anna@example.com", note))
    assert(!none)
    assert(log.exists(_.contains("no BREVO_API_KEY")), log)
  }

  test("a client by Resend: its API asked with the key, the name, the reply-to, a key per letter; no key: said why") {
    val resend = cloudflare.copy(client = "resend", resendKey = Some("re_k"))
    val (sent, letters, asked, _) = sending(resend, reply = Response(200, Map.empty, "{\"id\":\"x\"}"))(
      Mail.toClient[C]("anna@example.com", note))
    assert(sent)
    assert(letters.isEmpty)
    val r = asked.head
    assertEquals((r.path, r.header("authorization")), ("/emails", Some("Bearer re_k")))
    val body = okay.effects.json.Descent.parse(r.text).toOption.get.asInstanceOf[okay.effects.json.Json.Obj]
    def str(k: String) = body.get(k).collect { case okay.effects.json.Json.Str(s) => s }
    assertEquals(str("from"), Some("Test Atelier <atelier@example.com>"))
    assertEquals(str("reply_to"), Some("atelier@example.com"))
    assertEquals((str("subject"), str("html"), str("text")), (Some(note.subject), Some(note.html), Some(note.text)))
    assert(r.text.contains("\"to\":[\"anna@example.com\"]"), r.text)
    // the same letter, the same key; another letter, another
    val key = r.header("idempotency-key").get
    val (_, _, again, _) = sending(resend)(Mail.toClient[C]("anna@example.com", note))
    assertEquals(again.head.header("idempotency-key"), Some(key))
    val (_, _, other, _) = sending(resend)(Mail.toClient[C]("anna@example.com", note.copy(subject = "Inny")))
    assertNotEquals(other.head.header("idempotency-key"), Some(key))
    assert(key.length <= 256 && key.startsWith("okay-"))
    val (none, _, _, log) = sending(resend.copy(resendKey = None))(Mail.toClient[C]("anna@example.com", note))
    assert(!none)
    assert(log.exists(_.contains("no RESEND_API_KEY")), log)
  }

  test("Resend refusing: not sent, the status and its name logged — never the address nor the letter") {
    val resend = cloudflare.copy(client = "resend", resendKey = Some("re_k"))
    for (status, name, says) <- Seq((403, "validation_error", "domain not verified"), (422, "invalid_from_address",
        "invalid"), (429, "rate_limit_exceeded", "limit"), (500, "internal_server_error", "Resend failing")) do
      val reply = Response(status, Map.empty, s"""{"statusCode":$status,"name":"$name","message":"anna@example.com"}""")
      val (sent, _, _, log) = sending(resend, reply = reply)(Mail.toClient[C]("anna@example.com", note))
      assert(!sent)
      assert(log.exists(l => l.contains(s"Resend answered $status ($name)") && l.contains(says)), log)
      assert(!log.exists(l => l.contains("anna@") || l.contains("Dziękujemy") || l.contains("TST-1")), log)
  }

  test("a display name with a special character is quoted") {
    assertEquals(okay.shop.handlers.Resend.display("Test Atelier"), "Test Atelier")
    assertEquals(okay.shop.handlers.Resend.display("Kowalski, Jan \"Szycie\""), "\"Kowalski, Jan Szycie\"")
  }
