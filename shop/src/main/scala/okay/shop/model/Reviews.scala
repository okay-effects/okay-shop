package okay.shop.model

/** a change of a review by its shop, kept: when, who (an address), what (`reply`, `hide:<reason>`, `show`) */
final case class ReviewLog(at: Long, who: String, what: String)

/**
 * A CLIENT'S REVIEW (specs/shop.md "Reviews"): of an order or a booking, one each, written by its client through a
 * one-use link — the stars (1–5), a text (may be empty), the name shown (`Anna K.`, or none), the client's language;
 * the owner's public reply (one, editable) and when; hidden — never deleted — for a reason of the list; every change
 * by the shop logged
 */
final case class Review(of: Of, ref: String, service: String, master: String, rating: Int, text: String,
                        name: String, lang: Lang, at: Long, reply: String = "", replied: Long = 0,
                        hidden: String = "", log: Vector[ReviewLog] = Vector.empty):
  def id: String = s"${of.key}-$ref"
  def shown: Boolean = hidden.isEmpty

/** WHAT A STOREFRONT SHOWS of its reviews: the average and the count of those shown, how many hidden (counted apart,
 * never shown), the latest shown */
final case class Rated(average: Double, count: Int, hidden: Int, latest: Vector[Review]):
  /** `4.7`: one decimal, as shown and as in the structured data */
  def score: String =
    val tenths = math.round(average * 10)
    s"${tenths / 10}.${tenths % 10}"

/** THE RULES OF REVIEWS, pure (specs/shop.md "Reviews"): what a client may write, what the owner may do */
object Reviews:
  val textMax = 1000
  val replyMax = 1000
  val nameMax = 40
  /** a review asked this long after a visit ends */
  val after: Long = 2 * 60 * 60 * 1000L
  /** a review link lives 60 days */
  val linkLife: Long = 60L * 24 * 60 * 60 * 1000
  /** the only reasons a review is hidden: never because it is negative */
  val reasons: Vector[String] = Vector("spam", "personal", "offensive")
  /** the latest reviews a storefront shows */
  val shownLatest = 6
  /** JSON-LD's aggregate rating only from this many reviews on */
  val structuredFrom = 3

  /** a client's review, checked by the name of the first wrong field: `rating` 1–5, `text` at most 1000 */
  def check(rating: String, text: String): Either[String, (Int, String)] =
    val t = text.replace("\r\n", "\n").trim
    rating.trim.toIntOption.filter(r => r >= 1 && r <= 5) match
      case None => Left("rating")
      case Some(_) if t.length > textMax => Left("text")
      case Some(r) => Right((r, t))

  /** the name a client gives, as shown: the first word and the last word's initial — `Anna K.`; one word as it is;
   * none: empty (shown as "a client") */
  def shownName(said: String): String =
    val words = said.replaceAll("[\\p{Cntrl}<>]", " ").trim.split("\\s+").toVector.filter(_.nonEmpty)
    val first = words.headOption.getOrElse("").take(nameMax)
    if words.size < 2 then first else s"$first ${words.last.take(1).toUpperCase}."

  /** the shop's change of a review (`reply` with a text, `hide` with a reason of the list, `show`), the owner's
   * only (`owner`); refusals by name: `reply`, `reason`, `action` */
  def moderate(r: Review, action: String, value: String, who: String, owner: Boolean, now: Long)
    : Either[String, Review] =
    val v = value.replace("\r\n", "\n").trim
    if !owner then Left("owner")
    else action match
      case "reply" if v.isEmpty || v.length > replyMax => Left("reply")
      case "reply" => Right(r.copy(reply = v, replied = now, log = r.log :+ ReviewLog(now, who, "reply")))
      case "hide" if !reasons.contains(v) => Left("reason")
      case "hide" => Right(r.copy(hidden = v, log = r.log :+ ReviewLog(now, who, s"hide:$v")))
      case "show" => Right(r.copy(hidden = "", log = r.log :+ ReviewLog(now, who, "show")))
      case _ => Left("action")

  /** what a storefront shows: none while nothing is shown */
  def rated(all: Vector[Review]): Option[Rated] =
    val shown = all.filter(_.shown)
    Option.when(shown.nonEmpty)(Rated(shown.map(_.rating).sum.toDouble / shown.size, shown.size,
      all.size - shown.size, shown.sortBy(r => -r.at).take(shownLatest)))
