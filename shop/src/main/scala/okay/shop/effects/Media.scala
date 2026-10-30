package okay.shop.effects

import okay.scala3.*
import okay.serverless.cloud.Bytes

/** PRIVATE FILES of a shop (specs/shop.md "Photos"): a quote's photos kept by name, and shown only by a link the
 * host makes for the one who may see them (the mall's signed and short-lived) */
enum Media[X]:
  case Keep(name: String, bytes: Bytes, contentType: String) extends Media[Unit]
  case Link(name: String) extends Media[String]

object Media:
  def keep[R[_]](name: String, bytes: Bytes, contentType: String)(using In[Media, R]): Unit ! R =
    perform[Media](Keep(name, bytes, contentType))
  def link[R[_]](name: String)(using In[Media, R]): String ! R = perform[Media](Link(name))

  /** a site on its own: no store (`Site.photos` off), nothing kept, no link */
  def none[R[_], Rest[_], A](p: A ! R)(using Split[Media, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Media, Rest, A, A](pure)([X] => (op: Media[X]) => op match
      case Keep(_, _, _) => ()
      case Link(_) => ""))

  /** IN MEMORY, for tests: each file kept by name, its link `/media/<name>` */
  def kept[R[_], Rest[_], A](files: scala.collection.mutable.Map[String, (Bytes, String)])(p: A ! R)(
    using Split[Media, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Media, Rest, A, A](pure)([X] => (op: Media[X]) => op match
      case Keep(name, bytes, ct) => files(name) = (bytes, ct); ()
      case Link(name) => s"/media/$name"))
