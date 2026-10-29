package okay.shop.content

import okay.shop.model.*

/**
 * THE CONTENT REPOSITORIES' MARKDOWN, READ: a front matter of `key: value` lines between `---`, and fenced blocks
 * (```` ```yaml services ````, ```` ```yaml i18n ````) each a list of maps — `- key: value`, then `  key: value`. The
 * YAML they use and nothing more; a value is the text after `: `, as it is
 */
object Parse:
  def frontMatter(md: String): Map[String, String] =
    val lines = md.linesIterator.toVector
    val start = lines.indexWhere(_.trim == "---")
    if start < 0 then Map.empty
    else
      val end = lines.indexWhere(_.trim == "---", start + 1)
      lines.slice(start + 1, if end < 0 then lines.length else end).filterNot(_.trim.startsWith("#"))
        .flatMap(pair).toMap

  /** the fenced block whose info string is `yaml <name>`: its list of maps, in order */
  def block(md: String, name: String): Vector[Map[String, String]] =
    val lines = md.linesIterator.toVector
    val start = lines.indexWhere(_.trim == s"```yaml $name")
    if start < 0 then Vector.empty
    else
      val body = lines.drop(start + 1).takeWhile(_.trim != "```")
      body.foldLeft(Vector.empty[Map[String, String]]) { (acc, line) =>
        if line.startsWith("- ") then acc :+ pair(line.drop(2)).toMap
        else if line.startsWith("  ") && acc.nonEmpty then acc.init :+ (acc.last ++ pair(line.trim))
        else acc
      }

  /** the text of the fenced block whose info string is `info` (```` ```json work ````), as it is */
  def raw(md: String, info: String): Option[String] =
    val lines = md.linesIterator.toVector
    val start = lines.indexWhere(_.trim == s"```$info")
    Option.when(start >= 0)(lines.drop(start + 1).takeWhile(_.trim != "```").mkString("\n"))

  private def pair(line: String): Option[(String, String)] =
    line.indexOf(':') match
      case -1 => None
      case i => Some(line.substring(0, i).trim -> line.substring(i + 1).trim)

/** A SITE, from its content: its front matter (also its `domain` and its refs' `refPrefix`) and offers, their words
 * in the four languages from its dictionary — a translation used while its `src` is still the site's text (the
 * dictionary's own rule) */
object Sites:
  def of(id: String, siteMd: String, i18nMd: String): Site =
    val f = Parse.frontMatter(siteMd)
    val dict = Parse.block(i18nMd, "i18n").flatMap(m => m.get("token").map(_ -> m)).toMap
    def words(token: String, text: String): T = dict.get(token).filter(_.get("src").contains(text)) match
      case Some(m) => T(m.getOrElse("ru", text), m.getOrElse("pl", text), m.getOrElse("en", text),
          m.getOrElse("uk", text))
      case None => T.all(text)
    val offers = Parse.block(siteMd, "services").map { m =>
      val key = m.getOrElse("key", "")
      Offer(key, words(s"name.$key", m.getOrElse("name", "")), words(s"desc.$key", m.getOrElse("description", "")),
        Money.parse(m.getOrElse("price", "0")).getOrElse(0L), m.get("order").flatMap(_.toIntOption).getOrElse(0),
        m.get("minutes").flatMap(_.toIntOption).getOrElse(0))
    }.sortBy(_.order)
    val domain = f.getOrElse("domain", s"$id.pl")
    val prefix = f.getOrElse("refPrefix", id.take(3).toUpperCase)
    val tagline = f.getOrElse("tagline", "")
    val contact = f.getOrElse("contact", "")
    val site = Site(id, f.getOrElse("slug", id), f.getOrElse("name", id), words("tagline", tagline),
      f.get("langDefault").flatMap(Lang.parse).getOrElse(Lang.pl), f.getOrElse("logoText", f.getOrElse("name", id)),
      f.getOrElse("color", "#ff6f91"), words("contact", contact),
      f.get("lineId") match
        case Some("line-atelier") => Kind.Atelier
        case Some("line-salon") => Kind.Salon
        case _ => Kind.It,
      prefix, domain, offers, schedule(f, Parse.block(siteMd, "schedule")))
    // how it works (specs/shop.md "How the work happens"): a ```json work``` block, checked; a wrong one is the
    // content's mistake, said by its field
    Parse.raw(siteMd, "json work").fold(site)(text => okay.effects.json.Descent.parse(text).left.map(_ => "json")
      .flatMap(Work.fromJson(_, offers.map(_.key).toSet)) match
      case Right(w) => site.working(w)
      case Left(why) => throw IllegalArgumentException(s"$id: work: $why"))

  private val days = Vector("mon", "tue", "wed", "thu", "fri", "sat", "sun")

  /** the content's schedule: its week and exceptions (a fenced `yaml schedule`), the front matter's step, horizon
   * and lead; none when the block is not there */
  def schedule(f: Map[String, String], rows: Vector[Map[String, String]]): Option[Schedule] =
    Option.when(rows.nonEmpty) {
      def span(m: Map[String, String]) = for a <- m.get("from").flatMap(Span.minutes); b <- m.get("to")
        .flatMap(Span.minutes) if a < b yield Span(a, b)
      val week = rows.flatMap(m => m.get("day").map(d => days.indexOf(d.trim.toLowerCase.take(3)) + 1)
        .filter(_ > 0).flatMap(d => span(m).map(d -> _))).groupMap(_._1)(_._2)
      val exceptions = rows.flatMap(m => m.get("date").map(d => d.trim -> (if m.get("closed").contains("true")
        then None else span(m)))).groupMap(_._1)(_._2).map((d, ss) => d -> ss.flatten)
      def n(k: String, default: Int, lo: Int, hi: Int) = f.get(k).flatMap(_.toIntOption).map(_.max(lo).min(hi))
        .getOrElse(default)
      Schedule(week, exceptions, n("step", 15, 5, 120), n("horizon", 21, 14, 28), n("lead", 60, 0, 2880))
    }
