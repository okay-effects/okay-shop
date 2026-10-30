package okay.shop.invoice

import okay.effects.json.Json
import okay.shop.model.*

/** AN INVOICE'S STORED JSON (`site/invoice/<id>`): written once, read back the same; the seller and the buyer as they
 * were on the day */
object InvoiceCodec:
  private def line(l: Item): Json.Obj = Json.obj("name" -> Json.str(l.name), "quantity" -> Json.num(l.quantity),
    "gross" -> Json.num(l.gross), "rate" -> Json.str(l.rate.key), "unit" -> Json.str(l.unit))
  private def line(j: Json.Obj): Item = Item(J.s(j, "name"), J.n(j, "quantity").toInt, J.n(j, "gross"),
    Rate.parse(J.s(j, "rate")).getOrElse(Rate.R23), Some(J.s(j, "unit")).filter(_.nonEmpty).getOrElse("usł."))

  def ksef(k: KsefState): Json.Obj = Json.obj("status" -> Json.str(k.status), "env" -> Json.str(k.env),
    "session" -> Json.str(k.session), "reference" -> Json.str(k.reference), "number" -> Json.str(k.number),
    "error" -> Json.str(k.error), "attempts" -> Json.num(k.attempts), "next" -> Json.num(k.next),
    "at" -> Json.num(k.at), "upo" -> Json.bool(k.upo),
    "hash" -> Json.str(k.hash))
  def ksef(j: Json.Obj): KsefState = KsefState(Some(J.s(j, "status")).filter(_.nonEmpty).getOrElse("none"),
    J.s(j, "env"), J.s(j, "session"), J.s(j, "reference"), J.s(j, "number"), J.s(j, "error"),
    J.n(j, "attempts").toInt, J.n(j, "next"), J.n(j, "at"), J.b(j, "upo"), J.s(j, "hash"))

  def invoice(i: Invoice): Json.Obj = Json.obj("id" -> Json.str(i.id), "number" -> Json.str(i.number),
    "kind" -> Json.str(i.kind.key), "of" -> Json.str(i.of.key), "ref" -> Json.str(i.ref),
    "issued" -> Json.str(i.issued), "sold" -> Json.str(i.sold), "seller" -> Seller.toJson(i.seller),
    "buyer" -> Buyer.toJson(i.buyer), "lines" -> Json.Arr(i.lines.map(line)), "method" -> Json.str(i.method.key),
    "paidDay" -> Json.str(i.paidDay), "by" -> Json.str(i.by), "at" -> Json.num(i.at), "reason" -> Json.str(i.reason),
    "corrects" -> i.corrects.fold(Json.Null)(c => Json.obj("id" -> Json.str(c.id), "number" -> Json.str(c.number),
      "issued" -> Json.str(c.issued), "ksef" -> Json.str(c.ksef))),
    "before" -> Json.Arr(i.before.map(line)), "ksef" -> ksef(i.ksef),
    "corrections" -> Json.Arr(i.corrections.map(Json.str)))

  def invoice(j: Json.Obj): Invoice =
    val sellerJ = J.obj(j, "seller")
    // the seller as it was kept: read back without the checks of a new one
    val seller = Seller(J.s(sellerJ, "name"), J.s(sellerJ, "nip"), J.s(sellerJ, "street"), J.s(sellerJ, "postcode"),
      J.s(sellerJ, "city"), VatStatus.parse(J.s(sellerJ, "status")).getOrElse(VatStatus.Payer), J.s(sellerJ, "basis"),
      Rate.parse(J.s(sellerJ, "rate")).getOrElse(Rate.R23), J.obj(sellerJ, "rates").fields.flatMap((k, v) => v match
        case Json.Str(r) => Rate.parse(r).map(k -> _)
        case _ => None).toMap, J.s(sellerJ, "pattern"), J.s(sellerJ, "corrections"), J.s(sellerJ, "bank"))
    Invoice(J.s(j, "id"), J.s(j, "number"), InvoiceKind.parse(J.s(j, "kind")).getOrElse(InvoiceKind.Vat),
      Of.parse(J.s(j, "of")).getOrElse(Of.Order), J.s(j, "ref"), J.s(j, "issued"), J.s(j, "sold"), seller,
      Buyer.fromJson(J.obj(j, "buyer")), J.arr(j, "lines").map(line), Method.parse(J.s(j, "method"))
        .getOrElse(Method.Other), J.s(j, "paidDay"), J.s(j, "by"), J.n(j, "at"), J.s(j, "reason"),
      j.get("corrects").collect { case c: Json.Obj => Corrected(J.s(c, "id"), J.s(c, "number"), J.s(c, "issued"),
        J.s(c, "ksef")) }, J.arr(j, "before").map(line), ksef(J.obj(j, "ksef")),
      j.get("corrections").collect { case Json.Arr(xs) => xs.collect { case Json.Str(s) => s } }
        .getOrElse(Vector.empty))
