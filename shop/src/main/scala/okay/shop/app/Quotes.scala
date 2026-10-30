package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.*
import okay.shop.html.{Conf, Letters, QuotePage}
import okay.shop.model.*

/**
 * INQUIRY AND QUOTE (specs/shop.md): the client describes the job (Turnstile, a limit, the address checked as a
 * booking's), the workshop answers with a price or a range and maybe a time, the client accepts — a booking or an
 * order — or declines by the quote's link. Each step told by `Mail`
 */
object Quotes:
  def manageLink(c: Conf, q: Quote): String =
    s"${c.origin}/${q.lang.code}/quote?ref=${okay.http.Query.encode(q.ref)}&k=${okay.http.Query.encode(q.key)}"

  def ask[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Captcha, R], In[Ids, R], In[Clock, R],
    In[Notify, R]): Response ! R =
    val f = Web.fields(r)
    def g(k: String, n: Int) = f.getOrElse(k, "").trim.take(n)
    val name = g("name", 120)
    val contact = g("contact", 200)
    val description = f.getOrElse("description", "").trim
    val offer = c.site.offer(g("service", 60)).filter(o => o.quote && !o.hidden)
    val mode = offer.flatMap(o => c.site.work.map(_.modesOf(o.key))).getOrElse(Vector.empty)
    val m = Mode.parse(g("mode", 20)).filter(mode.contains).orElse(mode.headOption)
    // the client's address when the work is at theirs: given whole, inside the area
    val address = if !m.contains(Mode.AtClient) then Right(None) else
      val a = Address("", g("street", 120), g("postcode", 6), g("city", 60), g("notes", 200))
      if a.street.isEmpty || a.city.isEmpty || !a.postcode.matches("[0-9]{2}-[0-9]{3}") then Left("address")
      else if !c.site.work.flatMap(_.area).exists(_.inside(a.city, a.postcode)) then Left("area")
      else Right(Some(a))
    if name.isEmpty then pure(Web.refused("name"))
    else if !Contact.valid(contact) then pure(Web.refused("contact"))
    else if offer.isEmpty then pure(Web.refused("service"))
    else if description.isEmpty || description.length > 2000 then pure(Web.refused("description"))
    else if !f.get("rodo_consent").exists(v => v.nonEmpty && v != "false") then pure(Web.refused("consent"))
    else address match
      case Left(why) => pure(Web.refused(why, if why == "area" then 409 else 400))
      case Right(where) => Clock.now[R].flatMap { now =>
          Store.allow[R](s"quote:${Web.ip(r)}", 5, Api.tenMinutes, now).flatMap { allowed =>
            if !allowed then pure(Web.refused("limit", 429))
            else Captcha.verify[R](f.getOrElse("cf-turnstile-response", ""), Web.ip(r)).flatMap { human =>
              if !human then pure(Web.refused("captcha", 403))
              else for
                ref <- Ids.ref[R](c.site.refPrefix + "Q")
                key <- Ids.secret[R]
                q = Quote(ref, key, offer.get.key, name, contact, description, m, where, g("preferred", 200),
                  Lang.parse(g("lang", 5)).getOrElse(c.site.lang), Consent(now, c.policy), now)
                _ <- Store.ask[R](q)
                _ <- Mail.toOwner[R](Letters.quoteOwner(c, q, "Новий запит на оцінку"))
                _ <- Notify.tell[R](Notice.Asked(q))
                _ <- q.email.fold(unit[R])(to => Mail.toClient[R](to, Letters.quoteAsked(c, q)).map(_ => ()))
              yield Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(ref), "key" -> Json.str(key),
                "manage" -> Json.str(manageLink(c, q)), "quote" -> Json.bool(true)))
            }
          }
        }

  /** the quote of the request's ref, when its key is right; else none */
  private def owned[R[_]](f: Map[String, String])(using In[Store, R]): Option[Quote] ! R =
    val ref = f.getOrElse("ref", "").trim.toUpperCase
    if ref.isEmpty then pure(None)
    else Store.quote[R](ref).map(_.filter(q => Api.same(q.key, f.getOrElse("k", f.getOrElse("key", "")))))

  def page[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R]): Response ! R =
    owned[R](Map("ref" -> r.param("ref").getOrElse(""), "k" -> r.param("k").getOrElse(""))).map {
      case Some(q) => Web.page(QuotePage.page(c, l, q))
      case None => Web.page(QuotePage.missing(c, l), 404)
    }

  def decline[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Notify, R]): Response ! R =
    owned[R](Web.fields(r)).flatMap {
      case None => pure(Web.refused("no such quote", 404))
      case Some(q) => Store.decide[R](q.ref, accepted = false, "").flatMap {
          case None => pure(Web.refused("not quoted", 409))
          case Some(d) => Mail.toOwner[R](Letters.quoteOwner(c, d, "Оцінку відхилено"))
              .flatMap(_ => Notify.tell[R](Notice.Decided(d, accepted = false, "")))
              .as(Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(d.ref))))
        }
    }

  /** accepted: a booking when it has a time and its service is booked (the store's check), else an order */
  def accept[R[_]](c: Conf, r: Request)(using In[Store, R], In[Mail, R], In[Ids, R], In[Clock, R], In[Notify, R])
    : Response ! R =
    owned[R](Web.fields(r)).flatMap {
      case None => pure(Web.refused("no such quote", 404))
      case Some(q) if q.status != QuoteStatus.Quoted => pure(Web.refused("not quoted", 409))
      case Some(q) => Clock.now[R].flatMap { now =>
          val plan = Option.when(q.start > 0)(()).flatMap(_ => Bookings.plan(c, q.service, q.mode.fold("")(_.key), "")
            .toOption).map(p => p.copy(address = q.address.orElse(p.address), price = q.price))
          plan match
            case Some(p) => Bookings.reserve[R](c, p, q.start, q.name, q.contact, q.lang, "", now).flatMap {
                case Left(why) => pure(Web.refused(why, 409))
                case Right(b) => Store.decide[R](q.ref, accepted = true, b.ref).flatMap(_ =>
                  Mail.toOwner[R](Letters.bookedOwner(c, b))).flatMap(_ => Notify.tell[R](Notice.Decided(q,
                    accepted = true, b.ref))).flatMap(_ => b.email.fold(unit[R])(to =>
                    Mail.toClient[R](to, Letters.booked(c, b)).map(_ => ()))).as(Web.json(Json.obj(
                      "ok" -> Json.bool(true), "ref" -> Json.str(b.ref), "booking" -> Json.str(
                        Letters.manageLink(c, b)))))
              }
            case None =>
              for
                ref <- Ids.ref[R](c.site.refPrefix)
                key <- Ids.secret[R]
                o = Order(ref, c.site.id, key, q.service, q.name, q.contact, q.description, "", "", "", "", "",
                  q.lang, q.consent, now, Status.Accepted, amount = q.price)
                _ <- Store.place[R](o)
                _ <- Store.decide[R](q.ref, accepted = true, ref)
                _ <- Mail.toOwner[R](Letters.owner(c, o))
                _ <- Notify.tell[R](Notice.Decided(q, accepted = true, ref))
                _ <- o.email.fold(unit[R])(to => Mail.toClient[R](to, Letters.confirmation(c, o)).map(_ => ()))
              yield Web.json(Json.obj("ok" -> Json.bool(true), "ref" -> Json.str(ref),
                "track" -> Json.str(Letters.trackLink(c, o))))
        }
    }

  // ---- THE WORKSHOP'S

  def list[R[_]](c: Conf)(using In[Store, R]): Response ! R =
    Store.quotes[R](200).map(qs => Web.json(Json.obj("quotes" -> Json.Arr(qs.map(q => Json.Obj(
      okay.shop.handlers.Codec.quote(q).fields :+ ("serviceName" -> Json.str(c.site.offer(q.service)
        .map(_.name(Lang.pl)).getOrElse(q.service)))))))))

  /** the workshop's answer: a price (zł), maybe up to a second, maybe a time (`2026-11-03 14:00`), a note */
  def reply[R[_]](c: Conf, f: Map[String, String])(using In[Store, R], In[Mail, R]): Response ! R =
    val price = Money.parse(f.getOrElse("price", "")).filter(p => p > 0 && p <= 100000000L)
    val to = Some(f.getOrElse("priceTo", "").trim).filter(_.nonEmpty).map(Money.parse)
    val when = Some(f.getOrElse("start", "").trim).filter(_.nonEmpty).map(Day.parse)
    if price.isEmpty then pure(Web.refused("price"))
    else if to.exists(x => x.isEmpty || x.exists(_ < price.get)) then pure(Web.refused("priceTo"))
    else if when.exists(_.isEmpty) then pure(Web.refused("start"))
    else Store.reply[R](f.getOrElse("ref", ""), price.get, to.flatten.getOrElse(0L), when.flatten.getOrElse(0L),
      f.getOrElse("note", "").trim.take(1000)).flatMap {
      case None => pure(Web.refused("no such quote", 404))
      case Some(q) => q.email.fold(pure(false))(to => Mail.toClient[R](to, Letters.quoted(c, q))).map(sent =>
          Web.json(Json.obj("ok" -> Json.bool(true), "sent" -> Json.bool(sent))))
    }
