# okay-shop — a small business's site on effects: storefront, orders, tracking, a workshop

## Overview

The engine first written for two small businesses' sites (2026-10-28), made a library so each site's repository holds
only
what is its own — its content, its look, a one-line Worker main, its deployment — and both run the same engine:

- the order of a ready-made offer or of an open request, its tracking by a short ref, its receipt; an inquiry;
- the workshop: staff signed in by a mailed one-use link, the orders moved through their statuses, clients written to;
- the pages pre-rendered per language (the site's look gives their markup), the forms' script, the letters.

Why a library of its own (operator, 2026-10-28): it fits none of the family — okay-serverless is the cloud (it gives
the Worker, the object, `Durable`, `Mails`, `Tcp`/`Smtp`), okay-ui the screens (the workshop's are okay-ui's), and
this is a business's domain on top of both.

## The rule

THE ARCHITECTURE IS EFFECTS. The data model is effects (`Store`, `Mail`, `Captcha`, `Ids`; the workshop's `Staff`,
`Human`); every implementation a handler — onto okay-serverless' effects in the cloud (`StoreDurable`, `MailCloud`,
`Turnstile`), in memory in tests. A site's object runs `Api.answer` on one line of handlers.

## Modules (package `okay.shop`)

| module | artifact | what | platforms |
| --- | --- | --- | --- |
| `shop` | `okay-shop` | the model, the content's reader, the effects and their handlers, the programs (`Api`, `Front`, `Workshop` API, `Submit`), the pages of an order (`Track`), the letters, the storefront's markup and forms (`Pages`, given a site's `Look`), the privacy policy (`Privacy`: a sole trader's RODO policy in four languages, the processors of its kind; a look may give its own), the static pages (`StaticPages`) | JVM, Scala.js |
| `workshop` | `okay-shop-workshop` | the workshop's program on okay-ui (`Workshop.main`), `Staff` over `Fetch`; in a page `okay.shop.workshop.InThePage` (a main) | JVM, Scala.js |
| `page` | `okay-shop-page` | the forms' script, `okay.shop.page.Site` (a main) | Scala.js |
| `worker` | `okay-shop-worker` | the Worker of a site on okay-serverless: `ShopWorker(site, look)` — the app (redirects, the static pages, the dynamic paths to the object) and the site's `Actor` | Scala.js |

A site's repository: its `site/site.md` and `site/i18n.md` (read by `Sites.of`; the front matter says the domain and
the ref's prefix), its `Look` (the theme's CSS, its own sections, its SEO words, its privacy policy, pages of its own
like IT's `/products`, its 404), a JVM main writing the static pages (`StaticPages.of(site, look, sitekey)`), the
Worker's one line (`@JSExportTopLevel("worker") val worker = ShopWorker(site, look)` — never in this library: a
top-level export in a library lands in every user's linked code), `wrangler.toml`, `deploy.sh`, its look's tests
and its e2e.

## Behavior

Everything specified and checked for its first two sites (2026-10-28), unchanged:
orders (validation, Turnstile, 5 per IP in 10 minutes, consent with its time and policy version, the owner's letter,
the client's confirmation with the ref the track page takes and a link with the order's key), the track page (by the
key, or by the ref and the email or phone; nothing told to a stranger), the receipt, the IT inquiry, the workshop
(sign-in, the moves `Workflow` offers, the letters on ready and parcel out, a free letter), the redirects of busi's
URLs. The tests run on two FICTIONAL sites (shop/src/test/resources): "Test Atelier" (an atelier: courier, BLIK
+48 600 000 000, paczkomat TST01A) and "Test Studio" (IT-like: the AI offer first, products); addresses are
@example.com — no real business's data in this repository.

## The workshop's link, and search engines (operator, 2026-10-28)

Every public page's foot — the storefront's, an offer's, the privacy policy's, the track search's — ends with one
muted word in the page's language ("panel", "staff", "панель") leading to `/workshop/`, `rel="nofollow"`, in the
foot's own colour and size (`Look.staffLink`, on by default; a look's own page puts `Pages.staff` in its foot). The
workshop is not for search engines: its frame says `noindex`, the static files `_headers` give
`X-Robots-Tag: noindex` for `/workshop/*`, and `robots.txt` disallows `/workshop/` and `/api/`.

