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
    modes()

  private def byId(id: String): Option[HTMLElement] =
    Option(dom.document.getElementById(id)).map(_.asInstanceOf[HTMLElement])

  private def fields(f: HTMLFormElement): Vector[(String, String)] =
    val out = Vector.newBuilder[(String, String)]
    js.Dynamic.newInstance(js.Dynamic.global.FormData)(f)
      .forEach((v: js.Any, k: String) => out += k -> v.toString)
    out.result()

  /** Turnstile's widget, when the page has it (`typeof`: a page without its script has no such name at all) */
  private def turnstile: Option[js.Dynamic] =
    if js.typeOf(js.Dynamic.global.turnstile) == "undefined" then None else Some(js.Dynamic.global.turnstile)

  /** Turnstile's token, given a few seconds to come: an invisible widget answers on its own, a little later */
  private def token(f: HTMLFormElement, tries: Int)(go: () => Unit): Unit =
    val t = Option(f.querySelector("[name=cf-turnstile-response]")).map(_.asInstanceOf[dom.HTMLInputElement].value)
    if t.exists(_.nonEmpty) || tries <= 0 || turnstile.isEmpty then go()
    else js.timers.setTimeout(250)(token(f, tries - 1)(go))

  private def formLang(f: dom.Element): Lang =
    Option(f.querySelector("[name=lang]")).map(_.asInstanceOf[dom.HTMLInputElement].value).flatMap(Lang.parse)
      .getOrElse(Lang.pl)

  private def el(tag: String, cls: String, text: String): HTMLElement =
    val e = dom.document.createElement(tag).asInstanceOf[HTMLElement]
    e.className = cls
    e.textContent = text
    e

  private def input(f: dom.Element, sel: String): Option[dom.HTMLInputElement] =
    Option(f.querySelector(sel)).map(_.asInstanceOf[dom.HTMLInputElement])

  private def hide(e: HTMLElement, off: Boolean): Unit =
    if off then e.setAttribute("hidden", "") else e.removeAttribute("hidden")

  /** a form asked to show its slots again: the mode or the place changed */
  private val reload = "okay-reload"

  /**
   * HOW THE WORK HAPPENS (specs/shop.md): the mode chosen (`how`) shows its panel — the booking form or the order
   * form —, in the booking form its block (the location, the address, the call) with the others' fields off, and
   * asks for its slots; a place chosen asks again; an address typed is checked against the area
   */
  private def modes(): Unit =
    val doc = dom.document
    def choose(m: String): Unit =
      doc.querySelectorAll("[data-how-panel]").foreach { p =>
        val e = p.asInstanceOf[HTMLElement]
        hide(e, e.getAttribute("data-how-panel") != (if m == "byMail" then "byMail" else "book"))
      }
      if m != "byMail" then doc.querySelectorAll("form[data-send=book]").foreach { f =>
        input(f, "[name=mode]").foreach(_.value = m)
        f.querySelectorAll("[data-for]").foreach { b =>
          val e = b.asInstanceOf[HTMLElement]
          val off = e.getAttribute("data-for") != m
          hide(e, off)
          e.querySelectorAll("input").foreach(_.asInstanceOf[dom.HTMLInputElement].disabled = off)
        }
        masters(f)
        f.dispatchEvent(new dom.Event(reload))
      }
    doc.querySelectorAll("input[name=how]").foreach(r => r.addEventListener("change", (_: dom.Event) =>
      choose(r.asInstanceOf[dom.HTMLInputElement].value)))
    doc.querySelectorAll("input[name=place][type=radio]").foreach(r => r.addEventListener("change",
      (_: dom.Event) => Option(r.closest("form")).foreach { f =>
        masters(f)
        f.dispatchEvent(new dom.Event(reload))
      }))
    doc.querySelectorAll("select[name=master]").foreach { s =>
      Option(s.closest("form")).foreach(masters)
      s.addEventListener("change", (_: dom.Event) => Option(s.closest("form")).foreach(_.dispatchEvent(
        new dom.Event(reload))))
    }
    doc.querySelectorAll("[data-area-said]").foreach { said =>
      val form = said.closest("form")
      val l = formLang(form)
      def check(): Unit =
        val city = input(form, "[name=city]").map(_.value.trim).getOrElse("")
        val code = input(form, "[name=postcode]").map(_.value.trim).getOrElse("")
        if city.nonEmpty && code.matches("[0-9]{2}-[0-9]{3}") then
          Async.start(Port.handle(WebJs.fetch)(Submit.area[Async + Fetch](dom.window.location.origin, city, code)))(
            using queue).future.recover { case _ => None }.foreach {
            case Some(true) => said.textContent = Submit.inside(l)
            case Some(false) => said.textContent = Submit.outside(l)
            case None => said.textContent = ""
          }
      Seq("[name=city]", "[name=postcode]").foreach(sel => input(form, sel).foreach(_.addEventListener("change",
        (_: dom.Event) => check())))
    }

  /** the masters a form offers: only those working in its mode and place (specs/shop.md "Masters"); one no longer
   * offered gives way to any free master */
  private def masters(f: dom.Element): Unit =
    Option(f.querySelector("select[name=master]")).map(_.asInstanceOf[dom.HTMLSelectElement]).foreach { s =>
      val mode = input(f, "[name=mode]").map(_.value).getOrElse("")
      val place = Option(f.querySelector("[name=place]:checked")).orElse(Option(f.querySelector(
        "input[type=hidden][name=place]"))).map(_.asInstanceOf[dom.HTMLInputElement].value).getOrElse("")
      s.querySelectorAll("option").foreach { o =>
        val e = o.asInstanceOf[dom.HTMLOptionElement]
        def has(attr: String, v: String) = Option(e.getAttribute(attr)).map(_.split(' ').filter(_.nonEmpty))
          .forall(xs => xs.isEmpty || v.isEmpty || xs.contains(v))
        val ok = e.value.isEmpty || (has("data-modes", mode) && (mode != "atShop" || has("data-places", place)))
        hide(e, !ok)
        e.disabled = !ok
        if !ok && e.selected then s.value = ""
      }
    }

  /**
   * THE SLOT PICKER: a bookable service's free starts from `/api/slots`, the days as buttons, the chosen day's
   * starts as buttons; the start chosen goes into the form's hidden `start`
   */
  private def picker(box: HTMLElement): Unit =
    val form = box.closest("form")
    val l = formLang(form)
    val start = Option(form.querySelector("[name=start]")).map(_.asInstanceOf[dom.HTMLInputElement])
    form.addEventListener(reload, (_: dom.Event) => load())
    load()
    // the mode and the place the form says now (none: a shop before the modes)
    def load(): Unit =
      val mode = input(form, "[name=mode]").map(_.value).getOrElse("")
      val place = Option(form.querySelector("[name=place]:checked")).orElse(Option(form.querySelector(
        "input[type=hidden][name=place]"))).map(_.asInstanceOf[dom.HTMLInputElement].value).getOrElse("")
      val master = Option(form.querySelector("select[name=master]")).map(_.asInstanceOf[dom.HTMLSelectElement].value)
        .getOrElse("")
      start.foreach(_.value = "")
      shown(Async.start(Port.handle(WebJs.fetch)(Submit.slots[Async + Fetch](dom.window.location.origin,
        box.getAttribute("data-slots"), mode, place, master)))(using queue).future)
    def shown(days: scala.concurrent.Future[Vector[Submit.Day]]): Unit =
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
              val t = el("button", "slot", d.left.get(at).fold(label)(Submit.seats(l, label, _)))
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
        case Outcome.Asked(ref, manage) => linked(f, ref, Submit.asked(lang), manage)
        case Outcome.Booked(ref, when, manage, master) =>
          booked(f, ref, lang, if master.isEmpty then when else s"$when · $master", manage)
        case Outcome.Refused(why) =>
          button.foreach(_.disabled = false)
          turnstile.foreach(_.reset())
          dom.window.alert(Submit.refusal(lang, why))
      }
    }

  /** a booking's done panel: the ref, the time, the link that manages it */
  private def booked(f: HTMLFormElement, ref: String, l: Lang, when: String, manage: String): Unit =
    linked(f, ref, Submit.booked(l, when), manage)

  /** a done panel with its ref, its words and the link to follow it */
  private def linked(f: HTMLFormElement, ref: String, said: String, manage: String): Unit =
    byId(f.getAttribute("data-form")).foreach(_.style.display = "none")
    byId(f.getAttribute("data-done")).foreach { d =>
      d.style.display = "block"
      d.appendChild(el("p", "done-ref", "№ " + ref))
      val p = el("p", "", said + " ")
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
