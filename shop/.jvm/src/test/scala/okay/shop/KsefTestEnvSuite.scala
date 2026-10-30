package okay.shop

import okay.scala3.*
import okay.scala3.std.{Async, Clock}
import okay.serverless.cloud.{Cloud, Crypto, JdkFetch, Platform}
import okay.shop.invoice.*
import okay.shop.model.{Method, Of}

import java.nio.file.{Files, Paths}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * KSeF'S TEST ENVIRONMENT, FOR REAL (specs/shop.md "KSeF"): an FA(3) invoice of a fictional seller sent by `KsefHttp`
 * (the JDK's HTTP client and crypto) to api-test.ksef.mf.gov.pl, its status asked until its KSeF number, its UPO
 * fetched. Run only when `KSEF_TEST_FILE` names the file `scripts/ksef-test-token.sc` wrote (a random test NIP and
 * its TE token); never against production — the environment is fixed to `KsefEnv.Test` here. Writes the XML, the
 * KSeF number and the UPO beside that file
 */
class KsefTestEnvSuite extends munit.FunSuite:
  override val munitTimeout = 5.minutes
  private val file = sys.env.get("KSEF_TEST_FILE").map(Paths.get(_))

  test("an invoice sent to KSeF's TEST environment: accepted, its KSeF number, its UPO") {
    assume(file.exists(Files.exists(_)), "KSEF_TEST_FILE not set")
    given scala.concurrent.ExecutionContext = Async.defaultContext
    val j = ujsonLike(Files.readString(file.get))
    val nip = j("nip")
    val seller = Seller("TEST Okay Shop (fikcyjny)", nip, "ul. Testowa 1", "00-001", "Warszawa")
    val buyer = Buyer("TEST Nabywca (fikcyjny)", "2222222222", "ul. Przykładowa 2", "30-001", "Kraków")
    val now = System.currentTimeMillis()
    val day = okay.shop.model.Day.of(now)
    val inv = Invoice("000001", s"TEST/${now % 1000000}", InvoiceKind.Vat, Of.Booking, "TEST-1", day, day, seller,
      buyer, Vector(Item("Usługa testowa", 1, 12300, Rate.R23)), Method.Transfer, day, "test", now)
    val xml = Fa3.xml(inv, okay.shop.model.Civil.iso(now))
    type P[X] = (Cloud + Crypto + Ksef)[X]
    def poll(s: KsefSent, left: Int): Either[String, KsefStatus] ! P =
      Ksef.status[P](s.session, s.reference).flatMap {
        case Right(st) if st.waiting && left > 0 => Clock.sleep[P](2000).flatMap(_ => poll(s, left - 1))
        case other => pure(other)
      }
    val program: (KsefSent, KsefStatus, String) ! P = Ksef.send[P](xml).flatMap {
      case Left(why) => throw RuntimeException("send: " + why)
      case Right(sent) => poll(sent, 60).flatMap {
          case Right(st) if st.done => Ksef.upo[P](sent.session, st.number).map(u => (sent, st, u.fold(w =>
            throw RuntimeException("upo: " + w), identity)))
          case other => throw RuntimeException("status: " + other)
        }
    }
    val platform = Platform(fetch = JdkFetch())
    val run = platform.run(Crypto.run[Cloud + Crypto, Cloud, (KsefSent, KsefStatus, String)](
      KsefHttp.run[P, Cloud + Crypto, (KsefSent, KsefStatus, String)](KsefAccount(KsefEnv.Test, nip, j("token")))(
        program)))
    val (sent, st, upo) = Await.result(Async.toFuture(run), 5.minutes)
    val dir = file.get.getParent
    Files.writeString(dir.resolve("sent.xml"), xml)
    Files.writeString(dir.resolve("upo.xml"), upo)
    Files.writeString(dir.resolve("result.txt"), s"session ${sent.session}\ninvoice ${sent.reference}\n" +
      s"status ${st.code} ${st.description}\nksefNumber ${st.number}\nnumber ${inv.number}\n")
    assert(st.number.startsWith(nip + "-"), st)
    assert(upo.contains(st.number), upo.take(300))
  }

  /** the token file's two fields, read without a JSON library of the tests */
  private def ujsonLike(text: String): Map[String, String] =
    "\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"".r.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap
