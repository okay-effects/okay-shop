package okay.shop.commands.bench

import java.nio.file.{Files, Path}
import okay.frames.{Choice, Laws}
import okay.shop.commands.{Booked, Commands, Computed, Desk}
import okay.shop.model.T
import scala.jdk.CollectionConverters.*

/** THE SEALED SET (specs/commands.md): the 60 phrases of scripts/commands/phrases.tsv over their fictional shop —
 * the desk `CommandsSuite` checks the laws on. Read here, measured, never trained on */
object Sealed:
  val desk: Desk = Desk(
    days = (16 to 29).toVector.map(d => s"2026-11-$d"),
    times = (540 until 1140 by 30).toVector,
    services = Vector("haircut" -> T("Стрижка", "Strzyżenie", "Haircut", "Стрижка"),
      "beard" -> T("Борода", "Broda", "Beard", "Борода"),
      "color" -> T("Окрашивание", "Koloryzacja", "Colouring", "Фарбування"),
      "manicure" -> T("Маникюр", "Manicure", "Manicure", "Манікюр")),
    masters = Vector("olena" -> "Olena", "marek" -> "Marek"),
    bookings = Vector(Booked("b1", "Anna Kowalska", "haircut", "olena", "2026-11-17", 600),
      Booked("b2", "Piotr Nowak", "beard", "marek", "2026-11-17", 840),
      Booked("b3", "Oksana Melnyk", "color", "olena", "2026-11-18", 720),
      Booked("b4", "Ivan Petrenko", "haircut", "marek", "2026-11-19", 990),
      Booked("b5", "Maria Wiśniewska", "manicure", "olena", "2026-11-20", 570)))

  /** the phrases as texts: language, phrase, the hand-written choices */
  def phrases: Vector[Said] =
    Files.readAllLines(Path.of("scripts/commands/phrases.tsv")).asScala.toVector.filter(_.nonEmpty)
      .map(_.split("\t", -1)).collect { case Array(lang, text, action, gold) =>
        Said(0, lang, "sealed", text, Asking.path(action, gold), Some(desk))
      }

/**
 * A SET READ BY A READER (specs/commands.md A2), each answer written to a TSV: language, phrase, the meant
 * command, the command read (`-` when no command came out), the least margin.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet <set>[+when] <adapter|none> <out.tsv> …"
 * `sealed`: the 60 phrases; `new`: the held-out slice with every action (`Teach held`, A2b); `held`: the teacher's
 * texts over the first held-out desks (COMMANDS_TEXTS). `+when`: read in `Computed`, the days and times computed
 * (A2b). The file's last column is the text's kind (`Teach.kind`). Several readings, three arguments each, are
 * one job on the GPU.
 */
object ReadSet:
  def line(path: Vector[Choice]): String = (path.head.option + " " + Asking.fields(path)).trim

  def main(args: Array[String]): Unit = args.grouped(3).foreach { case Array(asked, adapter, out) =>
    val which = asked.stripSuffix("+when")
    val space = if asked.endsWith("+when") then Computed else Commands
    val set = which match
      case "sealed" => Sealed.phrases
      case "new" => Texts.load(Teach.heldTexts)
      case _ =>
        val texts = Texts.load(Teach.texts)
        // `held`: the texts over the held-out desks; `seen`: a hundred of those trained on (the pipeline's check)
        if which == "held" then texts.filter(s => Texts.heldOut(s.seed))
        else texts.filterNot(s => Texts.heldOut(s.seed)).take(100)
    val unreachable = set.count(s => s.command.forall(c => Laws.reach(space)(s.desk, s.text, c).nonEmpty))
    println(s"$asked by $adapter: ${set.size} texts, ${set.size - unreachable} reachable by choices")
    val reader = Reader.load(Some(adapter).filter(_ != "none"))
    val t0 = System.nanoTime()
    val rows = set.map { s =>
      val r = reader.read(s.desk, s.text, space)
      val got = r.meaning.map(c => line(Commands.path(s.desk, c))).getOrElse("-")
      val margin = if r.margin.isInfinite then 99.0 else r.margin
      println(f"${s.lang} ${if got == line(s.path) then "right" else "WRONG"} $margin%5.2f ${s.text} → $got")
      f"${s.lang}\t${s.text}\t${line(s.path)}\t$got\t$margin%.3f\t${Teach.kind(s)}"
    }
    Files.write(Path.of(out), ("lang\tphrase\tgold\tread\tmargin\tkind" +: rows).asJava)
    println(f"${rows.size} read, ${(System.nanoTime() - t0) / 1e9 / rows.size}%.2f s a phrase; written $out")
    reader.close()
  }

