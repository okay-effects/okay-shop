package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.{Durable, Storage}
import okay.shop.booking.Slots
import okay.shop.effects.*
import okay.shop.invoice.{Buyer, InvoiceCodec, InvoiceKind, InvoiceMath}
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
 *   site/quote/<ref>             an inquiry for a quote, as it is now (asked, quoted, decided)
 *   site/booking/<ref>           a booking as it is now (its master, start, minutes, status, reminded; its mode,
 *                                place, address, buffers, price)
 *   site/pay/<of>/<ref>          a payment of an order, a booking or manual income, with its log
 *   site/receipts/<year>         the last receipt number given in a year
 *   site/buyer/<kind>/<ref>      an order's, a booking's or a quote's buyer data (specs/shop.md "Invoices")
 *   site/invoice/<id>            an invoice (its id the shop's sequence, six digits), its KSeF side
 *   site/invoiceof/<of>/<ref>    the id of an order's or a booking's VAT invoice
 *   site/invoiceseq/id           the last id given; site/invoiceseq/<series> the last number of a series
 *   site/ksefq/<id>              an invoice to send to KSeF again: when (the object's alarm)
 *   site/upo/<id>                its UPO, as KSeF gave it
 *   site/review/<of>/<ref>      a client's review of an order or a booking, its reply, hidden or not, its log
 *   site/reviewlink/<hash>       a review link not yet used: {of, ref, until}
 *   site/reviewask/<of>/<ref>   when its review was asked (asked once)
 *   site/reviewq/<ref>           a booking's review to ask for: when (the object's alarm)
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
  private def quoteKey(ref: String) = s"$scope/quote/$ref"
  private def payKey(of: Of, ref: String) = s"$scope/pay/${of.key}/$ref"
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

  /** the object's alarm at the earliest reminder still due — a day before a booking not yet reminded — or KSeF's next
   * try of an invoice in its queue (specs/shop.md "Invoices"), whichever comes first */
  private def nextReminder[D[_]: Has[Durable]](all: Vector[Booking], now: Long): Unit ! D =
    // and the reviews to ask for (specs/shop.md "Reviews")
    Durable.keys[D](s"$scope/ksefq/").flatMap(a => Durable.keys[D](s"$scope/reviewq/").map(a ++ _)).flatMap(ks =>
      ks.foldLeft(pure[Vector[Long], D](Vector.empty))((acc, k) =>
      acc.flatMap(ts => Durable.get[Long, D](k).map(ts ++ _)))).flatMap { tries =>
      (all.filter(b => b.active && !b.reminded && b.start > now).map(_.start - day) ++ tries).minOption match
        case Some(at) => Durable.wakeAt[D](at.max(now + 1000))
        case None => unit[D]
    }

  /** an invoice found by what it is of (a VAT one), or kept on the one it corrects (a correction) */
  private def linked[D[_]: Has[Durable]](made: okay.shop.invoice.Invoice): Unit ! D =
    if made.kind == InvoiceKind.Vat then Durable.put[String, D](s"$scope/invoiceof/${made.of.key}/${made.ref}", made.id)
    else made.corrects.fold(unit[D])(c => Durable.get[Json, D](s"$scope/invoice/${c.id}").flatMap {
      case Some(o: Json.Obj) =>
        val orig = InvoiceCodec.invoice(o)
        Durable.put[Json, D](s"$scope/invoice/${c.id}", InvoiceCodec.invoice(orig.copy(corrections =
          orig.corrections :+ made.id)))
      case _ => unit[D]
    })

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
      case Store.Seat(b, schedule, o, now) => step[Either[String, Booking]](allBookings[D].flatMap { all =>
          if !Slots.hasSeat(schedule, o, all, now, b.start) then pure(Left("full"))
          else
            val kept = b.copy(reminded = b.start - day <= now)
            Durable.put[Json, D](bookingKey(b.ref), Codec.booking(kept))
              .andThen(nextReminder[D](all :+ kept, now)).as(Right(kept))
        })
      case Store.Reseat(ref, start, schedule, o, now) => step[Either[String, Booking]](allBookings[D].flatMap { all =>
          all.find(_.ref == ref).filter(_.active) match
            case None => pure(Left("no such booking"))
            case Some(b) =>
              val others = all.filterNot(_.ref == ref)
              if !Slots.hasSeat(schedule, o, others, now, start) then pure(Left("full"))
              else
                val moved = b.copy(start = start, reminded = start - day <= now)
                Durable.put[Json, D](bookingKey(ref), Codec.booking(moved))
                  .andThen(nextReminder[D](others :+ moved, now)).as(Right(moved))
        })
      case Store.Ask(q) => step[Unit](Durable.put[Json, D](quoteKey(q.ref), Codec.quote(q)))
      case Store.FindQuote(ref) => read[R](List(quoteKey(ref))).map(_.get(quoteKey(ref)).map(Codec.quote))
      case Store.Quotes(limit) => readAll[R](s"$scope/quote/").map(_.map((_, j) => Codec.quote(j))
          .sortBy(q => -q.placed).take(limit).toVector)
      case Store.Reply(ref, price, priceTo, start, note) => step[Option[Quote]](Durable.get[Json, D](quoteKey(ref))
        .flatMap {
          case Some(j: Json.Obj) =>
            val q = Codec.quote(j)
            if q.status != QuoteStatus.Asked && q.status != QuoteStatus.Quoted then pure(None)
            else
              val quoted = q.copy(status = QuoteStatus.Quoted, price = price, priceTo = priceTo, start = start,
                note = note)
              Durable.put[Json, D](quoteKey(ref), Codec.quote(quoted)).as(Some(quoted))
          case _ => pure(None)
        })
      case Store.Decide(ref, accepted, result) => step[Option[Quote]](Durable.get[Json, D](quoteKey(ref)).flatMap {
          case Some(j: Json.Obj) if Codec.quote(j).status == QuoteStatus.Quoted =>
            val decided = Codec.quote(j).copy(status = if accepted then QuoteStatus.Accepted else QuoteStatus.Declined,
              result = result)
            Durable.put[Json, D](quoteKey(ref), Codec.quote(decided)).as(Some(decided))
          case _ => pure(None)
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
      case Store.Pay(m, start, who, owner, cancelled, today, now) => step[Either[String, Payment]](
        Durable.get[Json, D](payKey(m.of, m.ref)).flatMap { kept =>
          val current = kept.collect { case j: Json.Obj => Codec.payment(j) }.orElse(start)
          Payments.mark(current, m, who, owner, cancelled, today, now) match
            case Left(why) => pure(Left(why))
            // the first time paid: the year's next number, in this step
            case Right(p) if p.paid && p.receipt.isEmpty =>
              val counter = s"$scope/receipts/${p.day.take(4)}"
              Durable.get[Long, D](counter).flatMap { n =>
                val numbered = p.copy(receipt = Payments.receipt(p.day.take(4), n.getOrElse(0L) + 1))
                Durable.put[Long, D](counter, n.getOrElse(0L) + 1)
                  .andThen(Durable.put[Json, D](payKey(m.of, m.ref), Codec.payment(numbered))).as(Right(numbered))
              }
            case Right(p) => Durable.put[Json, D](payKey(m.of, m.ref), Codec.payment(p)).as(Right(p))
        })
      case Store.FindPayment(of, ref) => read[R](List(payKey(of, ref))).map(_.get(payKey(of, ref)).map(Codec.payment))
      case Store.Payments() => readAll[R](s"$scope/pay/").map(_.map((_, j) => Codec.payment(j)).toVector)
      case Store.NoShow(ref, now) => step[Option[Booking]](Durable.get[Json, D](bookingKey(ref)).flatMap {
          case Some(j: Json.Obj) if Codec.booking(j).active && Codec.booking(j).start < now =>
            val missed = Codec.booking(j).copy(status = BookingStatus.NoShow)
            Durable.put[Json, D](bookingKey(ref), Codec.booking(missed)).as(Some(missed))
          case _ => pure(None)
        })
      case Store.KeepBuyer(kind, ref, b) => step[Unit](b match
          case Some(x) => Durable.put[Json, D](s"$scope/buyer/$kind/$ref", Buyer.toJson(x))
          case None => Durable.delete[D](s"$scope/buyer/$kind/$ref"))
      case Store.FindBuyer(kind, ref) => read[R](List(s"$scope/buyer/$kind/$ref")).map(_.get(
          s"$scope/buyer/$kind/$ref").map(Buyer.fromJson))
      case Store.Issue(d, pattern) => step[Either[String, okay.shop.invoice.Invoice]](
        Durable.get[String, D](s"$scope/invoiceof/${d.of.key}/${d.ref}").flatMap { had =>
          if d.kind == InvoiceKind.Vat && had.isDefined then pure(Left("issued"))
          else
            val series = InvoiceMath.series(pattern, d.issued)
            for
              id <- Durable.get[Long, D](s"$scope/invoiceseq/id").map(_.getOrElse(0L) + 1)
              n <- Durable.get[Long, D](s"$scope/invoiceseq/$series").map(_.getOrElse(0L) + 1)
              made = d.copy(id = f"$id%06d", number = InvoiceMath.number(pattern, d.issued, n))
              _ <- Durable.put[Long, D](s"$scope/invoiceseq/id", id)
              _ <- Durable.put[Long, D](s"$scope/invoiceseq/$series", n)
              _ <- Durable.put[Json, D](s"$scope/invoice/${made.id}", InvoiceCodec.invoice(made))
              _ <- linked[D](made)
            yield Right(made)
        })
      case Store.FindInvoice(id) => read[R](List(s"$scope/invoice/$id")).map(_.get(s"$scope/invoice/$id")
          .map(InvoiceCodec.invoice))
      case Store.InvoiceOf(of, ref) =>
        val k = s"$scope/invoiceof/${of.key}/$ref"
        perform[Storage](Storage.Read(List(k))).flatMap(_.get(k).map(_.value).flatMap(v => Descent.parse(v).toOption)
          .collect { case Json.Str(id) => id } match
          case Some(id) => read[R](List(s"$scope/invoice/$id")).map(_.get(s"$scope/invoice/$id")
              .map(InvoiceCodec.invoice))
          case None => pure(None))
      case Store.Invoices() => readAll[R](s"$scope/invoice/").map(_.map((_, j) => InvoiceCodec.invoice(j)).toVector)
      case Store.KsefMarked(id, k) => step[Option[okay.shop.invoice.Invoice]](
        Durable.get[Json, D](s"$scope/invoice/$id").flatMap {
          case Some(j: Json.Obj) =>
            val marked = InvoiceCodec.invoice(j).copy(ksef = k)
            Durable.put[Json, D](s"$scope/invoice/$id", InvoiceCodec.invoice(marked))
              .andThen(if k.next > 0 then Durable.put[Long, D](s"$scope/ksefq/$id", k.next)
                else Durable.delete[D](s"$scope/ksefq/$id"))
              .andThen(allBookings[D].flatMap(all => nextReminder[D](all, k.at)))
              .as(Some(marked))
          case _ => pure(None)
        })
      case Store.KeepUpo(id, xml) => step[Unit](Durable.put[String, D](s"$scope/upo/$id", xml))
      case Store.FindUpo(id) =>
        perform[Storage](Storage.Read(List(s"$scope/upo/$id"))).map(_.get(s"$scope/upo/$id").map(_.value)
          .flatMap(v => Descent.parse(v).toOption).collect { case Json.Str(x) => x })
      // ---- REVIEWS (specs/shop.md "Reviews")
      case Store.ReviewLater(ref, at, now) => step[Unit](Durable.put[Long, D](s"$scope/reviewq/$ref", at)
        .andThen(allBookings[D].flatMap(all => nextReminder[D](all, now))))
      case Store.ReviewsDue(now) => step[Vector[String]](Durable.keys[D](s"$scope/reviewq/").flatMap(ks =>
        ks.foldLeft(pure[Vector[String], D](Vector.empty))((acc, k) => acc.flatMap(due => Durable.get[Long, D](k)
          .flatMap {
            case Some(at) if at <= now => Durable.delete[D](k).as(due :+ k.stripPrefix(s"$scope/reviewq/"))
            case _ => pure(due)
          }))).flatMap(due => allBookings[D].flatMap(all => nextReminder[D](all, now)).as(due)))
      case Store.Invite(of, ref, hash, until, now) => step[Boolean](
        Durable.get[Long, D](s"$scope/reviewask/${of.key}/$ref").flatMap {
          case Some(_) => pure(false)
          case None => Durable.put[Long, D](s"$scope/reviewask/${of.key}/$ref", now)
              .andThen(Durable.put[Json, D](s"$scope/reviewlink/$hash", Json.obj("of" -> Json.str(of.key),
                "ref" -> Json.str(ref), "until" -> Json.num(until)))).as(true)
        })
      case Store.Invitation(hash, now) => read[R](List(s"$scope/reviewlink/$hash")).map(_.get(
          s"$scope/reviewlink/$hash").flatMap(invitation(_, now)))
      case Store.Write(rv, hash, now) => step[Either[String, Review]](
        Durable.get[Json, D](s"$scope/reviewlink/$hash").flatMap {
          case Some(j: Json.Obj) if invitation(j, now).contains((rv.of, rv.ref)) =>
            val key = s"$scope/review/${rv.of.key}/${rv.ref}"
            Durable.get[Json, D](key).flatMap {
              case Some(_) => Durable.delete[D](s"$scope/reviewlink/$hash").as(Left("reviewed"))
              case None => Durable.put[Json, D](key, Codec.review(rv))
                  .andThen(Durable.delete[D](s"$scope/reviewlink/$hash")).as(Right(rv))
            }
          case _ => pure(Left("link"))
        })
      case Store.AllReviews() => readAll[R](s"$scope/review/").map(_.map((_, j) => Codec.review(j)).toVector
          .sortBy(r => -r.at))
      case Store.Moderate(id, action, value, who, owner, now) =>
        val key = s"$scope/review/${id.replaceFirst("-", "/")}"
        step[Either[String, Review]](Durable.get[Json, D](key).flatMap {
          case Some(j: Json.Obj) => Reviews.moderate(Codec.review(j), action, value, who, owner, now) match
              case Left(why) => pure(Left(why))
              case Right(x) => Durable.put[Json, D](key, Codec.review(x)).as(Right(x))
          case _ => pure(Left("no such review"))
        })
      case Store.Remind(now) => step[Vector[Booking]](allBookings[D].flatMap { all =>
          val due = all.filter(b => b.active && !b.reminded && b.start - day <= now && b.start > now)
          val marked = due.map(_.copy(reminded = true))
          marked.foldLeft(unit[D])((p, b) => p.andThen(Durable.put[Json, D](bookingKey(b.ref), Codec.booking(b))))
            .andThen(nextReminder[D](all.filterNot(b => due.exists(_.ref == b.ref)) ++ marked, now)).as(marked)
        })

  /** a review link's order or booking, while it is not past */
  private def invitation(j: Json.Obj, now: Long): Option[(Of, String)] =
    (j.get("of"), j.get("ref"), j.get("until")) match
      case (Some(Json.Str(of)), Some(Json.Str(ref)), Some(Json.Num(until))) if until.toLong >= now =>
        Of.parse(of).map(_ -> ref)
      case _ => None
