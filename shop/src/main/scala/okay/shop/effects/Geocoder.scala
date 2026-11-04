package okay.shop.effects

import okay.scala3.*
import okay.shop.model.Geo

/** WHAT TO FIND: an address — a street with its number, a postcode, a city — or a place's words (a district, a
 * landmark, a postcode alone) with the city it is in */
final case class GeoQuery(street: String = "", postcode: String = "", city: String = "", words: String = ""):
  /** its text, the cache's key: folded, one space */
  def key: String = okay.shop.model.Work.fold(Vector(words, street, postcode, city).filter(_.nonEmpty).mkString(", "))
  def isEmpty: Boolean = key.isEmpty

/**
 * GEOCODING AS AN EFFECT (specs/shop.md "Places on the map"): an address or a place's words to a point, or none. Its
 * handlers are `Geocoders`' (GUGiK's, OpenStreetMap's Nominatim, a fake); a host paces and caches them
 */
enum Geocoder[X]:
  case Find(q: GeoQuery) extends Geocoder[Option[Geo]]

object Geocoder:
  def find[R[_]](q: GeoQuery)(using In[Geocoder, R]): Option[Geo] ! R = perform[Geocoder](Find(q))

  /** a site with no geocoding: nothing found (a radius falls back to the lists) */
  def none[R[_], Rest[_], A](p: A ! R)(using Split[Geocoder, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Geocoder, Rest, A, A](pure)([X] => (op: Geocoder[X]) => op match
      case Find(_) => None))

  /** a fake, for tests: what `answer` says, each query kept */
  def fake[R[_], Rest[_], A](answer: GeoQuery => Option[Geo], asked: scala.collection.mutable.Buffer[GeoQuery] =
    scala.collection.mutable.Buffer.empty)(p: A ! R)(using Split[Geocoder, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Geocoder, Rest, A, A](pure)([X] => (op: Geocoder[X]) => op match
      case Find(q) => asked += q; answer(q)))
