package okay.shop.booking

import okay.shop.model.*

/** THE MASTER'S CALENDAR as iCalendar (RFC 5545): one event a booking, its times in UTC, its text escaped */
object Calendar:
  private def stamp(millis: Long): String = Civil.iso(millis).replace("-", "").replace(":", "")
  private def text(s: String): String =
    s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")
  /** a line folded as the format asks: at most 75 octets a line, the continuation's leading space counted, never
   * inside a character */
  private def fold(line: String): String =
    val b = new StringBuilder
    var used = 0
    var i = 0
    while i < line.length do
      val cp = line.codePointAt(i)
      val w = Character.charCount(cp)
      val n = if cp < 0x80 then 1 else if cp < 0x800 then 2 else if cp < 0x10000 then 3 else 4
      if used + n > 75 then
        b.append("\r\n ")
        used = 1
      b.append(line.substring(i, i + w))
      used += n
      i += w
    b.toString

  def ics(site: Site, bookings: Vector[Booking], now: Long): String =
    val events = bookings.map { b =>
      val service = site.offer(b.service).map(_.name(site.lang)).getOrElse(b.service)
      Vector("BEGIN:VEVENT", s"UID:${b.ref}@${site.domain}", s"DTSTAMP:${stamp(now)}", s"DTSTART:${stamp(b.start)}",
        s"DTEND:${stamp(b.end)}", s"SUMMARY:${text(s"$service — ${b.name}")}",
        s"DESCRIPTION:${text(s"${b.ref} · ${b.contact}" + b.mode.fold("")(m => s" · ${m.label(site.lang)}"))}") ++
        b.address.map(a => s"LOCATION:${text(a.line + (if a.notes.isEmpty then "" else s" (${a.notes})"))}") :+
        "END:VEVENT"
    }
    (Vector("BEGIN:VCALENDAR", "VERSION:2.0", s"PRODID:-//okay-shop//${text(site.name)}//EN",
      s"X-WR-CALNAME:${text(site.name)}") ++ events.flatten :+ "END:VCALENDAR").map(fold).mkString("", "\r\n", "\r\n")
