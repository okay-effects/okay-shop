package okay.shop.commands.bench

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration
import okay.effects.json.Json
import okay.frames.Choice
import okay.shop.commands.{Command, Commands, Desk, Words}
import okay.shop.model.{Lang, Money}
import scala.util.{Random, Try}

/**
 * THE TEACHER'S PHRASES (specs/commands.md A2): a large model (Qwen3.6-35B through the local gateway, thinking
 * off) is given a command in canonical words and writes how a master would say it in a hurry, a new client's name
 * marked `[[…]]`. A phrase is KEPT only if
 *  (i) its marks stripped, the command with the marked span for its client closes over the phrase's own options
 *      (the span is a client's option, the amount one of the text's numbers), and
 *  (ii) the teacher, asked apart to read the phrase back into a command given the desk, answers the same command
 *      (every line but the client's words, which are the marked span's: a large model writes a name back in the
 *      nominative, A0).
 * Everything else is written to the rejected with its reason.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Teach <from> <until>"
 *   env: COMMANDS_OUT (the directory of teacher.jsonl and teacher-rejected.tsv), GATEWAY, MODEL
 */
object Teach:
  val out: Path = Path.of(sys.env.getOrElse("COMMANDS_OUT",
    sys.props("user.home") + "/work/my/okay-shop-private/commands"))
  /** the kept texts (COMMANDS_TEXTS: another file of them) */
  def texts: Path = Path.of(sys.env.getOrElse("COMMANDS_TEXTS", out.resolve("teacher.jsonl").toString))
  private val gateway = sys.env.getOrElse("GATEWAY", "http://127.0.0.1:8090")
  private val model = sys.env.getOrElse("MODEL", "mlx-community/Qwen3.6-35B-A3B-4bit")
  private val client = HttpClient.newHttpClient()
  private val names = Map(Lang.ru -> "Russian", Lang.uk -> "Ukrainian", Lang.pl -> "Polish", Lang.en -> "English")

  private val writing =
    """You write what a master of a small shop (a salon, a workshop, a studio) says or types to the shop's booking
      |assistant, in a hurry. You are given a command in exact canonical words; you answer with ONE phrase as the
      |master would really put it, in the language asked. The phrase alone: no quotes, no explanation.
      |
      |Rules:
      |- The phrase holds everything the command holds — who, which service, which master, the day, the time, the
      |  amount and the way of paying — said as people speak: any order, short, in the language's own grammar (a
      |  name, a service, a master inflected as the sentence needs).
      |- A day as people say it, counted from today: today, tomorrow, the day after tomorrow, a weekday, or the
      |  day's number; never the year. A time as people say it ("at three", "15:30", "half past ten").
      |- A NEW booking (Book): put the client's name in double square brackets, [[…]] — the name's words exactly as
      |  they stand in your phrase, inflected as the phrase needs, and nothing but the name. No brackets in any
      |  other command.
      |- A booking that exists is given as: its code, «the client», (service, master, day and time). NEVER say the
      |  code. Name the booking as a master would — by the client's name, and by its service, day or time if you
      |  like.
      |- "any master" and "all masters": name no master at all.
      |- An amount: the number in digits.
      |- A note may say what a move leaves as it was: do not say that part.
      |- Instead of a command you may get a topic outside the assistant's reach: write a master's request about it.
      |
      |Examples:
      |Language: English. Today: Wednesday 03.03. Command: Book: «Sophie Hall» — Back massage, Tomasz, Friday 05.03 at
      |15:00. → Put [[Sophie Hall]] down for a back massage with Tomasz, Friday at 3
      |Language: Polish. Today: środa 03.03. Command: Zapis: «Ewa Lis» — Pedicure, dowolny mistrz, czwartek 04.03,
      |godz. 10:30. → Zapisz [[Ewę Lis]] na pedicure jutro na wpół do jedenastej
      |Language: Ukrainian. Today: середа 03.03. Command: Запис: «Тарас» — Заміна мастила, Roman, середа 03.03 о
      |16:00. → [[Тараса]] на заміну мастила сьогодні о четвертій до Романа
      |Language: Russian. Today: среда 03.03. Command: Перенос: R-17 «Tom Wilson» (Диагностика, Igor, четверг 04.03 в
      |09:00) — на понедельник 08.03 в 09:00. Note: only the day changes. → Тома Уилсона с четверга перекинь на
      |понедельник
      |Language: Ukrainian. Today: середа 03.03. Command: Скасування: K4 «Леся Бойко» (Манікюр, Daria, п'ятниця 05.03
      |о 12:00). → Леся Бойко в п'ятницю не прийде, скасуй
      |Language: Russian. Today: среда 03.03. Command: Оплачено: R-2 «Наташа Белова» (Стрижка, Nina, среда 03.03 в
      |11:00) — 120 zł, Карта. → Наташа Белова заплатила 120 картой
      |Language: Polish. Today: środa 03.03. Command: Dzień wolny: Kamil, sobota 06.03. → Kamil ma wolne w sobotę
      |Language: English. Today: Wednesday 03.03. Command: Show bookings: Thursday 04.03, all masters. → What have we
      |got tomorrow?
      |Language: English. Outside topic: change the price of a service. → Raise the back massage to 200""".stripMargin

  private val reading =
    """You turn what the shop's master says (Polish, Ukrainian, Russian or English) into ONE command, a JSON object.
      |Choose every value among the options above; never invent one. A day is written as its date, YYYY-MM-DD; a
      |time as HH:MM; a service and a master by the key; a booking by its code. The commands:
      |{"action":"book","client":…,"service":…,"master":…,"day":…,"time":…} — client: the client's name exactly as
      |  the phrase writes it; master: a master's key, or "any" when none is named.
      |{"action":"move","booking":…,"day":…,"time":…} — the booking's NEW day and time; what the phrase does not
      |  change stays as the booking has it.
      |{"action":"cancel","booking":…}
      |{"action":"dayoff","master":…,"day":…}
      |{"action":"paid","booking":…,"amount":<a number>,"way":"cash"|"card"|"blik"|"transfer"|"other"}
      |{"action":"list","day":…,"master":…} — master "any" when none is named.
      |{"action":"cannot"} — anything else.
      |Answer with the JSON object only.""".stripMargin

  private val styles = Vector("terse, a few words, like a note typed with one thumb", "a plain spoken sentence",
    "colloquial, as said aloud to a colleague", "polite and full", "the day or the time first", "the name first",
    "an order, short", "as a remark about what happened or will happen")

  private def ask(system: String, user: String, temperature: Double, most: Int): Option[String] =
    val body = Json.obj("model" -> Json.Str(model), "temperature" -> Json.num(temperature),
      "max_tokens" -> Json.num(most), "chat_template_kwargs" -> Json.obj("enable_thinking" -> Json.Bool(false)),
      "messages" -> Json.arr(Json.obj("role" -> Json.Str("system"), "content" -> Json.Str(system)),
        Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(user))))
    val request = HttpRequest.newBuilder(URI.create(s"$gateway/v1/chat/completions"))
      .timeout(Duration.ofSeconds(180)).header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.render)).build()
    def once = Try(Json.fromText(client.send(request, HttpResponse.BodyHandlers.ofString()).body())("choices")
      .list.head("message")("content").string).toOption
    once.orElse(once)

  /** the teacher's answer read as a command's lines, the amount as a line writes it */
  private def lines(answer: String): Option[(String, Map[String, String])] =
    val from = answer.indexOf('{'); val to = answer.lastIndexOf('}')
    if from < 0 || to < from then None
    else Try {
      val fields = Json.fromText(answer.substring(from, to + 1)).record.map {
        case (k, Json.Str(s)) => k -> s.trim
        case (k, Json.Num(n)) => k -> Commands.amount((n * 100).toLong)
        case (k, other)       => k -> other.render
      }.toMap
      (fields.getOrElse("action", ""), fields - "action")
    }.toOption

  /** the request a text is written from: the canonical words, today, what a move leaves, a style */
  private def request(r: Random, desk: Desk, c: Command, l: Lang, noted: Option[String] = None,
                      styled: Option[String] = None): String =
    val head = s"Language: ${names(l)}. "
    val drawn = styles(r.nextInt(styles.size))
    val style = s" Style: ${styled.getOrElse(drawn)}."
    c match
      case Command.Cannot => s"${head}Outside topic: ${Gen.outside(r.nextInt(Gen.outside.size))}.$style"
      case _ =>
        val note = noted.getOrElse(c match
          case Command.Move(b, d, t) => desk.bookings.find(_.ref == b).fold("") { e =>
            if e.day == d then " Note: only the time changes." else if e.time == t then " Note: only the day changes."
            else "" }
          case _ => "")
        s"${head}Today: ${Words.day(desk.days.head, l)}. Command: ${Words.say(desk, c, l)}$note$style"

  /** a marked name is the given one's: each of its words begins as one of the given name's words does */
  private def named(span: String, name: String): Boolean =
    val of = name.toLowerCase.split(' ').map(_.take(2))
    span.toLowerCase.split(' ').forall(w => of.contains(w.take(2)))

  /** THE TEXT OF AN ATTEMPT, or why it is rejected (and what was written, what was read back) */
  def attempt(i: Int): Either[(String, String, String), Said] =
    val seed = 1000L + i / 8
    val l = Lang.values(i % 4)
    val r = new Random(i * 7L + 1)
    val desk = Gen.desk(seed)
    val c = Gen.command(r, desk, Gen.shares((i / 4) % Gen.shares.size), l)
    tried(seed, l, desk, c, request(r, desk, c, l), "teacher", writing)

  /** a command written by the teacher as asked, checked and read back */
  private def tried(seed: Long, l: Lang, desk: Desk, c: Command, asked: String, source: String, rules: String)
      : Either[(String, String, String), Said] =
    def marked(phrase: String) = "\\[\\[(.+?)\\]\\]".r.findAllMatchIn(phrase).map(_.group(1).trim).toVector
    def write = ask(rules, asked, 0.9, 80).map(_.trim.linesIterator.nextOption().getOrElse("").trim)
    // a new booking written without its one mark is asked for once more (from attempt 320 on; the pilot was not)
    val written = write match
      case Some(p) if c.isInstanceOf[Command.Book] && marked(p).size != 1 => write
      case other => other
    written match
      case None => Left(("gateway", "", ""))
      case Some(phrase) =>
        val marks = marked(phrase)
        val text = phrase.replace("[[", "").replace("]]", "").replaceAll("\\s+", " ").trim
        def no(why: String, back: String = "") = Left((why, phrase, back))
        val meant: Either[String, Command] = c match
          case b: Command.Book => marks match
            case Vector(span) if !Commands.spans(text).contains(span) => Left("span")
            case Vector(span) if !named(span, b.client) => Left("name")
            case Vector(span) => Right(b.copy(client = span))
            case _ => Left("marks")
          case _ => Right(c) // marks in another command are only stripped (from attempt 320 on)
        meant match
          case Left(why) => no(why)
          case Right(m) =>
            val path = Commands.path(desk, m)
            val code = path.collectFirst { case Choice("booking", ref) => ref }
            if code.exists(text.contains) then no("code")
            else if !Commands.close(desk, text, path).contains(m) then no("close")
            else ask(Asking.desk(desk) + "\n\n" + reading, text, 0, 120) match
              case None => no("gateway")
              case Some(answer) => lines(answer) match
                case None => no("unreadable", answer)
                case Some((action, fields)) =>
                  val back = Choice(Commands.Action, action) +: Commands.lines(action).map { line =>
                    val v = fields.getOrElse(line, "")
                    Choice(line, line match
                      case "amount" => Money.parse(v).fold(v)(Commands.amount)
                      case "client" => path.collectFirst { case Choice("client", s) => s }.getOrElse(v)
                      case "master" | "service" | "way" => v.toLowerCase
                      case "day" => "\\d{4}-\\d{2}-\\d{2}".r.findFirstIn(v).getOrElse(v)
                      case _ => v)
                  }
                  if back == path then Right(Said(seed, l.code, source, text, path))
                  else if back.head != path.head then no("read: action", answer)
                  else no("read: " + back.zip(path).collect { case (b, p) if b != p => p.hole }.mkString(","), answer)

  private def append(file: String, line: String): Unit = Files.writeString(out.resolve(file), line + "\n",
    StandardOpenOption.CREATE, StandardOpenOption.APPEND): Unit

  /**
   * THE HELD-OUT SLICE WITH EVERY ACTION IN IT (A2b): texts over NEW desks — seeds from `heldFrom` on, which no
   * training ever drew (the teacher's are 1000…, the canonical ones 100000…) — a desk an attempt, the action by
   * the attempt's number alone (so not by the desk, as `Texts.heldOut` had it), until every kind has its share in
   * every language: of 75 a language, the sealed set's shares of the actions, the moves 9 by a new day and time and
   * 6 by a difference (`Gen.relative`). A list and a cancel are asked for in everyday words. The teacher is told
   * one more rule: a weekday alone is the nearest such day.
   */
  val heldFrom = 500000L
  val heldQuota: Map[String, Int] = Map("book" -> 25, "move" -> 9, "move-rel" -> 6, "cancel" -> 10, "dayoff" -> 5,
    "paid" -> 10, "list" -> 5, "cannot" -> 5)
  def heldTexts: Path = out.resolve("held.jsonl")
  /** a held text's kind: its action, a move by a difference apart */
  def kind(s: Said): String = if s.source == "held-rel" then "move-rel" else s.path.head.option
  private val nearest = writing + "\n- A weekday alone means the NEAREST such day counted from today. For a day a " +
    "week or more ahead say \"next week\" with the weekday, or the day's number."
  private val everyday = "everyday words, said aloud as a question or a remark — not as an order to the assistant"

  private def held(from: Int, until: Int): Unit =
    var have = (if Files.exists(heldTexts) then Texts.load(heldTexts) else Vector.empty)
      .groupBy(s => (kind(s), s.lang)).view.mapValues(_.size).toMap.withDefaultValue(0)
    def wanted = heldQuota.exists((k, n) => Lang.values.exists(l => have((k, l.code)) < n))
    var j = from
    var asked = 0
    while j < until && wanted do
      val l = Lang.values(j % 4)
      val r = new Random(j * 7L + 3)
      val desk = Gen.desk(heldFrom + j)
      val action = Gen.shares((j / 4) % Gen.shares.size)
      val relative = if action == "move" && have(("move-rel", l.code)) < heldQuota("move-rel") then
        Gen.relative(r, desk) else None
      val (c, note, source) = relative match
        case Some((c, note)) => (c, Some(note), "held-rel")
        case None => (Gen.command(r, desk, action, l), None, "held")
      val k = if source == "held-rel" then "move-rel" else Commands.path(desk, c).head.option
      if have((k, l.code)) < heldQuota(k) then
        val style = if k == "list" || k == "cancel" then Some(everyday) else None
        asked += 1
        tried(heldFrom + j, l, desk, c, request(r, desk, c, l, note, style), source, nearest) match
          case Right(said) => append("held.jsonl", said.json); have = have.updated((k, l.code), have((k, l.code)) + 1)
          case Left((reason, phrase, back)) =>
            append("held-rejected.tsv", s"$j\t$k\t$reason\t$phrase\t${back.replaceAll("\\s+", " ")}")
        if asked % 25 == 0 then println(s"$j: asked $asked; kept " + heldQuota.keys.toVector.sorted.map(k =>
          s"$k ${Lang.values.map(l => have((k, l.code))).mkString("/")}").mkString(", "))
      j += 1
    println(s"stopped at $j, asked $asked; every share ${if wanted then "NOT " else ""}filled")

  /**
   * MORE TEXTS FOR THE TRAINING (A2b, when the held-out slice says the reader is the gap): what A2's texts have
   * next to none of — moves by a difference (three attempts in five), lists and cancels in everyday words (one
   * each) — over training desks of their own, seeds from `moreFrom`: neither A2's nor the held-out slice's.
   */
  val moreFrom = 200000L
  def moreTexts: Path = out.resolve("more.jsonl")

  private def more(from: Int, until: Int): Unit =
    var kept = 0
    var asked = 0
    for j <- from until until do
      val l = Lang.values(j % 4)
      val r = new Random(j * 7L + 5)
      val desk = Gen.desk(moreFrom + j)
      val drawn: Option[(Command, Option[String], Option[String], String)] = (j / 4) % 5 match
        case 3 => Some((Gen.command(r, desk, "list", l), None, Some(everyday), "more"))
        case 4 => if desk.bookings.isEmpty then None
          else Some((Gen.command(r, desk, "cancel", l), None, Some(everyday), "more"))
        case _ => Gen.relative(r, desk).map((c, note) => (c, Some(note), None, "more-rel"))
      for (c, note, style, source) <- drawn do
        asked += 1
        tried(moreFrom + j, l, desk, c, request(r, desk, c, l, note, style), source, nearest) match
          case Right(said) => kept += 1; append("more.jsonl", said.json)
          case Left((reason, phrase, back)) =>
            append("more-rejected.tsv", s"$j\t$source\t$reason\t$phrase\t${back.replaceAll("\\s+", " ")}")
        if asked % 50 == 0 then println(s"${j + 1}: kept $kept of $asked")
    println(s"kept $kept of $asked")

  def main(args: Array[String]): Unit =
    Files.createDirectories(out)
    if args.head == "held" then held(args(1).toInt, args(2).toInt)
    else if args.head == "more" then more(args(1).toInt, args(2).toInt)
    else first(args(0).toInt, args(1).toInt)

  /** A2's texts: the attempts of a range */
  private def first(from: Int, until: Int): Unit =
    var kept = 0
    var why = Map.empty[String, Int].withDefaultValue(0)
    val t0 = System.nanoTime()
    for i <- from until until do
      attempt(i) match
        case Right(said) => kept += 1; append("teacher.jsonl", said.json)
        case Left((reason, phrase, back)) =>
          val key = reason.takeWhile(_ != ',')
          why = why.updated(key, why(key) + 1)
          append("teacher-rejected.tsv", s"$i\t$reason\t$phrase\t${back.replaceAll("\\s+", " ")}")
      if (i + 1 - from) % 50 == 0 || i + 1 == until then
        println(f"${i + 1}: kept $kept of ${i + 1 - from} (${(System.nanoTime() - t0) / 1e9}%.0f s); rejected: " +
          why.toVector.sortBy(-_._2).map((k, n) => s"$k $n").mkString(", "))
