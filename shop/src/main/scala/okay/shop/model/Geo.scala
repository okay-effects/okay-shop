package okay.shop.model

/**
 * A PLACE'S POINT (specs/shop.md "Places on the map"): WGS 84 degrees, who found it (`gugik`, `nominatim`, `owner`),
 * how exactly (`address`, `street`, `postcode`, `city`, `point`), and the address line it was found for — a point found
 * for another address is stale
 */
final case class Geo(lat: Double, lon: Double, source: String, precision: String, of: String):
  def point: okay.facts.Value.Point = okay.facts.Value.Point(lat, lon)

object Geo:
  /** a place's own point, when it was found for the address it has now */
  def current(l: Location): Option[Geo] = l.geo.filter(_.of == l.address.line)

  /** metres between two points: the great circle's (okay-facts' `Geo`) */
  def meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Long =
    okay.facts.Geo.meters(okay.facts.Value.Point(aLat, aLon), okay.facts.Value.Point(bLat, bLon))

  /** a client's point as it is used: three decimals (about 100 m), never more */
  def rounded(d: Double): Double = math.round(d * 1000) / 1000.0

  /** a degree's text as JSON keeps it: up to seven decimals (about a centimetre) */
  def degrees(d: Double): BigDecimal = BigDecimal(d).setScale(7, BigDecimal.RoundingMode.HALF_EVEN)

  /** a latitude and longitude that are a place on the Earth */
  def valid(lat: Double, lon: Double): Boolean =
    !lat.isNaN && !lon.isNaN && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
