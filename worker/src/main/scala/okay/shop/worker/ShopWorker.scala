package okay.shop.worker

import okay.scala3.*
import okay.scala3.std.Env
import okay.serverless.cloud.{Actor, App, Cloud, InActor, Request, Response, Services, SmtpServer, Tls}
import okay.serverless.cloudflare.Worker
import okay.shop.app.{Api, Front, Web}
import okay.shop.effects.*
import okay.shop.handlers.{MailCloud, Mailing, StoreDurable, Turnstile}
import okay.shop.html.{Conf, Look}
import okay.shop.model.Site

/** A SITE'S SETTINGS, from its Worker's vars and secrets (its wrangler.toml, `wrangler secret put`) */
final case class Settings(sitekey: String, turnstileSecret: String, mailing: Mailing, staff: Set[String],
                          blik: String, paczkomat: String)

object Settings:
  def read[R[_]](site: Site)(using In[Env, R]): Settings ! R =
    def v(name: String) = Env.variable[R](name).map(_.getOrElse(""))
    def s(name: String) = Env.secret[R](name).map(_.filter(_.nonEmpty))
    for
      sitekey <- v("TURNSTILE_SITEKEY"); secret <- s("TURNSTILE_SECRET")
      from <- v("MAIL_FROM"); name <- v("MAIL_NAME"); owner <- v("OWNER_EMAIL"); client <- v("MAIL_CLIENT")
      brevo <- s("BREVO_API_KEY"); staff <- v("STAFF_EMAILS"); blik <- v("BLIK_PHONE"); paczkomat <- v("PACZKOMAT")
      smtpHost <- v("SMTP_HOST"); smtpPort <- v("SMTP_PORT"); smtpUser <- s("SMTP_USER"); smtpPass <- s("SMTP_PASS")
    yield Settings(sitekey, secret.getOrElse(""),
      Mailing(if from.isEmpty then s"kontakt@${site.domain}" else from, if name.isEmpty then site.name else name,
        owner, if client.isEmpty then "smtp" else client, brevo,
        // the relay, when all of it is set (Brevo's SMTP, 587, STARTTLS), its sign-in the Worker's secrets
        for host <- Option(smtpHost).filter(_.nonEmpty); user <- smtpUser; pass <- smtpPass
        yield SmtpServer(host, smtpPort.toIntOption.getOrElse(587), Tls.StartTls, user, pass,
          site.domain.split('.').takeRight(2).mkString("."))),
      staff.split(',').map(_.trim.toLowerCase).filter(_.nonEmpty).toSet, blik, paczkomat)

/**
 * A SITE'S WORKER, on okay-serverless (specs/shop.md): the app is the front — the old URLs and `/` redirected, the
 * static pages the assets' (`Services` to the `ASSETS` binding) — and what is dynamic goes, as it came, to the
 * site's object, an `Actor` whose program runs on the site's handlers: the one line in `actor.fetch`. A site's main
 * exports it: `@JSExportTopLevel("worker") val worker = ShopWorker(site, look)` — never this library, whose top-level
 * exports would land in every user's code
 */
final class ShopWorker(site: Site, look: Look):
  object app extends App:
    override def objectFor(r: Request): Option[(String, String)] = Option.when(Front.dynamic(r))(("SITES", "site"))
    override def fetch(r: Request): Response ! Cloud =
      Front.redirect(site, r).fold(Services.call[Cloud]("ASSETS", r))(pure)

  /** the site's object: its state okay-serverless' `Durable` (the object's SQLite); its requests one at a time */
  object actor extends Actor:
    type Live[X] = (InActor + Store + Ids + Captcha + Mail)[X]
    def fetch(name: String, r: Request): Response ! InActor = Settings.read[InActor](site).flatMap { s =>
      val conf = Conf(site, Web.origin(r), s.blik, s.paczkomat, s.sitekey, s.staff, s.mailing.from, look.policy)
      MailCloud.run(s.mailing)(Turnstile.run(s.turnstileSecret)(Ids.from(Platform.randomInt)(StoreDurable.run(
        Api.answer[Live](conf, r)))))
    }

  val worker: Worker = Worker(app, Map("SITES" -> actor))

object ShopWorker:
  def apply(site: Site, look: Look): Worker = new ShopWorker(site, look).worker
