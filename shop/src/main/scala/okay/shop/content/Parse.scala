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
        Money.parse(m.getOrElse("price", "0")).getOrElse(0L), m.get("order").flatMap(_.toIntOption).getOrElse(0))
    }.sortBy(_.order)
    val domain = f.getOrElse("domain", s"$id.pl")
    val prefix = f.getOrElse("refPrefix", id.take(3).toUpperCase)
    val tagline = f.getOrElse("tagline", "")
    val contact = f.getOrElse("contact", "")
    Site(id, f.getOrElse("slug", id), f.getOrElse("name", id), words("tagline", tagline),
      f.get("langDefault").flatMap(Lang.parse).getOrElse(Lang.pl), f.getOrElse("logoText", f.getOrElse("name", id)),
      f.getOrElse("color", "#ff6f91"), words("contact", contact),
      if f.get("lineId").contains("line-atelier") then Kind.Atelier else Kind.It, prefix, domain, offers)
