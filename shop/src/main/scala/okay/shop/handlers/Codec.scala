package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.shop.model.*

/** THE JOURNAL'S JSON: an order, an inquiry, an event — written once, read back the same (a test holds it) */
object Codec:
  private def s(o: Json.Obj, k: String): String = o.get(k) match
    case Some(Json.Str(v)) => v
    case _ => ""
  private def n(o: Json.Obj, k: String): Long = o.get(k) match
    case Some(Json.Num(v)) => v.toLong
    case _ => 0L
  private def b(o: Json.Obj, k: String): Boolean = o.get(k).contains(Json.Bool(true))
  private def obj(text: String): Option[Json.Obj] = Descent.parse(text).toOption.collect { case o: Json.Obj => o }

  def order(o: Order): Json.Obj = Json.obj("ref" -> Json.str(o.ref), "site" -> Json.str(o.site),
    "key" -> Json.str(o.key), "offerKey" -> Json.str(o.offerKey), "name" -> Json.str(o.name),
    "contact" -> Json.str(o.contact), "need" -> Json.str(o.need), "handover" -> Json.str(o.handover),
    "deliveryMethod" -> Json.str(o.deliveryMethod), "paymentMethod" -> Json.str(o.paymentMethod),
    "itemValue" -> Json.str(o.itemValue), "fabric" -> Json.str(o.fabric), "lang" -> Json.str(o.lang.code),
    "consentAt" -> Json.num(o.consent.at), "policy" -> Json.str(o.consent.policy), "placed" -> Json.num(o.placed),
    "status" -> Json.str(o.status.key), "tracking" -> Json.str(o.tracking), "amount" -> Json.num(o.amount),
    "paidAt" -> Json.num(o.paidAt), "note" -> Json.str(o.note))

  def order(j: Json.Obj): Order = Order(s(j, "ref"), s(j, "site"), s(j, "key"), s(j, "offerKey"), s(j, "name"),
    s(j, "contact"), s(j, "need"), s(j, "handover"), s(j, "deliveryMethod"), s(j, "paymentMethod"),
    s(j, "itemValue"), s(j, "fabric"), Lang.parse(s(j, "lang")).getOrElse(Lang.pl),
    Consent(n(j, "consentAt"), s(j, "policy")), n(j, "placed"),
    Status.parse(s(j, "status")).getOrElse(Status.Received), s(j, "tracking"), n(j, "amount"), n(j, "paidAt"),
    s(j, "note"))

  def orderText(text: String): Option[Order] = obj(text).map(order)

  def inquiry(i: Inquiry): Json.Obj = Json.obj("ref" -> Json.str(i.ref), "site" -> Json.str(i.site),
    "name" -> Json.str(i.name), "contact" -> Json.str(i.contact), "subject" -> Json.str(i.subject),
    "kind" -> Json.str(i.kind), "body" -> Json.str(i.body), "confidential" -> Json.bool(i.confidential),
    "lang" -> Json.str(i.lang.code), "consentAt" -> Json.num(i.consent.at), "policy" -> Json.str(i.consent.policy),
    "at" -> Json.num(i.at))

  def inquiry(j: Json.Obj): Inquiry = Inquiry(s(j, "ref"), s(j, "site"), s(j, "name"), s(j, "contact"),
    s(j, "subject"), s(j, "kind"), s(j, "body"), b(j, "confidential"), Lang.parse(s(j, "lang")).getOrElse(Lang.pl),
    Consent(n(j, "consentAt"), s(j, "policy")), n(j, "at"))

  def inquiryText(text: String): Option[Inquiry] = obj(text).map(inquiry)

  def event(e: Event): Json.Obj = e match
    case Event.Placed(o) => Json.obj("kind" -> Json.str("placed"), "order" -> order(o))
    case Event.Moved(st, note, amount, tracking) => Json.obj("kind" -> Json.str("moved"),
      "status" -> Json.str(st.key), "note" -> Json.str(note), "amount" -> Json.num(amount),
      "tracking" -> Json.str(tracking))
    case Event.Emailed(to, subject, sent) => Json.obj("kind" -> Json.str("emailed"), "to" -> Json.str(to),
      "subject" -> Json.str(subject), "sent" -> Json.bool(sent))
    case Event.Inquired(i) => Json.obj("kind" -> Json.str("inquired"), "inquiry" -> inquiry(i))

  def eventText(text: String): Option[Event] = obj(text).flatMap { j =>
    s(j, "kind") match
      case "placed" => j.get("order").collect { case o: Json.Obj => Event.Placed(order(o)) }
      case "moved" => Status.parse(s(j, "status")).map(st =>
        Event.Moved(st, s(j, "note"), n(j, "amount"), s(j, "tracking")))
      case "emailed" => Some(Event.Emailed(s(j, "to"), s(j, "subject"), b(j, "sent")))
      case "inquired" => j.get("inquiry").collect { case o: Json.Obj => Event.Inquired(inquiry(o)) }
      case _ => None
  }

  def print(j: Json): String = Json.print(j)

  def booking(b: Booking): Json.Obj = Json.obj("ref" -> Json.str(b.ref), "key" -> Json.str(b.key),
    "service" -> Json.str(b.service), "master" -> Json.str(b.master), "start" -> Json.num(b.start),
    "minutes" -> Json.num(b.minutes), "name" -> Json.str(b.name), "contact" -> Json.str(b.contact),
    "lang" -> Json.str(b.lang.code), "consentAt" -> Json.num(b.consent.at), "policy" -> Json.str(b.consent.policy),
    "placed" -> Json.num(b.placed), "status" -> Json.str(b.status.key), "reminded" -> Json.bool(b.reminded),
    "mode" -> Json.str(b.mode.fold("")(_.key)), "place" -> Json.str(b.place),
    "address" -> b.address.fold(Json.Null)(a => Json.obj("name" -> Json.str(a.name), "street" -> Json.str(a.street),
      "postcode" -> Json.str(a.postcode), "city" -> Json.str(a.city), "notes" -> Json.str(a.notes),
      "map" -> Json.str(a.map))),
    "before" -> Json.num(b.before), "after" -> Json.num(b.after), "price" -> Json.num(b.price))

  def booking(j: Json.Obj): Booking = Booking(s(j, "ref"), s(j, "key"), s(j, "service"), s(j, "master"),
    n(j, "start"), n(j, "minutes").toInt, s(j, "name"), s(j, "contact"), Lang.parse(s(j, "lang")).getOrElse(Lang.pl),
    Consent(n(j, "consentAt"), s(j, "policy")), n(j, "placed"),
    BookingStatus.parse(s(j, "status")).getOrElse(BookingStatus.Booked), b(j, "reminded"), Mode.parse(s(j, "mode")),
    s(j, "place"), j.get("address").collect { case a: Json.Obj => Address(s(a, "name"), s(a, "street"),
      s(a, "postcode"), s(a, "city"), s(a, "notes"), s(a, "map")) }, n(j, "before").toInt, n(j, "after").toInt,
    n(j, "price"))

  private def address(a: Address): Json = Json.obj("name" -> Json.str(a.name), "street" -> Json.str(a.street),
    "postcode" -> Json.str(a.postcode), "city" -> Json.str(a.city), "notes" -> Json.str(a.notes),
    "map" -> Json.str(a.map))
  private def address(j: Json.Obj, k: String): Option[Address] = j.get(k).collect { case a: Json.Obj =>
    Address(s(a, "name"), s(a, "street"), s(a, "postcode"), s(a, "city"), s(a, "notes"), s(a, "map")) }

  /** a quote; its photos only when it has some (an old quote's JSON as before) */
  def quote(q: Quote): Json.Obj = Json.Obj(Json.obj("ref" -> Json.str(q.ref), "key" -> Json.str(q.key),
    "service" -> Json.str(q.service), "name" -> Json.str(q.name), "contact" -> Json.str(q.contact),
    "description" -> Json.str(q.description), "mode" -> Json.str(q.mode.fold("")(_.key)),
    "address" -> q.address.fold(Json.Null)(address), "preferred" -> Json.str(q.preferred),
    "lang" -> Json.str(q.lang.code), "consentAt" -> Json.num(q.consent.at), "policy" -> Json.str(q.consent.policy),
    "placed" -> Json.num(q.placed), "status" -> Json.str(q.status.key), "price" -> Json.num(q.price),
    "priceTo" -> Json.num(q.priceTo), "start" -> Json.num(q.start), "note" -> Json.str(q.note),
    "result" -> Json.str(q.result)).fields ++ Option.when(q.photos.nonEmpty)("photos" -> Json.Arr(q.photos.map(a =>
      Json.obj("file" -> Json.str(a.file), "width" -> Json.num(a.width), "height" -> Json.num(a.height),
        "size" -> Json.num(a.size))))))

  def quote(j: Json.Obj): Quote = Quote(s(j, "ref"), s(j, "key"), s(j, "service"), s(j, "name"), s(j, "contact"),
    s(j, "description"), Mode.parse(s(j, "mode")), address(j, "address"), s(j, "preferred"),
    Lang.parse(s(j, "lang")).getOrElse(Lang.pl), Consent(n(j, "consentAt"), s(j, "policy")), n(j, "placed"),
    QuoteStatus.parse(s(j, "status")).getOrElse(QuoteStatus.Asked), n(j, "price"), n(j, "priceTo"), n(j, "start"),
    s(j, "note"), s(j, "result"), j.get("photos").collect { case Json.Arr(xs) => xs.collect { case a: Json.Obj =>
      Attachment(s(a, "file"), n(a, "width").toInt, n(a, "height").toInt, n(a, "size").toInt) } }
      .getOrElse(Vector.empty))

  // ---- a payment (specs/shop.md "Money")

  private def log(l: PayLog): Json = Json.obj("at" -> Json.num(l.at), "by" -> Json.str(l.by),
    "what" -> Json.str(l.what),
    "amount" -> Json.num(l.amount), "method" -> Json.str(l.method.key), "day" -> Json.str(l.day))

  def payment(p: Payment): Json.Obj = Json.obj("of" -> Json.str(p.of.key), "ref" -> Json.str(p.ref),
    "amount" -> Json.num(p.amount), "method" -> Json.str(p.method.key), "day" -> Json.str(p.day),
    "state" -> Json.str(p.state.key), "by" -> Json.str(p.by), "at" -> Json.num(p.at), "note" -> Json.str(p.note),
    "refunded" -> Json.str(p.refunded), "description" -> Json.str(p.description), "service" -> Json.str(p.service),
    "master" -> Json.str(p.master), "receipt" -> Json.str(p.receipt), "log" -> Json.Arr(p.log.map(log)))

  def payment(j: Json.Obj): Payment = Payment(Of.parse(s(j, "of")).getOrElse(Of.Order), s(j, "ref"), n(j, "amount"),
    Method.parse(s(j, "method")).getOrElse(Method.Other), s(j, "day"),
    PayState.parse(s(j, "state")).getOrElse(PayState.Unpaid), s(j, "by"), n(j, "at"), s(j, "note"), s(j, "refunded"),
    s(j, "description"), s(j, "service"), s(j, "master"), s(j, "receipt"),
    j.get("log").collect { case Json.Arr(xs) => xs.collect { case l: Json.Obj => PayLog(n(l, "at"), s(l, "by"),
      s(l, "what"), n(l, "amount"), Method.parse(s(l, "method")).getOrElse(Method.Other), s(l, "day")) } }
      .getOrElse(Vector.empty))

  // ---- a review (specs/shop.md "Reviews")

  def review(r: Review): Json.Obj = Json.obj("of" -> Json.str(r.of.key), "ref" -> Json.str(r.ref),
    "service" -> Json.str(r.service), "master" -> Json.str(r.master), "rating" -> Json.num(r.rating),
    "text" -> Json.str(r.text), "name" -> Json.str(r.name), "lang" -> Json.str(r.lang.code), "at" -> Json.num(r.at),
    "reply" -> Json.str(r.reply), "replied" -> Json.num(r.replied), "hidden" -> Json.str(r.hidden),
    "log" -> Json.Arr(r.log.map(l => Json.obj("at" -> Json.num(l.at), "who" -> Json.str(l.who),
      "what" -> Json.str(l.what)))))

  def review(j: Json.Obj): Review = Review(Of.parse(s(j, "of")).getOrElse(Of.Booking), s(j, "ref"), s(j, "service"),
    s(j, "master"), n(j, "rating").toInt, s(j, "text"), s(j, "name"), Lang.parse(s(j, "lang")).getOrElse(Lang.pl),
    n(j, "at"), s(j, "reply"), n(j, "replied"), s(j, "hidden"),
    j.get("log").collect { case Json.Arr(xs) => xs.collect { case l: Json.Obj => ReviewLog(n(l, "at"), s(l, "who"),
      s(l, "what")) } }.getOrElse(Vector.empty))
