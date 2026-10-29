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
    doc.querySelectorAll("[data-slots]").foreach(d => picker(d.asInstanceOf[HTMLElement]))

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

  private def formLang(f: dom.Element): Lang =
    Option(f.querySelector("[name=lang]")).map(_.asInstanceOf[dom.HTMLInputElement].value).flatMap(Lang.parse)
      .getOrElse(Lang.pl)

  private def el(tag: String, cls: String, text: String): HTMLElement =
    val e = dom.document.createElement(tag).asInstanceOf[HTMLElement]
    e.className = cls
    e.textContent = text
    e

  /**
   * THE SLOT PICKER: a bookable service's free starts from `/api/slots`, the days as buttons, the chosen day's
   * starts as buttons; the start chosen goes into the form's hidden `start`
   */
  private def picker(box: HTMLElement): Unit =
    val form = box.closest("form")
    val l = formLang(form)
    val start = Option(form.querySelector("[name=start]")).map(_.asInstanceOf[dom.HTMLInputElement])
    val days = Async.start(Port.handle(WebJs.fetch)(Submit.slots[Async + Fetch](dom.window.location.origin,
      box.getAttribute("data-slots"))))(using queue).future
    days.recover { case _ => Vector.empty }.foreach { ds =>
      box.innerHTML = ""
      if ds.isEmpty then box.appendChild(el("span", "", Submit.noSlots(l)))
      else
        val dayRow = el("div", "days", "")
        val slotRow = el("div", "times", "")
        box.appendChild(dayRow)
        box.appendChild(slotRow)
        def show(d: Submit.Day, b: HTMLElement): Unit =
          dayRow.querySelectorAll(".day").foreach(_.asInstanceOf[HTMLElement].classList.remove("on"))
          b.classList.add("on")
          slotRow.innerHTML = ""
          start.foreach(_.value = "")
          d.slots.foreach { (at, label) =>
            val t = el("button", "slot", label)
            t.setAttribute("type", "button")
            t.addEventListener("click", (_: dom.Event) =>
              slotRow.querySelectorAll(".slot").foreach(_.asInstanceOf[HTMLElement].classList.remove("on"))
              t.classList.add("on")
              start.foreach(_.value = at.toString))
            slotRow.appendChild(t)
          }
        ds.zipWithIndex.foreach { (d, i) =>
          val b = el("button", "day", Submit.dayLabel(l, d))
          b.setAttribute("type", "button")
          b.addEventListener("click", (_: dom.Event) => show(d, b))
          dayRow.appendChild(b)
          if i == 0 then show(d, b)
        }
    }

  private def submit(f: HTMLFormElement): Unit =
    val button = Option(f.querySelector(".send")).map(_.asInstanceOf[dom.HTMLButtonElement])
    button.foreach(_.disabled = true)
    token(f, 20) { () =>
      val fs = fields(f)
      val lang = fs.collectFirst { case ("lang", v) => v }.flatMap(Lang.parse).getOrElse(Lang.pl)
      val picks = f.querySelector("[data-slots]") != null
      def sent = Async.start(Port.handle(WebJs.fetch)(Submit.send[Async + Fetch](dom.window.location.origin,
        f.getAttribute("data-send"), fs)))(using queue).future
      val chosen = !picks || fs.exists((k, v) => k == "start" && v.nonEmpty)
      // no answer at all — the network — is a refusal like the others: the form kept, said why
      (if chosen then sent else scala.concurrent.Future.successful(Outcome.Refused("start")))
        .recover { case _ => Outcome.Refused("network") }.foreach {
        case Outcome.Placed(ref, blik, price, paczkomat) => done(f, ref, lang, blik, price, paczkomat)
        case Outcome.Booked(ref, when, manage) => booked(f, ref, lang, when, manage)
        case Outcome.Refused(why) =>
          button.foreach(_.disabled = false)
          if !js.isUndefined(js.Dynamic.global.turnstile) then js.Dynamic.global.turnstile.reset()
          dom.window.alert(Submit.refusal(lang, why))
      }
    }

  /** a booking's done panel: the ref, the time, the link that manages it */
  private def booked(f: HTMLFormElement, ref: String, l: Lang, when: String, manage: String): Unit =
    byId(f.getAttribute("data-form")).foreach(_.style.display = "none")
    byId(f.getAttribute("data-done")).foreach { d =>
      d.style.display = "block"
      d.appendChild(el("p", "done-ref", "№ " + ref))
      val p = el("p", "", Submit.booked(l, when) + " ")
      p.style.cssText = "margin-top:10px;font-size:14px"
      val a = el("a", "", manage).asInstanceOf[dom.html.Anchor]
      a.href = manage
      a.style.cssText = "color:inherit;word-break:break-all"
      p.appendChild(a)
      d.appendChild(p)
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
