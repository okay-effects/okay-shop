package okay.shop.commands

import okay.scala3.*
import okay.scala3.std.Clock
import okay.shop.booking.Slots
import okay.shop.effects.Store
import okay.shop.html.Conf
import okay.shop.model.*

/**
 * THE DESK FROM A SHOP'S DATA (specs/commands.md "A1 as built"): what a command may name, read from the site's
 * content and the store — nothing here is invented, and nothing here changes anything.
 */
object Desks:
  /** every schedule something is booked by: the shop's one (before the modes), or each bookable service's in each
   * of its modes and places; and each of those as each master's own (their week, their days off closed) */
  private def schedules(site: Site): Vector[Schedule] =
    val plans = site.work match
      case None => site.schedule.toVector
      case Some(_) =>
        for o <- site.shown.filter(site.books); m <- site.bookedModes(o); p <- site.places(m)
          s <- site.plan(o, m, p).toVector
        yield s
    (plans ++ (for s <- plans; m <- site.masters yield Masters.schedule(s, m))).distinct

  /**
   * THE DESK of `site` at `now`, `taken` the store's bookings:
   *  - `days`: the days of the booking horizon, today (Poland's) first — `Slots`' own days;
   *  - `times`: EVERY start on a schedule's step inside any opening span the shop has on any day (its week and
   *    exceptions, its places', its masters'), and a class's own starts. Not the free starts of a service on a
   *    day: a start here may be closed that day or taken — the engine's check, in the store's step, decides;
   *  - `services`: the services shown and booked for a time, by key, in the content's order;
   *  - `masters`: the shop's masters, by key (none: a shop of one calendar);
   *  - `bookings`: the active bookings from `now` on, by their start, each as a command names it.
   */
  def of(site: Site, taken: Vector[Booking], now: Long): Desk =
    val horizon = site.schedule.getOrElse(Schedule(Map.empty, Map.empty))
    val services = site.shown.filter(site.books)
    val onStep =
      for s <- schedules(site); span <- s.week.values.flatten ++ s.exceptions.values.flatten
        t <- span.from until span.to by s.step
      yield t
    Desk(days = Slots.free(horizon, 0, Vector.empty, now).map(_.date),
      times = (onStep ++ services.filter(_.isClass).flatMap(_.times.map(_._2))).distinct.sorted,
      services = services.map(o => o.key -> o.name),
      masters = site.masters.map(m => m.key -> m.name),
      bookings = taken.filter(b => b.active && b.start >= now).sortBy(_.start).map(booked))

  /** a booking as a command names it: its day and its minutes from midnight in Poland's time */
  def booked(b: Booking): Booked =
    val at = Day.time(b.start) // `2026-10-30 10:00`
    Booked(b.ref, b.name, b.service, b.master, at.take(10), Span.minutes(at.drop(11)).getOrElse(0))

  /** the desk of the shop now: its content, the store's bookings to come */
  def read[R[_]](c: Conf)(using In[Store, R], In[Clock, R]): Desk ! R =
    Clock.now[R].flatMap(now => Store.bookings[R](now, Long.MaxValue).map(of(c.site, _, now)))
