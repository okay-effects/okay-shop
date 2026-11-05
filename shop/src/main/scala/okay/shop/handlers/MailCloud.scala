package okay.shop.handlers

import okay.scala3.*
import okay.scala3.std.{Clock, Log}
import okay.serverless.cloud.{Fetch, Mail as Letter, Mails, Smtp, SmtpServer, Tcp}
import okay.shop.effects.{Mail, Note}

/** how a site mails: who from, the owner, and how clients are reached (`MAIL_CLIENT`: `smtp`, `cloudflare`,
 * `brevo`, `resend`) with what that way needs — the SMTP relay and its sign-in, Brevo's key, Resend's key; and how
 * the owner and the staff are (`staffWay`): `routing` (the default) by Email Routing's `MAIL`, or `client` the
 * clients' way — for a host whose owners are not Email Routing's verified destinations (a self-made shop) */
final case class Mailing(from: String, name: String, owner: String, client: String, brevoKey: Option[String],
                         smtp: Option[SmtpServer] = None, resendKey: Option[String] = None,
                         staffWay: String = "routing")

/**
 * `Mail` ON THE CLOUD'S EFFECTS (specs/sites.md "Mail"): the owner and staff by `Mails` through the `MAIL` binding
 * (Email Routing's `send_email`: verified destinations, any plan), or the clients' way (`Mailing.staffWay`);
 * clients by `MAIL_CLIENT` — `smtp` (the default): the relay busi used (Brevo's, port 587, STARTTLS) by
 * `Smtp.deliver` over the cloud's `Tcp`; `cloudflare`: `Mails`
 * through `CLIENT_MAIL` (Email Service: any recipient, Workers Paid, the domain onboarded); `brevo`: Brevo's API over
 * `Fetch`; `resend`: Resend's API over `Fetch`. Every letter answers whether it went (`Mails.deliver`, the API's status); why not is logged — never a failure of the
 * program, and never skipped silently
 */
object MailCloud:
  def run[R[_], Rest[_], A](m: Mailing)(p: A ! R)(using Split[Mail, R, Rest], In[Mails, Rest], In[Fetch, Rest],
    In[Tcp, Rest], In[Clock, Rest], In[Log, Rest]): A ! Rest =
    var sent = 0
    def client(to: String, n: Note): Boolean ! Rest = (m.client, m.brevoKey) match
      case ("smtp", _) if m.smtp.isDefined => Clock.now[Rest].flatMap { now =>
          sent += 1
          val id = s"$now.$sent@${m.from.dropWhile(_ != '@').drop(1)}"
          Smtp.deliver[Rest](m.smtp.get, letter(m, to, n), id, now).flatMap {
            case Right(()) => pure(true)
            case Left(why) => Log.warn[Rest](s"mail to a client not sent by SMTP: $why").as(false)
          }
        }
      case ("cloudflare", _) => routed[Rest](m, "CLIENT_MAIL", to, n)
      case ("brevo", Some(key)) => Fetch[Rest](Brevo.base, Brevo.request(key, m.from, m.name, to, n)).flatMap { r =>
          if Brevo.sent(r) then pure(true)
          else Log.warn[Rest](s"mail to a client not sent: Brevo answered ${r.status}").as(false)
        }
      case ("resend", _) if m.resendKey.isDefined =>
        Fetch[Rest](Resend.base, Resend.request(m.resendKey.get, m.from, m.name, to, n)).flatMap { r =>
          if Resend.sent(r) then pure(true)
          else Log.warn[Rest](s"mail to a client not sent: ${Resend.why(r)}").as(false)
        }
      case (way, _) => Log.warn[Rest](s"mail to a client not sent: no way set up (MAIL_CLIENT=$way" +
          s"${if way == "brevo" then ", no BREVO_API_KEY" else if way == "resend" then ", no RESEND_API_KEY"
            else if way == "smtp" then ", no SMTP relay" else ""})").as(false)
    // the owner and the staff: Email Routing's MAIL, or the clients' way when the host says so
    def staff(to: String, n: Note): Boolean ! Rest =
      if m.staffWay == "client" then
        if to.isEmpty then Log.warn[Rest]("mail not sent: no address for the staff").as(false) else client(to, n)
      else routed[Rest](m, "MAIL", to, n)
    handle(p, Handler.general[Mail, Rest, A, A](pure)([X] => (op: Mail[X], k: X => A ! Rest) => op match
      case Mail.ToOwner(n) => staff(m.owner, n).flatMap(k)
      case Mail.ToStaff(to, n) => staff(to, n).flatMap(k)
      case Mail.ToClient(to, n) => client(to, n).flatMap(k)))

  def letter(m: Mailing, to: String, n: Note): Letter =
    Letter(m.from, to, n.subject, n.text, Some(n.html), n.replyTo, Some(m.name))

  private def routed[R[_]](m: Mailing, binding: String, to: String, n: Note)(using In[Mails, R], In[Log, R])
    : Boolean ! R =
    if to.isEmpty then Log.warn[R](s"mail not sent: no address for $binding").as(false)
    else Mails.deliver[R](binding, letter(m, to, n)).flatMap {
      case Right(()) => pure(true)
      case Left(why) => Log.warn[R](s"mail through $binding not sent: $why").as(false)
    }
