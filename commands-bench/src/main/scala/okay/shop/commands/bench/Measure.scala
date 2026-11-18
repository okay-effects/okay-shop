package okay.shop.commands.bench

import java.nio.file.{Files, Path}
import okay.frames.{Choice, Laws}
import okay.shop.commands.{Booked, Commands, Desk}
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
 *   sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet sealed|held <adapter|none> <out.tsv> …"
 * `sealed`: the 60 phrases; `held`: the teacher's texts over the held-out desks (COMMANDS_TEXTS). Several
 * readings, three arguments each, are one job on the GPU.
 */
object ReadSet:
  def line(path: Vector[Choice]): String = (path.head.option + " " + Asking.fields(path)).trim

  def main(args: Array[String]): Unit = args.grouped(3).foreach { case Array(which, adapter, out) =>
    val set = which match
      case "sealed" => Sealed.phrases
      case _ =>
        val texts = Texts.load(Teach.texts)
        // `held`: the texts over the held-out desks; `seen`: a hundred of those trained on (the pipeline's check)
        if which == "held" then texts.filter(s => Texts.heldOut(s.seed))
        else texts.filterNot(s => Texts.heldOut(s.seed)).take(100)
    val unreachable = set.count(s => s.command.forall(c => Laws.reach(Commands)(s.desk, s.text, c).nonEmpty))
    println(s"$which by $adapter: ${set.size} texts, ${set.size - unreachable} reachable by choices")
    val reader = Reader.load(Some(adapter).filter(_ != "none"))
    val t0 = System.nanoTime()
    val rows = set.map { s =>
      val r = reader.read(s.desk, s.text)
      val got = r.meaning.map(c => line(Commands.path(s.desk, c))).getOrElse("-")
      val margin = if r.margin.isInfinite then 99.0 else r.margin
      println(f"${s.lang} ${if got == line(s.path) then "right" else "WRONG"} $margin%5.2f ${s.text} → $got")
      f"${s.lang}\t${s.text}\t${line(s.path)}\t$got\t$margin%.3f"
    }
    Files.write(Path.of(out), ("lang\tphrase\tgold\tread\tmargin" +: rows).asJava)
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
  private final case class Row(lang: String, phrase: String, gold: String, read: String)
  private def load(file: String): Vector[Row] = Files.readAllLines(Path.of(file)).asScala.toVector.drop(1)
    .map(_.split("\t", -1)).collect { case Array(l, p, g, r, _) => Row(l, p, g, r) }
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
    for (name, rows) <- readers; r <- rows if r.read != r.gold do
      println(s"$name\t${r.lang}\t${r.phrase}\t${r.gold}\t${r.read}")
