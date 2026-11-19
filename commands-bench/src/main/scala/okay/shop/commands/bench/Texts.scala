package okay.shop.commands.bench

import java.nio.file.{Files, Path}
import okay.effects.json.Json
import okay.frames.{Choice, Laws, Space}
import okay.shop.commands.{Command, Commands, Desk}
import okay.shop.model.Lang
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** A TRAINING TEXT: what was said over the desk of a seed, in a language, and the choices it means */
final case class Said(seed: Long, lang: String, source: String, text: String, path: Vector[Choice],
                      at: Option[Desk] = None):
  /** the desk it was said over: the seed's, or one given (the sealed shop's) */
  lazy val desk: Desk = at.getOrElse(Gen.desk(seed))
  def command: Option[Command] = Commands.close(desk, text, path)
  def json: String = Json.obj("seed" -> Json.num(seed), "lang" -> Json.Str(lang), "source" -> Json.Str(source),
    "text" -> Json.Str(text), "path" -> Json.Arr(path.map(c => Json.Str(s"${c.hole}=${c.option}")))).render

object Texts:
  /** the desks kept out of every training: one in twenty, by seed — the held-out slice of the generated texts */
  def heldOut(seed: Long): Boolean = seed % 20 == 0

  /** REACHABLE BY CHOICES: the command can be chosen, hole by hole, among what the space offers for the text —
   * in `Computed`, the organ does not refute its day or its time. `ReadSet`'s count and the teacher's gate (A2c) */
  def reachable(space: Space[Desk, Command])(desk: Desk, text: String, c: Command): Boolean =
    Laws.reach(space)(desk, text, c).isEmpty

  /** a file's texts; a text whose choices do not close over its desk (the generator changed since) stops it */
  def load(path: Path): Vector[Said] = Files.readAllLines(path).asScala.toVector.filter(_.trim.nonEmpty).map { l =>
    val j = Json.fromText(l)
    val said = Said(j("seed").long, j("lang").string, j("source").string, j("text").string,
      j("path").list.map { c => val Array(k, v) = c.string.split("=", 2); Choice(k, v) })
    require(said.command.nonEmpty, s"a text no longer closes over its desk: $l")
    said
  }

  /** THE CANONICAL WORDS as texts (`Words.say`: free and exact): `n` commands over the desks from `from` on, the
   * actions in the sealed set's shares, the four languages in turn */
  def canon(n: Int, from: Long): Vector[Said] = Vector.tabulate(n) { i =>
    val seed = from + i / 4
    val lang = Lang.values(i % 4)
    val r = new Random(seed * 31 + i)
    val desk = Gen.desk(seed)
    val c = Gen.command(r, desk, Gen.shares(r.nextInt(Gen.shares.size)), lang)
    Said(seed, lang.code, "canon", Commands.say(desk, c, lang.code), Commands.path(desk, c))
  }