/**
 * THE TABLES of readers' answers (`ReadSet`'s files): each reader's right, wrong and unread, by language and by
 * line; and every pair and all of them together as one reader with a doubt — the same command from each, or the
 * master is asked.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Tables <a.tsv> <b.tsv> …"
 */
object Tables:
  /** `kind`: the action, a move by a difference apart (`move-rel`) — the gold's action in a file without it */
  private final case class Row(lang: String, phrase: String, gold: String, read: String, kind: String)
  private def load(file: String): Vector[Row] = Files.readAllLines(Path.of(file)).asScala.toVector.drop(1)
    .map(_.split("\t", -1)).collect {
      case Array(l, p, g, r, _)    => Row(l, p, g, r, g.takeWhile(_ != ' '))
      case Array(l, p, g, r, _, k) => Row(l, p, g, r, k)
    }
  private def parts(line: String): (String, Map[String, String]) =
    val (action, rest) = line.span(_ != ' ')
    (action, Asking.path(action, rest.trim).drop(1).map(c => c.hole -> c.option).toMap)

  def main(args: Array[String]): Unit =
    val readers = args.toVector.map(f => Path.of(f).getFileName.toString.stripSuffix(".tsv") -> load(f))
    val langs = readers.head._2.map(_.lang).distinct.sorted
    println("| reader | right | wrong | unread | " + langs.map(_ + " right").mkString(" | ") + " |")
    println("| --- | --- | --- | --- | " + langs.map(_ => "---").mkString(" | ") + " |")
    for (name, rows) <- readers do
      val right = rows.count(r => r.read == r.gold); val unread = rows.count(_.read == "-")
      println(s"| $name | $right | ${rows.size - right - unread} | $unread | " + langs.map { l =>
        val mine = rows.filter(_.lang == l)
        s"${mine.count(r => r.read == r.gold)}/${mine.size}"
      }.mkString(" | ") + " |")
    println()
    // by kind: right/wrong/unread of each
    val kinds = Vector("book", "move", "move-rel", "cancel", "dayoff", "paid", "list", "cannot")
      .filter(k => readers.head._2.exists(_.kind == k))
    println("| reader | " + kinds.map(_ + " r/w/u").mkString(" | ") + " |")
    println("| --- | " + kinds.map(_ => "---").mkString(" | ") + " |")
    for (name, rows) <- readers do
      println(s"| $name | " + kinds.map { k =>
        val mine = rows.filter(_.kind == k)
        val right = mine.count(r => r.read == r.gold); val unread = mine.count(_.read == "-")
        s"$right/${mine.size - right - unread}/$unread"
      }.mkString(" | ") + " |")
    println()
    val holes = Vector("action", "client", "service", "master", "day", "time", "booking", "amount", "way")
    println("| reader | " + holes.mkString(" | ") + " |")
    println("| --- | " + holes.map(_ => "---").mkString(" | ") + " |")
    for (name, rows) <- readers do
      val counted = rows.filter(_.read != "-").map(r => parts(r.gold) -> parts(r.read))
      println(s"| $name | " + holes.map {
        case "action" => s"${rows.count(r => parts(r.gold)._1 == parts(r.read)._1)}/${rows.size}"
        case h =>
          val with_ = counted.filter { case ((ga, g), (ra, _)) => ga == ra && g.contains(h) }
          s"${with_.count { case ((_, g), (_, r)) => g(h) == r.getOrElse(h, "") }}/${with_.size}"
      }.mkString(" | ") + " |")
    println()
    println("| readers agreeing | right | asked | wrong | " + langs.map(_ + " r/a/w").mkString(" | ") + " |")
    println("| --- | --- | --- | --- | " + langs.map(_ => "---").mkString(" | ") + " |")
    for n <- 2 to readers.size; together <- readers.combinations(n) do
      val verdicts = together.head._2.indices.map { i =>
        val answers = together.map(_._2(i).read).distinct
        val gold = together.head._2(i)
        gold.lang -> (if answers.size > 1 || answers.head == "-" then "asked"
          else if answers.head == gold.gold then "right" else "wrong")
      }
      def count(of: Seq[(String, String)]) = Vector("right", "asked", "wrong").map(v => of.count(_._2 == v))
      println(s"| ${together.map(_._1).mkString(" + ")} | ${count(verdicts).mkString(" | ")} | " +
        langs.map(l => count(verdicts.filter(_._1 == l)).mkString("/")).mkString(" | ") + " |")
    println()
    // every pair agreeing, by kind: right/asked/wrong
    println("| readers agreeing | " + kinds.map(_ + " r/a/w").mkString(" | ") + " |")
    println("| --- | " + kinds.map(_ => "---").mkString(" | ") + " |")
    for together <- readers.combinations(2) do
      val rows = together.head._2
      println(s"| ${together.map(_._1).mkString(" + ")} | " + kinds.map { k =>
        val of = rows.indices.filter(i => rows(i).kind == k).map { i =>
          val answers = together.map(_._2(i).read).distinct
          if answers.size > 1 || answers.head == "-" then "asked" else if answers.head == rows(i).gold then "right"
          else "wrong"
        }
        Vector("right", "asked", "wrong").map(v => of.count(_ == v)).mkString("/")
      }.mkString(" | ") + " |")
    println()
    for (name, rows) <- readers; r <- rows if r.read != r.gold do
      println(s"$name\t${r.lang}\t${r.kind}\t${r.phrase}\t${r.gold}\t${r.read}")

