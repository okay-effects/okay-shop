package okay.shop

import okay.effects.json.Descent
import okay.serverless.cloud.text
import okay.shop.commands.*
import okay.shop.effects.Store
import okay.shop.html.Letters
import okay.shop.model.*

/** THE MASTER'S COMMANDS ON A SHOP'S DATA (specs/commands.md "A1 as built"): the desk read from the fictional
 * shops' content and store, and each kind of command run by the engine's own operations — checked by reading the
 * store back; a refused command changes nothing */
class CommandsRunSuite extends munit.FunSuite:
  private val hour = 3600000L
  private val staff = "staff@example.com"

  /** a booking as the storefront's form posts it: its ref and its key */
  private def book(w: World, start: Long, fields: (String, String)*): (String, String) =
    val base = Map("service" -> "cut", "start" -> start.toString, "name" -> "Anna", "contact" -> "anna@example.com",
      "rodo_consent" -> "on", "lang" -> "pl", "cf-turnstile-response" -> "t")
    val j = w.json(w.post("/api/book", (base ++ fields).toSeq*))
    assert(j.contains("ref"), j.toString)
    (j("ref"), j("key"))
  private def fri10(w: World): Long = w.now + 19 * hour // Friday 2026-10-30 10:00 in Warsaw
  private def desk(w: World): Desk = w.run(Desks.read[w.W](w.conf))
  private def run(w: World, c: Command, who: String = staff): Either[Refusal, Ran] = w.run(Run[w.W](w.conf, c, who))
  private def stored(w: World, ref: String): Booking = w.run(Store.booking[w.W](ref)).get
  /** `run`, and that it changed nothing: the store as it was, no letter */
  private def unchanged(w: World, c: Command, who: String = staff): Either[Refusal, Ran] =
    // all the store's keys but its own count of steps taken (a refused step is a step)
    def kept = w.storage.snapshot.filterNot(_._1.endsWith("#steps"))
    val (before, letters) = (kept, w.letters)
    val out = run(w, c, who)
    assertEquals(kept, before, c.toString)
    assertEquals(w.letters, letters, c.toString)
    out
  /** every start the engine offers anybody, by `/api/slots`, is one the desk offers */
  private def offered(w: World, d: Desk): Unit =
    val queries = w.site.work match
      case None => d.services.map((k, _) => s"service=$k")
      case Some(_) => for (k, _) <- d.services; m <- w.site.bookedModes(w.site.offer(k).get); p <- w.site.places(m)
        yield s"service=$k&mode=${m.key}&place=$p"
    // a service nobody does in a mode has no slots to ask for (404 `master`)
    val answers = queries.map(q => w.get(s"/api/slots?$q")).filter(_.status == 200)
    val starts = for r <- answers; day <- Descent.parse(r.text).toOption.get("days").list
      slot <- day("slots").list yield (day("date").string, slot("label").string)
    assert(starts.size > 100, starts.size)
    assertEquals(starts.filterNot((date, label) => d.days.contains(date) && d.times.map(Span.label)
      .contains(label)), Vector.empty)

  test("the desk of a shop of one calendar: its days, starts, booked services and the bookings still to come") {
    val w = World("salon")
    val (ref, _) = book(w, fri10(w))
    val (gone, key) = book(w, fri10(w) + hour, "service" -> "beard", "name" -> "Ewa")
    assertEquals(w.post("/api/booking/cancel", "ref" -> gone, "k" -> key).status, 200)
    val d = desk(w)
    assertEquals((d.days.size, d.days.head, d.days.last), (21, "2026-10-29", "2026-11-18"))
    assertEquals(d.times, (600 until 1080 by 15).toVector) // 10:00–18:00 on the step of 15
    assertEquals(d.services.map((k, name) => k -> name(Lang.pl)), Vector("cut" -> "Strzyżenie", "beard" -> "Broda"))
    assertEquals(d.masters, Vector.empty)
    assertEquals(d.bookings, Vector(Booked(ref, "Anna", "cut", "main", "2026-10-30", 600)))
    // what a line may choose is that data, nothing else
    assertEquals(Commands.options(d, "", "move", "booking"), Vector(ref))
    assertEquals(Commands.options(d, "", "book", "master"), Vector(Commands.Any))
    assertEquals(Commands.options(d, "", "dayoff", "master"), Vector.empty)
    offered(w, d)
    // two days on: the booking is past, the horizon moved
    w.now += 48 * hour
    assertEquals((desk(w).bookings, desk(w).days.head), (Vector.empty, "2026-10-31"))
    assertEquals(Desks.of(w.site, Vector(stored(w, ref)), w.now).bookings, Vector.empty)
    // a hidden service takes nothing new: it is not the desk's
    val hiding = World("salon", edit = s => s.copy(offers = s.offers.map(o => o.copy(hidden = o.key == "beard"))))
    assertEquals(desk(hiding).services.map(_._1), Vector("cut"))
  }

  test("the desk of a shop with modes and masters: every start the engine offers is one of the desk's") {
    val w = World("crew")
    val (ref, _) = book(w, fri10(w), "mode" -> "atShop", "place" -> "centrum", "master" -> "ola")
    val d = desk(w)
    assertEquals(d.masters, Vector("ola" -> "Ola Testowa", "piotr" -> "Piotr Próbny", "kasia" -> "Kasia"))
    // the parcel's service is not booked for a time
    assertEquals(d.services.map(_._1), Vector("cut", "visit", "consult", "both", "fix", "yoga"))
    // Mokotów opens at 9:00 on Saturdays; the class starts at 18:00, when the shop closes
    assertEquals((d.times.head, d.times.last), (540, 1080))
    assertEquals(d.bookings, Vector(Booked(ref, "Anna", "cut", "ola", "2026-10-30", 600)))
    offered(w, d)
    // a master's own week outside the shop's hours: Kasia consults from 8:00, and the desk has those starts
    val early = World("crew", edit = s => s.copy(masters = s.masters.map(m =>
      if m.key == "kasia" then m.copy(hours = Map(2 -> Vector(Span(480, 600)))) else m)))
    assertEquals(desk(early).times.head, 480)
    offered(early, desk(early))
  }

  test("move: the booking is at the new time in the store, its master kept, the client told; taken is refused") {
    val w = World("crew")
    val (ref, _) = book(w, fri10(w), "mode" -> "atShop", "place" -> "centrum", "master" -> "ola")
    val (other, _) = book(w, fri10(w) + 2 * hour, "mode" -> "atShop", "place" -> "centrum", "master" -> "ola",
      "name" -> "Ewa")
    val moved = run(w, Command.Move(ref, "2026-10-30", 660))
    assertEquals(moved, Right(Ran.Moved(stored(w, ref), true)))
    assertEquals((stored(w, ref).start, stored(w, ref).master), (fri10(w) + hour, "ola"))
    assertEquals(w.letters.last, "anna@example.com" -> Letters.rebooked(w.conf, stored(w, ref)))
    // Ola is taken at 12:00 and works until 14:00; Sunday is closed; nothing of these moves anything
    assertEquals(unchanged(w, Command.Move(ref, "2026-10-30", 720)), Left(Refusal.Refused("taken")))
    assertEquals(unchanged(w, Command.Move(ref, "2026-10-30", 15 * 60)), Left(Refusal.Refused("taken")))
    assertEquals(unchanged(w, Command.Move(ref, "2026-11-01", 660)), Left(Refusal.Refused("taken")))
    assertEquals(unchanged(w, Command.Move("TCRB-NOPE", "2026-10-30", 660)), Left(Refusal.Refused("no such booking")))
    assertEquals(unchanged(w, Command.Move(ref, "not a day", 660)), Left(Refusal.Refused("start")))
    assertEquals(stored(w, other).start, fri10(w) + 2 * hour)
  }

  test("cancel: the booking is cancelled in the store, the client told; once only") {
    val w = World("salon")
    val (ref, key) = book(w, fri10(w))
    val cancelled = run(w, Command.Cancel(ref))
    assertEquals(cancelled, Right(Ran.Cancelled(stored(w, ref), true)))
    assertEquals(stored(w, ref).status, BookingStatus.Cancelled)
    assertEquals(w.letters.last, "anna@example.com" -> Letters.unbooked(w.conf, stored(w, ref)))
    assertEquals(w.post("/api/booking/cancel", "ref" -> ref, "k" -> key).status, 409)
    assertEquals(desk(w).bookings, Vector.empty)
    assertEquals(unchanged(w, Command.Cancel(ref)), Left(Refusal.Refused("no such booking")))
  }

  test("paid: the payment is in the store by the workshop's rules — numbered, by whom; the staff cannot change it") {
    val w = World("salon", owner = _ => false)
    val (ref, _) = book(w, fri10(w))
    val paid = run(w, Command.Paid(ref, 6000, Method.Blik))
    val kept = w.run(Store.payment[w.W](Of.Booking, ref)).get
    assertEquals(paid, Right(Ran.Paid(kept)))
    assertEquals((kept.paid, kept.amount, kept.method, kept.day, kept.by, kept.receipt),
      (true, 6000L, Method.Blik, "2026-10-29", staff, "RACH/2026/0001"))
    assertEquals(unchanged(w, Command.Paid(ref, 7000, Method.Cash)), Left(Refusal.Refused("owner")))
    val (second, _) = book(w, fri10(w) + hour, "name" -> "Ewa")
    assertEquals(unchanged(w, Command.Paid(second, 0, Method.Cash)), Left(Refusal.Refused("amount")))
    assertEquals(unchanged(w, Command.Paid("TSLB-NOPE", 6000, Method.Cash)), Left(Refusal.Refused("no such booking")))
  }

  test("list: a day's bookings, and a master's, are the workshop's own list of them") {
    val w = World("crew")
    val at = Seq("mode" -> "atShop", "place" -> "centrum")
    book(w, fri10(w), (at :+ ("master" -> "ola"))*)
    book(w, fri10(w), (at ++ Seq("master" -> "piotr", "name" -> "Ewa"))*)
    val (gone, _) = book(w, fri10(w) + 2 * hour, (at ++ Seq("master" -> "piotr", "name" -> "Jan"))*)
    book(w, fri10(w) + 72 * hour, (at ++ Seq("master" -> "piotr", "name" -> "Ola"))*) // Monday
    assert(run(w, Command.Cancel(gone)).isRight)
    val listed = Descent.parse(WorkshopApiSuite().signedIn(w).get("/workshop/api/bookings").text).toOption.get(
      "bookings").list.map(b => (b("ref").string, Day.of(b("start").long), b("master").string))
    assertEquals(listed.size, 4)
    def refs(c: Command) = unchanged(w, c) match
      case Right(Ran.Listed(bs)) => bs.map(_.ref)
      case other => fail(other.toString)
    val friday = listed.filter(_._2 == "2026-10-30")
    assertEquals(refs(Command.Show("2026-10-30", None)), friday.map(_._1))
    assertEquals(refs(Command.Show("2026-10-30", None)).size, 3) // the cancelled one too, as the workshop shows it
    assertEquals(refs(Command.Show("2026-10-30", Some("piotr"))), friday.filter(_._3 == "piotr").map(_._1))
    assertEquals(refs(Command.Show("2026-11-02", Some("piotr"))).size, 1)
    assertEquals(refs(Command.Show("2026-11-03", None)), Vector.empty)
  }

  test("what the engine cannot run today is refused by its name and changes nothing: a staff's booking, a day off") {
    val w = World("crew")
    book(w, fri10(w), "mode" -> "atShop", "place" -> "centrum", "master" -> "ola")
    val d = desk(w)
    // both are commands of the desk: the options offer them, the engine has no operation for them
    val booking = Command.Book("Emma", "cut", Some("piotr"), "2026-10-30", 720)
    val dayOff = Command.DayOff("ola", "2026-11-05")
    assertEquals(Commands.close(d, "book Emma", Commands.path(d, booking)), Some(booking))
    assertEquals(Commands.close(d, "", Commands.path(d, dayOff)), Some(dayOff))
    assertEquals(unchanged(w, booking), Left(Refusal.Missing(Run.book)))
    assertEquals(unchanged(w, dayOff), Left(Refusal.Missing(Run.dayOff)))
    assertEquals(unchanged(w, Command.Cannot), Right(Ran.NothingToDo))
    assertEquals(w.site.masters.head.off, Vector("2026-11-02" -> "2026-11-04"))
  }

  test("from the desk to the store: a command of the real desk, said and parsed in each language, then run") {
    val w = World("salon")
    val (ref, _) = book(w, fri10(w))
    val d = desk(w)
    val move = Command.Move(ref, d.days(2), 690) // Saturday 11:30
    assertEquals(Commands.close(d, "", Commands.path(d, move)), Some(move))
    for l <- Lang.values do
      assertEquals(Commands.parse(d, Commands.say(d, move, l.code), l.code), Some(move), l.code)
    // a booking that is not on the desk is no command at all
    assertEquals(Commands.close(d, "", Commands.path(d, Command.Cancel("TSLB-NOPE"))), None)
    assert(run(w, move).isRight)
    assertEquals(desk(w).bookings, Vector(Booked(ref, "Anna", "cut", "main", "2026-10-31", 690)))
  }