## Booking (operator, 2026-10-29: a single shop's feature, so it is here)

A shop may take bookings for services that last: a barber's cut, a fitting. One master per shop in v1; the model
names the master everywhere, so more come without a change of data.

- **The content says it.** A service with `minutes: 30` in `site.md`'s services is bookable; a fenced block
  ```` ```yaml schedule ```` gives the week (`- day: mon` `from: 10:00` `to: 18:00`, a day may come twice) and the
  exceptions (`- date: 2026-12-24` `closed: true`, or its own `from`/`to`); the front matter may say `step: 15`
  (minutes between starts), `horizon: 21` (days ahead, 14–28) and `lead: 60` (minutes from now to the first). Times
  are Poland's (`Day`), stored as UTC instants.
- **Slots** (`Slots.free`, pure): each day of the horizon, its hours by the week or its exception, starts every `step`
  minutes where the service fits before the hours end, from `lead` minutes on, none overlapping an active booking
  of the master. `GET /api/slots?service=<key>` answers them by day.
- **A booking** (`POST /api/book {service, start, name, contact, rodo_consent, lang, turnstile}`): Turnstile, 5 per
  IP in 10 minutes, the consent kept as an order's; `Store.Book` checks the slot against the bookings IN THE SAME
  `Durable` step — the shop's object is one writer, so two people never get one slot; a slot taken meanwhile is
  refused (`409 taken`). Answered `{ref, key, start}`; the client mailed (when the contact is an email) with the
  time and the link to manage it, the owner mailed.
- **Manage** (`/{lang}/booking?ref=&k=`, the letter's link): the booking, cancel (`POST /api/booking/cancel`), move
  to another free slot (`POST /api/booking/move`, the slot picker again); the owner mailed of each.
- **A reminder 24 h before**: every change of the bookings sets the object's alarm (`Durable.wakeAt`) at the earliest
  reminder due; the alarm (`Actor.alarm`) takes the bookings whose reminder is due, marks them reminded in one step,
  mails each client, and sets the next alarm.
- **The master's calendar**: `/calendar/<CALENDAR_KEY>.ics` — the active bookings from a week ago to the horizon as
  iCalendar events (30 days ahead), to subscribe to from a phone; a wrong key, or none set, is a 404. The
  reminders' links use the `ORIGIN` var (default `https://<domain>`): an alarm has no request to say it.
- **The workshop** shows the bookings from a day ago on ("Записи", only when the shop has a schedule): time,
  service, client, contact; a booking cancelled there mails the client.
- **The pages**: a bookable service's page has the slot picker (days, then their starts; the pages' script reads
  `/api/slots`) and the booking form in place of the order form; its done panel shows the ref, the time and the
  manage link. Tests: `BookingSuite` (the fictional `salon` fixture) and the workshop's `WorkshopSuite`.

## How the work happens: service modes (operator, 2026-10-29)

Where and how a service meets its client is the business's nature: by mail, in person, or both. A shop says which
of four MODES it works in; each service may narrow them. Data (`Work`, in `Site.work`), read from the content or
given by the host; with none (`work: None`, every config before this) every page, letter and answer is as it was,
byte for byte — the atelier is `byMail` by its courier flow, a salon with a schedule `atShop` with its hours.

