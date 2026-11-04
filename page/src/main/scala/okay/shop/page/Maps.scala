package okay.shop.page

import org.scalajs.dom
import org.scalajs.dom.HTMLElement

import scala.scalajs.js

/**
 * A PLACE'S MAP (specs/shop.md "Places on the map"): a box `[data-map="lat,lon,label;…"]` filled with Leaflet (1.9.4
 * from cdnjs, pinned by its SRI hashes) and OpenStreetMap's tiles with their attribution — only when the box scrolls
 * into view or is clicked (the tile usage policy: what the visitor views, nothing prefetched); the tiles' referrer
 * policy `strict-origin-when-cross-origin` (they need one); the tiles' URL the box's `data-tiles`, else
 * OpenStreetMap's.
 * Several points: a marker each, the view fitted to them. Nothing on a page with no box
 */
object Maps:
  val script = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"
  val scriptSri = "sha384-NElt3Op+9NBMCYaef5HxeJmU4Xeard/Lku8ek6hoPTvYkQPh3zLIrJP7KiRocsxO"
  val style = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css"
  val styleSri = "sha384-c6Rcwz4e4CITMbu/NBmnNS8yN2sC3cUElMEMfP3vqqKFp7GOYaaBBCqmaWBjmkjb"
  val tiles = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  val attribution = "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

  /** the window as an object: a name it lacks is undefined there, never a ReferenceError (a bare global's would
   * be) */
  private def win: js.Dynamic = dom.window.asInstanceOf[js.Dynamic]

  private var loading = false
  private val waiting = scala.collection.mutable.Buffer.empty[() => Unit]

  /** Leaflet loaded once, then `then` (every caller's, in order) */
  def leaflet(andThen: () => Unit): Unit =
    if !js.isUndefined(win.selectDynamic("L")) then andThen()
    else
      waiting += andThen
      if !loading then
        loading = true
        val doc = dom.document
        val css = doc.createElement("link").asInstanceOf[dom.HTMLLinkElement]
        css.rel = "stylesheet"
        css.href = style
        css.setAttribute("integrity", styleSri)
        css.setAttribute("crossorigin", "anonymous")
        doc.head.appendChild(css)
        val s = doc.createElement("script").asInstanceOf[dom.HTMLScriptElement]
        s.src = script
        s.setAttribute("integrity", scriptSri)
        s.setAttribute("crossorigin", "anonymous")
        s.addEventListener("load", (_: dom.Event) => { waiting.foreach(_()); waiting.clear() })
        doc.head.appendChild(s)

  /** the points a box names: `lat,lon,label` each, `;` between */
  def points(spec: String): Vector[(Double, Double, String)] = spec.split(';').toVector.flatMap { p =>
    p.split(",", 3) match
      case Array(a, b, rest*) => for x <- a.trim.toDoubleOption; y <- b.trim.toDoubleOption yield
        (x, y, rest.headOption.getOrElse(""))
      case _ => None
  }

  /** a box filled: the tiles, a marker each (its label as text, never HTML), the view on them */
  def show(box: HTMLElement, again: Boolean = false): Unit =
    if again || box.getAttribute("data-shown") != "1" then
      box.setAttribute("data-shown", "1")
      leaflet { () =>
        val L = win.L
        val ps = points(box.getAttribute("data-map"))
        // shown again (the page's points changed): the old map taken away first
        val old = box.asInstanceOf[js.Dynamic].selectDynamic("okayMap")
        if !js.isUndefined(old) && old != null then old.remove()
        box.innerHTML = ""
        val m = L.map(box, js.Dynamic.literal(scrollWheelZoom = false))
        box.asInstanceOf[js.Dynamic].updateDynamic("okayMap")(m)
        L.tileLayer(Option(box.getAttribute("data-tiles")).filter(_.startsWith("https://")).getOrElse(tiles),
          js.Dynamic.literal(maxZoom = 19, attribution = attribution,
            referrerPolicy = "strict-origin-when-cross-origin")).addTo(m)
        ps.foreach { (lat, lon, label) =>
          val mk = L.marker(js.Array(lat, lon)).addTo(m)
          if label.nonEmpty then
            val t = dom.document.createElement("span")
            t.textContent = label
            mk.bindPopup(t)
        }
        if ps.isEmpty then m.setView(js.Array(52.07, 19.48), 6) // Poland
        else if ps.size == 1 then m.setView(js.Array(ps.head._1, ps.head._2), 15)
        else if ps.nonEmpty then m.fitBounds(js.Array(ps.map((a, b, _) => js.Array(a, b))*),
          js.Dynamic.literal(padding = js.Array(24, 24)))
      }

  /** every box on the page: filled when it comes into view (or is clicked) */
  def start(): Unit =
    val boxes = dom.document.querySelectorAll("[data-map]")
    if boxes.length > 0 then
      val io = win.selectDynamic("IntersectionObserver")
      val observer: Option[js.Dynamic] = Option.when(js.typeOf(io) == "function")(js.Dynamic.newInstance(io)(
        (entries: js.Array[js.Dynamic], obs: js.Dynamic) => entries.foreach { en =>
          if en.isIntersecting.asInstanceOf[Boolean] then
            obs.unobserve(en.target)
            show(en.target.asInstanceOf[HTMLElement])
        }, js.Dynamic.literal(rootMargin = "100px")))
      boxes.foreach { b =>
        val box = b.asInstanceOf[HTMLElement]
        box.addEventListener("click", (_: dom.Event) => show(box))
        // a page that changed the points (a new search) asks for the map again
        box.addEventListener("okay-map", (_: dom.Event) => show(box, again = true))
        observer.foreach(_.observe(box))
      }
