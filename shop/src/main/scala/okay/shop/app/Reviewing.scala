package okay.shop.app

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.Clock
import okay.serverless.cloud.{Request, Response}
import okay.shop.effects.*
import okay.shop.handlers.Codec
import okay.shop.html.{Conf, Letters, ReviewPage}
import okay.shop.model.*

/**
 * CLIENTS' REVIEWS (specs/shop.md "Reviews"), a program of the shop's effects: a client asked once — two hours after a
 * visit (the object's alarm), at once when an order is delivered or paid — by a one-use link; the review written by
 * it; the workshop's list, the owner's reply, hide and show. Nothing when the site does not take reviews
 */
object Reviewing:
  def hash(secret: String): String = Sha256.hex(secret.getBytes("UTF-8"))

  /** a client asked for a review of `of`/`ref`, once: the link kept, the letter sent; false when asked before */
  def invite[R[_]](c: Conf, of: Of, ref: String, lang: Lang, service: String, email: String, now: Long)(
    using In[Store, R], In[Mail, R], In[Ids, R]): Boolean ! R =
    Ids.secret[R].flatMap(secret => Store.invite[R](of, ref, hash(secret), now + Reviews.linkLife, now).flatMap {
      case false => pure(false)
      case true =>
        val link = s"${c.origin}/${lang.code}/review?t=${okay.http.Query.encode(secret)}"
        val name = c.site.offer(service).map(_.name(lang)).getOrElse(service)
        Mail.toClient[R](email, Letters.reviewAsk(c, lang, name, link)).as(true)
    })

  /** an order done (delivered or paid): its client asked, when the site takes reviews and the client has an email */
  def done[R[_]](c: Conf, o: Order, now: Long)(using In[Store, R], In[Mail, R], In[Ids, R]): Unit ! R =
    if !c.site.reviews || o.status == Status.Cancelled then unit[R]
    else o.email.fold(unit[R])(to => invite[R](c, Of.Order, o.ref, o.lang, o.offerKey, to, now).as(()))

  /** a booking made or moved: its review asked two hours after it ends */
  def later[R[_]](c: Conf, b: Booking, now: Long)(using In[Store, R]): Unit ! R =
    if !c.site.reviews then unit[R] else Store.reviewLater[R](b.ref, b.end + Reviews.after, now)

  /** the object's alarm: the visits whose review is due — kept (not cancelled, nor a no-show) and past — asked */
  def due[R[_]](c: Conf)(using In[Store, R], In[Mail, R], In[Ids, R], In[Clock, R]): Int ! R =
    if !c.site.reviews then pure(0)
    else Clock.now[R].flatMap(now => Store.reviewsDue[R](now).flatMap(refs => refs.foldLeft(pure[Int, R](0))(
      (acc, ref) => acc.flatMap(n => Store.booking[R](ref).flatMap {
        case Some(b) if b.status == BookingStatus.Booked && b.end <= now && b.email.isDefined =>
          invite[R](c, Of.Booking, b.ref, b.lang, b.service, b.email.get, now).map(asked => if asked then n + 1 else n)
        case _ => pure(n)
      }))))

  // ---- THE CLIENT'S PAGE AND FORM

  private def what[R[_]](of: Of, ref: String)(using In[Store, R]): Option[(String, String, Long)] ! R = of match
    case Of.Order => Store.find[R](ref).map(_.map(o => (o.offerKey, "", o.placed)))
    case _ => Store.booking[R](ref).map(_.map(b => (b.service, b.master, b.start)))

  /** `GET /{lang}/review?t=`: the form, or "used or past" */
  def page[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R], In[Clock, R]): Response ! R =
    val t = r.param("t").getOrElse("").take(64)
    shown[R](c, l, t, "", "", "", "")

  private def shown[R[_]](c: Conf, l: Lang, t: String, wrong: String, rating: String, text: String, name: String)(
    using In[Store, R], In[Clock, R]): Response ! R =
    Clock.now[R].flatMap(now => (if t.isEmpty then pure(None) else Store.invitation[R](hash(t), now)).flatMap {
      case None => pure(Web.page(ReviewPage.gone(c.site, l), 404))
      case Some((of, ref)) => what[R](of, ref).map {
          case None => Web.page(ReviewPage.gone(c.site, l), 404)
          case Some((service, _, at)) => Web.page(ReviewPage.form(c.site, l, t, c.site.offer(service).fold(service)(
            _.name(l)), Day.of(at), wrong, rating, text, name), if wrong.isEmpty then 200 else 400)
        }
    })

  /** `POST /{lang}/review` (a plain form: `t`, `rating`, `text`, `name`): the review written, the link used up */
  def write[R[_]](c: Conf, l: Lang, r: Request)(using In[Store, R], In[Clock, R], In[Notify, R]): Response ! R =
    val f = Web.fields(r)
    val t = f.getOrElse("t", "").take(64)
    val text = f.getOrElse("text", "").take(Reviews.textMax * 2)
    val name = f.getOrElse("name", "").take(Reviews.nameMax * 2)
    Reviews.check(f.getOrElse("rating", ""), text) match
      case Left(why) => shown[R](c, l, t, why, f.getOrElse("rating", ""), text, name)
      case Right((rating, said)) => Clock.now[R].flatMap(now =>
        (if t.isEmpty then pure(None) else Store.invitation[R](hash(t), now)).flatMap {
          case None => pure(Web.page(ReviewPage.gone(c.site, l), 404))
          case Some((of, ref)) => what[R](of, ref).flatMap {
              case None => pure(Web.page(ReviewPage.gone(c.site, l), 404))
              case Some((service, master, _)) =>
                val review = Review(of, ref, service, master, rating, said, Reviews.shownName(name), l, now)
                Store.write[R](review, hash(t), now).flatMap {
                  case Left(_) => pure(Web.page(ReviewPage.gone(c.site, l), 409))
                  case Right(x) => Notify.tell[R](Notice.Reviewed(x)).as(Web.page(ReviewPage.thanks(c.site, l)))
                }
            }
        })

  // ---- THE WORKSHOP'S

  /** a review as the workshop reads it: its fields, its id, its service's name */
  def json(c: Conf, x: Review): Json.Obj = Json.Obj(Codec.review(x).fields ++ Vector("id" -> Json.str(x.id),
    "serviceName" -> Json.str(c.site.offer(x.service).fold(x.service)(_.name(Lang.pl)))))

  /** `GET <desk>/api/reviews`: every review, newest first; the average and the counts; whether the person moderates */
  def list[R[_]](c: Conf, email: String)(using In[Store, R]): Response ! R = Store.reviews[R].map { all =>
    val r = Reviews.rated(all)
    Web.json(Json.obj("reviews" -> Json.Arr(all.map(json(c, _))), "average" -> Json.str(r.fold("")(_.score)),
      "count" -> Json.num(r.fold(0)(_.count)), "hidden" -> Json.num(all.count(!_.shown)),
      "owner" -> Json.bool(c.owner(email))))
  }

  /** `POST <desk>/api/review {id, action: reply|hide|show, value}`: the owner's only */
  def moderate[R[_]](c: Conf, f: Map[String, String], email: String, now: Long)(using In[Store, R]): Response ! R =
    Store.moderate[R](f.getOrElse("id", "").trim, f.getOrElse("action", ""), f.getOrElse("value", ""), email,
      c.owner(email), now).map {
      case Left("owner") => Web.refused("owner", 403)
      case Left("no such review") => Web.refused("no such review", 404)
      case Left(why) => Web.refused(why)
      case Right(x) => Web.json(Json.obj("review" -> json(c, x)))
    }

  /** `<desk>/reviews/`: the screen, for the staff (the owner's controls for the owner) */
  def pages[R[_]](c: Conf, r: Request)(using In[Store, R], In[Clock, R]): Response ! R =
    val l = r.param("lang").flatMap(Lang.parse).orElse(Web.cookie(r, "lang").flatMap(Lang.parse)).getOrElse(Lang.pl)
    Workshop.person[R](c, r).flatMap {
      case None => pure(Response.redirect(s"${c.desk.path}/", 303).header("Cache-Control", "no-store"))
      case Some(email) => Store.reviews[R].map(all => Web.page(ReviewPage.desk(l, c, all, c.owner(email))))
    }

  /** the workshop's link, in its languages */
  val link = "Opinie · Відгуки · Reviews"
