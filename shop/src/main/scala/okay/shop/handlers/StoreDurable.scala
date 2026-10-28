package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.{Durable, Storage}
import okay.shop.effects.*
import okay.shop.model.*

/**
 * `Store` ON okay-serverless' DURABLE STATE — in the site's object, its SQLite on Cloudflare (`ObjectStorage`), memory
 * in tests (`Memory`), the same keys:
 *
 *   site/order/<ref>             the order as it is now (JSON, the journal's `Codec`)
 *   site/event/<ref>/<n>         its events in order: {at, event} — the journal
 *   site/inquiry/<ref>           an inquiry
 *   site/hit/<key>/<window>      a rate limit's count in a window
 *   site/link/<hash>             a sign-in link not yet used: {email, until}
 *   site/session/<hash>          a session: {email, until}
 *
 * A change is ONE `Durable.step`: the event and the order it moves written together or not at all; a reading is a
 * read of what is committed, no step. Only the SHA-256 of a link's or a session's secret is a key
 */
object StoreDurable:
  val scope = "site"
  private def order(ref: String) = s"$scope/order/$ref"
  private def events(ref: String) = s"$scope/event/$ref/"
  private def inquiry(ref: String) = s"$scope/inquiry/$ref"
  private def hits(key: String) = s"$scope/hit/$key/"
  private def link(hash: String) = s"$scope/link/$hash"
  private def session(hash: String) = s"$scope/session/$hash"

  def run[R[_], Rest[_], A](p: A ! R)(using Split[Store, R, Rest], In[Storage, Rest]): A ! Rest =
    handle(p, Handler.general[Store, Rest, A, A](pure)([X] => (op: Store[X], k: X => A ! Rest) =>
      answer[Rest, X](op).flatMap(k)))

  // ---- what is committed, read

  private def read[R[_]](keys: List[String])(using In[Storage, R]): Map[String, Json.Obj] ! R =
    perform[Storage](Storage.Read(keys)).map(_.flatMap((k, v) =>
      Descent.parse(v.value).toOption.collect { case o: Json.Obj => k -> o }))
  private def readAll[R[_]](prefix: String)(using In[Storage, R]): List[(String, Json.Obj)] ! R =
    perform[Storage](Storage.Keys(prefix)).flatMap(keys => read[R](keys).map(m => keys.flatMap(k => m.get(k)
      .map(k -> _))))

  private def signin(o: Json.Obj): Signin =
    Signin(o.get("email").collect { case Json.Str(s) => s }.getOrElse(""),
      o.get("until").collect { case Json.Num(n) => n.toLong }.getOrElse(0L))
  private def signinJson(si: Signin): Json = Json.obj("email" -> Json.str(si.email), "until" -> Json.num(si.until))

  // ---- a change, one step

  /** an event appended to an order's journal, numbered after those there */
  private def append[D[_]: Has[Durable]](ref: String, at: Long, e: Event): Unit ! D =
    Durable.keys[D](events(ref)).flatMap(ks => Durable.put[Json, D](f"${events(ref)}${ks.size + 1}%06d",
      Json.obj("at" -> Json.num(at), "event" -> Codec.event(e))))

  private def answer[R[_], X](op: Store[X])(using In[Storage, R]): X ! R =
    type D[Y] = (R + Durable)[Y]
    def step[A](p: A ! D): A ! R = Durable.step[D, R, A](scope)(p)
    op match
      case Store.Place(o) => step[Unit](Durable.put[Json, D](order(o.ref), Codec.order(o))
        .andThen(append[D](o.ref, o.placed, Event.Placed(o))))
      case Store.Find(ref) => read[R](List(order(ref))).map(_.get(order(ref)).map(Codec.order))
      case Store.Record(ref, e, at) => step[Option[Order]](Durable.get[Json, D](order(ref)).flatMap {
          case Some(j: Json.Obj) =>
            val moved = Orders(Codec.order(j), e, at)
            Durable.put[Json, D](order(ref), Codec.order(moved)).andThen(append[D](ref, at, e)).as(Some(moved))
          case _ => pure(None)
        })
      case Store.History(ref) => readAll[R](events(ref)).map(_.flatMap((_, o) =>
          (o.get("at"), o.get("event")) match
            case (Some(Json.Num(at)), Some(e: Json.Obj)) => Codec.eventText(Json.print(e)).map(at.toLong -> _)
            case _ => None).toVector)
      case Store.Orders(limit) => readAll[R](s"$scope/order/").map(_.map((_, j) => Codec.order(j))
          .sortBy(o => -o.placed).take(limit).toVector)
      case Store.Inquire(i) => step[Unit](Durable.put[Json, D](inquiry(i.ref), Codec.inquiry(i))
        .andThen(append[D](i.ref, i.at, Event.Inquired(i))))
      case Store.Inquiries(limit) => readAll[R](s"$scope/inquiry/").map(_.map((_, j) => Codec.inquiry(j))
          .sortBy(i => -i.at).take(limit).toVector)
      case Store.Allow(key, max, window, now) =>
        val w = now / window
        // this window's count, one more; the windows before the last forgotten
        step[Boolean](Durable.keys[D](hits(key)).flatMap { ks =>
          val old = ks.filter(_.stripPrefix(hits(key)).toLongOption.exists(_ < w - 1))
          old.foldLeft(unit[D])((p, k) => p.andThen(Durable.delete[D](k))).andThen(
            Durable.get[Long, D](s"${hits(key)}$w").flatMap { n =>
              val next = n.getOrElse(0L) + 1
              Durable.put[Long, D](s"${hits(key)}$w", next).as(next <= max)
            })
        })
      case Store.KeepLink(hash, si) => step[Unit](Durable.put[Json, D](link(hash), signinJson(si)))
      case Store.UseLink(hash, now) => step[Option[Signin]](Durable.get[Json, D](link(hash)).flatMap {
          case Some(j: Json.Obj) => Durable.delete[D](link(hash)).as(Some(signin(j)).filter(_.until >= now))
          case _ => pure(None)
        })
      case Store.KeepSession(hash, si) => step[Unit](Durable.put[Json, D](session(hash), signinJson(si)))
      case Store.Session(hash, now) => read[R](List(session(hash))).map(_.get(session(hash)).map(signin)
          .filter(_.until >= now))
      case Store.EndSession(hash) => step[Unit](Durable.delete[D](session(hash)))
