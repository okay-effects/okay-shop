package okay.shop.booking

import okay.shop.model.*

/** a free start: its instant (UTC) and its time as the shop says it (`14:30`) */
final case class Slot(start: Long, label: String)

/** a day of slots: its date (`2026-11-03`), its weekday (1 Monday … 7 Sunday), its free starts */
final case class SlotDay(date: String, weekday: Int, slots: Vector[Slot])

/**
 * THE FREE STARTS of a service (specs/shop.md "Booking"), pure: each day of the horizon from today (Poland's time),
 * its spans by the week or its exception, a start every `step` minutes where the service fits before the span ends,
 * from `lead` minutes after now, none overlapping an active booking of the master
 */
object Slots:
  private val day = 86400000L

  /** a local time of Poland (milliseconds of its wall clock since the epoch) as the instant it is */
  def instant(local: Long): Long = local - Day.offset(local - Day.offset(local))

  /** `before` and `after`: the new booking's own buffer (the master's travel), kept free of others around it */
  def free(schedule: Schedule, minutes: Int, taken: Vector[Booking], now: Long, before: Int = 0, after: Int = 0)
    : Vector[SlotDay] =
    val today = Math.floorDiv(now + Day.offset(now), day)
    val earliest = now + schedule.lead * 60000L
    val busy = taken.filter(b => b.active && b.master == schedule.master)
    (0 until schedule.horizon).toVector.map { i =>
      val days = today + i
      val (y, m, d) = Civil.ymd(days)
      val date = f"$y%04d-$m%02d-$d%02d"
      val weekday = Math.floorMod(days + 3, 7L).toInt + 1
      val starts = for
        span <- schedule.spans(date, weekday)
        t <- span.from to (span.to - minutes) by schedule.step
        start = instant(days * day + t * 60000L)
        if start >= earliest && !busy.exists(_.overlaps(start - before * 60000L, start + (minutes + after) * 60000L))
      yield Slot(start, Span.label(t))
      SlotDay(date, weekday, starts)
    }

  /** whether `start` is one of the free starts of the service now: a booking's check, again, in its step */
  def isFree(schedule: Schedule, minutes: Int, taken: Vector[Booking], now: Long, start: Long, before: Int = 0,
             after: Int = 0): Boolean =
    free(schedule, minutes, taken, now, before, after).exists(_.slots.exists(_.start == start))
