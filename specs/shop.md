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

THE ARCHITECTURE IS EFFECTS. The data model is effects (`Store`, `Mail`, `Captcha`, `Ids`, `Notify`; the workshop's
`Staff`, `Human`); every implementation a handler — onto okay-serverless' effects in the cloud (`StoreDurable`,
`MailCloud`, `Turnstile`), in memory in tests. A site's object runs `Api.answer` on one line of handlers.

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
  Since 2026-11-04 also a radius (`radius` km from one of the shop's places, `from` its key; "Places on the map").
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
  the inquiry. Up to 3 photos when the host keeps them ("Photos" below).
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

## The site's host in its privacy policy (operator, 2026-10-30)

A site run on someone else's platform (the mall: many shops on one Worker) has that platform as its PROCESSOR (art. 28
RODO): the policy must name it among the recipients and point to its own policy.

- `Privacy.page(site, l, email, host)`: `host` the host's items for the recipients' list, in the page's language —
  each an HTML fragment the host writes (its name, what it does, the link to its own policy) — said FIRST, before the
  engine's processors (Cloudflare, Turnstile, the clients' mail as configured, InPost for an atelier). None (the
  default, every site on its own Worker): the page exactly as before, byte for byte.
- Tests: `PagesSuite` (the host's item first in the list, in its language; without one the same bytes).

## Notices (operator, 2026-10-30)

Beside the owner's letter, the shop GIVES A NOTICE of what happened — data, not words — for a host to tell its people
at once where it likes (the mall: a Telegram chat). An effect, `Notify` (`Tell(notice)`), its handler the host's:

- **The notices** (`Notice`): `Ordered(order)`, `Booked(booking)`, `Moved(booking)`, `Cancelled(booking)` (by the
  client's link), `Inquired(inquiry)`, `Asked(quote)` (an inquiry for a quote), `Decided(quote, accepted, result)`
  (the client's accept — `result` the booking's or the order's ref — or decline). Each given right after the owner's
  letter, in the same program.
- **Handlers**: `Notify.ignore` (a site on its own Worker: its owner has the letter; `ShopWorker` uses it, so its
  sites behave exactly as before) and `Notify.kept(buffer)` (tests).
- Tests: `ApiSuite` (an order, an inquiry), `BookingSuite` (booked, moved, cancelled), `QuotesSuite` (asked, accepted,
  declined).

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

Not built here: no fiscal printer, no official ledger (invoices and KSeF since: "Invoices", "KSeF"). Sources read on
2026-10-29; uncertain points marked.

- **KSeF (e-invoices)**: obligatory from 1 Feb 2026 for firms with 2024 sales above 200 mln zł, from 1 Apr 2026
  for everyone else; receiving through KSeF since 1 Feb 2026. Until 31 Dec 2026 invoices may stay outside KSeF
  while their gross total in a month is at most 10 000 zł (from the invoice that passes it, everything in KSeF);
  from 1 Jan 2027 everyone. Invoices to consumers (B2C) in KSeF are voluntary. A VAT-exempt seller (zwolnienie
  podmiotowe, 240 000 zł from 2026) issues an invoice only when a buyer asks — then in KSeF; the Ministry said
  (26 Jan 2026) a rachunek outside KSeF may be given to a business instead (MEDIUM confidence: a ministry letter,
  not the statute). CORRECTED 2026-10-30: the letter itself says the opposite — always an invoice ("Invoices": the
  law the engine follows). No penalties in 2026; from 1 Jan 2027 (art. 106ni). Sources:
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

## Invoices (operator, 2026-10-30: step 1, no KSeF yet in the law's sense — KSeF below)

A business client may ask for a faktura. The engine keeps the buyer's data, the seller's data is the host's
(`Conf.seller`:
a site's own config; the mall's owner screen "Dane do faktur"), an invoice is issued from a PAID order or booking,
numbered per shop in its series, never reused; corrected by a faktura korygująca. Package `okay.shop.invoice`
(model, rules, FA(3), KSeF) and `okay.shop.app.Invoicing` (the programs).

- **The client's form** (`Site.invoices`, the host's switch; off — every site before this — the pages byte for byte as
  before: `OldPagesSuite`): the order, booking and inquiry forms get "Potrzebuję faktury (dla firmy)"; ticked, the
  company's name, NIP, street, postcode, city appear (CSS `:has`, no script). `POST /api/order`, `/api/book`,
  `/api/quote` take `invoice=on`, `buyer_name`, `buyer_nip`, `buyer_street`, `buyer_postcode`, `buyer_city`: checked
  before anything is made (`Buyer.check`: `buyer.name` 1–200, `buyer.nip` — ten digits, PL/spaces/hyphens allowed,
  its check digit: weights 6,5,7,2,3,4,5,6,7 on the first nine, the sum mod 11, 10 never issued —, `buyer.address`:
  a street, `NN-NNN`, a city), refused 400 by that name (the page's words in four languages); kept beside what was
  made (`site/buyer/<order|booking|quote>/<ref>`). A quote accepted carries its buyer to the order or booking it became.
- **Personal and company data**: the buyer's data is shown only to the staff and the owner (the workshop) and on the
  client's own links (the invoice's page by the order's or booking's key); never on a public page.
- **The workshop** (okay-ui): an order's and a booking's screen show "Фактура" — "клієнт не просив", the buyer's data,
  or the invoice's number and KSeF state with its link; "Дані для фактури" (the staff gives or changes the buyer:
  `GET/POST <desk>/api/buyer {of, ref, name, nip, street, postcode, city | clear}`) and, once paid with a buyer,
  "Виставити фактуру" (`POST <desk>/api/invoice/issue {of, ref}`). Nothing new on a shop without seller data and a
  buyer.
- **The seller** (`Seller`, JSON by `Seller.fromJson`, checked by field: `seller.name`, `seller.nip`,
  `seller.address`, `seller.status`, `seller.basis`, `seller.rate`, `seller.rates`, `seller.pattern`, `seller.bank`):
  legal name, NIP, address; VAT status — an active payer (`vat`), exempt by turnover (`zw113`: art. 113 ust. 1, the
  basis said as „art. 113 ust. 1 ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług” unless given) or by
  activity (`zw43`: its basis required, e.g. „art. 43 ust. 1 pkt 29 lit. …”); the default rate and one per service
  (23, 8, 5, 0, zw, np; an exempt seller's lines always zw); the numbering patterns — invoices `FV/{yyyy}/{mm}/{n}`,
  corrections `FK/{yyyy}/{mm}/{n}` by default (`{yyyy}`, `{yy}`, `{mm}`, one `{n}`, letters, digits, `/-_.`, at most
  40, the two different); a bank account (NRB or IBAN).
- **An invoice** (`Invoice`): its id (the shop's sequence, `000001`…), number, kind (`vat`, `kor`), what it is of
  (order or booking, ref), the issue day (today) and the service's day (a booking's start, a paid order's day), the
  seller and buyer AS THEY WERE (copied in), its lines (the service's name in Polish, or the order's need; 1 ×, the
  payment's amount as GROSS — the shop's prices are gross), the payment's method and day, who issued it and when;
  a correction's reason, the corrected invoice (id, number, day, KSeF number) and its lines before; its KSeF side;
  the corrections made of it.
- **Sums from gross** (art. 106e ust. 7–9): per rate, VAT = gross × rate ÷ (100 + rate), rounded to the grosz (half
  up, ust. 11), net = gross − VAT; the invoice shows gross unit prices and values (ust. 8) and the table by rate.
- **Numbering** (`Store.Issue`, ONE step): the next id, the series' next number — the series is the pattern with its
  dates filled (`FV/2026/10/`: a monthly series starts at 1 each month) — and the order's or booking's index written
  together; a second VAT invoice of the same order or booking refused `issued`. Numbers are never deleted or reused
  (there is no delete). Refusals: `seller` (no seller data), `buyer`, `unpaid` (409).
- **Correction** (owner only, `POST <desk>/api/invoice/correct {id, reason, lines:[{name, quantity, gross, rate}]}`):
  the lines as they should be, starting from the last correction's; refused `reason` (1–256), `lines`, `same`,
  `kind` (a correction is not corrected: correct the original); numbered in the corrections' series; kept on the
  original (`corrections`). Its page: "Faktura korygująca nr …", "Korekta faktury nr … z dnia …" (+ its KSeF number),
  the reason, the lines before and after, the difference by rate. KOR in FA(3).
- **Pages**: the client's `/{lang}/invoice?ref=&k=` (the order's or booking's key; `&id=` a correction of it) and a
  link from the receipt; the workshop's `<desk>/invoices/` (the list: number, kind, day, buyer, amount, KSeF state,
  error, UPO, XML; the owner's "Wyślij do KSeF" and "Korekta"), `<desk>/invoices/view?id=`, `xml?id=` (FA(3)),
  `upo?id=`; the workshop's link "Faktury · Рахунки-фактури · Invoices" (`more`) when the shop has a seller. The
  printable page (`InvoicePage`): "Faktura nr …", issue place and day, the service's day, seller and buyer with NIP,
  the lines, the table by rate (a VAT payer's), the total, „zapłacono w całości”, an exempt seller's line „Sprzedawca
  zwolniony z VAT — podstawa prawna zwolnienia: …” and no VAT columns, the KSeF number and its QR code once accepted,
  a TEST notice when sent to a test environment; print CSS (a PDF by the browser).
- **The accountant's CSV** (owner only, `GET <desk>/api/invoices.csv?from=&to=&comma=1`): UTF-8 with a BOM, one row
  an invoice — Lp, number, kind (VAT/KOR), issue day, sale day (empty when the same), buyer's NIP and name, KSeF number
  or `BFK` (issued outside KSeF, JPK_V7's marker), net and VAT by rate under JPK_V7(3)'s column names (K_19/K_20 23 %,
  K_17/K_18 8 %, K_15/K_16 5 %, K_13 0 %, K_10 exempt, K_11 outside the country), gross, KSeF state, the corrected
  number; a correction's amounts its difference. A helper for the accountant's JPK, not the JPK file.
- Tests: `InvoiceSuite` (NIP, buyer, seller, sums, the forms on and off, the buyer kept and carried, numbering and
  series, the pages and their keys, exempt, correction, CSV, KSeF in the alarm), `Fa3XsdSuite` (JVM), the workshop's
  `WorkshopSuite`.

### Polish invoice law — what the engine follows (research of 2026-10-30; TO VERIFY with an accountant)

Primary texts read: the VAT Act, consolidated Dz.U. 2026 poz. 1263 (law as of 2026-08-26, the KSeF amendments in)
https://api.sejm.gov.pl/eli/acts/DU/2026/1263/text.pdf; the invoicing regulation Dz.U. 2021 poz. 1979 as amended by
2024 poz. 1938 and 2025 poz. 1742 (from 1 Feb 2026) https://api.sejm.gov.pl/eli/acts/DU/2021/1979/text.pdf,
https://api.sejm.gov.pl/eli/acts/DU/2025/1742/text.pdf; the MF letter to SKwP of 26 Jan 2026 (PT7.8165.6.2026.46)
https://skwp.pl//content/uploads/2026/01/MF-do-SKwP-odpowiedz-na-pismo-SKwP.pdf; the JPK_V7(3) brochure
https://www.podatki.gov.pl/media/wgbkrejs/broszura-jpk_vat-z-deklaracj%C4%85-od-1-lutego-2026-r.pdf.

- **Art. 106e ust. 1** (HIGH): issue date; a sequential number in one or more series identifying the invoice; names
  and addresses of both; the seller's NIP and the buyer's; the date of the service when known and different; the
  service; quantity; unit price; net value; rate; net by rate; VAT by rate; the total due; markers when they apply
  (metoda kasowa, samofakturowanie, odwrotne obciążenie, mechanizm podzielonej płatności — the latter only above
  15 000 zł for annex 15 goods and building works: not our services); pkt 19 the legal basis of an exemption. The
  engine prints all of these that apply to a small service business; the markers never apply to its cases (it issues
  no reverse charge, no self-billing, no cash method — a payer on the cash method must not use it yet: OPEN).
- **Exempt sellers** (HIGH): art. 113 ust. 1, the limit 240 000 zł from 2026 (Dz.U. 2025 poz. 896); consulting and
  legal services may NOT use it (art. 113 ust. 13). An invoice only on the buyer's request (art. 106b ust. 3 pkt 2,
  within 3 months of the month's end); its content may omit rate, net and VAT by rate (art. 106e ust. 4 pkt 3,
  regulation § 3 pkt 3). The legal basis is not in § 3 pkt 3's list (MEDIUM), but FA(3) requires it when P_19=1 —
  the engine always prints it. The document is a "faktura" (zw); "faktura bez VAT" is only a colloquial label.
- **A rachunek instead of an invoice: NO.** The spec's earlier note (2026-10-29, after a portal) said the Ministry
  allowed a rachunek outside KSeF to a business. The letter itself says the opposite: when an exempt seller documents
  a sale, „zawsze tym dokumentem będzie faktura, ponieważ rachunek nie stanowi ekwiwalentu faktury”, through KSeF from
  its date; a rachunek is for activities outside VAT (HIGH as to the letter; it is not an interpretation). So the
  engine issues invoices, not rachunki, to businesses; its receipt stays the consumer's confirmation.
- **Deadlines** (HIGH): by the 15th of the next month (art. 106i ust. 1); on a later request within 15 days (ust. 6).
- **Correction** (art. 106j, HIGH): its number and date; the corrected invoice's KSeF number when it had one (ust. 2
  pkt 2a); the corrected invoice's data (art. 106e ust. 1 pkt 1–5) and the service; the difference by rate, or the
  corrected items. "FAKTURA KORYGUJĄCA" and the reason are optional (ust. 2a) — the engine prints both. A correction
  of a KSeF invoice must go to KSeF too (ust. 4). Correction notes are abolished.
- **Numbering** (MEDIUM): monthly series like FV/2026/10/1 are common practice and the law allows several series;
  "kolejny" read as +1 (a KIS interpretation of 7 Feb 2023, 0114-KDIP1-3.4012.603.2022.1.KP, reported by infor.pl);
  no rule on gaps — the engine never makes one (the number is taken in the same step as the invoice is written).
- **NIP's check digit** (MEDIUM-HIGH): the algorithm above is universal in practice; no official MF text found.
- **Storage**: art. 112/112a until the limitation (5 years from the year's end); KSeF keeps its invoices 10 years
  (art. 112aa) — the shop's object keeps every invoice, its XML is made again from it, the UPO kept.
- **Paragon z NIP** (≤ 450 zł) is a simplified invoice until 31 Dec 2026 only (art. 145n; after: MEDIUM). Not built.
- **B2C invoices** are outside KSeF (art. 106ga ust. 2 pkt 4); the engine invoices businesses (a NIP required).
- **JPK_V7(3)** (from Feb 2026, HIGH): a sale's row has one of `NrKSeF` | `OFF` | `BFK` | `DI`; the amounts K_10–K_20
  by rate. The CSV uses those names; active VAT payers only file it.

## KSeF (operator, 2026-10-30: step 2, the TEST environment only)

KSeF 2.0's API (v2, API version 2.9.0; https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json; the Ministry's
documentation https://github.com/CIRFMF/ksef-api, commit ce3670e of 2026-10-14; the clients
https://github.com/CIRFMF/ksef-client-java, -csharp). Environments (`KsefEnv`): TEST `https://api-test.ksef.mf.gov.pl`
(QR `https://qr-test.ksef.mf.gov.pl`), DEMO `api-demo`/`qr-demo`, PRODUCTION `api.ksef.mf.gov.pl`/`qr.ksef.mf.gov.pl`.

- **The effect** (`Ksef`): `Send(xml)` → the session's and the invoice's references, `Status(session, reference)` →
  KSeF's code (100 accepted, 150 processing, 200 done with its `ksefNumber`, 4xx a refusal with its description),
  `Upo(session, number)` → the UPO's XML. A refusal of the call is a `Left` with KSeF's words (its exception code and
  description), never a failure. Handlers: `KsefHttp` (over okay-serverless' `Fetch` and `Crypto`, and `Clock` to
  poll), `KsefFake` (memory; numbers in KSeF's form), `Ksef.off` (not connected).
- **`KsefHttp`**, each handler instance signing in once:
  1. `POST /v2/auth/challenge` → `challenge`, `timestampMs`; `GET /v2/security/public-key-certificates` → the
     Ministry's certificates (base64 DER) by `usage` (`KsefTokenEncryption`, `SymmetricKeyEncryption`; the latest
     valid one), their public key taken out (`Crypto.spki`).
  2. `POST /v2/auth/ksef-token {challenge, contextIdentifier:{type:"Nip", value}, encryptedToken}` — the token is
     `"<token>|<timestampMs>"` encrypted RSA-OAEP with SHA-256 and MGF1-SHA-256 (base64) → `referenceNumber`,
     `authenticationToken`; `GET /v2/auth/{ref}` polled (status 100 → 200); `POST /v2/auth/token/redeem` → the
     `accessToken` (Bearer for the rest).
  3. Send: a fresh AES-256 key and 16-byte IV; `POST /v2/sessions/online {formCode:{systemCode:"FA (3)",
     schemaVersion:"1-0E", value:"FA"}, encryption:{encryptedSymmetricKey (RSA-OAEP to the SymmetricKeyEncryption
     key), initializationVector}}` → 201 `referenceNumber`; `POST /v2/sessions/online/{ref}/invoices {invoiceHash,
     invoiceSize, encryptedInvoiceHash, encryptedInvoiceSize, encryptedInvoiceContent, offlineMode:false}` — SHA-256
     (base64) of the UTF-8 XML and of its AES-256-CBC/PKCS#7 ciphertext → 202 the invoice's `referenceNumber`;
     `POST …/close` (its UPO made).
  4. Status: `GET /v2/sessions/{session}/invoices/{reference}` → `status{code, description, details}`, `ksefNumber`.
  5. UPO: `GET /v2/sessions/{session}/invoices/ksef/{ksefNumber}/upo` (XML, schema
     `http://upo.schematy.mf.gov.pl/KSeF/v4-3`).
- **FA(3)** (`Fa3.xml`): namespace `http://crd.gov.pl/wzor/2025/06/25/13775/`, `KodFormularza kodSystemowy="FA (3)"
  wersjaSchemy="1-0E"`, `WariantFormularza` 3; Podmiot1/Podmiot2 by NIP and a Polish address (Podmiot2's `JST`=2,
  `GV`=2); `P_1`, `P_1M`, `P_2`, `P_6`; the sums by rate (P_13_1/P_14_1 23 %, P_13_2/P_14_2 8 %, P_13_3/P_14_3 5 %,
  P_13_6_1 0 % KR, P_13_7 zw, P_13_8 np I), `P_15`; `Adnotacje` (P_16–P_18A 2, `Zwolnienie` P_19N or P_19 + P_19A
  the basis, P_22N, P_23, P_PMarzyN); `RodzajFaktury` VAT or KOR (+ `PrzyczynaKorekty`, `DaneFaKorygowanej` with
  `NrKSeF`+`NrKSeFFaKorygowanej` or `NrKSeFN`); rows `P_7`, `P_8A`, `P_8B`, `P_9B`, `P_11A` (gross, art. 106e ust.
  7–8), `P_12` (23, 8, 5, `0 KR`, `zw`, `np I`), a correction's rows before with `StanPrzed`; `Platnosc` (Zaplacono,
  DataZaplaty, FormaPlatnosci 1 cash, 2 card, 6 transfer, 7 mobile/BLIK, else PlatnoscInna; the bank account's NRB).
  Its creation instant is the invoice's: the same bytes whenever made. The schema: `schemat_FA(3)_v1-0E.xsd`
  (= http://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd, SHA-256 b646b6b5…958) and its imports
  StrukturyDanych_v10-0E, ElementarneTypyDanych_v10-0E, KodyKrajow_v10-0E (ksef-api's `faktury/schemy/FA`) in the
  JVM tests' resources; `Fa3XsdSuite` validates a VAT payer's, an exempt seller's and two corrections (the JDK's
  Xerces reads maxOccurs ≥ 10000 as unbounded: it cannot expand them).
- **In the shop** (`Conf.ksef`, the host's): an invoice issued while connected is QUEUED (its KSeF state `queued`,
  `next` = now, `site/ksefq/<id>`); the object's ALARM (`Invoicing.ksef`, run by the host beside the reminders) takes
  every due one: sends it (→ `sent`, the XML's SHA-256 kept for the QR), asks its status 3 s later (→ `accepted` with
  its KSeF number, the UPO fetched and kept `site/upo/<id>`; `rejected` with KSeF's description; still processing:
  again), and a failure (no connection, KSeF down, 5xx, a sign-in refused) keeps the error, counts the attempt and
  waits 1, 2, 4 … minutes, at most 6 hours. Every change ONE step of the store, which sets the object's alarm at the
  earliest of the reminders and the queue (`StoreDurable`'s `nextReminder`). Requests never wait for KSeF. The owner
  sends an invoice issued before the connection, or rejected, again ("Wyślij do KSeF", `POST <desk>/api/invoice/ksef
  {id}`); a correction of an invoice sent to KSeF is queued too.
- **The QR code (KOD I)** on an accepted invoice's page: `<qr base>/invoice/<seller NIP>/<issue day DD-MM-YYYY>/<SHA-256
  of the XML sent, base64url without padding>`, its label the KSeF number; drawn in the browser
  (qrcode-generator 1.4.4 from cdnjs). KOD II (offline invoices) needs a KSeF offline certificate: not built.
- **Offline modes** (not built; the queue is not offline mode: an invoice waiting in it has no KSeF number yet):
  offline24 (art. 106nda, any time, sent by the next business day), offline when KSeF is announced unavailable
  (art. 106nh, next business day after), awaria (art. 106nf, 7 business days); offline invoices need KOD I and KOD II
  and `offlineMode:true` ([docs] tryby-offline.md). OPEN: in 2026 an invoice may stay outside KSeF while the month's
  such invoices are at most 10 000 zł gross (art. 145m) — the owner chooses by connecting or not.
- **Test environment without a real identity** (verified 2026-10-30): TE takes self-signed certificates and random
  NIPs („W środowisku testowym TE dopuszczalne jest uwierzytelnianie przy użyciu samopodpisanych certyfikatów … Do
  testów należy używać losowych identyfikatorów NIP”, ksef-api srodowiska.md). `scripts/ksef-test-token.sc`
  (scala-cli): a random checksum-valid NIP, a fictional person's self-signed RSA certificate (`serialNumber
  TINPL-<NIP>`, openssl), an AuthTokenRequest (`http://ksef.mf.gov.pl/auth/token/2.0`) signed XAdES-BES (enveloped,
  rsa-sha256, exc-c14n, SignedProperties with SigningTime and SigningCertificate) by the JDK, `POST
  /v2/auth/xades-signature?verifyCertificateChain=false`, redeemed, `POST /v2/tokens {permissions:["InvoiceWrite",
  "InvoiceRead"]}` until `Active`; the token written to a file (mode 600), never printed. `KsefTestEnvSuite` (JVM,
  only with `KSEF_TEST_FILE`) sends a real invoice through `KsefHttp` and checks its KSeF number and UPO — done
  2026-10-30: TEST NIP 9010486374, KSeF number 9010486374-20261030-3D0B0D000000-0A, the UPO from „Ministerstwo
  Finansów - środowisko testowe (TE)”.
- **Production is never asked** by anything here: the host decides the environment, and the mall keeps production
  disabled until its operator enables it (specs/mall.md).

## Reviews (operator, 2026-10-30)

A shop's clients rate what they got; only real clients, once each; the shop answers in public but cannot bury the bad
ones. The host's switch: `Site.reviews` (off — every site before this, both single sites — nothing is asked, no
page changes); what the storefront shows is `Site.rated` (the host keeps it from the store: `Reviews.rated`).

- **Who is asked, when**: a BOOKING's client two hours after the visit ends (`Reviews.after`) — made or moved, the
  booking puts its ref in a queue (`site/reviewq/<ref>`, the time; `Store.ReviewLater`), the object's alarm
  (`Bookings.remind` → `Reviewing.due`, the store's `nextReminder` counts the queue) takes the due ones and asks
  those still `booked` (not cancelled, not a no-show) and past; an ORDER's client at once when the workshop moves it
  to `delivered` or `paid`, or marks it paid (`Reviewing.done`). Only a client with an email. ASKED ONCE per order or
  booking (`site/reviewask/<of>/<ref>`, `Store.Invite` false the second time).
- **The link**: a secret (`Ids.secret`, 120 bits) mailed in a letter in the client's language ("Jak było?",
  `Letters.reviewAsk`, the shop's contact as reply-to) as `/{lang}/review?t=<secret>`; only its SHA-256 is kept
  (`site/reviewlink/<hash>`: of, ref, until — 60 days). SINGLE USE: the review written and the link deleted in ONE
  store step (`Store.Write`); a second review of the same order or booking refused (`reviewed`); a forged, used or
  past link answers "used or expired" (404), nothing written.
- **The form** (`ReviewPage.form`, a plain HTML form, no script; `POST /{lang}/review` with `t`, `rating`, `text`,
  `name`): what it is of and when; the stars 1–5 (`rating`), a text up to 1000 characters (`text`; may be empty), a
  name to sign with — shown as its first word and the last word's initial, "Anna K." (`Reviews.shownName`; none:
  "Klient"); "do not put phone numbers or addresses in it". Refused by field (400, the form again with the words
  kept). Thanks page; the notice `Notify.Reviewed(review)` (the host tells its people).
- **A review** (`Review`, `site/review/<of>/<ref>`): of, ref, its service and master, the stars, the text, the name
  shown, the language, when; the owner's reply and when; `hidden` (a reason, empty: shown); its log (when, who,
  `reply`, `hide:<reason>`, `show`). Never deleted: there is no call that deletes one.
- **The workshop** (`<desk>/reviews/`, "Opinie · Відгуки · Reviews" in `more` when the site takes reviews; signed
  out: to the sign-in): every review, newest first, hidden ones marked with their reason; the average and the counts
  (shown, hidden apart). `GET <desk>/api/reviews`, `POST <desk>/api/review {id, action, value}`: the OWNER only (403
  `owner`) — `reply` (1–1000 characters; again: edited, one reply), `hide` with a reason of the list ONLY — `spam`,
  `personal` (personal data in it), `offensive` — else 400 `reason`: never because it is negative; `show` (hidden
  by mistake). Every change logged on the review.
- **The storefront** (`ReviewPage.block`, after the look's sections; NOTHING while nothing is shown — the page then
  byte for byte as before): "Opinie", the stars, the average to one decimal, the count ("12 opinii", the language's
  plural), the line "Opinie zweryfikowane: wystawiają je tylko klienci po wizycie lub zamówieniu — przez link z
  wiadomości.", the latest six shown, each with its stars, name, day, service, text and the owner's reply. Escaped.
  **Structured data** (`ReviewPage.structured`, in the head): a schema.org `LocalBusiness` (`@id` `<origin>/#business`,
  name, url) with its `AggregateRating` (ratingValue, reviewCount, best 5, worst 1) ONLY from 3 shown reviews on.
  Google shows no stars for a business's own reviews of itself on its own site (its "self-serving reviews" rule,
  2019) — the data is still correct, and other readers take it.
- Tests: `ReviewsSuite` (asked two hours after a visit, once, in the client's language; none for a cancelled visit
  or with the switch off; only by the link, a wrong rating or text refused, single use; an order asked on delivery,
  not again on payment; the owner's reply and hide by reason, staff refused, never deleted, the log; the name and the
  averages; the block and the JSON-LD threshold; the pages unchanged without reviews); `OldPagesSuite` unchanged.

### Reviews and the law — TO VERIFY (research of 2026-10-30)

- **The Omnibus directive** (2019/2161, art. 3: UCPD art. 7(6) and annex I points 23b, 23c), in Poland the act on
  unfair market practices (ustawa z 23 sierpnia 2007 o przeciwdziałaniu nieuczciwym praktykom rynkowym, as amended
  from 1 Jan 2023): a trader who gives access to consumers' reviews must say WHETHER AND HOW it ensures they come
  from consumers who used or bought the product (material information, art. 6 ust. 4 pkt 7 as commentators cite it —
  MEDIUM confidence on the numbering); stating that reviews are by real users without reasonable and proportionate
  steps to check it, and posting or commissioning false reviews or distorting them (e.g. showing only the positive),
  are unfair in all circumstances (art. 7 pkt 25–26 as cited — MEDIUM). So the storefront says how it verifies
  ("tylko klienci po wizycie lub zamówieniu — przez link z wiadomości") and the hide rule allows no "negative"
  reason. UOKiK fined traders for fake or filtered reviews (e.g. decision RKT-3/2022, Opinie.pro). Sources:
  https://eur-lex.europa.eu/eli/dir/2019/2161/oj, https://lexlege.pl/ustawa-o-przeciwdzialaniu-nieuczciwym-praktykom-rynkowym/art-7/,
  https://bezprawnik.pl/moderowanie-opinii-konsumentow/,
  https://czasopismo.legeartis.org/2022/12/zamieszczanie-falszywych-opinii-konsumentow-obowiazek-weryfikacji-recenzji-klientow/
- **RODO**: a review is personal data of its author (the name shown, the text); its basis the author's own act of
  publishing; shown only as "Anna K."; the form asks for no personal data in the text, and `personal` is a reason to
  hide. The email that asked is the order's or the booking's (its existing basis). The privacy policy's wording: OPEN
  for the host (the mall's legal drafts).

## Clients' letters: the ways (operator, 2026-10-30)

The owner's and the staff's letters go by Email Routing's `send_email` (`MAIL`: verified destinations, any plan). A
client's letter goes the way `MAIL_CLIENT` names (`MailCloud`, `Mailing.client`); each answers whether it went, and
why not is logged — the status and the provider's error name, never an address nor the letter's words:

- `cloudflare` — Cloudflare Email Service's Email Sending through the `CLIENT_MAIL` binding (`[[send_email]]`, the
  same `EmailMessage` as `MAIL`): any recipient once the account has Workers Paid AND the sending domain is
  onboarded (dashboard → Compute → Email Service → Email Sending → Onboard Domain: Cloudflare adds the `cf-bounce`
  MX, SPF and DKIM records and `_dmarc`); before that only verified destinations. Beta (2026-10-30); 3 000 letters a
  month included, then $0.35 per 1 000; a new account's daily quota starts low and grows. The `from` must be on an
  onboarded domain. Sources: https://developers.cloudflare.com/email-service/get-started/send-emails/,
  …/platform/pricing/, …/platform/limits/, …/api/send-emails/workers-api/ (read 2026-10-30).
- `resend` — Resend's API over `Fetch` (`Resend`): `POST https://api.resend.com/emails`, `Authorization: Bearer
  <RESEND_API_KEY>` (a secret), `from` as `Name <address>` (the name quoted when it has a special character),
  `to`, `subject`, `html`, `text`, `reply_to` (the note's); an `Idempotency-Key` per letter — `okay-` and the SHA-256
  of its address, subject, text and HTML: the same letter given twice within Resend's 24 hours is sent once. A 2xx
  sent; 401/403 (key, or domain not verified), 422 (invalid), 429 (limit), 5xx (Resend failing) not, logged so.
  Without the key: not sent, "no RESEND_API_KEY". Source: https://resend.com/docs/api-reference/emails/send-email.
- `brevo` — Brevo's API (`BREVO_API_KEY`); `smtp` (the default) — a relay by `Smtp.deliver` over `Tcp`
  (`SMTP_HOST`, `SMTP_USER`, `SMTP_PASS`).
- A site's Worker (`ShopWorker`) reads `MAIL_CLIENT`, `RESEND_API_KEY` as the rest: a site that sets neither behaves
  as before. Tests: `MailCloudSuite` (a fake HTTP: the request, the key per letter, the refusals and the log).

## Photos: the logo, the gallery, a quote's photos (operator, 2026-10-30)

A shop shows its logo and its work; a client shows the job to be quoted. The engine gives the model, the checks, the
markup and the quote's attachments as data; where the files live and who uploads the logo and the gallery is the
host's (the mall: R2 and its workshop screens).

- **An image accepted** (`Images.clean(bytes, max)`, pure, the same on the JVM and in a Worker): JPEG, PNG or WebP
  ONLY, known by its first bytes (magic), never by its name or the type it claims; at most `max` bytes (5 MB);
  its width and height read from its own header (JPEG's SOF, PNG's IHDR, WebP's VP8/VP8L/VP8X); anything else —
  GIF, SVG, HTML, a PDF, a truncated or malformed file — refused (`type`, `broken`, `size`). Its METADATA STRIPPED
  without decoding the picture (no codec runs in a Worker, so no re-encoding): JPEG — every APPn segment but APP0
  (JFIF), APP2 `ICC_PROFILE` and APP14 (Adobe colour), and every comment, dropped: EXIF (camera, GPS, time), XMP,
  IPTC/Photoshop; the EXIF orientation kept as a minimal EXIF of that one tag (so a phone's portrait photo stays
  upright), the size given as shown; PNG — only IHDR, PLTE, IDAT, IEND, tRNS, cHRM, gAMA, iCCP, sBIT, sRGB, bKGD,
  pHYs, cICP and APNG's acTL, fcTL, fdAT kept (text, eXIf, tIME and anything unknown dropped); WebP — EXIF, XMP and
  unknown chunks dropped, VP8X's EXIF and XMP flags cleared, the RIFF size fixed. Its name is the SHA-256 of the
  cleaned bytes (20 hex) and its kind's extension: `<hash>.jpg|png|webp`; its content type from the kind, never the
  client's.
- **The model**: `Photo(src, width, height, alt: T)` (a picture shown; `src` the host's path); `Site.logo:
  Option[Photo]`, `Site.gallery: Vector[Photo]` (none, none: every page byte for byte as before); `Site.photos`
  (the host keeps a quote's photos; off: the quote form as before, photos sent refused).
- **The storefront**: the logo in the header's mark, before the logo text (storefront and offer pages; `alt` the
  site's name, its width and height given, at most 40 px high); "Nasze prace · Our work · Наши работы · Наші
  роботи" (`Gallery.block`) after the look's sections and before the reviews when the gallery has photos: a grid,
  each picture `loading="lazy" decoding="async"` with its width, height and alt in the page's language (the site's
  own language's when that one is empty), a link to the full picture. Its style added to the page only then.
- **A quote's photos** (specs "Inquiry and quote"): with `Site.photos` the form has a file field (up to 3, JPEG, PNG
  or WebP, 5 MB each; the page's script checks them first and sends each as base64 in the JSON's `photos`, an array
  of `{data}`); `POST /api/quote` refuses more than 3 (`photos`), a wrong kind (`photo.type`), a broken file
  (`photo.type`), one too large (`photo.size`) — before Turnstile and the limit are spent nothing is kept. Each
  cleaned picture KEPT PRIVATE by the `Media` effect (`Media.keep(name, bytes, type)`; the name
  `quote/<ref>/<hash>.<ext>`), the quote keeping `photos` (`Attachment`: file, width, height, size). Shown ONLY by a
  link the host makes for that viewer (`Media.link(name)`: the mall's is signed and expires) — on the client's quote
  page (its key checked as before) and in the workshop's list (`GET <desk>/api/quotes`: `links` beside each
  quote, the staff signed in), the workshop's quote screen a link a photo. Never on a public page.
- **Handlers**: `Media.none` (a site on its own Worker: `Site.photos` off, nothing kept, no link) and `Media.kept`
  (in memory, tests: the link `/media/<name>`); the host's onto its blob store (the mall: okay-serverless' `Blobs`).
- Tests: `ImagesSuite` (each kind's metadata gone and the picture's bytes kept, the orientation kept, the sizes, GIF,
  SVG, HTML and broken files refused), `GallerySuite` (the logo and the gallery drawn, escaped, lazy; none: the same
  bytes), `QuotesSuite` (photos kept and linked for the client and the staff only; too many, wrong kind, too large
  refused; a site without photos refuses them).

## Structured data (operator, 2026-11-03)

What a search engine reads of the business is ONE schema.org node, `@id` `<origin>/#business`: the rating of
"Reviews" is given to it, and so is the look's description. `Structured.business(site, kind, fields)` writes it:
its type the look's (`ClothingStore`, `HairSalon`; default `LocalBusiness`), name, url, the look's fields
(`description`, `priceRange`, `areaServed`, ...) and `hasOfferCatalog` — every SHOWN offer as an `Offer` of a
`Service` (its name and description in the page's language), its page `/<lang>/offer/<key>/`, `price` ("60.00")
and `priceCurrency` `PLN` when it has a price and is not quoted. No offers: no catalog. Escaped as JSON. The look
puts it in its `Seo.head`; a look that does not has its pages byte for byte as before (`OldPagesSuite`). Without
the shared `@id` the look's node and the rating's would read as two businesses. Tests: `StructuredSuite`.

## The assistant (operator, 2026-11-03: okay-facts, a single shop's facts — the multi-tenant chat is the host's)

A chat on the storefront answers a client's questions about the shop — services, prices, durations, free times, the
masters, the address, the rating — and books, on okay-facts (`io.github.okay-effects:okay-facts`): the model never
receives, writes or computes a fact. It sees handles (`#v1`), answers with a template and named parameters bound to
expressions over them; okay-facts renders every value from the shop's data. Package `okay.shop.assistant`.

- **The shop's facts** (`ShopFacts`): the schema (schema.org's words, with the overlay's keys and cardinalities) —
  `Shop` (LocalBusiness: name, category, city visible; address, url, hours, rating, reviews hidden), `Service`
  (Service: name, description visible; price, duration, bookable hidden; its shop, its masters — a set), `Master`
  (Person: name hidden; shop, services — a set), `Slot` (a free start: time, date, available hidden; master,
  service, shop; its service's name visible), `Review` (rating, text hidden; only shown reviews), `Booking` (made in
  the chat: its values pinned). Rows from the site: the SHOWN offers (a hidden one is not there), the masters, the
  rated reviews; the shop's category and city are the host's (`About`), else none. Slots are COMPUTED by the
  resolver, never listed whole to the model: a find of `Slot` takes the service it is of (else every bookable one),
  its days (a date filter, else the next 7), each eligible master's free starts by `Bookings.plan`, `masters`,
  `Slots.free` / `Slots.classes` (the default mode and place), filtered on time, master, date; a slot's key
  `service|master|start`; `available` asked again when rendered. Values are current when rendered; the shop's
  version is the host's (its config's).
- **Find, open, book** (the handler of okay-facts' `Facts`): `find` over the rows and the computed slots; `open` a
  shop → a card linking its storefront in the user's language; `book` a slot → a card (the service, the day and time,
  the price, the master — rendered by the resolver) the client confirms with their name, contact and consent. The
  model never types the client's data.
- **A booking from the chat** (`Bookings.chat`): the booking form's checks and path — name, contact, consent, the
  service shown and bookable, its plan and master, the store deciding in its step (`409 taken`) — Turnstile replaced
  by limits: 5 per IP in 10 minutes (the form's) and 3 per conversation a day (the host's key); the letters and the
  notice as the form's. Its confirmation is rendered by the resolver from the booking (pinned: service, time, price,
  master, ref, its manage link) — `ShopFacts.confirmation`.
- **The widget** (the pages' script, `site.js`): only where the page carries the chat's marker — `Look.chat`
  (`Chat(endpoint, focus)`; none by default: every page byte for byte as before, `OldPagesSuite`) puts `<div
  id="okay-chat" …>` before the scripts of the storefront and the offers' pages. A button opens a panel (a dialog; full
  screen on a phone): the conversation, the input, the answers' values as chips (the value; its entity and source on
  hover and focus), a slot's chip clickable (its booking card), a shop's card a link, "delete this conversation", "what
  the model wrote" (the raw `{params, template}`, hidden by default; the lane that answered — `router` or `model` —
  before it when the host says it). pl, uk, ru, en by the page's language; announced (`aria-live`), keyboard and Escape.
  It talks to the host: `POST <endpoint>` `{text, focus, lang}` → `{answer, cards, raw, …}`, `POST <endpoint>/book`,
  `POST <endpoint>/forget`, `GET <endpoint>/history`. When the page is idle and visible (at most once a minute in a
  session) it warms the conversation's object (round 3): `POST <endpoint>/warm` — the host answers an id the page keeps
  (no cookie: none until the visitor chats), given back with the first open (`history?warm=`) or message (`warm`).
