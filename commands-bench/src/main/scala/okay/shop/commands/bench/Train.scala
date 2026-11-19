package okay.shop.commands.bench

import java.nio.file.Files
import mlx.llm.{Adaptable, Chat, Lora, Model}
import scala.util.Random

/**
 * THE READER TRAINED (specs/commands.md A2): LoRA on the released Qwen3 0.6B, rank 16 on every projection,
 * lr 2e-4, an example a step (okay-desk's `FrameTrain`). An example: the prompt (`Asking.prompt`) and the
 * choices as the answer (`Reader.answer`). The texts: the teacher's kept phrases and canonical words (half as
 * many, or COMMANDS_CANON), none over a held-out desk (`Texts.heldOut`); the sealed phrases are never read here.
 * After each epoch the adapter is saved and the mean loss on the held-out teacher texts printed.
 *
 *   sbt "commandsBench/runMain okay.shop.commands.bench.Train"
 *   env: COMMANDS_SEED (1), COMMANDS_EPOCHS (2), COMMANDS_TEXTS (teacher.jsonl), COMMANDS_ONLY (so many of the
 *   teacher's texts), COMMANDS_CANON, COMMANDS_NAME (the adapter's name: reader), COMMANDS_OUT, COMMANDS_MODEL,
 *   COMMANDS_MORE (a file of more texts: `Teach more`), COMMANDS_CLEAN (leave out what the organ refutes),
 *   COMMANDS_TREE (a batch: an epoch in tries)
 */
object Train:
  def main(args: Array[String]): Unit =
    val seed = sys.env.get("COMMANDS_SEED").fold(1L)(_.toLong)
    val epochs = sys.env.get("COMMANDS_EPOCHS").fold(2)(_.toInt)
    val name = sys.env.getOrElse("COMMANDS_NAME", "reader")
    val all = Texts.load(Teach.texts)
    val taught = sys.env.get("COMMANDS_ONLY").fold(all)(n => all.take(n.toInt))
    val (held, first) = taught.partition(s => Texts.heldOut(s.seed))
    // A2b: more texts (COMMANDS_MORE, a file); and none whose day or time the organ refutes (COMMANDS_CLEAN)
    val more = sys.env.get("COMMANDS_MORE").fold(Vector.empty[Said])(f => Texts.load(java.nio.file.Path.of(f)))
    val refuted = if sys.env.contains("COMMANDS_CLEAN") then
      Narrowing.of(first ++ more).collect { case (s, _, "LOST") => s }.toSet else Set.empty[Said]
    val mine = (first ++ more).filterNot(refuted)
    println(s"${first.size} of the first texts, ${more.size} more, ${refuted.size} refuted by the organ and left out")
    val canon = Texts.canon(sys.env.get("COMMANDS_CANON").fold(mine.size / 2)(_.toInt), 100000)
      .filterNot(s => Texts.heldOut(s.seed))
    val items = mine ++ canon
    mlx.Mlx.randomSeed(seed)
    val dir = Reader.directory
    val model = Model.load(dir, mapped = true)
    val reader = Reader(Chat.load(dir), model)
    def tokens(s: Said): (Array[Int], Int) =
      val prompt = reader.prompt(s.desk, s.text)
      (prompt ++ reader.answer(s.path), prompt.length)
    println(s"${Reader.repo}, seed $seed: ${mine.size} of the teacher's, ${canon.size} canonical, ${held.size} held " +
      s"out; ${items.map(i => tokens(i)._1.length).sum / items.size} tokens a text")
    val lora = Lora(model.asInstanceOf[Adaptable], rank = 16, projs = Seq("q", "k", "v", "o", "gate", "up", "down"),
      lr = 2e-4f, seed = seed)
    val rnd = new Random(seed)
    val t0 = System.nanoTime()
    def seconds = (System.nanoTime() - t0) / 1e9
    Files.createDirectories(Teach.out.resolve("adapters"))
    // COMMANDS_TREE=<batch>: an epoch in tries (mlx-scala `Lora.epochTree`: texts over one desk share the prompt's
    // beginning, run once a batch). okay-desk's frame reader at 8, lr 2e-4: 1.8× faster an epoch and wrong 51
    // against 93 of 1004 never trained, three seeds alike (okay-desk specs/frames.md §5.12) — not yet measured here
    val tree = sys.env.get("COMMANDS_TREE").map(_.toInt)
    for e <- 1 to epochs do
      var sum = 0f; var k = 0
      tree match
        case Some(b) =>
          sum = lora.epochTree(items.map(tokens), rnd, b) * items.size; k = items.size
        case None =>
          for i <- rnd.shuffle(items) do
            val (ids, from) = tokens(i)
            sum += lora.step(ids, from); k += 1
            if k % 200 == 0 then println(f"epoch $e, $k/${items.size}: mean loss ${sum / k}%.4f ($seconds%.0f s)")
      val heldLoss = held.map { h => val (ids, from) = tokens(h); lora.evaluate(ids, from) }
      println(f"epoch $e: mean loss ${sum / k}%.4f; held out ${heldLoss.sum / math.max(1, held.size)}%.4f " +
        f"($seconds%.0f s)")
      val to = Teach.out.resolve("adapters").resolve(s"$name-s$seed-e$e.safetensors").toString
      lora.save(to)
      println(s"saved $to")
    lora.close(); model.close()
