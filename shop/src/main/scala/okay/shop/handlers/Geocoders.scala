package okay.shop.handlers

import okay.effects.json.{Descent, Json}
import okay.scala3.*
import okay.serverless.cloud.{Fetch, text}
import okay.shop.effects.{GeoQuery, Geocoder}
import okay.shop.model.Geo

/**
 * THE GEOCODERS (specs/shop.md "Places on the map"; okay-mall specs/mall.md "Geolocation, distance and maps"):
 *  - GUGiK's UUG — the Polish state's free service over the register's address points (PRG):
 *    `https://services.gugik.gov.pl/uug/?request=GetAddress&address=<city>, <street> <number>&srid=4326`; it answers
 *    `x` (longitude), `y` (latitude) and an `accuracy`; a full address only (a street with a number);
 *  - OpenStreetMap's Nominatim — `https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=pl&limit=1`,
 *    by its usage policy: an identifying `User-Agent` with a contact (`agent`), at most one request a second, results
 *    cached, no bulk — the pacing and the cache are the host's;
 *  - `web(agent)`: GUGiK first for an address, then Nominatim. Over okay-serverless' `Fetch`
 */
object Geocoders:
  val gugikUrl = "https://services.gugik.gov.pl/uug/"
  val nominatimUrl = "https://nominatim.openstreetmap.org/search"

  private def enc(s: String) = okay.http.Query.encode(s)

  /** GUGiK's answer read: its best result's point, an address's precision (none below its accuracy limit) */
  def gugikRead(body: String, of: String): Option[Geo] =
    Descent.parse(body).toOption.flatMap(_.field("results")).collect { case o: Json.Obj => o.fields }.flatMap(
      _.map(_._2).flatMap(r => for
        x <- r.field("x").collect { case Json.Str(s) => s }.flatMap(_.toDoubleOption)
        y <- r.field("y").collect { case Json.Str(s) => s }.flatMap(_.toDoubleOption)
        if Geo.valid(y, x)
      yield Geo(y, x, "gugik", "address", of)).headOption)

  /** Nominatim's answer read: its first result's point, its precision by the kind of place it is */
  def nominatimRead(body: String, of: String): Option[Geo] =
    Descent.parse(body).toOption.collect { case Json.Arr(xs) => xs }.flatMap(_.headOption).flatMap { r =>
      def s(k: String) = r.field(k).collect { case Json.Str(v) => v }
      for
        lat <- s("lat").flatMap(_.toDoubleOption)
        lon <- s("lon").flatMap(_.toDoubleOption)
        if Geo.valid(lat, lon)
      yield
        val kind = s("addresstype").orElse(s("type")).getOrElse("")
        Geo(lat, lon, "nominatim", kind match
          case "house" | "building" => "address"
          case "road" | "street" => "street"
          case "postcode" => "postcode"
          case "city" | "town" | "village" | "municipality" | "county" | "state" => "city"
          case _ => "place", of)
    }

  /** a full address: a street with a number, and a city */
  def addressed(q: GeoQuery): Boolean = q.street.exists(_.isDigit) && q.city.nonEmpty

  def gugik[R[_]](q: GeoQuery)(using In[Fetch, R]): Option[Geo] ! R =
    if !addressed(q) then pure(None)
    else
      val street = q.street.replaceAll("(?i)^(ul\\.|ulica|al\\.|aleja|pl\\.|plac|os\\.)\\s*", "").trim
      Fetch.get[R](s"$gugikUrl?request=GetAddress&srid=4326&address=${enc(s"${q.city}, $street")}").map(r =>
        if r.status == 200 then gugikRead(r.text, q.key) else None)

  def nominatim[R[_]](q: GeoQuery, agent: String)(using In[Fetch, R]): Option[Geo] ! R =
    val params =
      if q.words.isEmpty && q.street.isEmpty && q.city.isEmpty && q.postcode.nonEmpty then s"&postalcode=${enc(
        q.postcode)}"
      else if q.words.isEmpty && q.street.nonEmpty then s"&street=${enc(q.street)}&city=${enc(q.city)}" +
        (if q.postcode.nonEmpty then s"&postalcode=${enc(q.postcode)}" else "")
      else s"&q=${enc(Vector(q.words, q.street, q.postcode, q.city).filter(_.nonEmpty).mkString(", "))}"
    perform[Fetch](okay.http.Fetch.Call("GET", s"$nominatimUrl?format=jsonv2&countrycodes=pl&limit=1$params",
      Map("user-agent" -> agent, "accept-language" -> "pl"), "", identity)).map(r =>
      if r.status == 200 then nominatimRead(r.text, q.key) else None)

  /** GUGiK for an address, else Nominatim */
  def find[R[_]](q: GeoQuery, agent: String)(using In[Fetch, R]): Option[Geo] ! R =
    if q.isEmpty then pure(None)
    else gugik[R](q).flatMap(_.fold(nominatim[R](q, agent))(g => pure(Some(g))))

  /** the effect by both, over `Fetch` (no pacing, no cache: a host that calls Nominatim adds them) */
  def web[R[_], Rest[_], A](agent: String)(p: A ! R)(using Split[Geocoder, R, Rest], In[Fetch, Rest]): A ! Rest =
    handle(p, Handler.general[Geocoder, Rest, A, A](pure)([X] => (op: Geocoder[X], k: X => A ! Rest) => op match
      case Geocoder.Find(q) => find[Rest](q, agent).flatMap(k)))