/**
 * THE ORGAN ON TEXTS, NO MODEL (specs/commands.md A2b): for each text's day and time lines, what `Computed` leaves
 * of the options — the one meant (`settled`), several with the meant among them (`narrowed`), all (`unsaid`), or
 * not the meant one (`LOST`: the organ is wrong, or the text's command is) — and every lost line.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Narrowing <texts.jsonl> [held]"
 */
object Narrowing:
  def of(set: Vector[Said]): Vector[(Said, String, String)] = for
    s <- set
    (c, i) <- s.path.zipWithIndex if c.hole == "day" || c.hole == "time"
  yield
    val wide = Commands.next(s.desk, s.text, s.path.take(i)).fold(Vector.empty[String])(_.options)
    val left = Computed.next(s.desk, s.text, s.path.take(i)).fold(Vector.empty[String])(_.options)
    (s, c.hole, if !left.contains(c.option) then "LOST" else if left.size == 1 then "settled"
      else if left.size < wide.size then "narrowed" else "unsaid")

  def main(args: Array[String]): Unit =
    val all = Texts.load(Path.of(args(0)))
    val set = if args.lift(1).contains("held") then all.filter(s => Texts.heldOut(s.seed)) else all
    val rows = of(set)
    for
      line <- Vector("day", "time")
      (lang, mine) <- rows.filter(_._2 == line).groupBy(_._1.lang).toVector.sortBy(_._1)
    do
      println(s"$line $lang: " + Vector("settled", "narrowed", "unsaid", "LOST").map(k =>
        s"$k ${mine.count(_._3 == k)}").mkString(", ") + s" of ${mine.size}")
    // what is lost, and what a command that is no move says in words the organ does not hear
    for (s, line, kind) <- rows if kind == "LOST" || (kind == "unsaid" && s.path.head.option != "move") do
      val ahead = s.path.collectFirst { case Choice("day", d) => s.desk.days.indexOf(d) }.fold("")(n => s", day +$n")
      println(s"$kind $line\t${s.lang}\t${s.text}\t${ReadSet.line(s.path)}\ttoday " +
        Asking.day(s.desk.days.head) + ahead)
