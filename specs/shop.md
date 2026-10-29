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
- **The sign-in link is limited per address too**: 5 per IP and 5 per address (its SHA-256) in 10 minutes; the
  answer is the same for an address on the list and one not.

## Open core (operator, 2026-10-29)

okay-shop is public (Apache-2.0) and is the engine of ONE site: its storefront, orders, tracking and workshop — and,
later, booking with a calendar as a feature. The multi-tenant SaaS platform to come lives in a separate PRIVATE
repository on top of okay-shop and is never added here: self-service sign-up and its wizard, niche templates,
auto-translation, themes, custom domains, subscriptions and billing, its admin, and abuse protection.

## Release

Scala 3.3, the JVM and Scala.js, on okay-serverless 0.1.0, okay-ui 0.1.0, okay-scala 0.1.1. A Maven repository on
GitHub Pages (RELEASING.md); while only the two sites use it, a change is republished under 0.1.0.
