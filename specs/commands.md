# The master's commands in words — a study, nothing built yet

Started 2026-11-17. The workshop takes a master's sentence ("book Emma for a haircut on Friday at 3 with Marek"),
reads it into a **command frame** whose every value is chosen among what the shop itself offers, says the frame
back, and runs it only after the master confirms. The reader is a small model in the phone's browser (okay-desk's
frame reader, okay-llm); the engine is the workshop as it is. No answer of the model is ever an action by itself.

Why here: the options are few (a shop's services, masters, days, bookings), so reading by choice should be far
easier than okay-desk's questions over tables — a HYPOTHESIS, measured below before anything is built. And a model
on the master's own phone costs the platform nothing per message.

## The frame (v0, for the measure)

| action | lines |
| --- | --- |
| `book` | client (the phrase's own words), service, master or `any`, day, time |
| `move` | booking, the new day, the new time (what the phrase leaves unsaid stays the booking's) |
| `cancel` | booking |
| `dayoff` | master, day |
| `paid` | booking, amount, way (`cash`, `card`, `transfer`) |
| `list` | day, master or `any` |
| `cannot` | — anything else |

Options: services and masters from the shop's config; days from the booking horizon, each with its weekday; times
on the service's step; bookings as the workshop lists them. The client's name is a quote of the phrase, never
invented.

## Steps

- **A0 — the phrases and the ceiling.** `scripts/commands/phrases.tsv`: 60 phrases a master would say, 15 each in
  Russian, Ukrainian, Polish and English (5 book, 3 move, 2 cancel, 1 day off, 2 paid, 1 list, 1 outside), each
  with its frame written by hand, over one fictional shop (two masters, four services, five bookings; today a
  Monday). `scripts/commands/ceiling.sc`: a large model (Qwen3.6-35B through the local gateway, thinking off) reads
  each phrase with the shop's options before it; its frame is compared with the hand-written one, field by field.
  Done when the ceiling is known. Under about 95%: the frame language or the phrases are the gap, fix them first.
- **A1 — the frame in the engine**: the lines and their options from a shop's data, a compiler to the workshop's
  existing calls, the frame said back in four languages; the law: said back → canonical parser → the same frame.
- **A2 — the small reader**: templates and a teacher's rewrites, a LoRA on Qwen3 0.6B, three seeds. Done when, on
  the sealed phrases with two readers agreeing, wrong ≤ 2% and right ≥ 80%.
- **A3 — the workshop's page**: a field, the frame said back, Confirm, then the engine. A test: nothing runs
  without the confirmation.
- **A4 — the phone**: the time from the phrase to the frame said back, on a real iPhone.
- **A5 — one master, one week.**

The 60 phrases are the sealed set from A2 on: never trained on, never used to tune a prompt after A0.

## A1 as built (2026-11-17): the exact part

`okay.shop.commands` (`shop`, both platforms, no effect, no model):

- **`Desk`** — the shop as a command sees it: the days of the horizon, a day's starts, the services' and masters'
  keys and names, the bookings to come (`Booked`). Every option comes from it or from the text.
- **`Command`** — `Book`, `Move`, `Cancel`, `DayOff`, `Paid`, `Show`, `Cannot`.
- **`Commands`** — the space of commands, an okay-frames `Space[Desk, Command]` (since 2026-11-17; before that a
  `Frames` of its own, the fourth writing of the same interface): the first decision is the action, then the
  action's `lines` in a fixed order; a line's `options`: services, masters (and `any`, but never for a day off),
  days, starts and bookings from the desk, the ways of paying from `Method`; a client's name among the text's
  own spans of one to three words, none across a punctuation mark; an amount among the text's own numbers.
  okay-frames' `close` makes the command of the choices and refuses a value that is not one of its options;
  `path` is its inverse.
- **`Words`** — a command's canonical words in the four languages (`say`) and their parser (`parse`). The words
  are labelled lines, not prose (`Book: «Emma» — Haircut, Marek, Friday 20.11 at 15:00.`), so no name is ever
  put in a case; a booking is said with its reference first, so no two read alike. Templates are data, one set a
  language; the parser reads by the same templates and answers only a command that says back to the same words.

Laws, `CommandsSuite` by okay-frames' `Laws` (theory: okay-desk specs/theory.md §3), over the fictional shop
and the 60 sealed phrases
(`Fixtures.phrases`, written by the build from `scripts/commands/phrases.tsv`):

- **L1** every line's options are finite and there; **L2** each phrase's hand-written command is reachable by the
  options and closes; a value outside the options closes nothing;
- **L5** `parse(say(c)) == c` for the phrases' 60 commands and each kind over the shop's options, in every
  language (about 580 commands a language); two commands never say the same; other words are no command.
- Checked by mutation: the parser's last literal found at its first place fails L5; `any` not offered fails L2.
- A reading driven by okay-frames' `Reading`: an oracle reads all 60 phrases; a weigher with no opinion is
  asked 60 times and answers nothing wrongly (`Measure`).

### The desk from a shop's data (`Desks`, 2026-11-17)

`Desks.read(conf)`, a program over `Store` and `Clock` (`Desks.of(site, bookings, now)` is its pure part):

- **days** — the days of the booking horizon (`Schedule.horizon`), today in Poland's time first: `Slots`' own days.
- **times** — EVERY start on a schedule's step inside any opening span the shop has on any day: its week and its
  exceptions, its places' and its visits' hours, each master's own week; and a class's own starts. It is one list
  for all services and days, so it is NOT the free starts: a start of the desk may be closed that day, too late for
  a long service, or taken. That is decided where it always was, by `Slots` in the store's step, and a command at
  such a start is refused `taken`. The law kept instead: every start `/api/slots` offers anybody is a day and a
  time of the desk. (A desk of the free starts of each service and day needs a `Desk` whose times depend on the
  lines chosen before — not built.)
- **services** — the services shown (not hidden) and booked for a time, by key, in the content's order.
- **masters** — `Site.masters`, by key; none in a shop of one calendar (then a master's line offers only `any`,
  and a day off has nobody to name).
- **bookings** — the store's active bookings from now on, by their start, as `Booked`: the ref, the client's name,
  the service's and the master's keys, the day and the minutes from midnight in Poland's time.

### The compiler (`Run`, 2026-11-17)

`Run(conf, command, who): Either[Refusal, Ran]`, a program over `Store`, `Mail` and `Clock`. It takes a command
that WAS ALREADY CONFIRMED (said back by `Words.say`, accepted by the person) and never a reading alone; `who` is
the person signed in to the workshop, checked by the caller. It runs the engine's existing operations and has
none of its own:

- **`Move`** — `Bookings.rebook` (the page's move without its letters: the booking's own mode and place,
  `Store.Rebook` / `RebookWith` / `Reseat`, its review asked anew), then the client's letter `Letters.rebooked`.
  Done: `Ran.Moved(booking, told)`. Refused (`Refusal.Refused`, the engine's own word): `no such booking`,
  `taken`, `full`, `master`, `no schedule`, `start`.
- **`Cancel`** — `Workshop.unbook` (the workshop's cancel: `Store.CancelBooking`, the client's letter). Done:
  `Ran.Cancelled(booking, told)`. Refused: `no such booking`.
- **`Paid`** — `Store.Pay` with a `Mark(Of.Booking, ref, "paid", amount, way, today)`: `Payments.mark`'s rules (the
  staff marks, only the owner changes), the receipt numbered in that step. Done: `Ran.Paid(payment)`. Refused:
  `no such booking`, `owner`, `amount`, `day`.
- **`Show`** — `Workshop.listed` (the workshop's list), the day's and the master's. Done: `Ran.Listed(bookings)`.
- **`Cannot`** — nothing. `Ran.NothingToDo`.

Notes:

- A master's move keeps the booking's master: they are asked for, so the move is theirs still or `taken`. The
  words confirmed name no other master, and the page's rule (pass to the first free master) would change one
  unsaid. A booking of no master of the shop's (made before the masters) moves by the page's rule.
- Like the workshop's cancel, a master's move and cancel write to the client only (no letter to the owner, no
  notice): the staff did it themselves.
- `Show` lists what the workshop lists — any status, a cancelled booking too.
- For this `Bookings.rebook`, `Workshop.unbook` and `Workshop.listed` were cut out of the routes that had them
  inline (`POST /api/booking/move`, the workshop's `booking/cancel` and `bookings`); the routes call them and
  answer as before.

**Refused, because the engine has no operation for it** (`Refusal.Missing`, nothing changed, nothing faked):

- **`Book`** — a booking made by the staff. `Bookings.reserve` writes the client's contact and `Consent(now,
  policy)`: the client's own consent to the privacy policy, given on the form. A master's word is neither.
  Missing: a booking the staff makes for a client — who made it, a contact that may be absent, and what stands in
  the consent's place (an owner's decision, with the privacy policy's text) — in the model, the store's step and
  the letters.
- **`DayOff`** — a master's days off are `Master.off`, the site's content (`site.md`'s `json masters` block, or a
  host's copy), read into `Conf`; the store has no step for them and `Content.write` does not write masters.
  Missing: days off kept in the store (or the masters written back by `Content`), and the slots reading them.

Both stay commands of the space: the options offer them and the words say them, so a reader may read them and the
person is told plainly which operation is missing.

Tests, `CommandsRunSuite`, on the fictional `salon` (one calendar) and `crew` (modes, places, three masters)
through the tests' `World` (okay-serverless' `Memory`): the desks equal the data, and every start `/api/slots`
offers is the desk's; a move, a cancel and a payment read back from the store with their letters; the list equal
to the workshop's own (`GET <desk>/api/bookings`); each refusal with the store's keys (but its count of steps)
and the letters as they were; a command of the real desk said and parsed in the four languages, then run.
Checked by mutation, each caught: starts cut an hour short, the masters' weeks or the classes' starts left out,
cancelled or past bookings kept, a booking's time an hour off, hidden services kept; a move free to change the
master or telling nobody, a payment marked as the owner's, the list without its day or its master, a day off
answered as done, a cancel telling nobody.

Still not built in A1: the frame said back and confirmed on a page (A3 calls `Desks.read`, `Words.say`, then
`Run`), and the two missing operations above.

## Results

### A0 (2026-11-17)

Pending: the run waits for the GPU.
