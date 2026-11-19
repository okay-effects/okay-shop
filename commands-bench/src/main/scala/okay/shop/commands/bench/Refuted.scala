package okay.shop.commands.bench

import java.nio.file.{Files, Path}
import okay.shop.commands.{Booked, Command, Computed, Desk, Narrowed, When}
import okay.shop.model.{Reports, Span}
import scala.jdk.CollectionConverters.*

/**
 * WHY THE ORGAN REFUTED A MOVE (specs/commands.md A2d), no model: every `organ` rejection of a move in a slice's
 * `<slice>-rejected.tsv` (attempt, kind, reason, phrase, read-back) is rebuilt by `Teach.draw` — the desk, the
 * gold command, the booking moved — and the organ is asked again for the phrase's day and time. A line (the day,
 * the time) REFUTES when the gold value is not among what the organ leaves; each refuting line gets a class, by
 * rule and by nothing read:
 *
 *  - `a`  the organ left the BOOKED value (the old one, named) and no difference was heard;
 *  - `c1` a difference was heard, the gold IS the booked value shifted by it, and the organ answered a value named
 *         beside it instead (`c1a`: that value is the booked one — the old time named, then the difference);
 *  - `c2` a difference was heard and the gold is NOT the booked value shifted by it;
 *  - `b`  the day's gold is a week or more after a day the organ took (the teacher's far weekday);
 *  - `d`  the organ found a value that is neither the booked one nor the gold (`dx`: and none the desk offers);
 *  - `e`  anything else.
 *
 * A rejection's class is its day's and its time's, the first of `a`, `c1`, `c2`, `b`, `d`, `e` among them.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Refuted [rows.tsv]"       (every row, to a file)
 */
object Refuted:
  val kinds: Set[String] = Set("move", "move-rel", "move-ind", "move-rel-ind")

  final case class Row(slice: String, attempt: Int, kind: String, lang: String, text: String, desk: Desk,
                       booked: Booked, gold: Command.Move, day: Narrowed, time: Narrowed, heard: Vector[When.Heard]):
    /** the classes of the day's line and of the time's: empty when the line does not refute */
    val dayClass: String = classOf(day, gold.day, booked.day, heard.collect {
      case When.Heard(When.What.DaysLater(n), _, _) => n }.distinct.map(Reports.plus(booked.day, _)),
      (took, meant) => { val by = Reports.epochDay(meant) - Reports.epochDay(took); by >= 7 && by % 7 == 0 })
    val timeClass: String = classOf(time, Span.label(gold.time), Span.label(booked.time), heard.collect {
      case When.Heard(When.What.Later(n), _, _) => n }.distinct.map(n => Span.label(booked.time + n)),
      (_, _) => false)
    val cls: String = Vector("a", "c1", "c2", "b", "d", "e")
      .find(c => dayClass.startsWith(c) || timeClass.startsWith(c)).getOrElse("-")
    def reachable: Boolean = Texts.reachable(Computed)(desk, text, gold)

  private def classOf(organ: Narrowed, gold: String, booked: String, shifted: Vector[String],
                      far: (String, String) => Boolean): String =
    val (took, outside) = organ match
      case Narrowed.Unsaid         => (Vector.empty[String], false)
      case Narrowed.Among(options) => (options, false)
      case Narrowed.Outside(said)  => (said, true)
    if organ == Narrowed.Unsaid || (!outside && took.contains(gold)) then ""
    else if shifted.contains(gold) then (if took.contains(booked) then "c1a" else "c1")
    else if shifted.nonEmpty then "c2"
    else if took.contains(booked) then "a"
    else if took.exists(far(_, gold)) then "b"
    else if outside then "dx"
    else if took.nonEmpty then "d"
    else "e"

  private val week = Vector("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
  private def day(desk: Desk, d: String): String =
    val n = Reports.epochDay(d)
    s"${week(Math.floorMod(n + 3, 7L).toInt)}+${n - Reports.epochDay(desk.days.head)}"
  private def shown(n: Narrowed, of: String => String): String = n match
    case Narrowed.Unsaid         => "unsaid"
    case Narrowed.Among(options) => options.map(of).mkString("among ", ",", "")
    case Narrowed.Outside(said)  => said.map(of).mkString("outside ", ",", "")

  /** the organ's rejections of moves in a slice's file, rebuilt */
  def rows(slice: Teach.Slice): Vector[Row] =
    Files.readAllLines(Teach.out.resolve(s"${slice.name}-rejected.tsv")).asScala.toVector.map(_.split("\t", -1))
      .collect { case r if r.length >= 4 && r(2) == "organ" && kinds(r(1)) => r }.flatMap { r =>
        val text = r(3).replace("[[", "").replace("]]", "").replaceAll("\\s+", " ").trim
        Teach.draw(slice, r(0).toInt).collect { case Teach.Asked(_, l, desk, kind, c: Command.Move, _, _) =>
          require(kind == r(1), s"${slice.name} ${r(0)}: drawn $kind, the file says ${r(1)}")
          val booked = desk.bookings.find(_.ref == c.booking).get
          Row(slice.name, r(0).toInt, kind, l.code, text, desk, booked, c,
            When.days(desk, text, "move", Some(booked)), When.times(desk, text, "move", Some(booked)),
            When.hear(text))
        }
      }

  def line(r: Row): String = Vector(r.slice, r.attempt, r.kind, r.lang, r.cls,
    if r.reachable then "reachable" else "refuted", s"day ${r.dayClass}", s"time ${r.timeClass}",
    s"today ${day(r.desk, r.desk.days.head)}",
    s"booked ${day(r.desk, r.booked.day)} ${Span.label(r.booked.time)}",
    s"gold ${day(r.desk, r.gold.day)} ${Span.label(r.gold.time)}",
    s"day ${shown(r.day, day(r.desk, _))}", s"time ${shown(r.time, identity)}", r.text,
    r.heard.map(_.what).mkString(" ")).mkString("\t")

  def main(args: Array[String]): Unit =
    val all = Vector(Teach.more, Teach.held3).flatMap(rows)
    args.headOption.foreach(f => Files.write(Path.of(f), all.map(line).asJava))
    def table(name: String, of: Vector[Row]): Unit =
      println(s"$name: ${of.size} rejections, ${of.count(_.reachable)} reachable now")
      for (c, mine) <- of.groupBy(_.cls).toVector.sortBy(_._1) do
        println(s"  $c\t${mine.size}\t" + Vector("en", "pl", "ru", "uk").map(l =>
          s"$l ${mine.count(_.lang == l)}").mkString(", ") + s"\treachable now ${mine.count(_.reachable)}")
    table("moves said indirectly (move-ind, move-rel-ind)", all.filter(_.kind.endsWith("-ind")))
    table("moves said plainly (move, move-rel)", all.filterNot(_.kind.endsWith("-ind")))
    table("all", all)
    println("lines refuting, by class: " + all.flatMap(r => Vector("day " + r.dayClass, "time " + r.timeClass))
      .filterNot(_.endsWith(" ")).groupBy(identity).toVector.sortBy(_._1).map((k, v) => s"$k ${v.size}")
      .mkString(", "))
    for (c, mine) <- all.groupBy(_.cls).toVector.sortBy(_._1); r <- mine.take(5) do println(line(r))
