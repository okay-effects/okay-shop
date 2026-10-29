package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.{Durable, Storage}
import okay.shop.booking.Slots
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
 *   site/booking/<ref>           a booking as it is now (its master, start, minutes, status, reminded; its mode,
 *                                place, address, buffers, price)
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
  private def bookingKey(ref: String) = s"$scope/booking/$ref"
  private val day = 86400000L

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

  /** every booking, as the step sees them */
  private def allBookings[D[_]: Has[Durable]]: Vector[Booking] ! D =
    Durable.keys[D](s"$scope/booking/").flatMap(keys => keys.foldLeft(pure[Vector[Booking], D](Vector.empty))(
      (acc, k) => acc.flatMap(bs => Durable.get[Json, D](k).map {
        case Some(j: Json.Obj) => bs :+ Codec.booking(j)
        case _ => bs
      })))

  /** the object's alarm at the earliest reminder still due: a day before a booking not yet reminded */
  private def nextReminder[D[_]: Has[Durable]](all: Vector[Booking], now: Long): Unit ! D =
    all.filter(b => b.active && !b.reminded && b.start > now).map(_.start - day).minOption match
      case Some(at) => Durable.wakeAt[D](at.max(now + 1000))
      case None => unit[D]

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
      case Store.Book(b, schedule, now) => step[Either[String, Booking]](allBookings[D].flatMap { all =>
          if !Slots.isFree(schedule, b.minutes, all, now, b.start, b.before, b.after) then pure(Left("taken"))
          else
            // a reminder due already (booked within a day) is not sent: the booking's own letter says the time
            val kept = b.copy(reminded = b.start - day <= now)
            Durable.put[Json, D](bookingKey(b.ref), Codec.booking(kept))
              .andThen(nextReminder[D](all :+ kept, now)).as(Right(kept))
        })
      case Store.BookWith(b, candidates, masters, now) => step[Either[String, Booking]](allBookings[D].flatMap { all =>
          candidates.find((k, sch) => Slots.isFree(sch, b.minutes, Masters.taken(all, k, masters), now, b.start,
            b.before, b.after)) match
            case None => pure(Left("taken"))
            case Some((k, _)) =>
              val kept = b.copy(master = k, reminded = b.start - day <= now)
              Durable.put[Json, D](bookingKey(b.ref), Codec.booking(kept))
                .andThen(nextReminder[D](all :+ kept, now)).as(Right(kept))
        })
      case Store.RebookWith(ref, start, candidates, masters, now) => step[Either[String, Booking]](
        allBookings[D].flatMap { all =>
          all.find(_.ref == ref).filter(_.active) match
            case None => pure(Left("no such booking"))
            case Some(b) =>
              val others = all.filterNot(_.ref == ref)
              candidates.find((k, sch) => Slots.isFree(sch, b.minutes, Masters.taken(others, k, masters), now, start,
                b.before, b.after)) match
                case None => pure(Left("taken"))
                case Some((k, _)) =>
                  val moved = b.copy(start = start, master = k, reminded = start - day <= now)
                  Durable.put[Json, D](bookingKey(ref), Codec.booking(moved))
                    .andThen(nextReminder[D](others :+ moved, now)).as(Right(moved))
        })
      case Store.FindBooking(ref) => read[R](List(bookingKey(ref))).map(_.get(bookingKey(ref)).map(Codec.booking))
      case Store.Bookings(from, until) => readAll[R](s"$scope/booking/").map(_.map((_, j) => Codec.booking(j))
          .filter(b => b.start >= from && b.start < until).sortBy(_.start).toVector)
      case Store.Rebook(ref, start, schedule, now) => step[Either[String, Booking]](allBookings[D].flatMap { all =>
          all.find(_.ref == ref).filter(_.active) match
            case None => pure(Left("no such booking"))
            case Some(b) =>
              val others = all.filterNot(_.ref == ref)
              if !Slots.isFree(schedule, b.minutes, others, now, start, b.before, b.after) then pure(Left("taken"))
              else
                val moved = b.copy(start = start, reminded = start - day <= now)
                Durable.put[Json, D](bookingKey(ref), Codec.booking(moved))
                  .andThen(nextReminder[D](others :+ moved, now)).as(Right(moved))
        })
      case Store.CancelBooking(ref, now) => step[Option[Booking]](allBookings[D].flatMap { all =>
          all.find(_.ref == ref).filter(_.active) match
            case None => pure(None)
            case Some(b) =>
              val cancelled = b.copy(status = BookingStatus.Cancelled)
              Durable.put[Json, D](bookingKey(ref), Codec.booking(cancelled))
                .andThen(nextReminder[D](all.filterNot(_.ref == ref) :+ cancelled, now)).as(Some(cancelled))
        })
      case Store.Remind(now) => step[Vector[Booking]](allBookings[D].flatMap { all =>
          val due = all.filter(b => b.active && !b.reminded && b.start - day <= now && b.start > now)
          val marked = due.map(_.copy(reminded = true))
          marked.foldLeft(unit[D])((p, b) => p.andThen(Durable.put[Json, D](bookingKey(b.ref), Codec.booking(b))))
            .andThen(nextReminder[D](all.filterNot(b => due.exists(_.ref == b.ref)) ++ marked, now)).as(marked)
        })
