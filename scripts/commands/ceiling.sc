//> using scala 3.3.8
//> using dep com.lihaoyi::ujson:4.1.0
/*
 * THE CEILING OF THE MASTER'S COMMANDS (specs/commands.md, step A0): a large model reads each phrase of
 * phrases.tsv into a command frame, choosing every value among the options a fictional shop offers; the frame is
 * compared with the one written by hand. What the large model misses is the frame language's gap (or the
 * phrase's), not a small model's.
 *
 *   scala-cli run scripts/commands/ceiling.sc -- [phrases.tsv] [out.tsv]
 *   env: GATEWAY (http://127.0.0.1:8090), MODEL (mlx-community/Qwen3.6-35B-A3B-4bit), THINK=1 (thinking on)
 */
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

val here    = Path.of(scriptPath).getParent
val phrases = Path.of(args.lift(0).getOrElse(here.resolve("phrases.tsv").toString))
val out     = Path.of(args.lift(1).getOrElse(here.resolve("ceiling-out.tsv").toString))
val gateway = sys.env.getOrElse("GATEWAY", "http://127.0.0.1:8090")
val model   = sys.env.getOrElse("MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit")
val think   = sys.env.get("THINK").contains("1")

val shop =
  """Today is Monday 2026-11-16. The shop works 09:00-19:00; a time is HH:MM on a half hour.
    |Days: 2026-11-16 Mon, 2026-11-17 Tue, 2026-11-18 Wed, 2026-11-19 Thu, 2026-11-20 Fri, 2026-11-21 Sat,
    |2026-11-22 Sun, 2026-11-23 Mon, 2026-11-24 Tue, 2026-11-25 Wed, 2026-11-26 Thu, 2026-11-27 Fri,
    |2026-11-28 Sat, 2026-11-29 Sun.
    |Masters: olena (Olena, Елена, Олена), marek (Marek, Марек, Марк).
    |Services: haircut, beard, color, manicure.
    |Bookings: b1 Anna Kowalska, haircut, olena, 2026-11-17 10:00; b2 Piotr Nowak, beard, marek, 2026-11-17 14:00;
    |b3 Oksana Melnyk, color, olena, 2026-11-18 12:00; b4 Ivan Petrenko, haircut, marek, 2026-11-19 16:30;
    |b5 Maria Wiśniewska, manicure, olena, 2026-11-20 09:30.""".stripMargin

val task =
  """You turn what the shop's master says (Polish, Ukrainian, Russian or English) into ONE command, a JSON object.
    |Choose every value among the options above; never invent one. The commands:
    |{"action":"book","client":…,"service":…,"master":…,"day":…,"time":…} — client: the client's name exactly as
    |  the phrase writes it; master: a master's key, or "any" when none is named.
    |{"action":"move","booking":…,"day":…,"time":…} — the booking's NEW day and time; what the phrase does not
    |  change stays as the booking has it.
    |{"action":"cancel","booking":…}
    |{"action":"dayoff","master":…,"day":…}
    |{"action":"paid","booking":…,"amount":<a number>,"way":"cash"|"card"|"transfer"}
    |{"action":"list","day":…,"master":…} — master "any" when none is named.
    |{"action":"cannot"} — anything else.
    |Answer with the JSON object only.""".stripMargin

val client = HttpClient.newHttpClient()

def ask(phrase: String): String =
  val body = ujson.Obj(
    "model" -> model, "temperature" -> 0, "max_tokens" -> (if think then 2000 else 120),
    "chat_template_kwargs" -> ujson.Obj("enable_thinking" -> think),
    "messages" -> ujson.Arr(
      ujson.Obj("role" -> "system", "content" -> s"$shop\n\n$task"),
      ujson.Obj("role" -> "user", "content" -> phrase)))
  val request = HttpRequest.newBuilder(URI.create(s"$gateway/v1/chat/completions"))
    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.render())).build()
  val answer = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
  ujson.read(answer)("choices")(0)("message")("content").str

/** The answer's fields as the gold line writes them: `k=v;k=v`, the action apart. */
def read(answer: String): Option[(String, Map[String, String])] =
  val from = answer.lastIndexOf('{'); val to = answer.lastIndexOf('}')
  if from < 0 || to < from then None
  else scala.util.Try(ujson.read(answer.substring(from, to + 1)).obj).toOption.map { o =>
    val fields = o.collect { case (k, v) if k != "action" =>
      k -> (v match
        case ujson.Str(s) => s
        case ujson.Num(n) => if n == n.toLong then n.toLong.toString else n.toString
        case other        => other.render())
    }.toMap
    (o.get("action").map(_.str).getOrElse(""), fields)
  }

def norm(s: String): String = s.trim.toLowerCase

val rows = Files.readAllLines(phrases).asScala.toVector.filter(_.nonEmpty).map(_.split("\t", -1))
val log  = new StringBuilder("lang\tphrase\tgold\tanswer\tverdict\n")
var right = Map.empty[String, Int].withDefaultValue(0)
var total = Map.empty[String, Int].withDefaultValue(0)
var missed = Map.empty[String, Int].withDefaultValue(0)

for case Array(lang, phrase, action, gold) <- rows do
  val wanted = gold.split(";").filter(_.nonEmpty).map { kv => val Array(k, v) = kv.split("=", 2); k -> v }.toMap
  val answer = ask(phrase)
  val wrong: Vector[String] = read(answer) match
    case None => Vector("unreadable")
    case Some((a, fields)) =>
      (if a != action then Vector("action") else Vector.empty) ++
        (if a != action then Vector.empty
         else (wanted.keySet ++ fields.keySet).toVector.sorted
           .filter(k => norm(wanted.getOrElse(k, "")) != norm(fields.getOrElse(k, ""))))
  total = total.updated(lang, total(lang) + 1)
  if wrong.isEmpty then right = right.updated(lang, right(lang) + 1)
  wrong.foreach(k => missed = missed.updated(k, missed(k) + 1))
  val verdict = if wrong.isEmpty then "right" else "wrong: " + wrong.mkString(",")
  log ++= s"$lang\t$phrase\t$action $gold\t${answer.replaceAll("\\s+", " ")}\t$verdict\n"
  println(s"$lang  $verdict  | $phrase")

Files.writeString(out, log.toString)
println()
total.keys.toVector.sorted.foreach(l => println(s"$l  ${right(l)}/${total(l)}"))
println(s"all ${right.values.sum}/${total.values.sum}; missed by field: " +
  missed.toVector.sortBy(-_._2).map((k, n) => s"$k $n").mkString(", "))
println(s"model $model, thinking ${if think then "on" else "off"}; written $out")
