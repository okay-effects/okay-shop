//> using scala 3.3.8
//> using dep com.lihaoyi::ujson:4.1.0
/*
 * A KSeF TOKEN ON THE TEST ENVIRONMENT, NO REAL IDENTITY (specs/shop.md "KSeF"): KSeF's test environment (TE,
 * api-test.ksef.mf.gov.pl) takes a self-signed certificate ("W środowisku testowym TE dopuszczalne jest
 * uwierzytelnianie przy użyciu samopodpisanych certyfikatów … Do testów należy używać losowych identyfikatorów NIP",
 * github.com/CIRFMF/ksef-api srodowiska.md). So: a random checksum-valid NIP; an RSA key and a self-signed
 * certificate of a fictional person (serialNumber TINPL-<NIP>, by openssl); an AuthTokenRequest for that NIP signed
 * XAdES-BES (enveloped, the JDK's XML signature); POST /v2/auth/xades-signature, the sign-in polled, redeemed; then a
 * KSeF token (InvoiceWrite, InvoiceRead) made by POST /v2/tokens and waited for until Active.
 *
 *   scala-cli run scripts/ksef-test-token.sc -- <out dir>
 *
 * Writes <out dir>/ksef-test.json (mode 600): {"nip", "token", "reference"}. Prints the NIP and the token's reference,
 * never the token. TEST ENVIRONMENT ONLY: the base is fixed here; production is never asked.
 */
import java.io.{ByteArrayInputStream, StringWriter}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermissions
import java.security.{KeyFactory, MessageDigest}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.xml.crypto.dom.DOMStructure
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.spec.{C14NMethodParameterSpec, TransformParameterSpec}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

val Base = "https://api-test.ksef.mf.gov.pl/v2"
val out = Paths.get(args.headOption.getOrElse(sys.error("usage: ksef-test-token.sc <out dir>")))
Files.createDirectories(out)
val http = HttpClient.newHttpClient()

def call(method: String, path: String, body: String = "", kind: String = "application/json",
         bearer: String = ""): (Int, ujson.Value) =
  val b = HttpRequest.newBuilder(URI.create(Base + path)).header("accept", "application/json")
  if bearer.nonEmpty then b.header("authorization", s"Bearer $bearer")
  val req = if body.isEmpty && method != "POST" then b.method(method, HttpRequest.BodyPublishers.noBody())
    else b.header("content-type", kind).method(method, HttpRequest.BodyPublishers.ofString(body))
  val res = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
  (res.statusCode(), scala.util.Try(ujson.read(res.body())).getOrElse(ujson.Str(res.body())))

// ---- a random NIP: ten digits, the first 1–9, the second and third not both 0, its check digit
def nip(): String =
  val r = scala.util.Random()
  Iterator.continually {
    val d = (r.nextInt(9) + 1) +: Vector.fill(8)(r.nextInt(10))
    val sum = Vector(6, 5, 7, 2, 3, 4, 5, 6, 7).zip(d).map(_ * _).sum % 11
    Option.when(sum != 10 && !(d(1) == 0 && d(2) == 0))((d :+ sum).mkString)
  }.flatten.next()
val id = nip()

// ---- a fictional person's self-signed certificate (valid from an hour ago, two days)
val dir = Files.createTempDirectory(out, "cert")
val subject = s"/C=PL/GN=Test/SN=Okay/serialNumber=TINPL-$id/CN=Test Okay $id"
val made = Seq("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-subj", subject,
  "-not_before", java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
    .format(Instant.now().minusSeconds(3660)), "-days", "2",
  "-keyout", dir.resolve("key.pem").toString, "-out", dir.resolve("cert.pem").toString).!(ProcessLogger(_ => ()))
require(made == 0, "openssl failed")
Seq("openssl", "pkcs8", "-topk8", "-nocrypt", "-outform", "DER", "-in", dir.resolve("key.pem").toString,
  "-out", dir.resolve("key.der").toString).!(ProcessLogger(_ => ()))
val cert = CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(dir.resolve("cert.pem")))
  .asInstanceOf[X509Certificate]
val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(dir.resolve("key.der"))))
Files.walk(dir).iterator.asScala.toVector.reverse.foreach(Files.delete)

// ---- the challenge, the request, its XAdES-BES signature
val (cs, ch) = call("POST", "/auth/challenge")
require(cs == 200, s"challenge: $cs")
val xml = s"""<?xml version="1.0" encoding="utf-8"?><AuthTokenRequest xmlns="http://ksef.mf.gov.pl/auth/token/2.0">""" +
  s"""<Challenge>${ch("challenge").str}</Challenge><ContextIdentifier><Nip>$id</Nip></ContextIdentifier>""" +
  "<SubjectIdentifierType>certificateSubject</SubjectIdentifierType></AuthTokenRequest>"
