package okay.shop.commands.bench

import java.nio.file.{Files, Path}
import mlx.{Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Adaptable, Chat, Lora, Model, Models}
import okay.effects.json.Json
import okay.frames.{Choice, Reading, Space}
import okay.shop.commands.{Command, Commands, Desk}

/**
 * THE SMALL READER (specs/commands.md A2): a language model that reads a phrase into a command BY CHOICES and
 * never writes. At each hole of `Commands` every option is weighed — the mean log-probability of the tokens of its
 * line's end — and the best is taken (okay-frames' `Reading`); `close` makes the command.
 *
 * The answer's writing, the same for training (`answer`) and reading (`read`): for each choice a newline, the
 * hole's name and a colon (`head`), then a space and the option (`option`); after the last choice the turn's end.
 * An option is weighed WITH the token that follows it (the next line's newline, or the end): so "Olu" and
 * "Olu Nowak", both a client's options, are told apart by where the name ends.
 */
final class Reader(chat: Chat, model: Model):
  private val tok = chat.tokenizer
  private val end = tok.id("<|im_end|>")
  private val newline = tok.encode("\n") match
    case Array(one) => one
    case other      => sys.error(s"a newline is ${other.length} tokens")

  def prompt(desk: Desk, text: String): Array[Int] =
    def msg(role: String, content: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))
    val (system, user) = Asking.prompt(desk, text)
    chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))

  private def head(hole: String): Array[Int] = newline +: tok.encode(hole + ":")
  private def option(c: Choice): Array[Int] = tok.encode(" " + Asking.written(c))

  /** the choices as the answer's tokens */
  def answer(path: Vector[Choice]): Array[Int] = path.toArray.flatMap(c => head(c.hole) ++ option(c)) :+ end

  // the state fed with a context but its last token, kept: a longer context feeds only what is new
  private var kept: Option[(Array[Int], Model.State)] = None

  /** the options weighed after the context: from a checkpoint of the context's state each option is fed after
   * the context's last token, the probabilities of its tokens read, the state put back (okay-desk's `MlxReading`) */
  def score(context: Array[Int], options: Vector[Array[Int]]): Vector[Double] =
    val (before, last) = (context.init, context.last)
    val s = new Scope
    try
      def feed(st: Model.State, ids: Array[Int]): Unit =
        if ids.nonEmpty then model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval()
      val st = kept match
        case Some((ids, st)) if before.startsWith(ids) => feed(st, before.drop(ids.length)); st
        case other =>
          other.foreach(_._2.close())
          val st = model.newState(); feed(st, before); st
      kept = Some(before -> st)
      options.map { o =>
        val at = st.checkpoint()
        val input = last +: o.init
        val lp = Scope(s) {
          val logits = astype(model.logitsAll(ints(input, 1, input.length), st), Dtype.Float32)
          val v = logits.dim(-1)
          takeAlong(softmax(reshape(logits, input.length, v), 1), ints(o, input.length, 1), 1).toFloats.take(o.length)
        }
        st.rewind(at, 0): Unit
        at.close()
        lp.map(p => math.log(math.max(p, 1e-30f).toDouble)).sum / o.length
      }
    finally s.close()

  /** THE READING of a phrase over a desk: every hole's options weighed, the best taken. The space is `Commands`,
   * or `Computed` (A2b): then a day or a time the phrase settles is not weighed at all */
  def read(desk: Desk, text: String, space: Space[Desk, Command] = Commands): Reading[Desk, Command] =
    val asked = prompt(desk, text)
    Reading.run(space)(desk, text) { (r, h) =>
      val context = asked ++ r.chosen.toArray.flatMap(c => head(c.hole) ++ option(c)) ++ head(h.name)
      score(context, h.options.map { o =>
        val c = Choice(h.name, o)
        option(c) :+ (if space.next(desk, text, r.chosen :+ c).isEmpty then end else newline)
      })
    }

  def close(): Unit =
    kept.foreach(_._2.close())
    model.close()

object Reader:
  val repo: String = sys.env.getOrElse("COMMANDS_MODEL", "mlx-community/Qwen3-0.6B-4bit")
  def directory: Path = Models.snapshot(repo).orElse(Some(Path.of(repo)).filter(Files.isDirectory(_)))
    .getOrElse(sys.error(s"$repo not in the cache"))

  /** the model of the cache, an adapter on it when one is named */
  def load(adapter: Option[String]): Reader =
    val dir = directory
    val model = Model.load(dir, mapped = true)
    adapter.foreach(f => Lora.attach(model.asInstanceOf[Adaptable], f))
    Reader(Chat.load(dir), model)
