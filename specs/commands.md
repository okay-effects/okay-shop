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

Not built yet (the rest of A1): **the desk from a shop's data** (`Conf`, the store's bookings, `Slots.free` for a
service's real starts instead of every start of the day) and **the compiler** from a command to the workshop's
existing operations (`Bookings.reserve` / `move` / `cancel`, a master's days off, `Payments`' mark, the
workshop's list). Until then nothing here can change a shop's data.

## Results

### A0 (2026-11-17)

Pending: the run waits for the GPU.
