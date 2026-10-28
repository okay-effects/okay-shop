package okay.shop.effects

import okay.scala3.*

/** NEW NAMES: an order's ref (`SZY-7K3F2Q`: a prefix and 6 of Crockford's letters and digits), a secret (24 of
 * them: 120 bits) */
enum Ids[X]:
  case Ref(prefix: String) extends Ids[String]
  case Secret() extends Ids[String]

object Ids:
  def ref[R[_]](prefix: String)(using In[Ids, R]): String ! R = perform[Ids](Ref(prefix))
  def secret[R[_]](using In[Ids, R]): String ! R = perform[Ids](Secret())

  /** Crockford's base 32: no I, L, O, U — read aloud and typed without doubt */
  val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  def word(n: Int, next: () => Int): String = Vector.fill(n)(alphabet(Math.floorMod(next(), 32))).mkString

  /** from a source of random ints: the platform's secure one in the Worker */
  def from[R[_], Rest[_], A](next: () => Int)(p: A ! R)(using Split[Ids, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Ids, Rest, A, A](pure)([X] => (op: Ids[X]) => op match
      case Ref(prefix) => s"$prefix-${word(6, next)}"
      case Secret() => word(24, next)))

  /** tests: the same names each run */
  def seeded[R[_], Rest[_], A](seed: Long)(p: A ! R)(using Split[Ids, R, Rest]): A ! Rest =
    val random = new scala.util.Random(seed)
    from(() => random.nextInt())(p)