- **The modes** (`Mode`): `atShop` — the client comes to the master: one or more LOCATIONS (a key, name, street,
  city, postcode, an optional map link, notes like the floor or the entrance, its own hours — else the shop's);
  `atClient` — the master comes to the client: the client gives the address at booking, the shop its SERVICE AREA
  (`Area`: cities or districts by name — the client's city equal to one, or starting with it and a space or a
  hyphen, diacritics and case aside — and/or postcode prefixes like `02-` or `05-5`; either matching is inside), an
  optional travel fee, a TRAVEL BUFFER (minutes before and after, reserved by the calendar) and the visits' own hours
  — else the shop's; `byMail` — a parcel (InPost paczkomat, courier): the order flow as it is, no slot; `online` — a
  video call or a phone call (`Online`: how, and its details — a link, "we call you" — given in the confirmation).
  No radius from a base address: that would need geocoding.
- **Per service** (`Work.services`, by the offer's key): the modes it allows (default: all the shop's), a price
  difference per mode (an at-client surcharge), and its minutes when the content gives none (a service the host makes
  bookable). A service with minutes is booked in `atShop`, `atClient`, `online`; `byMail` is always the order form.
  The price of a booking: the service's, plus its mode's difference, plus the travel fee at the client's.
- **The content says it** (a single site): a fenced ```` ```json work ```` block in `site.md`, the same JSON as a host
  gives (`Work.fromJson`; `Work.toJson` writes it back): `{"modes":[…], "locations":[{key, name, street, city,
  postcode, map, notes, hours:[{day, from, to}]}], "area":{places, postcodes, fee, before, after, hours},
  "online":{how: video|phone, details}, "services":{<key>:{modes, extra:{<mode>: "20"}, minutes}}}`; amounts in zł
  as text. `Work.check(w, offers)` refuses by the name of the first wrong field: no mode or an unknown one, atShop
  with no location, a location without a name or city, a bad postcode (`NN-NNN`) or map link (`https://` only), a
  bad hour, atClient with no place or prefix, a fee or difference out of 0–10000 zł, a buffer out of 0–240, online
  without `how`, a service restricted to modes the shop has not, unknown services, texts too long.
- **Booking respects the mode**: the service's page asks how (only the modes it allows; one — no question), then for
  atShop the location (one — shown, not asked), for atClient the address (street, postcode, city, notes), for online
  nothing more; then the free starts. `GET /api/slots?service=&mode=&place=` (mode and place default to the first
  allowed): the hours of the location, of the visits (atClient) or the shop's; the shop's exceptions, step, horizon
  and lead; the active bookings of the master in the way — each with its own buffer around it, and the new one's
  buffer (atClient) around it too; the service itself fits the hours, a buffer may lie outside them. `GET
  /api/area?city=&postcode=` says whether an address is inside (the page tells the client before the slots).
  `POST /api/book` takes `mode`, `place`, `street`, `postcode`, `city`, `notes`: a mode the service does not allow,
  an unknown location, an address outside the area (`409 area`) or incomplete (`address`) refused; the store checks
  the slot with the buffers in its step as before. The booking keeps its mode, its location's key, the ADDRESS of
  the meeting (the location's, copied; or the client's), its buffer and its price; a move keeps them.
- **Shown**: the confirmation, the reminder, the move and cancel letters, the booking's page (its link) and the
  owner's letter and the workshop's list say the mode and the address — the location's with its map link, the
  client's with a map search link (`https://www.google.com/maps/search/?api=1&query=…`, no key), online its
  details; the master's calendar its `LOCATION`. The storefront's offer page says the modes and the locations'
  addresses (public: the shop's own).
- **Privacy**: a client's address is personal data — kept in the booking, shown only to the shop's staff (the
  workshop, the owner's letter, the calendar by its key) and on the client's own link; never on a public page. The
  privacy policy says so when the shop works at its clients'.
- **Tests**: `ModesSuite` (the fictional `visits` fixture: two locations, an area with a fee and a buffer, an
  online call, services narrowed), `OldPagesSuite` (the three older fixtures' pages, a booking's letters, its page
  and its calendar by their SHA-256 taken before the modes: unchanged).

## Masters: each with a calendar of their own (operator, 2026-10-29)

A shop with more than one master books each of them, not the shop. Data (`Site.masters`; none, every shop before
this: one calendar, the master `main`, exactly as before).

- **A master** (`Master`): a key, a display name, initials (from the name when not given), an optional email (a team
  member who signs in to the workshop; none: a master without a login, a name only), the services they do, the
  modes they work in and the locations (all of each when none), their own week (none: the place's or the shop's
  hours) and their days off (date ranges, both ends included).
- **The content says it** (a single site): a fenced ```` ```json masters ```` block in `site.md` — a list, the same
  JSON a host gives (`Masters.fromJson`, checked by field against the services, the modes and the locations:
  `master.key`, `master.name`, `master.email`, `master.services`, `master.modes`, `master.places`, `master.hours`,
  `master.off`, `masters.twice`, `masters.size` — at most 30).
- **Eligible**: for a service in a mode at a place, the masters who do that service in that mode there. **Slots**:
  with `master=<key>` that master's free starts (their week, their days off as closed days, their bookings with
  their buffers); without one ("any free master", the default), the union of the eligible masters' free starts.
  A booking made or moved before the masters (master `main`, or any key no longer a master's) keeps every master
  busy at its time: no double booking across the change.
- **A booking** is always a concrete master's: `POST /api/book` takes `master` (empty: any); the store chooses, IN
  ITS STEP, the asked master if free, else (any) the first eligible master free at that start, in the masters'
  order (`Store.BookWith`), or refuses (`409 taken`). A move (`/api/booking/move`, `master` as well) keeps the master
  when free, or takes the one asked, or (any) the first free (`Store.RebookWith`).
- **Shown**: the master's name in the confirmation, the reminder, the move and cancel letters, the booking's page
  (its move form offers the masters again), the owner's letter, the calendar's event, and the workshop's list (a
  "master" column; the bookings endpoint says each booking's master's name and whether it is the signed-in
  person's; a master who signs in sees their own first — "mine", with "all" a click away).
- **The pages**: a bookable service's form has a master picker (any free master first, then each master doing the
  service; the pages' script shows only those eligible for the mode and place chosen) when the shop has masters.
- **Tests**: `MastersSuite` (the fictional `crew` fixture: the visits' work and three masters).

## Inquiry and quote (operator, 2026-10-29)

Some work cannot be priced from a list: a leaking pipe, a sofa to clean. The client describes it; the shop answers
with a quote; the client accepts or declines by their link.

- **A quoted service**: `quote: true` in the content's `yaml services` (or the work's `services.<key>.quote`). Its
  page has the inquiry form in place of the order or booking form.
- **Asked** (`POST /api/quote {service, name, contact, description, mode, street, postcode, city, notes, preferred,
  rodo_consent, lang, turnstile}`): the description (1–2000 characters), the mode and the address when the service
  is at the client's (the area checked as a booking's), the preferred dates in the client's words; Turnstile, 5 per IP
  in 10 minutes, the consent kept. Answered `{ref, key, manage}`; the client mailed the link to its page, the owner
  the inquiry. Text only: no photos (no blob store in the engine yet).
- **Quoted** by the workshop (`POST /workshop/api/quote/reply {ref, price, priceTo, start, note}`): a price, or a
  range (`price`–`priceTo`), optionally a proposed time (an instant) and a note; the client mailed. The workshop's
  list (`GET /workshop/api/quotes`): every inquiry with its status — asked, quoted, accepted, declined.
- **Decided** by the client on the quote's page (`/{lang}/quote?ref=&k=`; `POST /api/quote/accept`,
  `/api/quote/decline` with `ref`, `k`): accepted, it becomes a BOOKING when it has a proposed time and its service
  is bookable (the store's check, as any booking: `409 taken` when the time was taken meanwhile) — else an ORDER
  (status accepted, the quoted price its amount, the description its need); its ref on the quote. The owner mailed
  either way; the client mailed the booking's or the order's link.
- Store: `site/quote/<ref>` (`Store.Ask`, `FindQuote`, `Quotes`, `Reply`, `Decide`, each change one step).

## Group classes (operator, 2026-10-29)

A class takes many clients at once: yoga, a dance class, a workshop.

- **A class**: a service with `seats: 12` and `times: mon 18:00, wed 18:00` (weekly starts) in the content's `yaml
  services` (or the work's `services.<key>.seats` and `.times`), its `minutes` its length. Its occurrences: each
  weekly time over the horizon, from the lead on, none on a closed date; in a mode and at a place as other bookings
  (a location's class, an online class).
- **Slots** (`GET /api/slots?service=…`): the occurrences with seats left (`left`), a full one not offered; the page
  shows "18:00 · 5 seats left". **A seat** (`POST /api/book` as a booking): the store counts the active bookings of
  that class at that start IN ITS STEP and refuses the one past the capacity (`409 full`); cancelling frees a seat.
  The price is per seat. A class's master is busy at its time for other services. No waiting list yet.
- Tests: `QuotesSuite`, `ClassesSuite` (the fictional `visits` fixture's `fix` and `yoga`).

## The host's own workshop pages (operator, 2026-10-29)

A site built on the engine may add pages of its own to the workshop (the mall's "Appearance" screen) without the
engine knowing what they are:

- **`Conf.more`**: given the email signed in, the host's pages for that person — each a text and an address
  (`Vector[(String, String)]`; none by default). `GET /workshop/api/more` answers them (`{"more":[{text, href}]}`;
  401 signed out). The workshop asks once after sign-in and shows each as a link on its orders screen
  (`Staff.More`, `Shown.more`).
- **`Workshop.person(c, r)`**: who is signed in (a session kept and not past, its email on the list still), and
  **`Workshop.sameSite(r)`**: whether a POST is this site's own (JSON, its own origin when said) — for the host's own
  endpoints and pages, which check them before anything else.
- The engine never adds a host's page itself, and a link to one is only what `Conf.more` says for that person.
- **`Conf.desk`** (`Desk(path, cookie)`, by default `/workshop` and `__Host-ws`): where the sign-in lives — the
  letter's link `<origin><path>/api/enter?t=…`, the enter page's form, the redirects to `<path>/` and
  `<path>/#expired`, the session's cookie; `Api.answer` routes `<path>/api/…` to the workshop. A
  host signs its own people in elsewhere with the same mechanism (the
  mall's operators at `/_admin`).
- **`Workshop.invite(c, email)`**: the host mails a sign-in link to an address on the staff list itself (a new
  shop's owner invited) — no captcha and no limit, so never reachable by a visitor; false for an address off the list.
- **The sign-in link is limited per address too**: 5 per IP and 5 per address (its SHA-256) in 10 minutes; the
  answer is the same for an address on the list and one not.

## The owner edits the content (operator, 2026-10-29)

A shop's words and prices are its content (`site.md`, `i18n.md`). A host lets its owner change them on a screen;
the engine gives the model of what may be edited, its check, and the content written back in its own format — so a
file-based site (a repository's `site/`) and a host's stored copy are the same text, read by the same `Sites.of`.

- **The model** (`okay.shop.content.Content`): the shop's language (the content's `langDefault`: the owner writes
  in it, its text is `site.md`'s), its name and logo text (one language), its tagline and its public contact text
  (`T`: the `tagline` and `contact` tokens), its services in their order — each its key, name and description (`T`:
  `name.<key>`, `desc.<key>`), price (grosze; 0: priced once seen), minutes (0: ordered, not booked), whether asked
  for a quote, a class's seats and weekly times, whether HIDDEN — and the texts machine-translated and not yet read
  by a person (`auto`: `<token>.<lang>`, e.g. `name.cut.en`). A price is one amount: the engine has no "from" price
  or range for a listed service (a quote's answer has a range: "Inquiry and quote").
- **Read and written** (`Content.read(siteMd, i18nMd)`, `Content.write(c, siteMd, i18nMd)`): the edit is a PATCH of
  the text — the front matter's `name`, `logoText`, `tagline`, `contact` lines replaced (added when missing), a
  service's row in `yaml services` changed only in the fields that changed (its other fields, its comments and its
  order kept), rows added, removed, and — only when the order of the services changed — written in the new order
  with `order: 1…n`; in `i18n.md` the rows of the texts that changed (`src` the shop language's text, the four
  languages, `auto: en uk` while machine-made), new rows added; everything else of both files as it was. Written
  unchanged, a content is the same bytes; written with an edit, read back it is the edit (`ContentSuite`).
- **Keys are stable**: a service keeps its key when renamed (orders, bookings, quotes, links and a host's work and
  masters name it by its key); a new service's key is made from its name (ASCII letters, digits, hyphens; `-2`…
  when taken).
- **Hidden** (`hidden: true` in its row; `Offer.hidden`): not on the storefront nor its own page (`Site.shown`), and
  no new order, booking or inquiry for it (`offer`, `service` refused); everything already made for it — a booking's
  page, a move, a cancel, the workshop, a letter, a receipt — still names it.
- **Removing a service something refers to hides it instead**: a service named by any order, booking or inquiry
  (`Content.used`) is kept, hidden; one nothing names is removed. The host is told which were hidden instead.
- **Checked** (`Content.check`, by the name of the first wrong field): `name` 1–80, `logoText` 1–40, `tagline` 1–160
  and `contact` 0–300 characters in every language, at most 50 services, a service's `service.key` unique and of the
  key's form, `service.name` 1–80, `service.description` 0–300, `service.price` 0–100 000 zł, `service.minutes` 0
  or 5–480, `service.seats` 0–200 (a class with times and minutes), `service.times` (`mon 18:00, wed 18:00`); every
  text one line (whitespace runs one space). No markup is taken: every text is escaped wherever it is drawn (the
  pages, the letters, the workshop), as before.
- Tests: `ContentSuite` (the five fixtures written unchanged byte for byte; edits read back; key stability; hidden;
  the checks; an escaped name on the pages).

## Money: payments, income, reports, receipts (operator, 2026-10-29)

What a shop was paid, as data — so its owner sees the month and gives the accountant a table. One shop's: the engine.
Expenses are a later stage (not here).

- **A payment** (`Payment`, `okay.shop.model`): what it is of (`order`, `booking` — a quote accepted became one of
  them — or `manual`: income from work outside the system), its ref, the amount (grosze), the method (`cash`, `card`,
  `blik`, `transfer`, `other`), the day paid (`YYYY-MM-DD`, Poland's), its state (`paid`, `unpaid`, `refunded`; a
  refund's own day), who marked it (an address) and when, a note; a manual one its description and, optionally, a
  service and a master; its receipt's number; a log of every change (when, who, what, amount, method, day). Stored
  `site/pay/<of>/<ref>`, each change ONE step of the shop's object (`Store.Pay`): the rule checked and the receipt
  numbered in the same step.
- **The rules** (`Payments.mark`, pure, by the name of the refusal): marking PAID an unpaid one (none, or `unpaid`)
  — any staff member or the owner; changing a paid one (amount, method, day, note), UNDOING it (`unpaid`) or
  REFUNDING it — the owner only (`owner`); a refund only of a paid one (`state`); a manual income — the owner only.
  The amount 0,01–1 000 000 zł (`amount`), the day a date not after today (`day`), a known method (`method`), a note
  at most 300 characters, a manual one's description 1–200 (`description`). A cancelled order or booking is marked
  paid by the owner only (a cancellation fee). Who the owner is, is the host's (`Conf.owner`; a site with none: every
  staff member).
- **Old data**: an order or booking without a record is unpaid — but an order the workflow moved to `paid` (the
  atelier's `Status.Paid`, its amount and `paidAt`) counts as paid (`Payments.effective`: its method from the
  order's — a transfer `transfer`, else `other` —, its receipt the old `RACH-<year>-<ref>`), until a record says
  otherwise. Nothing old is rewritten.
- **A no-show**: a past booking marked so by the staff (`POST <desk>/api/booking/noshow`; `BookingStatus.NoShow`),
  counted in the reports.
- **The workshop**: an order's and a booking's screens show the payment and offer "paid" (amount — the order's or
  the booking's price to start with —, method, day, note), and for the owner "change", "not paid", "refund"; a
  past booking "no-show". `POST <desk>/api/pay {of, ref, action: paid|unpaid|refund, amount, method, day, note}`,
  `GET <desk>/api/payment?of=&ref=`; 403 `owner` for what only the owner may do.
- **Manual income** (the reports screen, the owner's): `POST <desk>/api/income {amount, day, method, description,
  service, master, note}`, removed by marking it unpaid; `GET <desk>/api/incomes`.

### Reports (`Reports`, pure; the screen `<desk>/reports/`, the owner only — staff 403, nobody 401)

- **A period**: from–to, days of Poland, both included, at most 366 days; the screen's quick ones: today, this
  week, this month (the whole month, the default), last month, a custom range; the previous period is the same
  number of days just before.
- **Totals**: income paid (the payments whose day is in the period, refunded later or not), refunds (by the refund's
  day), net; unpaid — done but not paid (an order `ready`, `parcel_out` or `delivered`, a booking past, with no
  payment: its amount, else its price, else its service's price) and expected (bookings ahead in the period, not
  paid); the counts of orders (placed in the period, not cancelled), bookings (starting in it, kept), visits (kept
  and past), cancellations (orders and bookings), no-shows; the average ticket (income ÷ payments).
- **Breakdowns** of the income: by service, by master, by mode (a booking's; an order "order"; a manual income
  "other"), by method; a day by day chart (inline SVG, no library) and table; each total beside the previous
  period's.
- **Exports**: CSV (`<desk>/api/report.csv?from=&to=&comma=1`): UTF-8 with a BOM (Excel), one row a payment —
  date, receipt number, kind, ref, description, service, master, mode, method, amount, state —, comma decimals and
  `;` columns when `comma=1` (Excel in Polish), else `.` and `,`; a printable page (`<desk>/reports/print?…`: print
  CSS, a PDF by the browser's print). A month's **"ewidencja przychodów" HELPER** (`<desk>/reports/ewidencja?month=`
  and `&format=csv`): Lp., date of the income, the document's number (the receipt's), description, amount, the total
  — said on its face to be a helper, NOT the official record, to be checked with the accountant (the rules below).

### Receipts

- A paid order or booking (or manual income) has a receipt (`rachunek`, non-fiscal) numbered per shop per year in the
  order of marking: `RACH/<year>/<n>` (the year of the day paid; four digits, more when needed), kept by its
  payment (undone and paid again: the same number). The client's page links it: the track page of a paid order, the
  booking's page of a paid booking — `/{lang}/receipt?ref=&k=` (the key the second factor; 404 when unpaid). The
  page: the shop (and `Conf.contact`), the client, the service, the date of the service and of the payment, the
  method, the amount; "not a VAT invoice nor a fiscal receipt". An order paid by the old workflow keeps its old
  receipt, byte for byte.

### Polish rules — TO VERIFY with an accountant before relying on any of it (research of 2026-10-29)

Not built: no KSeF, no fiscal printer, no official ledger. Sources read on 2026-10-29; uncertain points marked.

- **KSeF (e-invoices)**: obligatory from 1 Feb 2026 for firms with 2024 sales above 200 mln zł, from 1 Apr 2026
  for everyone else; receiving through KSeF since 1 Feb 2026. Until 31 Dec 2026 invoices may stay outside KSeF
  while their gross total in a month is at most 10 000 zł (from the invoice that passes it, everything in KSeF);
  from 1 Jan 2027 everyone. Invoices to consumers (B2C) in KSeF are voluntary. A VAT-exempt seller (zwolnienie
  podmiotowe, 240 000 zł from 2026) issues an invoice only when a buyer asks — then in KSeF; the Ministry said
  (26 Jan 2026) a rachunek outside KSeF may be given to a business instead (MEDIUM confidence: a ministry letter,
  not the statute). No penalties in 2026; from 1 Jan 2027 (art. 106ni). Sources:
  https://ksef.podatki.gov.pl/od-kiedy-trzeba-wystawiac-faktury-w-ksef/, https://ksef.podatki.gov.pl/ponizej-10-000-zl/,
  https://ksef.podatki.gov.pl/konsumenci-i-osoby-fizyczne/,
  https://www.podatki.biz/artykuly/ksef-rachunek-zamiast-faktury-w-przypadku-podatnikow-zwolnionych-z-vat-mf-odpowiada_34_61179.htm
- **Kasa fiskalna online**: Rozporządzenie MF z 17 grudnia 2024 (Dz.U. 2024 poz. 1902, amended Dz.U. 2026 poz. 420),
  exemptions valid until 31 Dec 2027. Exempt by turnover: sales to private persons up to 20 000 zł a year
  (proportionally in the first year), lost 2 months after passing it. ALWAYS a register, whatever the turnover or
  the way of payment: hairdressing, cosmetic and cosmetology services (§ 4 ust. 1 pkt 2 lit. j) — so a barber, a
  hairdresser, a nail or beauty studio needs one from the first sale to a private client; also car repair, tyres,
  legal and tax advice, catering, taxis. Tailoring, cleaning, a handyman, classes: not on that list. Exempt by
  payment (annex, position 42): a service to a private person paid WHOLLY through a bank, the post or a SKOK, the
  record showing what each payment was for — any cash spoils it; card, BLIK and online payments accepted by a KIS
  ruling (14 Mar 2024, 0114-KDIP1-3.4012.682.2023.2.KP) and two WSA judgments (MEDIUM confidence: case law, not the
  regulation's text). A June 2026 draft adapting the regulation to KSeF exists; its content not known. Sources:
  https://eli.gov.pl/api/acts/DU/2024/1902/text/O/D20241902.pdf, https://eli.gov.pl/eli/DU/2026/420/ogl,
  https://ksiegowosc.infor.pl/podatki/vat/kasy-fiskalne/6716102,zaplata-karta-lub-blikiem-czy-mozna-nie-rejestrowac-transakcji-na-kasie-fiskalnej-kiedy-jeszcze-stanowiska-fiskusa-i-sadow.html
- **Ryczałt — ewidencja przychodów**: Rozporządzenie MFiG z 6 września 2025 (Dz.U. 2025 poz. 1294), from 1 Jan 2026:
  columns Lp., date of the entry, date of the income, the KSeF number of an invoice, the document's number, the
  contractor's tax id, the income by rate (17/15/14/12,5/12/10/8,5/5,5/3 %), total, remarks; entries by the 20th of
  the next month; sales without an invoice and without a register: one internal document a day with that day's
  total by rate. Electronic record and a yearly JPK_EWP: for active VAT payers for 2026 (first file by 30 Apr 2027),
  for the others from 1 Jan 2027 — a draft (Aug 2026) would defer it for non-VAT ryczałt payers to 2029 (NOT
  confirmed signed). Rates: 8,5 % for most services (hairdressing, beauty, clothes repair, cleaning); 15 % for
  some (building upkeep and minor repairs PKWiU 81.10.10.0, sport and recreation division 93 — a yoga class may be
  8,5 % as instruction 85.51 or 15 % under 93: the classification decides); 5,5 % for making goods. Sources:
  https://eli.gov.pl/eli/DU/2025/1294/ogl/pol/pdf, https://api.sejm.gov.pl/eli/acts/DU/2025/843/text/U/D20250843Lj.pdf,
  https://bezprawnik.pl/ministerstwo-finansow-odracza-uciazliwy-obowiazek-dla-28-mln-firm/
- **Rachunek** (Ordynacja podatkowa art. 87–88): given when a buyer asks and no invoice is due, within 7 days of the
  service (or the request), not when asked more than 3 months later; numbered in sequence, copies kept until the
  limitation ends. Its content (names and addresses of both sides, date, number, the service, quantity, price, total)
  is from a ministry regulation whose number was NOT confirmed (LOW confidence on the exact list). Source:
  https://api.sejm.gov.pl/eli/acts/DU/2025/111/text/U/D20250111Lj.pdf
- **Działalność nierejestrowana**: from 2026 a quarterly limit of 225 % of the minimum wage — 10 813,50 zł a quarter
  in 2026 (a portal's figure: https://www.pit.pl/aktualnosci/ponad-10-tys-zlotych-bez-zakladania-firmy-dzialalnosc-nierejestrowana-na-nowych-zasadach-w-2026-roku-1011738).
- So the engine's receipt is a client's confirmation (non-fiscal), its "ewidencja" a helper table; a hairdresser's
  or beautician's sales to private persons still go through their fiscal register.
- Tests: `MoneySuite` (the rules, the store's step and numbering, the old paid orders, the report's sums, periods,
  breakdowns, the previous period, the CSV, the receipts and their links, owner-only).

## Open core (operator, 2026-10-29)

okay-shop is public (Apache-2.0) and is the engine of ONE site: its storefront, orders, tracking and workshop — and,
later, booking with a calendar as a feature. The multi-tenant SaaS platform to come lives in a separate PRIVATE
repository on top of okay-shop and is never added here: self-service sign-up and its wizard, niche templates,
auto-translation, themes, custom domains, subscriptions and billing, its admin, and abuse protection.

## Release

Scala 3.3, the JVM and Scala.js, on okay-serverless 0.1.0, okay-ui 0.1.0, okay-scala 0.1.1. A Maven repository on
GitHub Pages (RELEASING.md); while only the two sites use it, a change is republished under 0.1.0.
