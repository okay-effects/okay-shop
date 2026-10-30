package okay.shop.invoice

import okay.effects.json.Json
import okay.shop.model.*

/** A POLISH TAX ID (NIP): ten digits, the last a check digit — weights 6,5,7,2,3,4,5,6,7 on the first nine, their sum
 * mod 11; 10 is never issued (specs/shop.md "Invoices") */
object Nip:
  private val weights = Vector(6, 5, 7, 2, 3, 4, 5, 6, 7)
  /** the digits of a NIP as people write it: `PL`, spaces and hyphens off */
  def clean(s: String): String = s.trim.toUpperCase.stripPrefix("PL").filter(c => c != ' ' && c != '-')
  def valid(s: String): Boolean =
    val d = clean(s)
    d.length == 10 && d.forall(_.isDigit) && {
      val sum = weights.zip(d).map((w, c) => w * (c - '0')).sum % 11
      sum != 10 && sum == d(9) - '0'
    }
  /** `526-123-45-67` → `5261234567`; none when not valid */
  def parse(s: String): Option[String] = Option(clean(s)).filter(valid)

/** A BUYER OF AN INVOICE: a business — its name, NIP, address (Poland's) */
final case class Buyer(name: String, nip: String, street: String, postcode: String, city: String):
  def address: String = s"$street, $postcode $city"

object Buyer:
  private def line(s: String) = s.replaceAll("\\s+", " ").trim
  /** the buyer asked, checked: the name of the first wrong field — `buyer.name`, `buyer.nip`, `buyer.address` */
  def check(name: String, nip: String, street: String, postcode: String, city: String): Either[String, Buyer] =
    val (n, st, pc, c) = (line(name), line(street), line(postcode), line(city))
    if n.isEmpty || n.length > 200 then Left("buyer.name")
    else Nip.parse(nip) match
      case None => Left("buyer.nip")
      case Some(id) =>
        if st.isEmpty || st.length > 200 || !pc.matches("[0-9]{2}-[0-9]{3}") || c.isEmpty || c.length > 100 then
          Left("buyer.address")
        else Right(Buyer(n, id, st, pc, c))
  /** from a form's fields (`buyer_name`, `buyer_nip`, `buyer_street`, `buyer_postcode`, `buyer_city`); none asked when
   * `invoice` is not ticked */
  def asked(f: Map[String, String]): Either[String, Option[Buyer]] =
    if !f.get("invoice").exists(v => v.nonEmpty && v != "false" && v != "off") then Right(None)
    else check(f.getOrElse("buyer_name", ""), f.getOrElse("buyer_nip", ""), f.getOrElse("buyer_street", ""),
      f.getOrElse("buyer_postcode", ""), f.getOrElse("buyer_city", "")).map(Some(_))
  def toJson(b: Buyer): Json.Obj = Json.obj("name" -> Json.str(b.name), "nip" -> Json.str(b.nip),
    "street" -> Json.str(b.street), "postcode" -> Json.str(b.postcode), "city" -> Json.str(b.city))
  def fromJson(j: Json.Obj): Buyer = Buyer(J.s(j, "name"), J.s(j, "nip"), J.s(j, "street"), J.s(j, "postcode"),
    J.s(j, "city"))

/** a VAT rate as an invoice says it, FA(3)'s code beside it (`P_12`) */
enum Rate(val key: String, val percent: Int, val fa: String, val label: String):
  case R23 extends Rate("23", 23, "23", "23%")
  case R8 extends Rate("8", 8, "8", "8%")
  case R5 extends Rate("5", 5, "5", "5%")
  case R0 extends Rate("0", 0, "0 KR", "0%")
  case Zw extends Rate("zw", 0, "zw", "zw")
  case Np extends Rate("np", 0, "np I", "np")

object Rate:
  def parse(s: String): Option[Rate] = values.find(_.key == s.trim.toLowerCase)

/** WHAT THE SELLER IS FOR VAT: an active payer, or exempt — by its turnover (art. 113 ust. 1) or by what it does
 * (art. 43 ust. 1), the legal basis said on every invoice */
enum VatStatus(val key: String):
  case Payer extends VatStatus("vat")
  case Exempt113 extends VatStatus("zw113")
  case Exempt43 extends VatStatus("zw43")

object VatStatus:
  def parse(s: String): Option[VatStatus] = values.find(_.key == s.trim)
  val basis113 = "art. 113 ust. 1 ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług"

