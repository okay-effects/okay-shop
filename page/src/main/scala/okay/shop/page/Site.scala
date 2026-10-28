package okay.shop.page

import okay.scala3.+
import okay.scala3.std.Async
import okay.serverless.cloud.{Fetch, Port, WebJs}
import org.scalajs.dom
import org.scalajs.dom.{HTMLFormElement, HTMLElement}
import okay.shop.app.Submit
import okay.shop.app.Submit.Outcome
import okay.shop.model.Lang

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * THE PAGES' SCRIPT (`site.js`): the modal opened and closed, a form posted as `Submit` says — its `Fetch` the
 * browser's (okay-serverless' `WebJs.fetch`) — and the done panel shown as busi showed it; the language chosen
 * remembered (`lang` cookie, which `/` reads). Everything else on the pages is HTML
 */
object Site:
  def main(args: Array[String]): Unit =
    val doc = dom.document
    doc.querySelectorAll("[data-open]").foreach(b => b.addEventListener("click", (_: dom.Event) =>
      byId(b.getAttribute("data-open")).foreach(_.classList.add("open"))))
    doc.querySelectorAll("[data-close]").foreach(b => b.addEventListener("click", (_: dom.Event) =>
      byId(b.getAttribute("data-close")).foreach(_.classList.remove("open"))))
    doc.querySelectorAll(".lang a[data-l]").foreach(a => a.addEventListener("click", (_: dom.Event) =>
      doc.cookie = s"lang=${a.getAttribute("data-l")}; path=/; max-age=31536000; SameSite=Lax"))
    doc.querySelectorAll("form[data-send]").foreach(f => f.addEventListener("submit", (e: dom.Event) =>
      e.preventDefault()
      submit(f.asInstanceOf[HTMLFormElement])))

  private def byId(id: String): Option[HTMLElement] =
    Option(dom.document.getElementById(id)).map(_.asInstanceOf[HTMLElement])

  private def fields(f: HTMLFormElement): Vector[(String, String)] =
    val out = Vector.newBuilder[(String, String)]
    js.Dynamic.newInstance(js.Dynamic.global.FormData)(f)
      .forEach((v: js.Any, k: String) => out += k -> v.toString)
    out.result()

  /** Turnstile's token, given a few seconds to come: an invisible widget answers on its own, a little later */
  private def token(f: HTMLFormElement, tries: Int)(go: () => Unit): Unit =
    val t = Option(f.querySelector("[name=cf-turnstile-response]")).map(_.asInstanceOf[dom.HTMLInputElement].value)
    if t.exists(_.nonEmpty) || tries <= 0 || js.isUndefined(js.Dynamic.global.turnstile) then go()
    else js.timers.setTimeout(250)(token(f, tries - 1)(go))

  private def submit(f: HTMLFormElement): Unit =
    val button = Option(f.querySelector(".send")).map(_.asInstanceOf[dom.HTMLButtonElement])
    button.foreach(_.disabled = true)
    token(f, 20) { () =>
      val fs = fields(f)
      val lang = fs.collectFirst { case ("lang", v) => v }.flatMap(Lang.parse).getOrElse(Lang.pl)
      val sent = Async.start(Port.handle(WebJs.fetch)(Submit.send[Async + Fetch](dom.window.location.origin,
        f.getAttribute("data-send"), fs)))(using queue).future
      // no answer at all — the network — is a refusal like the others: the form kept, said why
      sent.recover { case _ => Outcome.Refused("network") }.foreach {
        case Outcome.Placed(ref, blik, price, paczkomat) => done(f, ref, lang, blik, price, paczkomat)
        case Outcome.Refused(why) =>
          button.foreach(_.disabled = false)
          if !js.isUndefined(js.Dynamic.global.turnstile) then js.Dynamic.global.turnstile.reset()
          dom.window.alert(Submit.refusal(lang, why))
      }
    }

  /** busi's done panel: the form hidden, the thanks shown, the ref, BLIK, the paczkomat */
  private def done(f: HTMLFormElement, ref: String, l: Lang, blik: Option[String], price: Option[String],
                   paczkomat: Option[String]): Unit =
    byId(f.getAttribute("data-form")).foreach(_.style.display = "none")
    byId(f.getAttribute("data-done")).foreach { d =>
      d.style.display = "block"
      def p(css: String, text: String, cls: String = ""): dom.html.Paragraph =
        val e = dom.document.createElement("p").asInstanceOf[dom.html.Paragraph]
        if cls.nonEmpty then e.className = cls
        if css.nonEmpty then e.style.cssText = css
        e.textContent = text
        d.appendChild(e)
        e
      p("", "№ " + ref, "done-ref")
      blik.foreach(b => p("margin-top:14px;padding:10px 14px;background:rgba(255,255,255,.07);border-radius:8px;" +
        "font-size:14px;font-weight:500", Submit.blik(l, b, price)))
      paczkomat.foreach { code =>
        val e = p("margin-top:10px;padding:10px 14px;background:rgba(255,255,255,.07);border-radius:8px;font-size:14px",
          Submit.paczkomat(l))
        val b = dom.document.createElement("b").asInstanceOf[HTMLElement]
        b.style.cssText = "letter-spacing:.06em;font-size:16px"
        b.textContent = code
        e.appendChild(b)
      }
    }
