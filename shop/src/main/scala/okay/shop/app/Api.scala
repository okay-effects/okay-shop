package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.*
import okay.shop.html.{Conf, Letters, Receipt, Track}
import okay.shop.model.*

/**
 * THE SITE'S DYNAMIC SIDE (specs/sites.md "The Worker's API"): an order placed, an inquiry, an order looked up, its
 * receipt — a program of the site's effects, answered in its Durable Object, tested on the JVM with other handlers
 */
object Api:
  /** a field's longest */
  val longest: Map[String, Int] = Map("name" -> 120, "contact" -> 200, "need" -> 4000, "fabric" -> 400,
    "itemValue" -> 20, "subject" -> 200, "body" -> 4000)
  val tenMinutes: Long = 10 * 60 * 1000L

  def answer[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R])
    : Response ! R =
    val parts = r.path.split('/').toList.filter(_.nonEmpty)
    val desk = c.desk.path.split('/').toList.filter(_.nonEmpty) :+ "api"
    (r.method, parts) match
      case (_, ps) if ps.startsWith(desk) => Workshop.answer(c, r, ps.drop(desk.size))
      // the owner's reports (specs/shop.md "Reports"): pages beside the workshop's
      case ("GET", ps) if ps.startsWith(desk.init :+ "reports") => Accounts.pages(c, r, ps.drop(desk.size))
      case ("POST", List("api", "order")) => order(c, r)
      case ("POST", List("api", "inquiry")) => inquiry(c, r)
      case ("GET", List(l, "track")) if Lang.parse(l).isDefined => trackLink(c, Lang.parse(l).get, r)
      case ("POST", List(l, "track")) if Lang.parse(l).isDefined => trackForm(c, Lang.parse(l).get, r)
      case ("GET", List(l, "receipt")) if Lang.parse(l).isDefined => receipt(c, r)
      case ("GET", List("api", "slots")) => Bookings.slots(c, r)
      case ("GET", List("api", "area")) => pure(Bookings.area(c, r))
      case ("POST", List("api", "quote")) => Quotes.ask(c, r)
      case ("POST", List("api", "quote", "accept")) => Quotes.accept(c, r)
      case ("POST", List("api", "quote", "decline")) => Quotes.decline(c, r)
      case ("GET", List(l, "quote")) if Lang.parse(l).isDefined => Quotes.page(c, Lang.parse(l).get, r)
      case ("POST", List("api", "book")) => Bookings.book(c, r)
      case ("POST", List("api", "booking", "cancel")) => Bookings.cancel(c, r)
      case ("POST", List("api", "booking", "move")) => Bookings.move(c, r)
      case ("GET", List(l, "booking")) if Lang.parse(l).isDefined => Bookings.page(c, Lang.parse(l).get, r)
      case ("GET", List("calendar", file)) if file.endsWith(".ics") => Bookings.calendar(c, file.stripSuffix(".ics"))
      case _ => pure(Response.notFound())

  def ip(r: Request): String = Web.ip(r)

  private def fields(r: Request): Map[String, String] = Web.fields(r)
  private def field(f: Map[String, String], k: String): String =
    f.getOrElse(k, "").trim.take(longest.getOrElse(k, 100))

  private def refused(why: String, status: Int = 400): Response = Web.refused(why, status)

  // ---- AN ORDER

  def order[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R])
    : Response ! R =
    val f = fields(r)
    val site = c.site
    val name = field(f, "name")
    val contact = field(f, "contact")
    val offerKey = field(f, "offerKey")
    val consent = f.get("rodo_consent").exists(v => v.nonEmpty && v != "false")
    if name.isEmpty then pure(refused("name"))
    else if !Contact.valid(contact) then pure(refused("contact"))
    else if !consent then pure(refused("consent"))
    else if offerKey.nonEmpty && !site.offer(offerKey).exists(!_.hidden) then pure(refused("offer"))
    else for
      now <- Clock.now[R]
      allowed <- Store.allow[R](s"order:${ip(r)}", 5, tenMinutes, now)
      human <- if allowed then Captcha.verify[R](f.getOrElse("cf-turnstile-response", f.getOrElse("turnstile", "")),
        ip(r)) else pure(false)
      res <- if !allowed then pure(refused("limit", 429)) else if !human then pure(refused("captcha", 403))
        else place(c, f, name, contact, offerKey, now)
    yield res

  private def place[R[_]](c: Conf, f: Map[String, String], name: String, contact: String, offerKey: String, now: Long)
    (using In[Store, R], In[Mail, R], In[Ids, R]): Response ! R =
    val site = c.site
    val lang = Lang.parse(f.getOrElse("lang", "")).getOrElse(site.lang)
    def oneOf(k: String, allowed: Set[String]) = Some(field(f, k)).filter(allowed).getOrElse("")
    for
      ref <- Ids.ref[R](site.refPrefix)
      key <- Ids.secret[R]
      o = Order(ref, site.id, key, offerKey, name, contact, field(f, "need"),
        oneOf("handover", Set("self", "visit", "delivery")),
        oneOf("deliveryMethod", Set("courier", "in_person")) match
          case "" if site.clothing && offerKey.isEmpty => "courier"
          case d => d,
        oneOf("paymentMethod", Set("transfer", "cod")), field(f, "itemValue").filter(ch => ch.isDigit || ch == '.'),
        field(f, "fabric"), lang, Consent(now, c.policy), now)
      _ <- Store.place[R](o)
      _ <- Mail.toOwner[R](Letters.owner(c, o))
      _ <- o.email match
        case Some(to) =>
          val n = Letters.confirmation(c, o)
          Mail.toClient[R](to, n).flatMap(sent => Store.record[R](o.ref, Event.Emailed(to, n.subject, sent), now))
            .map(_ => ())
        case None => unit[R]
    yield
      val offer = site.offer(offerKey)
      Web.json(Json.Obj(Vector("ok" -> Json.bool(true), "ref" -> Json.str(o.ref),
        "key" -> Json.str(o.key)) ++
        (if site.clothing && c.blik.nonEmpty then Vector("blik" -> Json.str(c.blik)) else Vector.empty) ++
        offer.filter(_.priced).map(x => "price" -> Json.str(x.priceShort)) ++
        (if o.courier && c.paczkomat.nonEmpty then Vector("paczkomat" -> Json.str(c.paczkomat)) else Vector.empty)))

  // ---- AN INQUIRY (IT's /products)

  def inquiry[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R])
    : Response ! R =
    val f = fields(r)
    val name = field(f, "name")
    val contact = field(f, "contact")
    val body = field(f, "body")
    if name.isEmpty then pure(refused("name"))
    else if !Contact.valid(contact) then pure(refused("contact"))
    else if body.isEmpty then pure(refused("body"))
    else if !f.get("rodo_consent").exists(v => v.nonEmpty && v != "false") then pure(refused("consent"))
    else for
      now <- Clock.now[R]
      allowed <- Store.allow[R](s"inquiry:${ip(r)}", 5, tenMinutes, now)
      human <- if allowed then Captcha.verify[R](f.getOrElse("cf-turnstile-response", ""), ip(r)) else pure(false)
      res <- if !allowed then pure(refused("limit", 429)) else if !human then pure(refused("captcha", 403))
        else for
          ref <- Ids.ref[R](c.site.refPrefix + "Q")
          i = Inquiry(ref, c.site.id, name, contact, field(f, "subject"),
            Some(field(f, "kind")).filter(Set("question", "task", "incident",
                 "investor_inquiry")).getOrElse("question"),
            body, f.get("confidential").exists(v => v == "true" || v == "on"),
            Lang.parse(f.getOrElse("lang", "")).getOrElse(c.site.lang), Consent(now, c.policy), now)
          _ <- Store.inquire[R](i)
          _ <- Mail.toOwner[R](Letters.inquiry(c, i))
        yield Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(ref)))
    yield res

  // ---- AN ORDER LOOKED UP: by its ref and key (a letter's link), or by its ref and contact (the form)

  private def page(html: String): Response = Web.page(html)

  /** the order of `ref` when `ok` says its second factor is right; else none — the same as no such order */
  private def found[R[_]](ref: String)(ok: Order => Boolean)(using In[Store, R]): Option[Order] ! R =
    if ref.isEmpty then pure(None) else Store.find[R](ref.trim.toUpperCase).map(_.filter(ok))

  def trackLink[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R]): Response ! R =
    val ref = r.param("ref").getOrElse("").take(40)
    val key = r.param("k").getOrElse("")
    if key.isEmpty then pure(page(Track.search(c.site, l, ref, c.sitekey, failed = false)))
    else found[R](ref)(o => same(o.key, key)).flatMap {
      case Some(o) => Store.payment[R](Of.Order, o.ref).map(p =>
          page(Track.status(c.site, l, o, p.exists(_.paid))))
      case None => pure(page(Track.search(c.site, l, ref, c.sitekey, failed = true)))
    }

  def trackForm[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R], In[Captcha, R], In[Clock, R])
    : Response ! R =
    val f = fields(r)
    val ref = field(f, "ref").take(40)
    for
      now <- Clock.now[R]
      allowed <- Store.allow[R](s"track:${ip(r)}", 20, tenMinutes, now)
      human <- if allowed then Captcha.verify[R](f.getOrElse("cf-turnstile-response", ""), ip(r)) else pure(false)
      o <- if allowed && human then found[R](ref)(o => Contact.matches(field(f, "contact"), o.contact))
        else pure(None)
      paid <- o.fold(pure[Option[Payment], R](None))(x => Store.payment[R](Of.Order, x.ref))
    yield o match
      case Some(o) => page(Track.status(c.site, l, o, paid.exists(_.paid)))
      case None => page(Track.search(c.site, l, ref, c.sitekey, failed = true))

  /** the receipt of a paid order or booking (specs/shop.md "Money"): by its payment's record, numbered; an order the
   * old workflow moved to `paid` by the old receipt, as it was */
  def receipt[R[_]](c: Conf, r: Request)(using In[Store, R]): Response ! R =
    val ref = r.param("ref").getOrElse("").trim
    val key = r.param("k").getOrElse("")
    val none = Web.page("Rachunek dostępny po opłaceniu zamówienia.", 404)
    found[R](ref)(o => same(o.key, key)).flatMap {
      case Some(o) => Store.payment[R](Of.Order, o.ref).map {
          case Some(p) if p.paid => page(Receipt.page(c, p, o.name, o.offerKey, Day.of(o.placed)))
          case Some(_) => none
          case None if o.status == Status.Paid => page(Track.receipt(c.site, o, c.contact))
          case None => none
        }
      case None => if ref.isEmpty then pure(none) else Store.booking[R](ref.toUpperCase).flatMap {
          case Some(b) if same(b.key, key) => Store.payment[R](Of.Booking, b.ref).map {
              case Some(p) if p.paid => page(Receipt.page(c, p, b.name, b.service, Day.of(b.start)))
              case _ => none
            }
          case _ => pure(none)
        }
    }

  /** equal, in a time that does not tell where they differ */
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