/**
 * THE SELLER (the owner's "Dane do faktur"): its legal name, NIP, address, VAT status and the exemption's legal basis,
 * the rate per service (none: the default), the numbering of invoices and of corrections, its bank account, the
 * place of issue
 */
final case class Seller(name: String, nip: String, street: String, postcode: String, city: String,
                        status: VatStatus = VatStatus.Payer, basis: String = "", rate: Rate = Rate.R23,
                        rates: Map[String, Rate] = Map.empty, pattern: String = Seller.pattern,
                        corrections: String = Seller.corrections, bank: String = ""):
  def exempt: Boolean = status != VatStatus.Payer
  /** the rate of a service: zw for an exempt seller, else its own or the default */
  def rateOf(service: String): Rate = if exempt then Rate.Zw else rates.getOrElse(service, rate)
  /** the legal basis written on an exempt seller's invoice */
  def exemption: String = if status == VatStatus.Exempt113 && basis.trim.isEmpty then VatStatus.basis113 else basis
  def address: String = s"$street, $postcode $city"

object Seller:
  val pattern = "FV/{yyyy}/{mm}/{n}"
  val corrections = "FK/{yyyy}/{mm}/{n}"
  private def line(s: String) = s.replaceAll("\\s+", " ").trim

  /** a numbering pattern: `{n}` once, `{yyyy}`, `{yy}`, `{mm}` as often as wanted, else letters, digits, `/`, `-`,
   * `_`, `.` — at most 40 characters */
  def validPattern(p: String): Boolean =
    p.length <= 40 && "\\{n\\}".r.findAllIn(p).size == 1 &&
      p.replace("{yyyy}", "").replace("{yy}", "").replace("{mm}", "").replace("{n}", "")
        .forall(c => c.isLetterOrDigit && c < 128 || "/-_.".contains(c))

  /** an account: Polish (26 digits, `PL` optional) or an IBAN, spaces kept as written */
  def validBank(s: String): Boolean =
    val t = s.replace(" ", "").toUpperCase
    t.isEmpty || t.matches("(PL)?[0-9]{26}") || t.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}")

  /** the seller as JSON (a host's config); checked by the name of the first wrong field */
  def fromJson(j: Json, services: Set[String]): Either[String, Seller] = j match
    case o: Json.Obj =>
      val (name, street, postcode, city) = (line(J.s(o, "name")), line(J.s(o, "street")), line(J.s(o, "postcode")),
        line(J.s(o, "city")))
      val rates = o.get("rates") match
        case Some(r: Json.Obj) => r.fields.map((k, v) => k -> (v match
          case Json.Str(x) => Rate.parse(x)
          case _ => None)).toMap
        case _ => Map.empty[String, Option[Rate]]
      val status = VatStatus.parse(J.s(o, "status"))
      val pattern = Some(J.s(o, "pattern").trim).filter(_.nonEmpty).getOrElse(Seller.pattern)
      val corr = Some(J.s(o, "corrections").trim).filter(_.nonEmpty).getOrElse(Seller.corrections)
      val basis = line(J.s(o, "basis"))
      if name.isEmpty || name.length > 200 then Left("seller.name")
      else if Nip.parse(J.s(o, "nip")).isEmpty then Left("seller.nip")
      else if street.isEmpty || street.length > 200 || !postcode.matches("[0-9]{2}-[0-9]{3}") || city.isEmpty ||
        city.length > 100 then Left("seller.address")
      else if status.isEmpty then Left("seller.status")
      else if status.contains(VatStatus.Exempt43) && basis.isEmpty || basis.length > 256 then Left("seller.basis")
      else if Rate.parse(Some(J.s(o, "rate")).filter(_.nonEmpty).getOrElse("23")).isEmpty then Left("seller.rate")
      else if rates.exists((k, v) => v.isEmpty || !services(k)) then Left("seller.rates")
      else if !validPattern(pattern) || !validPattern(corr) || pattern == corr then Left("seller.pattern")
      else if !validBank(J.s(o, "bank")) then Left("seller.bank")
      else Right(Seller(name, Nip.parse(J.s(o, "nip")).get, street, postcode, city, status.get, basis,
        Rate.parse(Some(J.s(o, "rate")).filter(_.nonEmpty).getOrElse("23")).get, rates.map((k, v) => k -> v.get),
        pattern, corr, line(J.s(o, "bank"))))
    case _ => Left("seller")

  def toJson(s: Seller): Json.Obj = Json.obj("name" -> Json.str(s.name), "nip" -> Json.str(s.nip),
    "street" -> Json.str(s.street), "postcode" -> Json.str(s.postcode), "city" -> Json.str(s.city),
    "status" -> Json.str(s.status.key), "basis" -> Json.str(s.basis), "rate" -> Json.str(s.rate.key),
    "rates" -> Json.Obj(s.rates.toVector.sortBy(_._1).map((k, r) => k -> Json.str(r.key))),
    "pattern" -> Json.str(s.pattern), "corrections" -> Json.str(s.corrections), "bank" -> Json.str(s.bank))