- **The router lane** (operator, 2026-11-03; okay-facts' design §17): before the model, `ShopRouter` answers what it
  understands with NO model — the intent by okay-facts-router (rules in its portable dialect, the static table, the
  probe fitted at build time), the slots by rules and the table, the answer AUTHORED. The data (`assistant/`):
  `intents.json` (16 intents — price, slots, book, manage, hours, address, area, modes, masters, reviews, compare,
  search, human, help, greet, and `other`: the model's — their rules and their phrasings in pl, uk, ru, en),
  `answers.json` (an ICU message per intent, variant and language; a list's item repeated with its parameters numbered),
  `vocabulary.txt` (the domain's words for the table), `lexicon.json` (words of one thing in services' names and
  clients' words — "szafa", "meble", "wardrobe"; "syn", "dziecięce", "kids" — and a name's words that say nothing of
  which it is: general words of small services, never one shop's), `static.safetensors` (the table: okay-facts'
  `tools/table.scala` over the examples, the vocabulary and MASSIVE's pl/ru/en words — 15 500 units × 256, int8),
  `probe.bin` (`tools/fit.scala`, okay-facts' binary `Weights`), `combiner.bin` (round 3: the combiner over the
  evidence, fitted and calibrated by okay-mall's `Fit` on its train/dev turns — okay-facts §17). The build turns the
  JSON into `AssistantData` (and the table's hash: a host reads `router/static-<hash>.safetensors`, never another table
  with this probe); the JVM's jar carries the table (`ShopRouter.resource`). Slots: the service — nearest among the
  shop's (its names in every language; words first, the table's meaning beside them; several close: asked, with the
  candidates as chips; none named: the conversation's on a follow-up, else the price list) —, the day and time (`When`),
  the price limit (`Amount`), the master by name in any form or script (`Spell`; a name not the shop's: the masters
  listed), the city (the mall's shops'), the place of the area. Answers bind handles found by `find` in code; a booking
  only gives the existing card; a comparison is an expression whose boolean chooses the words; a master's name asks its
  case (`as gen`) in its own select, the nominative otherwise. THE DECISION (round 3, okay-facts §17 "The turn
  decision": rules are evidence, not verdicts): the guard (`intents.json`'s `guard`, categories in order —
  `injection`: injections and questions about the assistant; `others`: others' data — clients, who booked, a master's
  private phone, earnings, someone else's things; `bargain`: a price set by the user, discounts and bargaining;
  `health`; `payments` (and invoices); `amenities` (parking, Wi-Fi, a child or a pet); `advice`; `domain`: out of
  domain) → **answered by the category's fixed authored answer, NEVER the model** (operator, 2026-11-04;
  `ShopRouter.guarded`, the route `guard.<category>`): pieces of `answers.json` (`guard.<category>`, pl/uk/ru/en, the
  turn's language), validated and rendered like any authored answer, the values only the resolver's — never the
  user's words. On a shop's pages: `injection` and `domain` the shop's name, a few of its services (chips) and its
  contact; `others` a refusal and the client's own booking's link when the conversation made one, else the
  confirmation e-mail's link; `bargain` the price list's value of the service named or in context (else its services
  with prices) and the shop's contact for promotions; `payments` the ways its data says (`payments`: BLIK for a
  clothing shop with a BLIK phone), else that the shop has not said, and its contact; `amenities` that it has not
  said, its address when it has one; `health` no medical advice — the person doing the service or a doctor;
  `advice` the person doing the service, the service named (its price) or a few services, the contact. The contact
  is the shop's e-mail, else its page. On the mall's home (`guard.<category>.mall`) the same without a shop's values.
  `guard.cooldown`: the host's fixed answer while a conversation cools down (`ShopRouter.fixed`). The guard is its
  own first step (operator, 2026-11-05; okay-facts §17): `ShopRouter.guardTurn` needs no table and no router (a
  service its answer names found by its words alone, okay-facts' `Table.empty`), so a host runs it before its router
  lane and its model lane in every configuration — the router off, its table missing — with the same answers;
  `turn` runs it first too. Its data is bundled with the code; `ShopRouter.guardReady` says whether it is whole
  (every category's patterns, its answers in every language) — when not, a host fails closed. The patterns are
  narrow: a false refusal has no model behind it — their false positives counted on okay-mall's every set and the
  intents' phrasings; the safe rules
  (`safe`: whole-message greetings, thanks, "help") answered at once; everything else by the combiner (`combiner.bin`)
  over the probe's probabilities, every intent's rule hits, a short follow-up's previous intent ("a jutro?", "a u Ewy?",
  "a pedicure?") and the host's evidence (`ShopRouter.evidenceNames`: a service of the shop named by all its words and
  the best share of any, a master named, a person not the shop's, a place named, a day, a time, an exact time, a
  booking's word — "zamów odbiór" too —, a price limit, "alone" — "samo", "only", "тільки" —, the mall's home, a shop
  named there by its name, short, opened by "a", context), answered when its calibrated probability passes the file's
  threshold. The evidence is read in one call to the shop's facts (`Facts.Prefetch`: the services in every language with
  their prices, durations, whether bookable; the masters and their name forms; the places; the shop's own properties). A
  person named after "u", "do", "z", "у", "до", "with" who is not the shop's: its people listed; no word of any
  service's name said and the meaning not close: none named, never "which one?". What a shop has not given is said so,
  with where to ask (its contact, else its page): its hours, its address (with its ways of working when it has them),
  its ways of working, the places it comes to — never another fact's absence (a ways-of-working question once said the
  address' absence). "Do you come to the client?" is the ways of working (`modes`, answered from the shop's work data);
  with a place named, the area. On the mall's home a shop named by its name answers its own facts (its hours, its
  address), and a price of a service only one shop has, its price (else a search: each shop's). "Samo strzyżenie", "the
  haircut alone": the plain one of the names the word fits, no combination. Not confident, `other`, nothing it can
  resolve: declined, the host asks the model. A master's name forms (`forms`: `{"pl": {"gen": "Marka", "ins":
  "Markiem"}, …}`, optional) come first. The shop's facts add `area` and `Place` (the places it comes to), `modes` (how
  it works) and `contact`; its hours are its place's, else its visits', else its schedule's. Tests: `ShopRouterSuite`
  (every authored answer checked in four languages; the intents on the crew's data, the model never asked; every
  intent's answer when the shop has not given its data; follow-ups, the words' evidence, the lexicon, Latin letters; the
  rule-breaking and others' data declined; round 3: the guard in four languages, the safe rules alone, a greeting's word
  inside a question decided by the combiner, cancel's stems).
- **Round 4** (operator, 2026-11-04; okay-facts §17 "Round 4": the error classes fixed by data and structure):
  - **Places** (`assistant/places.tsv` → `places.json` by okay-facts' `tools/gazetteer.scala`; `ShopRouter.places`):
    every Polish city of over 20 000 people (GUS, 1 January 2021) and the districts of the 10 largest cities (their
    statutes' names; TERYT's spelling), with the demo shops' own districts; each place's forms generated — Polish
    cases, Ukrainian and Russian transliteration and cases, the large cities' exonyms — and matched folded (any script,
    case, Latin letters for Cyrillic). A place named is the router's place evidence, decides the area over the ways of
    working, is matched against the places the shop serves by id ("в Сопоті" is the area's "Sopot"; a known place not
    served: `area.no`), and on the mall's home a city with no shop is "none found", never another city's shops.
  - **A shop named on the mall's home**: asked of its hours, address, contact, ways of working or area, those of
    that shop — a search or a price the combiner chose yields to the shop's own fact its rules name; the mall's own
    contact (`human.mall`) from the host's entity `_mall` (a `Shop` of source `_mall`: its name and contact).
  - **Comparable services**: a service's `kind` (`About.kinds`, the host's: okay-mall's catalogue `niche/template`),
    its `price` comparable by it (okay-facts `Prop.comparableBy`, the resolver's `comparable` check); "cheaper
    elsewhere" finds only other shops' services of the same kind — none of it: `compare.none.kind`, never an unrelated cheaper
    service; a comparison across the mall keeps the nearest service's kind (or its own shop's).
  - **The decision settled by evidence** (`settle`): a place named → the area, not the ways of working; a booking's
    word → a booking, else free times; a contact's words (phone, e-mail, call, write) → the contact, not the address;
    "only the X" with no comparative word → the price, not a comparison; a short follow-up naming only a place after
    the area → the area. **Asked back** (`clarify`): the combiner's top two of a confusable pair (hours/free times,
    hours/masters, masters/price, price/compare, free times/compare, address/contact, hours/price) under 0.8 and within
    0.25 → "Chodzi o … czy o …?" (authored `clarify`, `choice.<intent>`), the two as choices (`Decision.Answered`'s
    `choices`: a label, an intent); the widget shows them as buttons, a click asks the same words with `intent` — the
    router answers that intent (`turn(…, asked)`; only a domain's intent, never `other`).
- **Told it is an AI** (operator, 2026-11-05; EU AI Act art. 50(1) — research below): the widget says so before the
  first message and on every view, in the page's language (`ChatWords.disclosure(lang)`, the shop module, so tests
  read the words): the launcher and the panel's title "Asystent AI" / "AI-асистент" / "ИИ-ассистент" / "AI
  assistant" (the dialog's `aria-label` the same); under the title, above the conversation, a notice that stays when
  the conversation is deleted (`role="note"`, the dialog's `aria-describedby`): it is an AI assistant, the prices,
  times and the shop's data come straight from the shop's system, the answers' wording is written by an automaton and
  may be wrong, a booking is confirmed by the client — and a link to the privacy policy's assistant section: the
  marker's `data-privacy` when the host gives one (the mall's landing: its own policy), else `/<lang>/privacy/#chat`
  (the shop's policy, its assistant section carrying `id="chat"` — only when the shop has the chat). The answers'
  content is unchanged. No marker: no widget, every page byte for byte as before (`OldPagesSuite`).
- **AI Act art. 50 — TO VERIFY with a lawyer** (research of 2026-11-05): Regulation (EU) 2024/1689, art. 50(1):
  providers ensure AI systems meant to interact directly with natural persons are designed so that those persons are
  informed they interact with an AI system, unless obvious to a reasonably well-informed, observant and circumspect
  person in the circumstances; art. 50(5): clearly and distinguishably, at the latest at the first interaction, meeting
  accessibility requirements. It applies from 2 August 2026 (art. 113); the Digital Omnibus on AI (2026) moved the
  high-risk dates and gave existing generative systems until 2 December 2026 for art. 50(2)'s marking only — art.
  50(1) was not deferred (secondary sources; the Official Journal's text to be checked). The chat interacts directly,
  so the label is kept on rather than relied on as "obvious". Polish angle (to verify): no consumer-law rule of its
  own for chatbots found; the general ones apply — misleading actions or omissions (ustawa o przeciwdziałaniu
  nieuczciwym praktykom rynkowym, art. 5–6) if a client could take the automaton for a person or its words for the
  shop's binding offer, and the information duties of the ustawa o prawach konsumenta for a booking (the price and
  terms shown by the shop's own data, the booking confirmed by the client). Poland's act on AI systems (the market
  surveillance authority for the AI Act) — its status to be checked. Sources: artificialintelligenceact.eu/article/50,
  eur-lex.europa.eu/eli/reg/2024/1689/oj.
- **Privacy**: `Privacy.page(…, chat = days)` adds the assistant's section (purpose, what is kept, for how long,
  deletion in the chat, the processor) when the shop has the chat; none: the page as before.
- Tests: `AssistantSuite` — the dialogue of okay-facts' design §12 end to end on `Memory` (the fake model replaying
  fixed outputs: find, find slots, the answer rendered from the data, the booking card, confirmed, the confirmation
  pinned, the letters); the facts' visibility; limits; the widget's marker only where enabled.

## Places on the map (operator, 2026-11-04; the multi-tenant search and its caches are the host's)

- **A place's point** (`Location.geo: Option[Geo]`, `Geo(lat, lon, source, precision, of)`): WGS 84 degrees; `source`
  `gugik`, `nominatim` or `owner`; `precision` `address`, `street`, `postcode`, `city` or `point`; `of` the address line
  it was found for — a point found for another address is stale (`Geo.current`). In the work's JSON a place's `geo`
  object; none: as before. `Geo.distance` (metres, the great circle's) and `Geo.rounded` (a client's point to 3
  decimals) are plain functions.
- **Geocoding as an effect** (`Geocoder.find(GeoQuery(street, postcode, city))` → `Option[Geo]`), its handlers
  (`Geocoders`): `gugik` (GUGiK's UUG, the state's free address-point service, over `Fetch`), `nominatim(agent)`
  (OpenStreetMap's, its usage policy: the host's identifying `User-Agent`, one request a second — the host paces and
  caches), `chain(a, b)` (the second when the first finds nothing), `fake(map)` (tests), `none` (a site with no
  geocoding: the radius check falls back to the lists). The engine never caches or paces: its host does.
- **The radius**: `Area(radius, from)` beside the places and the prefixes; `Bookings.addressed` (and the quote's) asks
  `Geocoder` for the client's address when the shop has a radius and the lists do not already say inside: within
  `radius` km of the place `from` (or the first with a point) — inside; nothing found — the lists decide, and the answer
  says the distance could not be checked (`Submit.unchecked`). `/api/area` answers the same.
- **The address block** (`Pages.placeText`): a place with a point gets a map box (`data-map` with its point and its
  name) the pages' script fills with Leaflet only when it scrolls into view or is clicked (1.9.4 from cdnjs with its SRI
  hashes; OpenStreetMap's tiles with their attribution, the tiles' referrer policy `strict-origin-when-cross-origin`;
  the tiles' URL `Maps.tiles`, a box's `data-tiles` may say another). A place with no point: the block byte for byte as
  before (the atelier, the two sites).
- **The assistant**: `Shop.point` (the first place's, hidden) in `ShopFacts`; okay-facts' `distance(#a, #b)`. The
  router's `search` takes okay-facts-router's `Near` slot ("near me", "w okolicy Mokotowa"): the host gives the asked-of
  place's point (`ShopRouter.turn`'s `here`; the visitor's city its `suggest`), issued as a handle `#here1` (class
  `Here`, its `point` pinned, never visible); results sorted by distance, each with `distance(#s, #here1)` rendered by
  the resolver; "near me" with no point: the visitor's city offered (`search.near.ask`), never guessed.
- Tests: `GeoSuite` — a point's JSON, staleness, rounding, the radius inside/outside/unchecked with a fake geocoder,
  GUGiK's and Nominatim's answers read (fixed JSON), the map box only with a point.

## Retention (operator, 2026-11-05: the policies' periods made true by code)

What the privacy policy promises is what the store does — automatically, idempotently, its runs logged as counts only.

- **Rate limits** (`Store.Allow`): the key (`order:<ip>`, `link:<hash>`…) is never kept as it is — its HMAC-SHA256
  under the Worker's secret `RATE_KEY` (`StoreDurable.run(p, rateKey)`; without one its plain SHA-256), 32 hex
  digits: `site/hit/<hash>/<window ms>/<window number>`. A request deletes its key's earlier windows; the ended ones of
  every key go at the next retention run, and every key of the old raw form (`site/hit/<key>/<n>`, before
  2026-11-05) too.
- **`Store.Expire(now, force)`**, one step, at most once in 20 hours unless forced (`site/expired` the last run):
  the rate limits' ended windows; the sign-in links, sessions and review links past their `until`; and FIVE YEARS
  (`Expired.keep`, 1827 days — never less than five calendar years) after a client's last activity in a matter: an
  order with its journal (its last event, else its placing), a booking (its end, else its placing), a quote (its
  placing or the time it proposes), an inquiry with its journal, a review with its ask (its writing or the reply).
  Accounting records are NEVER deleted by it: payments (`site/pay/`), receipts' numbers, buyers' data
  (`site/buyer/`), invoices, their UPOs — kept as long as tax law requires (the owner's and the accountant's call). It
  answers `Expired` (counts; `photos`: the deleted quotes' private file names, for the host to delete).
- **Who runs it**: a site on its own — its object after each dynamic request's answer (`Sockets.later`; the 20 hours
  make it daily at most); a host (the mall) — its own daily schedule, forced. The log line says counts only
  (`ShopWorker.expired`: `RETENTION: hits 3, sign-ins 0, orders 0…`).
- The policy says it: "5 lat od ostatniej czynności w sprawie (np. realizacji zamówienia lub wizyty), a następnie
  usuwamy je automatycznie; dokumenty rozliczeniowe — tak długo, jak wymagają przepisy podatkowe". OPEN for a
  lawyer/accountant: whether five years from the activity is right for non-accounting data, and whether accounting
  records need longer (tax law counts five years from the end of the year of the tax's due date).
- Tests: `RetentionSuite` (memory, the clock a number): the HMAC key never the IP and keyed, the limit still
  counting, an old raw key and an ended window gone, the live one kept; an order kept a day short of five years and
  gone a day after, its journal with it, payments, buyers and invoices kept; the last event counts; once in 20 hours.

## The privacy policy from the config (operator, 2026-11-05)

The policy names only the processors the site's config actually uses, and every cookie the code sets.

- `Privacy.page(site, l, email, host, chat, mail, geocoding, cookies)`: `mail` the clients' letters' way —
  `Privacy.Mail.of(MAIL_CLIENT, SMTP_HOST)` as `Settings` reads them: `cloudflare` → Cloudflare Email Service,
  `brevo` (or `smtp` through Brevo's relay) → Brevo, `resend` → Resend, `smtp` elsewhere → that server's provider,
  none → no mail processor. A site's build passes it (`StaticPages.of(site, look, sitekey, mail)` →
  `Look.privacy(site, l, mail)`), read from its wrangler.toml as the Turnstile key is; the mall from its env.
- Cloudflare's item states the transfer basis as the mall's docs/legal (podmioty-przetwarzajace.md) do: the EU-US
  Data Privacy Framework (the Commission's adequacy decision of 10 July 2023; Cloudflare, Inc. certified), the
  standard contractual clauses the fallback. Sources: https://www.cloudflare.com/trust-hub/gdpr/,
  https://www.dataprivacyframework.gov/list, https://www.cloudflare.com/cloudflare-customer-dpa/.
- `chat > 0` adds Cloudflare Workers AI (the assistant's model) and the `okc-warm` session storage; `geocoding` (a
  host that geocodes) with a travel radius adds GUGiK and the OpenStreetMap Foundation (Nominatim) — the visit's
  address only.
- The cookies' section: `lang` (1 year, set by the visitor's click), `__Host-ws` (the workshop's session, 30 days or
  sign-out, staff only), `okc-warm` with the chat, then the host's items (`cookies`, HTML in the page's language: the
  mall's `z-chat`); no advertising, analytics or tracking — none needs consent (strictly necessary or asked for by
  the visitor; TO VERIFY with a lawyer).
- The retention section as "Retention" above. `Privacy.version` 2026-11-05.
- Tests: `PagesSuite` (the mail's way from the config, Brevo only when used, DPF, AI and its storage only with the
  chat, the host's cookies, the geocoding only with a radius, the list's last full stop); `OldPagesSuite` pins every
  page but the privacy policy (byte for byte as before this change).

## Open core (operator, 2026-10-29)

okay-shop is public (Apache-2.0) and is the engine of ONE site: its storefront, orders, tracking and workshop — and,
later, booking with a calendar as a feature. The multi-tenant SaaS platform to come lives in a separate PRIVATE
repository on top of okay-shop and is never added here: self-service sign-up and its wizard, niche templates,
auto-translation, themes, custom domains, subscriptions and billing, its admin, and abuse protection.

## Release

Scala 3.3, the JVM and Scala.js, on okay-serverless 0.1.0, okay-ui 0.1.0, okay-scala 0.1.1, okay-facts 0.1.0. A Maven
repository on GitHub Pages (RELEASING.md); while only the two sites use it, a change is republished under 0.1.0.
