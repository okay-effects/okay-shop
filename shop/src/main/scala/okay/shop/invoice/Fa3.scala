package okay.shop.invoice

import okay.shop.model.Method

/**
 * AN INVOICE AS FA(3) XML (specs/shop.md "KSeF"): the Ministry of Finance's structure, `http://crd.gov.pl/wzor/2025/
 * 06/25/13775/` (KodFormularza `FA (3)`, schema 1-0E, variant 3) — the seller and the buyer by NIP and Polish
 * address, the issue and service days, the sums by rate computed from gross (art. 106e ust. 7–8: P_9B, P_11A), an
 * exempt seller's P_19 with its legal basis (P_19A), a correction's KOR with its reason and the corrected invoice
 * (its KSeF number, or NrKSeFN when it had none) and its lines before (StanPrzed), the payment. UTF-8, no BOM, no
 * processing instructions but the declaration; validated against the official XSD in the tests
 */
object Fa3:
  val namespace = "http://crd.gov.pl/wzor/2025/06/25/13775/"

  private def e(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").filter(c => c == '\n' || c == '\t' || c >= ' ')
  private def el(name: String, value: String): String = s"<$name>${e(value)}</$name>"
  private def money(g: Long) = InvoiceMath.dot(g)

  /** the sums' fields of a rate: net (and VAT) */
  private def sumFields(s: Sum): String = s.rate match
    case Rate.R23 => el("P_13_1", money(s.net)) + el("P_14_1", money(s.vat))
    case Rate.R8 => el("P_13_2", money(s.net)) + el("P_14_2", money(s.vat))
    case Rate.R5 => el("P_13_3", money(s.net)) + el("P_14_3", money(s.vat))
    case Rate.R0 => el("P_13_6_1", money(s.net))
    case Rate.Zw => el("P_13_7", money(s.net))
    case Rate.Np => el("P_13_8", money(s.net))

  private def party(nip: String, name: String, street: String, postcode: String, city: String): String =
    "<DaneIdentyfikacyjne>" + el("NIP", nip) + el("Nazwa", name) + "</DaneIdentyfikacyjne>" +
      "<Adres><KodKraju>PL</KodKraju>" + el("AdresL1", street) + el("AdresL2", s"$postcode $city") + "</Adres>"

  private def payment(m: Method): String = m match
    case Method.Cash => el("FormaPlatnosci", "1")
    case Method.Card => el("FormaPlatnosci", "2")
    case Method.Transfer => el("FormaPlatnosci", "6")
    case Method.Blik => el("FormaPlatnosci", "7")
    case Method.Other => "<PlatnoscInna>1</PlatnoscInna>" + el("OpisPlatnosci", "inna")

  private def row(n: Int, l: Item, before: Boolean): String = "<FaWiersz>" + el("NrWierszaFa", n.toString) +
    el("P_7", l.name) + el("P_8A", l.unit) + el("P_8B", l.quantity.toString) + el("P_9B", money(l.unitGross)) +
    el("P_11A", money(l.gross)) + el("P_12", l.rate.fa) + (if before then "<StanPrzed>1</StanPrzed>" else "") +
    "</FaWiersz>"

  /** the invoice's XML; `created` its making's instant (ISO 8601, UTC) */
  def xml(i: Invoice, created: String): String =
    val s = i.seller
    val b = i.buyer
    val kor = i.kind == InvoiceKind.Correction
    val sums = if kor then i.difference else i.sums
    val total = sums.map(_.gross).sum
    val exemption =
      if s.exempt then "<Zwolnienie><P_19>1</P_19>" + el("P_19A", s.exemption) + "</Zwolnienie>"
      else "<Zwolnienie><P_19N>1</P_19N></Zwolnienie>"
    val corrected = i.corrects.fold("")(c => el("PrzyczynaKorekty", i.reason) + "<DaneFaKorygowanej>" +
      el("DataWystFaKorygowanej", c.issued) + el("NrFaKorygowanej", c.number) +
      (if c.ksef.nonEmpty then "<NrKSeF>1</NrKSeF>" + el("NrKSeFFaKorygowanej", c.ksef) else "<NrKSeFN>1</NrKSeFN>") +
      "</DaneFaKorygowanej>")
    val rows = (if kor then i.before.map((_, true)) else Vector.empty) ++ i.lines.map((_, false))
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
      s"<Faktura xmlns=\"$namespace\">" +
      "<Naglowek><KodFormularza kodSystemowy=\"FA (3)\" wersjaSchemy=\"1-0E\">FA</KodFormularza>" +
      "<WariantFormularza>3</WariantFormularza>" + el("DataWytworzeniaFa", created) +
      el("SystemInfo", "okay-shop") + "</Naglowek>" +
      "<Podmiot1>" + party(s.nip, s.name, s.street, s.postcode, s.city) + "</Podmiot1>" +
      "<Podmiot2>" + party(b.nip, b.name, b.street, b.postcode, b.city) + "<JST>2</JST><GV>2</GV></Podmiot2>" +
      "<Fa><KodWaluty>PLN</KodWaluty>" + el("P_1", i.issued) + el("P_1M", s.city) + el("P_2", i.number) +
      (if !kor then el("P_6", i.sold) else "") +
      sums.map(sumFields).mkString + el("P_15", money(total)) +
      "<Adnotacje><P_16>2</P_16><P_17>2</P_17><P_18>2</P_18><P_18A>2</P_18A>" + exemption +
      "<NoweSrodkiTransportu><P_22N>1</P_22N></NoweSrodkiTransportu><P_23>2</P_23>" +
      "<PMarzy><P_PMarzyN>1</P_PMarzyN></PMarzy></Adnotacje>" +
      el("RodzajFaktury", if kor then "KOR" else "VAT") + corrected +
      rows.zipWithIndex.map { case ((l, before), n) => row(n + 1, l, before) }.mkString +
      (if kor then "" else "<Platnosc><Zaplacono>1</Zaplacono>" + el("DataZaplaty", i.paidDay) + payment(i.method) +
        (if s.bank.nonEmpty then "<RachunekBankowy>" + el("NrRB", s.bank.replace(" ", "").stripPrefix("PL")) +
          "</RachunekBankowy>" else "") + "</Platnosc>") +
      "</Fa></Faktura>"