/** a line of an invoice: what, how many, its gross value (grosze; the gross method, art. 106e ust. 7–8), its rate */
final case class Item(name: String, quantity: Int, gross: Long, rate: Rate, unit: String = "usł."):
  def unitGross: Long = if quantity <= 0 then gross else gross / quantity

/** the sums of one rate: net, VAT, gross */
final case class Sum(rate: Rate, net: Long, vat: Long, gross: Long)

enum InvoiceKind(val key: String):
  case Vat extends InvoiceKind("vat")
  case Correction extends InvoiceKind("kor")

object InvoiceKind:
  def parse(s: String): Option[InvoiceKind] = values.find(_.key == s)

/** THE KSeF SIDE of an invoice: where it stands — `none` (not sent), `queued` (to be sent, by the alarm when it
 * failed), `sent` (taken by KSeF, its number awaited), `accepted` (its KSeF number), `rejected` (KSeF said no) — the
 * environment, the session's and the invoice's references, the KSeF number, the last error, the attempts, the next
 * try (0: none), when, whether its UPO is kept, the SHA-256 (base64url) of the XML sent (the QR code's) */
final case class KsefState(status: String = "none", env: String = "", session: String = "", reference: String = "",
                           number: String = "", error: String = "", attempts: Int = 0, next: Long = 0,
                           at: Long = 0, upo: Boolean = false, hash: String = "")

/** what an invoice corrects: the corrected one's id, number, issue date and KSeF number (none: issued outside it) */
final case class Corrected(id: String, number: String, issued: String, ksef: String)

/**
 * AN INVOICE (specs/shop.md "Invoices"): its id (the shop's sequence) and number (the series' next), its kind, what it
 * is of (an order or a booking, by its ref), the days of issue and of the service, the place, the seller and the buyer
 * as they were then, its lines, how and when it was paid; a correction's reason, the corrected invoice and its lines
 * before; the KSeF side; who issued it and when; the corrections made of it since
 */
final case class Invoice(id: String, number: String, kind: InvoiceKind, of: Of, ref: String, issued: String,
                         sold: String, seller: Seller, buyer: Buyer, lines: Vector[Item], method: Method,
                         paidDay: String, by: String, at: Long, reason: String = "",
                         corrects: Option[Corrected] = None, before: Vector[Item] = Vector.empty,
                         ksef: KsefState = KsefState(), corrections: Vector[String] = Vector.empty):
  def sums: Vector[Sum] = InvoiceMath.sums(lines)
  def total: Long = lines.map(_.gross).sum
  /** a correction's difference by rate (after minus before) */
  def difference: Vector[Sum] = InvoiceMath.difference(before, lines)

