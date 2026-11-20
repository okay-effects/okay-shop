package okay.shop.commands.bench

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Duration
import okay.effects.json.Json
import okay.frames.Choice
import okay.shop.commands.{Command, Commands, Computed, Desk, Words}
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
 *      nominative, A0);
 * and, from A2c on, before (ii): the organ does not refute it — its command is reachable in `Computed` (`refused`).
 * Everything else is written to the rejected with its reason.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Teach <from> <until>"
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Teach held|more|held3 <from> <until>"   (A2b, A2c)
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

  /**
   * WHY A WRITTEN TEXT IS NOT KEPT, before the teacher is asked to read it back — None when it may be. `code`: it
   * says the booking's reference; `close`: its command does not close over its own options; `organ` (A2c, THE
   * GATE): the command is not reachable in `Computed` — the text says another day or time than its command has (a
   * weekday with its "next" dropped, a garbled time), or one the desk does not offer.
   */
  def refused(desk: Desk, text: String, meant: Command): Option[String] =
    val path = Commands.path(desk, meant)
    val code = path.collectFirst { case Choice("booking", ref) => ref }
    if code.exists(text.contains) then Some("code")
    else if !Commands.close(desk, text, path).contains(meant) then Some("close")
    else if !Texts.reachable(Computed)(desk, text, meant) then Some("organ")
    else None

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
            val why = refused(desk, text, m)
            if why.nonEmpty then no(why.get)
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
  /** a text's kind: its action, a move by a difference apart; a slice's text (A2c) has it after its source's `:` */
  def kind(s: Said): String = s.source.split(':').lift(1)
    .getOrElse(if s.source == "held-rel" then "move-rel" else s.path.head.option)
  private val nearest = writing + "\n- A weekday alone means the NEAREST such day counted from today. For a day a " +
    "week or more ahead say \"next week\" with the weekday, or the day's number." + beside
  /** A2e, the operator's rule, told to the teacher (`When.nearBooked`): how the new day of a move is named once
   * the phrase names the booked day. `Gen` is not changed — `Teach.draw` must go on giving the attempts of the
   * slices already written; a text that breaks the rule is rejected by the gate */
  private def beside = "\n- In a MOVE whose phrase names the BOOKED day, a weekday alone for the NEW day is " +
    "counted from the booking, not from today: it means the such weekday NEAREST the booked day, at most three " +
    "days before or after it. If the new day is any other day, do not name it by a weekday alone: say the " +
    "day's number, or \"next week\" with the weekday. If the phrase does not name the booked day, the rule " +
    "above holds."
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
   * A SLICE FILLED TO A QUOTA (A2c, the data loop): texts over desks of the slice's own — seeds `from + j`, a desk
   * an attempt, `span` of them, drawn by nothing else — until every kind has its number in every language. A kind
   * is an action, or one of: `move-rel` (a move by a difference, said plainly), `move-ind` and `move-rel-ind` (a
   * move to a new day or time, and one by a difference, said INDIRECTLY: `Gen.indirect`), `cancel-ind` (a client
   * who will not come), `book-ind` (a new client's wish). Whose turn it is is the attempt's number's alone, never
   * the desk's (A2b).
   */
  final case class Slice(name: String, from: Long, quota: Map[String, Int]):
    val span = 100000
    def texts: Path = out.resolve(s"$name.jsonl")
    /** the kinds in turn: each as often as its quota, in a fixed order with no pattern */
    val turn: Vector[String] =
      new Random(from).shuffle(quota.toVector.sortBy(_._1).flatMap((k, n) => Vector.fill(n)(k)))

  /**
   * MORE TEXTS FOR THE TRAINING (A2c; prepared in A2b, never run in that shape): what A2's texts have next to none
   * of, a language — moves said indirectly, 40 to a new day or time and 40 by a difference; 30 moves by a
   * difference said plainly; the contrasts, 30 clients who will not come and 20 new clients' wishes; and, for the
   * reader that takes a list for `cannot`, 30 lists and 15 cancels in everyday words. Training desks of their
   * own, seeds from `moreFrom`: neither A2's nor a held-out slice's.
   */
  val moreFrom = 200000L
  val more: Slice = Slice("more", moreFrom, Map("move-ind" -> 40, "move-rel-ind" -> 40, "move-rel" -> 30,
    "cancel-ind" -> 30, "book-ind" -> 20, "list" -> 30, "cancel" -> 15))

  /**
   * THE THIRD HELD-OUT SLICE (A2c): new desks again — seeds from `held3From`, drawn by no training and by neither
   * earlier slice — with every action and, a language, 10 moves said indirectly to a new day or time and 10 by a
   * difference, and their contrasts. 90 a language, 360 in all.
   */
  val held3From = 600000L
  val held3: Slice = Slice("held3", held3From, Map("book" -> 15, "book-ind" -> 5, "move" -> 6, "move-rel" -> 6,
    "move-ind" -> 10, "move-rel-ind" -> 10, "cancel" -> 6, "cancel-ind" -> 6, "dayoff" -> 5, "paid" -> 8,
    "list" -> 8, "cannot" -> 5))

  /** the seeds every source of texts draws its desks from, `from` until `until`: no two may meet (`GenSuite`).
   * A2's teacher drew 1000 + i / 8 for attempts under 4320; the canonical texts 100000 + i / 4 */
  val seeds: Vector[(String, Long, Long)] = Vector(("teacher", 1000L, 1540L), ("canon", 100000L, 200000L),
    ("held", heldFrom, heldFrom + 100000)) ++ Vector(more, held3).map(s => (s.name, s.from, s.from + s.span))

  /** what an attempt of a slice asks the teacher for */
  final case class Asked(seed: Long, lang: Lang, desk: Desk, kind: String, command: Command, request: String,
                         source: String)

  /** THE ATTEMPT `j` OF A SLICE, no model: its language, desk, kind, command and the request to the teacher. None
   * when the desk has nothing for the kind (no booking to move, no master to free) */
  def draw(slice: Slice, j: Int): Option[Asked] =
    val l = Lang.values(j % 4)
    val r = new Random(j * 7L + slice.from)
    val seed = slice.from + j
    val desk = Gen.desk(seed)
    val kind = slice.turn((j / 4) % slice.turn.size)
    def plain(action: String, style: Option[String]) = Some(Gen.command(r, desk, action, l))
      .filter(c => Commands.path(desk, c).head.option == action).map(c => (c, Option.empty[String], style))
    val drawn = kind match
      case "move-ind" | "move-rel-ind" =>
        Gen.indirect(r, desk, kind == "move-rel-ind").map((c, note, way) => (c, note, Some(way)))
      case "move-rel"        => Gen.relative(r, desk).map((c, note) => (c, Some(note), Option.empty[String]))
      case "cancel-ind"      => plain("cancel", Some(Gen.unwilling))
      case "book-ind"        => plain("book", Some(Gen.wished))
      case "list" | "cancel" => plain(kind, Some(everyday))
      case action            => plain(action, None)
    drawn.map((c, note, style) =>
      Asked(seed, l, desk, kind, c, request(r, desk, c, l, note, style), s"${slice.name}:$kind"))

  /** a slice asked for until its quota is filled; a kind in a language is given up after six times its number of
   * requests, said so at the end, and the exit is 2. Appends: to go on after a stop, start from the attempt it
   * stopped at */
  private def fill(slice: Slice, from: Int, until: Int): Unit =
    require(until <= slice.span, s"${slice.name} has ${slice.span} seeds")
    var have = (if Files.exists(slice.texts) then Texts.load(slice.texts) else Vector.empty)
      .groupBy(s => (kind(s), s.lang)).view.mapValues(_.size).toMap.withDefaultValue(0)
    var tries = Map.empty[(String, String), Int].withDefaultValue(0)
    def open(at: (String, String)) = have(at) < slice.quota(at._1) && tries(at) < 6 * slice.quota(at._1)
    def wanted = slice.quota.keys.exists(k => Lang.values.exists(l => open((k, l.code))))
    var j = from
    var asked = 0
    while j < until && wanted do
      for a <- draw(slice, j) if open((a.kind, a.lang.code)) do
        val at = (a.kind, a.lang.code)
        asked += 1
        tries = tries.updated(at, tries(at) + 1)
        tried(a.seed, a.lang, a.desk, a.command, a.request, a.source, nearest) match
          case Right(said) => append(s"${slice.name}.jsonl", said.json); have = have.updated(at, have(at) + 1)
          case Left((reason, phrase, back)) => append(s"${slice.name}-rejected.tsv",
            s"$j\t${a.kind}\t$reason\t$phrase\t${back.replaceAll("\\s+", " ")}")
        if asked % 25 == 0 then println(s"$j: asked $asked; kept " + slice.quota.keys.toVector.sorted.map(k =>
          s"$k ${Lang.values.map(l => have((k, l.code))).mkString("/")}").mkString(", "))
      j += 1
    val short = for (k, n) <- slice.quota.toVector.sorted; l <- Lang.values if have((k, l.code)) < n
      yield s"$k ${l.code} ${have((k, l.code))}/$n"
    println(s"stopped at $j, asked $asked; " +
      (if short.isEmpty then "every share filled" else "NOT filled: " + short.mkString(", ")))
    if short.nonEmpty then sys.exit(2) // a queue does not go on to train or read on a slice that is short

  def main(args: Array[String]): Unit =
    Files.createDirectories(out)
    if args.head == "held" then held(args(1).toInt, args(2).toInt)
    else if args.head == "more" then fill(more, args(1).toInt, args(2).toInt)
    else if args.head == "held3" then fill(held3, args(1).toInt, args(2).toInt)
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
