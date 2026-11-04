package okay.shop

import okay.effects.json.Json
import okay.shop.effects.GeoQuery
import okay.shop.handlers.Geocoders
import okay.shop.html.Pages
import okay.shop.model.*

/** PLACES ON THE MAP (specs/shop.md "Places on the map"): a place's point, its staleness, a client's point rounded,
 * the radius with a fake geocoder (inside, outside, unchecked), the geocoders' answers read, the map box */
class GeoSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val fri10 = 1793282400000L + 19 * hour
  private val home = Seq("mode" -> "atClient", "street" -> "ul. Kwiatowa 7/12", "postcode" -> "30-001",
    "city" -> "Kraków", "notes" -> "")

  /** the visits' site with a radius of 10 km from its Centrum, which has a point */
  private def radiusWorld: World = World("visits", edit = s => s.copy(work = s.work.map(w => w.copy(
    locations = w.locations.map(l => if l.key == "centrum" then l.copy(geo = Some(Geo(52.2297, 21.0122, "gugik",
      "address", l.address.line))) else l), area = w.area.map(_.copy(radius = 10, from = "centrum"))))))

  private def book(w: World, fields: (String, String)*) =
    val base = Map("service" -> "cut", "start" -> fri10.toString, "name" -> "Anna", "contact" -> "anna@example.com",
      "rodo_consent" -> "on", "cf-turnstile-response" -> "ok", "lang" -> "pl")
    w.post("/api/book", (base ++ fields).toSeq*)

  test("a place's point in the work's JSON, stale when the address changed; a client's point to 3 decimals"):
    val w = World("visits").site.work.get
    val l = w.locations.head.copy(geo = Some(Geo(52.2297, 21.0122, "owner", "point", w.locations.head.address.line)))
    val keys = Set("cut", "visit", "consult", "parcel", "both", "fix", "yoga")
    val back = Work.fromJson(Work.toJson(w.copy(locations = Vector(l))), keys)
    assertEquals(back.map(_.locations.head.geo), Right(l.geo))
    assertEquals(Geo.current(l).map(_.source), Some("owner"))
    assertEquals(Geo.current(l.copy(address = l.address.copy(street = "ul. Inna 3"))), None)
    assertEquals(Geo.rounded(52.229676), 52.23)
    assertEquals(Geo.rounded(21.012228), 21.012)
    assert(math.abs(Geo.meters(52.2297, 21.0122, 50.0647, 19.9450) - 252000) < 2000)
    assertEquals(Work.fromJson(Json.fromText("""{"modes": ["atShop"], "locations": [{"name": "X", "city": "Y",
      "geo": {"lat": 95, "lon": 1, "source": "owner", "precision": "point", "of": ""}}]}"""), Set.empty),
      Left("location.geo"))

  test("the radius: within its km inside, beyond outside, nothing found — the lists decide and it says so"):
    val w = radiusWorld
    // Kraków is no place of the lists: 252 km from the centre — outside
    w.geocode = q => Option.when(q.city == "Kraków")(Geo(50.0647, 19.9450, "gugik", "address", q.key))
    assertEquals(w.json(book(w, home*))("error"), "area")
    // Ząbki, 9 km: inside by the radius
    w.geocode = _ => Some(Geo(52.2925, 21.1123, "gugik", "address", ""))
    val zabki = Seq("mode" -> "atClient", "street" -> "ul. Wolności 1", "postcode" -> "05-091", "city" -> "Ząbki")
    assertEquals(book(w, zabki*).status, 200)
    // nothing found: outside the lists, the distance not checked — said so
    w.geocode = _ => None
    val r = book(w, (zabki.filter(_._1 != "start") :+ ("start" -> (fri10 + 2 * hour).toString))*)
    assertEquals((r.status, w.json(r)("error")), (409, "area-unchecked"))
    assertEquals(w.json(w.get("/api/area?city=Z%C4%85bki&postcode=05-091")).get("checked"), Some("false"))
    // a shop with no radius: never geocoded
    val plain = World("visits")
    plain.geocode = _ => fail("asked")
    assertEquals(plain.json(book(plain, home*))("error"), "area")

  test("GUGiK's and Nominatim's answers read (their JSON as they give it)"):
    val gugik = """{"type":"address","results":{"1":{"city":"Gdańsk","street":"Aleja Grunwaldzka","number":"100",
      "x":"18.6061256682471","y":"54.3782028533184","accuracy":"0.785714"}}}"""
    assertEquals(Geocoders.gugikRead(gugik, "k").map(g => (g.lat, g.lon, g.source)), Some((54.3782028533184,
      18.6061256682471, "gugik")))
    assertEquals(Geocoders.gugikRead("""{"results":null}""", "k"), None)
    val osm = """[{"lat":"52.2064246","lon":"21.0219590","addresstype":"postcode","name":"02-512"}]"""
    assertEquals(Geocoders.nominatimRead(osm, "k").map(g => (g.lat, g.precision)), Some((52.2064246, "postcode")))
    assertEquals(Geocoders.nominatimRead("[]", "k"), None)
    assert(!Geocoders.addressed(GeoQuery(postcode = "02-512")))
    assert(Geocoders.addressed(GeoQuery("ul. Puławska 12", "", "Warszawa")))

  test("the map box only for a place with a current point"):
    given Lang = Lang.pl
    val l = World("visits").site.work.get.locations.head
    assertEquals(Pages.mapBox(l), "")
    val box = Pages.mapBox(l.copy(geo = Some(Geo(52.2297, 21.0122, "gugik", "address", l.address.line))))
    assert(box.contains("data-map=\"52.2297,21.0122,Test Visits Centrum\"") && box.contains("Pokaż mapę"), box)
