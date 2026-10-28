package okay.shop.workshop

import okay.business.faces.Skin
import okay.business.laminar.Laminar
import okay.business.model.Ui
import okay.scala3.*
import okay.scala3.std.{Async, Cancelled}
import okay.serverless.cloud.{Fetch, Port, WebJs}
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * THE WORKSHOP IN THE PAGE (`/workshop/`): the program on okay-faces' Laminar face in okay-ui's calm skin, the site's
 * accent; its `Staff` the site's API through the browser's `fetch` (okay-serverless' `WebJs.fetch`; the session's
 * cookie the browser's); its `Human` the page's
 * Turnstile widget. The one line is below
 */
object InThePage:
  type Page[X] = (Async + Ui + Human + Fetch + Staff)[X]

  def main(args: Array[String]): Unit =
    val root = dom.document.getElementById("app")
    // the site as its page says it: its name, its accent, the deep colour of its bar
    val site = Shown(root.getAttribute("data-name"))
    val accent = root.getAttribute("data-accent")
    val deep = root.getAttribute("data-deep")
    val expired = dom.window.location.hash == "#expired"
    val origin = dom.window.location.origin
    val running = Async.start(Laminar.run(root, Skin.calm(accent = accent, deep = deep))(Turnstile.run(
      Port.handle(WebJs.fetch)(StaffApi.run(origin)(Workshop.main[Page](site, expired))))))
    running.future.failed.foreach {
      case _: Cancelled => ()
      case e => dom.console.error(e.toString)
    }

/** `Human` IN THE PAGE: the token of the page's Turnstile widget (an invisible one answers on its own, a little
 * later), given once — the widget reset for the next ask */
object Turnstile:
  def run[R[_], Rest[_], A](p: A ! R)(using Split[Human, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Human, Rest, A, A](pure)([X] => (op: Human[X], k: X => A ! Rest) => op match
      case Human.Token() => Async.cancellable[String, Rest] { cb =>
          var timer: js.UndefOr[js.timers.SetTimeoutHandle] = js.undefined
          def poll(left: Int): Unit =
            val t = js.Dynamic.global.turnstile
            val token = if js.isUndefined(t) then "" else Option(t.getResponse()).filterNot(js.isUndefined)
              .map(_.toString).getOrElse("")
            if token.nonEmpty || left <= 0 then
              if !js.isUndefined(t) then t.reset()
              cb(Right(token))
            else timer = js.timers.setTimeout(250)(poll(left - 1))
          poll(40)
          () => timer.foreach(js.timers.clearTimeout)
        }.flatMap(k)))