object InvoiceMath:
  /** VAT out of a gross amount (art. 106e ust. 7): gross × rate / (100 + rate), rounded half up to the grosz */
  def vatOf(gross: Long, r: Rate): Long =
    if r.percent == 0 then 0L
    else
      val num = BigInt(gross) * r.percent
      val den = BigInt(100 + r.percent)
      val q = num / den
      val rem = (num % den).abs * 2
      (if rem >= den then (if num.signum < 0 then q - 1 else q + 1) else q).toLong

  /** the sums per rate, in the rates' order: VAT computed from each rate's gross total (art. 106e ust. 7, 9) */
  def sums(lines: Vector[Item]): Vector[Sum] =
    Rate.values.toVector.flatMap(r => Some(lines.filter(_.rate == r)).filter(_.nonEmpty).map { ls =>
      val gross = ls.map(_.gross).sum
      val vat = vatOf(gross, r)
      Sum(r, gross - vat, vat, gross)
    })

  def difference(before: Vector[Item], after: Vector[Item]): Vector[Sum] =
    val (b, a) = (sums(before), sums(after))
    Rate.values.toVector.flatMap { r =>
      val x = b.find(_.rate == r)
      val y = a.find(_.rate == r)
      Option.when(x.isDefined || y.isDefined)(Sum(r, y.map(_.net).getOrElse(0L) - x.map(_.net).getOrElse(0L),
        y.map(_.vat).getOrElse(0L) - x.map(_.vat).getOrElse(0L), y.map(_.gross).getOrElse(0L) -
          x.map(_.gross).getOrElse(0L)))
    }

  /** a number of `pattern` on `day` (`YYYY-MM-DD`), its `n` */
  def number(pattern: String, day: String, n: Long): String =
    series(pattern, day).replace("{n}", n.toString)
  /** the series a number of `pattern` on `day` belongs to: the pattern with the date filled in, `{n}` kept — its own
   * sequence (a monthly pattern starts again each month) */
  def series(pattern: String, day: String): String =
    pattern.replace("{yyyy}", day.take(4)).replace("{yy}", day.slice(2, 4)).replace("{mm}", day.slice(5, 7))

  /** a draft of the invoice of a paid order or booking, numbered by the store: the service, the payment's amount and
   * rate, its days; the refusal's name — `seller` (no seller data), `buyer`, `unpaid` */
  def draft(seller: Option[Seller], buyer: Option[Buyer], of: Of, ref: String, service: String, what: String,
            served: String, pay: Option[Payment], today: String, by: String, now: Long): Either[String, Invoice] =
    (seller, buyer, pay.filter(_.paid)) match
      case (None, _, _) => Left("seller")
      case (_, None, _) => Left("buyer")
      case (_, _, None) => Left("unpaid")
      case (Some(s), Some(b), Some(p)) =>
        Right(Invoice("", "", InvoiceKind.Vat, of, ref, today, if served.nonEmpty then served else p.day, s, b,
          Vector(Item(what.take(256), 1, p.amount, s.rateOf(service))), p.method, p.day, by, now))

  /** a correction of `original`: its lines `before` (as last corrected) and as they should be, the reason; refused by
   * name — `reason`, `lines`, `same`, `kind` (a correction is not corrected again: correct the original) */
  def correction(original: Invoice, before: Vector[Item], lines: Vector[Item], reason: String, today: String,
                 by: String, now: Long): Either[String, Invoice] =
    val why = reason.replaceAll("\\s+", " ").trim
    if original.kind != InvoiceKind.Vat then Left("kind")
    else if why.isEmpty || why.length > 256 then Left("reason")
    else if lines.isEmpty || lines.size > 50 || lines.exists(l => l.name.trim.isEmpty || l.name.length > 256 ||
      l.quantity <= 0 || l.gross < 0 || l.gross > Payments.most) then Left("lines")
    else if lines == before then Left("same")
    else
      Right(Invoice("", "", InvoiceKind.Correction, original.of, original.ref, today, original.sold, original.seller,
        original.buyer, lines, original.method, original.paidDay, by, now, why,
        Some(Corrected(original.id, original.number, original.issued, original.ksef.number)), before))

  /** the lines a correction starts from: the original's, or the last correction's */
  def current(original: Invoice, corrections: Vector[Invoice]): Vector[Item] =
    corrections.filter(_.corrects.exists(_.id == original.id)).sortBy(_.id).lastOption.fold(original.lines)(_.lines)

  /** an amount as an invoice writes it: `1 230,00` */
  def zl(grosze: Long): String =
    val sign = if grosze < 0 then "-" else ""
    val a = grosze.abs
    val whole = (a / 100).toString.reverse.grouped(3).mkString(" ").reverse
    f"$sign$whole,${a % 100}%02d"

  /** an amount as FA(3) writes it: `1230.00` */
  def dot(grosze: Long): String =
    val sign = if grosze < 0 then "-" else ""
    f"$sign${grosze.abs / 100}.${grosze.abs % 100}%02d"

/** reading JSON fields, quietly */
object J:
  def s(o: Json.Obj, k: String): String = o.get(k) match
    case Some(Json.Str(v)) => v
    case _ => ""
  def n(o: Json.Obj, k: String): Long = o.get(k) match
    case Some(Json.Num(v)) => v.toLong
    case _ => 0L
  def b(o: Json.Obj, k: String): Boolean = o.get(k).contains(Json.Bool(true))
  def obj(o: Json.Obj, k: String): Json.Obj = o.get(k) match
    case Some(x: Json.Obj) => x
    case _ => Json.Obj(Vector.empty)
  def arr(o: Json.Obj, k: String): Vector[Json.Obj] = o.get(k) match
    case Some(Json.Arr(xs)) => xs.collect { case x: Json.Obj => x }
    case _ => Vector.empty
