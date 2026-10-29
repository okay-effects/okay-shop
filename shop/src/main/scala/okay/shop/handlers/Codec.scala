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
