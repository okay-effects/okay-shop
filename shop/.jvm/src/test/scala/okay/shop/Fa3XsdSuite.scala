package okay.shop

import okay.shop.invoice.*
import okay.shop.model.{Method, Of}
import org.w3c.dom.ls.{LSInput, LSResourceResolver}

import java.io.{ByteArrayInputStream, InputStream, Reader, StringReader}
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/**
 * FA(3) VALIDATED AGAINST THE OFFICIAL XSD (specs/shop.md "KSeF"): `schemat_FA(3)_v1-0E.xsd`, namespace
 * http://crd.gov.pl/wzor/2025/06/25/13775/ (KodFormularza "FA (3)", wersjaSchemy 1-0E) — byte for byte
 * http://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd (SHA-256 b646b6b5…958, read 2026-10-30), and its imports
 * StrukturyDanych_v10-0E.xsd, ElementarneTypyDanych_v10-0E.xsd, KodyKrajow_v10-0E.xsd as the Ministry's KSeF
 * documentation keeps them (github.com/CIRFMF/ksef-api, faktury/schemy/FA, commit ce3670e) — in test resources, the
 * import's absolute address answered from them
 */
class Fa3XsdSuite extends munit.FunSuite:
  private def resource(name: String): InputStream =
    val in = getClass.getResourceAsStream("/fa3/" + name)
    if in == null then null
    else ByteArrayInputStream(new String(in.readAllBytes(), "UTF-8").replaceAll("maxOccurs=\"[0-9]{5,}\"",
      "maxOccurs=\"unbounded\"").getBytes("UTF-8"))

  private val schema =
    val f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    // the JDK's Xerces expands a bounded maxOccurs into states (FaWiersz's 10000 ran out of memory): the bounds of
    // 10000 and more read as unbounded — only the upper limits of repetition are not checked here
    f.setResourceResolver(new LSResourceResolver:
      def resolveResource(t: String, ns: String, publicId: String, systemId: String, base: String): LSInput =
        val name = systemId.split('/').last
        val in = resource(name)
        if in == null then null
        else new LSInput:
          def getByteStream: InputStream = in
          def getCharacterStream: Reader = null
          def setCharacterStream(r: Reader): Unit = ()
          def setByteStream(s: InputStream): Unit = ()
          def getStringData: String = null
          def setStringData(s: String): Unit = ()
          def getSystemId: String = systemId
          def setSystemId(s: String): Unit = ()
          def getPublicId: String = publicId
          def setPublicId(s: String): Unit = ()
          def getBaseURI: String = base
          def setBaseURI(s: String): Unit = ()
          def getEncoding: String = null
          def setEncoding(s: String): Unit = ()
          def getCertifiedText: Boolean = false
          def setCertifiedText(b: Boolean): Unit = ()
    )
    f.newSchema(StreamSource(resource("schemat_FA(3)_v1-0E.xsd"),
      "http://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd"))

  private def valid(xml: String): Unit =
    schema.newValidator().validate(StreamSource(ByteArrayInputStream(xml.getBytes("UTF-8"))))

  private val seller = Seller("TEST Salon Sp. z o.o. & Co", "1111111111", "ul. Testowa 1", "00-001", "Warszawa",
    bank = "PL 61 1090 1014 0000 0712 1981 2874")
  private val buyer = Buyer("TEST Kupiec <Sp. z o.o.>", "2222222222", "ul. Przykładowa 2", "30-001", "Kraków")
  private val vat = Invoice("000001", "FV/2026/10/1", InvoiceKind.Vat, Of.Booking, "SAL-1", "2026-10-30", "2026-10-29",
    seller, buyer, Vector(Item("Strzyżenie męskie", 1, 12300, Rate.R23), Item("Pielęgnacja brody", 2, 5000, Rate.R8),
      Item("Bon", 1, 1000, Rate.Zw), Item("Konsultacja", 1, 500, Rate.R0)), Method.Transfer, "2026-10-30",
    "staff@example.com", 1793282400000L)

  test("a VAT payer's invoice: rates 23, 8, zw, 0; gross prices; paid by transfer; a bank account") {
    val xml = Fa3.xml(vat, "2026-10-30T09:00:00Z")
    valid(xml)
    Seq("<P_13_1>100.00</P_13_1><P_14_1>23.00</P_14_1>", "<P_13_2>46.30</P_13_2><P_14_2>3.70</P_14_2>",
      "<P_13_6_1>5.00</P_13_6_1>", "<P_13_7>10.00</P_13_7>", "<P_15>188.00</P_15>", "<P_19N>1</P_19N>",
      "<P_9B>25.00</P_9B>", "<FormaPlatnosci>6</FormaPlatnosci>", "<NrRB>61109010140000071219812874</NrRB>",
      "&lt;Sp. z o.o.&gt;", "&amp; Co").foreach(x => assert(xml.contains(x), x))
  }

  test("an exempt seller's (art. 113 ust. 1): zw, P_13_7, P_19 with its legal basis; paid in cash, by BLIK, other") {
    val exempt = seller.copy(status = VatStatus.Exempt113, bank = "")
    for m <- Seq(Method.Cash, Method.Blik, Method.Card, Method.Other) do
      val xml = Fa3.xml(vat.copy(seller = exempt, method = m, lines = vat.lines.map(_.copy(rate = Rate.Zw))),
        "2026-10-30T09:00:00Z")
      valid(xml)
      assert(xml.contains("<P_19>1</P_19><P_19A>art. 113 ust. 1 ustawy") && xml.contains("<P_13_7>188.00</P_13_7>"))
  }

  test("a correction: KOR, its reason, the corrected invoice by its KSeF number or NrKSeFN; the lines before") {
    val before = vat.lines
    val after = vat.lines.updated(0, Item("Strzyżenie męskie", 1, 10000, Rate.R23))
    val k = InvoiceMath.correction(vat, before, after, "Rabat", "2026-10-31", "owner@example.com", 1L).toOption.get
      .copy(id = "000002", number = "FK/2026/10/1")
    val inKsef = Fa3.xml(k.copy(corrects = k.corrects.map(_.copy(ksef = KsefFake.number("1111111111", "20261030",
      "1")))), "2026-10-31T09:00:00Z")
    valid(inKsef)
    assert(inKsef.contains("<RodzajFaktury>KOR</RodzajFaktury><PrzyczynaKorekty>Rabat</PrzyczynaKorekty>"))
    assert(inKsef.contains("<P_13_1>-18.70</P_13_1><P_14_1>-4.30</P_14_1>") && inKsef.contains("<P_15>-23.00</P_15>"))
    assert(inKsef.contains("<StanPrzed>1</StanPrzed>") && inKsef.contains("<NrKSeF>1</NrKSeF>"))
    val outside = Fa3.xml(k, "2026-10-31T09:00:00Z")
    valid(outside)
    assert(outside.contains("<NrKSeFN>1</NrKSeFN>"))
  }

  test("what the schema refuses is refused: a broken document fails") {
    intercept[org.xml.sax.SAXException](valid(Fa3.xml(vat, "2026-10-30T09:00:00Z").replace("<P_2>", "<P_X>")
      .replace("</P_2>", "</P_X>")))
  }

  test("a KSeF number's check: CRC-8 of the rest, as the Ministry's example") {
    // the documentation's example: 5265877635-20250826-0100001AF629-AF
    assertEquals(KsefFake.crc8("5265877635-20250826-0100001AF629"), 0xaf)
  }