val dbf = DocumentBuilderFactory.newInstance()
dbf.setNamespaceAware(true)
val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml.getBytes("UTF-8")))
val XADES = "http://uri.etsi.org/01903/v1.3.2#"
val DS = XMLSignature.XMLNS
def el(ns: String, name: String, text: String = "") =
  val e = doc.createElementNS(ns, name)
  if text.nonEmpty then e.setTextContent(text)
  e
val qp = el(XADES, "xades:QualifyingProperties")
qp.setAttribute("Target", "#Signature")
val sp = el(XADES, "xades:SignedProperties")
sp.setAttribute("Id", "SignedProperties")
val ssp = el(XADES, "xades:SignedSignatureProperties")
ssp.appendChild(el(XADES, "xades:SigningTime", Instant.now().minusSeconds(60).toString))
val sc = el(XADES, "xades:SigningCertificate")
val c = el(XADES, "xades:Cert")
val cd = el(XADES, "xades:CertDigest")
val dm = el(DS, "ds:DigestMethod")
dm.setAttribute("Algorithm", DigestMethod.SHA256)
cd.appendChild(dm)
cd.appendChild(el(DS, "ds:DigestValue", Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256")
  .digest(cert.getEncoded))))
val is = el(XADES, "xades:IssuerSerial")
is.appendChild(el(DS, "ds:X509IssuerName", cert.getIssuerX500Principal.getName))
is.appendChild(el(DS, "ds:X509SerialNumber", cert.getSerialNumber.toString))
c.appendChild(cd); c.appendChild(is); sc.appendChild(c); ssp.appendChild(sc); sp.appendChild(ssp); qp.appendChild(sp)

val fac = XMLSignatureFactory.getInstance("DOM")
val sha256 = fac.newDigestMethod(DigestMethod.SHA256, null)
val exc = fac.newTransform(CanonicalizationMethod.EXCLUSIVE, null.asInstanceOf[TransformParameterSpec])
val whole = fac.newReference("", sha256, java.util.List.of(fac.newTransform(Transform.ENVELOPED,
  null.asInstanceOf[TransformParameterSpec]), exc), null, null)
val props = fac.newReference("#SignedProperties", sha256, java.util.List.of(exc),
  "http://uri.etsi.org/01903#SignedProperties", null)
val info = fac.newSignedInfo(fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
  null.asInstanceOf[C14NMethodParameterSpec]), fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
  java.util.List.of(whole, props))
val kif = fac.getKeyInfoFactory
val keyInfo = kif.newKeyInfo(java.util.List.of(kif.newX509Data(java.util.List.of(cert))))
val obj = fac.newXMLObject(java.util.List.of(DOMStructure(qp)), null, null, null)
val sig = fac.newXMLSignature(info, keyInfo, java.util.List.of(obj), "Signature", null)
val ctx = DOMSignContext(key, doc.getDocumentElement)
ctx.setDefaultNamespacePrefix("ds")
ctx.setIdAttributeNS(sp, null, "Id")
sig.sign(ctx)
val signed =
  val w = StringWriter()
  TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(w))
  w.toString

// ---- signed in, redeemed
val (as, auth) = call("POST", "/auth/xades-signature?verifyCertificateChain=false", signed, "application/xml")
require(as == 202, s"xades-signature: $as $auth")
val ref = auth("referenceNumber").str
val authToken = auth("authenticationToken")("token").str
def until[A](what: String, tries: Int = 30)(f: => Option[A]): A =
  Iterator.range(0, tries).map { _ => val r = f; if r.isEmpty then Thread.sleep(1000); r }.collectFirst {
    case Some(a) => a
  }.getOrElse(sys.error(s"$what: not done"))
until("sign-in") {
  val (s, j) = call("GET", s"/auth/$ref", bearer = authToken)
  val code = scala.util.Try(j("status")("code").num.toInt).getOrElse(0)
  if code != 100 && code != 200 then sys.error(s"sign-in: $s $j")
  Option.when(code == 200)(())
}
val (rs, redeemed) = call("POST", "/auth/token/redeem", bearer = authToken)
require(rs == 200, s"redeem: $rs")
val access = redeemed("accessToken")("token").str

// ---- the KSeF token
val (ts, t) = call("POST", "/tokens", ujson.write(ujson.Obj("permissions" -> ujson.Arr("InvoiceWrite", "InvoiceRead"),
  "description" -> "okay-shop TEST (fictional NIP)")), bearer = access)
require(ts == 202, s"tokens: $ts $t")
val tokenRef = t("referenceNumber").str
until("token") {
  val (_, j) = call("GET", s"/tokens/$tokenRef", bearer = access)
  scala.util.Try(j("status").str).toOption.filter(_ == "Active").map(_ => ())
}
val file = out.resolve("ksef-test.json")
Files.writeString(file, ujson.write(ujson.Obj("nip" -> id, "token" -> t("token").str, "reference" -> tokenRef)))
Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
println(s"TEST NIP $id, KSeF token $tokenRef Active, written to $file")
