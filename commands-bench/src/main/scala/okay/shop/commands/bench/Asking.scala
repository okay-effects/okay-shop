package okay.shop.commands.bench

import okay.frames.Choice
import okay.shop.commands.{Commands, Desk}
import okay.shop.model.{Day, Span}

/**
 * WHAT THE SMALL READER IS ASKED (specs/commands.md A2): a short fixed rule, the desk as text, the phrase; and how
 * a choice is written on its line. One writing for the training and the reading alike — and, later, the page's.
 */
object Asking:
  val rule: String =
    """You read what a shop's master says into ONE command, a line for each choice. Every value is one of the shop's
      |below or the phrase's own words.
      |action: book, move, cancel, dayoff, paid, list, or cannot for anything else.
      |book — client (the name as the phrase writes it), service, master (any when none is named), day, time.
      |move — booking, its new day, its new time (what the phrase does not change stays the booking's).
      |cancel — booking. dayoff — master, day. paid — booking, amount, way. list — day, master (any for all).
      |""".stripMargin.trim

  private val week = Vector("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

  /** `Fri 2026-11-20`: a day with its weekday, as the desk's text and a day's line write it */
  def day(date: String): String = date.split('-') match
    case Array(y, m, d) => week(Math.floorMod(Day.days(y.toInt, m.toInt, d.toInt) + 3, 7L).toInt) + " " + date
    case _              => date

  /** the starts: a range and its step when they are regular, else each */
  private def times(ts: Vector[Int]): String =
    val steps = ts.zip(ts.drop(1)).map((a, b) => b - a).distinct
    if ts.size > 2 && steps.size == 1 then
      s"${Span.label(ts.head)} to ${Span.label(ts.last)}, every ${steps.head} min"
    else ts.map(Span.label).mkString(" ")

  /** THE DESK AS TEXT: everything an option is drawn from */
  def desk(d: Desk): String =
    val near = Vector(" (today)", " (tomorrow)")
    val days = d.days.zipWithIndex.map((date, i) => day(date) + near.lift(i).getOrElse(""))
    val services = d.services.map((k, name) =>
      s"$k (${Vector(name.ru, name.pl, name.en, name.uk).distinct.mkString(", ")})")
    val masters = if d.masters.isEmpty then "none" else d.masters.map((k, name) => s"$k ($name)").mkString(", ")
    val bookings = if d.bookings.isEmpty then " none" else d.bookings.map(b =>
      s"\n${b.ref}: ${b.client}, ${b.service}, ${b.master}, ${day(b.day)} ${Span.label(b.time)}").mkString
    s"""Today: ${d.days.headOption.fold("")(day)}
       |Days: ${days.mkString(", ")}
       |Times: ${times(d.times)}
       |Services: ${services.mkString("; ")}
       |Masters: $masters
       |Bookings:$bookings""".stripMargin

  /** the prompt's two texts: the rule, then the desk and the phrase */
  def prompt(d: Desk, text: String): (String, String) = (rule, desk(d) + "\n\nPhrase: " + text)

  /** an option as its line writes it: a day with its weekday, anything else as it is */
  def written(c: Choice): String = if c.hole == "day" then day(c.option) else c.option

  /** a command's choices as `k=v` fields, the way the sealed set writes them, and back */
  def fields(path: Vector[Choice]): String = path.drop(1).map(c => s"${c.hole}=${c.option}").mkString(";")
  def path(action: String, fields: String): Vector[Choice] =
    val of = fields.split(";").filter(_.nonEmpty).map { kv => val Array(k, v) = kv.split("=", 2); k -> v }.toMap
    Choice(Commands.Action, action) +: Commands.lines(action).flatMap(l => of.get(l).map(Choice(l, _)))
