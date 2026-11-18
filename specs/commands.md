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

## A2 as built (2026-11-18): the small reader, trained and measured

`commandsBench` (`commands-bench/`, package `okay.shop.commands.bench`): the JVM alone, on mlx-scala (Apple's GPU),
never published and not among `root`'s modules — `sbt test` and a release never need MLX. Its own tests,
`sbt commandsBench/test` (`GenSuite`), need no model either. What is not for git — the teacher's texts, the
adapters, the logs — is in `COMMANDS_OUT` (`~/work/my/okay-shop-private/commands`).

- **`Asking`** — what the reader is asked, one writing for training and reading: a fixed rule of six lines; the desk
  as text (today, the days each with its weekday, the first two marked today and tomorrow, the starts as a range
  and a step, the services' keys with their names in the four languages, the masters' keys and names, the bookings
  each on a line with its reference first); the phrase. A day is written with its weekday (`Fri 2026-11-20`),
  every other option as it is.
- **`Reader`** — the reading by choices (okay-frames' `Reading.run` over `Commands`): the answer is, for each choice,
  a newline, the hole's name and a colon, a space and the option; after the last, the turn's end. At a hole every
  option is weighed by the mean log-probability of its tokens AND of the token that follows it (the next line's
  newline, or the end) — unlike okay-desk's `FramePort`, which weighs the option's own tokens: a client's options
  are spans of the phrase, one the beginning of another, and only the end tells "Olu" from "Olu Nowak". The best
  is taken; `close` makes the command. A hole of one option is not weighed; a hole of none (a payment in a phrase
  with no number) ends the reading with no command — `unread`. The model never writes a token of its own.
- **`Gen`** — fictional desks, each the function of a seed: one of nine trades (hair, nails, beauty, massage, a
  garage, pets, lessons, tattoo, photo; a quarter of the desks a salon of the first three together), two to six
  services, none to four masters (a fifth of the desks with Cyrillic names), a step of 15, 30 or 60 minutes,
  hours from 7–10 to 16–21, a horizon of 7, 14 or 21 days from a day of 2026–2027, none to eight bookings with a
  numbered or a store-like reference, their clients' names Polish, English, Ukrainian (in Latin or Cyrillic) or
  Russian. Commands in the sealed set's shares of the actions, the four languages in turn; a move changes the
  day, the time or both. All names come from lists of common first names and surnames; the names of the sealed
  shop's people are not in the lists, and no desk has its services or its bookings (`GenSuite`).
- **`Texts.canon`** — the canonical words (`Words.say`) as texts: free and exact.
- **`Teach`** — the teacher's phrases: Qwen3.6-35B through the local gateway, thinking off, temperature 0.9, is
  given the command's canonical words, today, a style drawn at random, and writes how a master would say it, a
  new client's name marked `[[…]]` (for `cannot`: a topic outside, of thirty). Kept only if (i) the marks
  stripped, the command — its client the marked span — closes over the phrase's own options, the span begins as
  the given name does, and the phrase does not say the booking's reference; and (ii) the teacher, asked apart at
  temperature 0 with the desk before it, reads the phrase back into the same command — every line but the
  client's words (a large model writes a name back in the nominative: A0). From attempt 320 on, a new booking
  written without its mark is asked for once more, and marks in another command are only stripped.
- **`Train`** — LoRA rank 16 on every projection, lr 2e-4, an example a step (okay-desk's `FrameTrain`), the
  seed the adapter's first values and the texts' order. The texts: the teacher's kept phrases and half as many
  canonical ones, none over a held-out desk (one seed in twenty, `Texts.heldOut`).
- **`ReadSet`**, **`Tables`** — a set read by a reader into a TSV (`sealed`: the 60 phrases; `held`: the teacher's
  texts over the held-out desks), and the tables of such files: each reader's right, wrong and unread by language
  and by line, and every pair and all three as one reader with a doubt — the same command from each, or `asked`.

To run again, each step one command (the GPU does one at a time — the gateway is the same GPU):

```sh
sbt "commandsBench/runMain okay.shop.commands.bench.Teach 0 3515"          # the teacher's texts (appends)
COMMANDS_SEED=1 COMMANDS_EPOCHS=1 sbt "commandsBench/runMain okay.shop.commands.bench.Train" # seeds 1 2 3
sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet held <adapter> <out.tsv>"
sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet sealed <adapter|none> scripts/commands/<name>.tsv"
sbt "commandsBench/runMain okay.shop.commands.bench.Tables scripts/commands/reader-s1.tsv …"
```

## A2b as built (2026-11-18): days and times computed

A2 missed its bar, and its misses that no agreement of readers can see were words for days and times. So they are
no longer read: an exact organ hears them, and the reader weighs only what the organ leaves open.

**How clean this step is.** The sealed set's misses told WHICH KINDS of expression to compute — a time in words,
a day by its distance, a move said as a difference; seven of its expressions are quoted in "Results / A2" above,
and could not be unseen. The organ was therefore written from each language's whole system of saying days and
times, not from those seven; `phrases.tsv` was not opened, the organ was not run on it and nothing was tuned on it
before the one final reading. Still: the sealed number after this step is less clean than a first reading — the
set chose the direction.

### The organ (`When`, `Tongues`; `shop`, both platforms, no model, no effect, no new dependency)

- **Dates reused, not written.** The engine's own calendar: `Reports.epochDay` / `ofEpoch` / `plus` and
  `Civil.ymd` (Hinnant's algorithm, already used by `Slots`, `Words.day`, the reports). okay-simple's
  `okay-effects-time-value` (`okay.effects.time.Date`) has the same and is not a dependency of okay-shop; the
  engine's calendar predates it and was not swapped here. `When` holds no date arithmetic but `+ n` on a day number
  and a weekday by `floorMod`.
- **`Tongue`** — how one language says days and times, as data: weekdays in their case forms and short writings,
  months, numerals in their case forms, the ordinals an hour or a date is said by, the words before a day or a
  time (`on`, `from`, `until`), the parts of the day, the words of a move by a difference; and the language's own
  halves and quarters as a small function (`idiom`). Words are written folded — lower case, `ё` as `е`, no
  apostrophe, Polish letters without their marks (`środę` is `srode`, as typed on a phone).
- **`When.hear(text)`** — a phrase is cut into words, numbers (`15`, `15:30`, `20.11`, `3pm`, `15-ту`) and marks;
  a reference (`b12`, `R-17`) is one opaque word; a name the canonical words quote (`«…»`) is skipped. Every
  language of the phrase's letters hears it (Cyrillic: Russian and Ukrainian; Latin: Polish and English — a
  phrase's language is not asked for), left to right, at each place a difference first, then a day, then a time;
  of two languages' hearings of the same words the longer wins. What is heard is before any calendar: `In(days)`,
  `Weekday(w, bare | this | next)`, `Date(day, month)`, a week, `At(hour, minute, twelve-hour?, part of day)`, a
  part of the day said apart, `Later(minutes)`, `DaysLater(days)`.
- **`When.days(desk, text, action, booking)`**, **`When.times(…)`** — the heard put on a desk (today is its first
  day), answered as a **`Narrowed`**: `Unsaid` (nothing heard: the options stay), `Among(options)` (the heard
  values that ARE the desk's options) or `Outside(said)` (heard, and none is an option — said so, never widened).
- **`Computed`** — the space of commands with those two lines narrowed: `Commands.next`, but a day's or a time's
  hole offers only `Among`'s options; `Outside` offers none (the phrase is not read: `unread`, the master is
  told); `Unsaid` offers all. A hole of one option is not weighed by any reader (okay-frames' `Reading`). Paths,
  words and parser are `Commands`' own, so said back → canonical parser → the same frame holds as before.
  `Commands` itself is unchanged: A1's laws and the 60 sealed phrases' reachability are what they were.

The API, whole:

```scala
enum Narrowed { case Unsaid; case Among(options: Vector[String]); case Outside(said: Vector[String]) }
object When:
  def hear(text: String): Vector[Heard]             // what a phrase says, before any desk
  def days(desk: Desk, text: String, action: String, booking: Option[Booked] = None): Narrowed   // 2026-11-20
  def times(desk: Desk, text: String, action: String, booking: Option[Booked] = None): Narrowed  // 15:00
object Computed extends Space[Desk, Command]       // Commands, its day and time holes narrowed by When
```

What is heard, in each of the four languages:

- **days** — today, tomorrow, the day after (`послезавтра`, `післязавтра`, `pojutrze`), also as adjectives
  (`dzisiejszych`); in N days, in a week, in N weeks; weekdays in all case forms and short writings; this / next /
  the nearest weekday; next week, this week; dates: `20 November`, `November 20th`, `20.11`, `20/11`, `the 20th`,
  `20-го`, `20 числа`, `15-go`, and in words (`двадцатого`, `dwudziestego pierwszego`, `the twentieth`).
- **times** — `15:00`, `15.00`, `15 30`, `3pm`, `3 p.m.`, `at 3`, `в 3`, `о 15`, `na 15`, `на 15-ту`,
  `o godz. 15`, `3 часа`, `3 o'clock`; numerals in words in their case forms (`в три`, `к трём`, `о третій`,
  `na trzecią`, `o piętnastej trzydzieści`, `семнадцать тридцать`); halves and quarters in each language's own
  system — `полшестого`, `половина шестого`, `четверть шестого`, `без четверти шесть`, `десять минут шестого`,
  `три с половиной`; `пів на шосту`, `пів до шостої`, `чверть на шосту`, `чверть по п'ятій`, `за чверть шоста`;
  `wpół do szóstej`, `kwadrans po ósmej`, `za kwadrans dziewiąta`, `za dziesięć szósta`; `half past five`,
  `half five`, `quarter to ten`, `ten past four`; noon and midnight; parts of the day, said after the time
  (`три дня`, `8 вечера`, `о 4 вечора`, `3 pm`) or anywhere in the phrase (`вечером`, `rano`, `після обіду`,
  `this afternoon`, `tonight`).
- **a move by a difference** — later / earlier by N minutes, N hours, half an hour, an hour and a half, a day, a
  week (`на час позже`, `на пів години раніше`, `o godzinę później`, `an hour later`, `later by an hour`); the
  same time; the same day. Applied only to a move whose booking is known: the booking's own day or time, shifted.

Conventions for what is ambiguous — decided from how the languages are used, and where usage is truly split, BOTH
candidates are answered (two options are still an exact narrowing; the reader or the master picks):

- **A bare weekday** (`on Friday`, `в пятницу`) is the nearest such day after today. Said ON that weekday it is
  today or a week from today: both.
- **`this` / `the nearest` weekday** (`this Friday`, `в эту среду`, `w tę środę`, `найближчої суботи`) is the
  nearest such day, today counted.
- **`next` weekday** (`next Friday`, `в следующую среду`, `w przyszły piątek`, `наступної п'ятниці`): speakers of
  all four languages split between "the next one to come" and "the one of next week". Both are answered when they
  differ — that is, when the weekday is still ahead in this week (said on a Wednesday, `next Friday` is this
  Friday or the one after). When it is today's weekday or already past this week, the two are one day (said on a
  Monday, `next Monday` / `w przyszły poniedziałek` is the Monday in seven days), and it is settled.
- **A weekday with `next week`** (`Wednesday next week`, `в среду на следующей неделе`, `we wtorek w przyszłym
  tygodniu`) is that weekday of the next calendar week (Monday to Sunday): settled. `next week` with no weekday is
  that week's days.
- **`через день`** (Russian, Ukrainian) is tomorrow or the day after: both. `через 1 день`, `in a day`, `za
  dzień` are tomorrow.
- **A day and a weekday said together** (`Friday 20.11`, `завтра, в четверг`) are one day when they agree; when
  they do not, both.
- **`10.11`** is a date when the horizon has such a day; else a time when the desk has such a start; else a date
  outside the horizon. `15.30`, `15.00` are times (no such month).
- **A time by a twelve-hour clock** — `at 3`, `в три`, `9:30`, `полшестого`, `half past five`: the hour or twelve
  more. A part of the day settles it (`дня`: 12–18; `вечера`, `pm`: the afternoon half; `утра`, `am`: the morning;
  `ночи`: 21–04). With no part said, both are tried against the desk's starts: one left is settled (`at 3` when the
  desk opens at 8), two are both answered (`at 8` on a desk from 7 to 21). `15`, `15:00`, and an hour written
  with a leading zero (`09:00`, as the canonical words write) are a twenty-four-hour clock: one time.
- **Russian `на 3 часа`** is taken for three o'clock, as said in a salon; it can also mean "for three hours".
  `на час` alone (an hour, or one o'clock, or by an hour) is not heard at all. Ukrainian `на 2 години` and Polish
  `na 2 godziny` are a length, never a time.
- **In a move** a day or a time said after `from` (`с пятницы`, `z 10`, `from 3`) is what the booking leaves, and
  one equal to the booking's own is the booking's description — both dropped when anything else is said (`from
  10 to 12` is 12; `Anna's Friday haircut to Saturday` is Saturday; `Anna's Friday haircut to 11:30` keeps
  Friday). **Elsewhere** `from 3 to 4` is 3: the `until` after a `from` is the end. In every action a day said
  after `instead of` (`вместо`, `замість`, `zamiast`) is dropped when another is said.
- **What is no day and no time**: a number after a word of paying or before a currency (`paid 150`, `12.50 zł`), a
  reference, a number with nothing around it that says a time (`Lipowa 12`, `2 haircuts`, `600 000 000`), a number
  of people, minutes, days or weeks (`for 3 people`, `на 2 години`), English `at 12 Baker Street`. And the narrowing
  is asked only for an action's own lines: a payment has no day and no time line at all.
- An ordinal alone below the eleventh (`пятого`, `the fifth`) is a date only after a word for a day (`на пятое`,
  `on the fifth`) or before a month or `числа`: `второго мастера` is no date.

Tests, `WhenSuite`, both platforms, none of its phrases of the sealed set: a table of expressions a language with
the narrowing expected, written from the language's grammar — English 73, Russian 75, Ukrainian 77, Polish 76 —
each with the phrases that say neither a day nor a time; 53 moves (by a difference; from one day or time to
another); the space (`a settled line has one option, an outside one none`, every other line `Commands`' own); and
the property: for 120 random desks, every canonical saying (`Words.say`) of a booking, a list, a day off and four
moves, in the four languages (about 3000 sayings), is narrowed to exactly its own day and time, its command is
reachable in `Computed`, and a reader is never asked for a day or a time of it. Checked by mutation, each caught:
`next` weekday as the nearest alone; the booking's own day not dropped in a move; a half read as the hour named.
In the bench (`GenSuite`): the 2000 canonical training texts over generated desks are all settled and reachable.

**The organ on the teacher's texts, no model** (`Narrowing`; the 2273 texts of A2 — the development set of this
step, with the tables above; the sealed set and the new held-out slice were not used):

| line | settled to the meant one | narrowed, the meant among | unsaid | the meant one lost |
| --- | --- | --- | --- | --- |
| day (1224 lines) | 1021 (83%) | 43 | 98 | 62 (5.1%) |
| time (881 lines) | 777 (88%) | 11 | 77 | 16 (1.8%) |

- **All 62 lost days are one thing, and it is the texts'**: a day seven or more days ahead named by a bare weekday
  ("Put Oliver down …, Wednesday at half past nine" for the Wednesday of next week). The teacher was told "a
  weekday, or the day's number" and given no rule for a far day; a person would say "next Wednesday" or the date.
  So about one day in twenty of A2's training texts teaches a reader a wrong day. Not repaired in A2's texts; the
  teacher's rule is corrected for the new held-out slice below.
- The 16 lost times: the teacher's own slips (`полвторого` written for 14:30, `wpół do trzeciej` for 14:00,
  `четверть третьего` for 14:30 — the organ's reading is the right one), and broken phrases (`о дванадцятій без
  півгодини`, `в восемь соропяти`).
- Unsaid is mostly a move that does not say the day or the time it keeps. Of the rest: typos (`ввівторок`,
  `четверь`), `на szóste`, an ordinal below the eleventh alone (`четвертого`).
- Found on these texts and added: `пів на п'ятнадцяту` and `wpół do piętnastej` (hours past twelve),
  `о сьомій годині тридцять хвилин`, `piętnaście piętnaście`, `о половині десятої`, `20-те`, `dzisiejszych`,
  `wpół do jednej`, the teacher's `тридцятій` and `trzydzieście` for thirty.

### The held-out slice with every action (`Teach held`)

Why the first one had three actions: an attempt `i` drew its desk by `i / 8` and its action by `(i / 4) % 15`, and
a desk was held out when its seed was a multiple of 20 — so `i / 4` was 0 or 1 modulo 40, and modulo 15 that is
only six places of the shares, all `book`, `move` or `list`. The desk decided the action.

The existing texts cannot give a clean slice (every other desk was trained on), so the new one is NEW texts over
NEW desks: seeds from 500000, a desk an attempt; no training ever drew them (the teacher's texts are over seeds
1000–1439, the canonical ones from 100000). The action is the attempt's number's alone. Texts are asked for until
every kind has its share in every language: 75 a language, 300 in all, in the sealed set's shares — 25 `book`, 15
`move` (9 by a new day and time, 6 by a difference: `Gen.relative` — half an hour to two hours on the booking's
day, another day at the same time, a day or a week later), 10 `cancel`, 5 `dayoff`, 10 `paid`, 5 `list`, 5
`cannot`. A list and a cancel are asked for "in everyday words, as a question or a remark". The teacher's rules
are A2's and one more: a weekday alone is the nearest such day (see above). Kept by A2's two checks, unchanged.
The people's names are from the same lists as the training's; the desks, bookings and phrases are new.

### To run (the GPU does one at a time)

```sh
sbt "commandsBench/runMain okay.shop.commands.bench.Narrowing <texts.jsonl>"       # the organ alone, no model
sbt "commandsBench/runMain okay.shop.commands.bench.Teach held 0 4000"             # held.jsonl (appends)
sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet new <adapter> <out.tsv> new+when <adapter> <out.tsv>"
sbt "commandsBench/runMain okay.shop.commands.bench.ReadSet sealed+when <adapter> scripts/commands/<name>.tsv"
```

### Not done in A2b

- A weekday with a bare day number (`Monday 24`); `Friday week`, `the Friday after next`; a time from now (`через
  час`, `in an hour`); `back` / `forward`, `назад` / `вперёд` as directions of a move; a part of the day with no
  hour (`after lunch` alone narrows nothing); hours above twelve in English words but `fifteen thirty`.
- A weekday's short writing that is also a name or a word (`Sob`, `Sat`, `Sun`) is heard as the day, outside the
  canonical quotes.
- In a move, a day or a time the phrase does NOT say is left to the reader (it stays the booking's by the frame's
  rule, but "not heard" is not "not said"): only an explicit difference or `the same` is computed.
- The desk's times are still one list for all days and services (A1): the organ narrows among them, it does not
  know a start is closed that day.
- A1's page (A3) does not call the organ yet; `Run` and `Desks` are untouched.

## Results

### A0 (run 2026-11-18)

`scripts/commands/ceiling.sc`, model mlx-community/Qwen3.6-35B-A3B-4bit, thinking off, one run. Every answer is in
`scripts/commands/ceiling-out.tsv`.

| language | exact frames |
| --- | --- |
| en | 15/15 |
| pl | 12/15 |
| ru | 11/15 |
| uk | 11/15 |
| all | 49/60 |

Missed by field: client 10, day 2, time 1 (11 phrases). The action, the service, the master, the booking and the
amount were right in all 60.

What the misses are, read row by row:

- **8 phrases: the client's name in the nominative.** The text has "Олю Иванову", "Kasię", "Adama Wójcika"; the
  model answered "Оля Иванова", "Kasia", "Adam Wójcik". The gold is the span as written, because the frame's client
  line offers spans of the text. The model did what a person would want in a booking; the strict score counts it
  wrong. English has no cases, which is why it is 15/15.
- **2 phrases: the name moved to Latin letters** ("Светланы" → "Svetlana", "Катю" → "Katya"). A real error.
- **2 phrases: the day or the time**, both Ukrainian: "післязавтра" read as four days ahead, and "завтра на пів на
  першу" read as the day after at 10:30. A real error.

So: 49/60 strict; 57/60 if a name is compared without its case ending; the reading of dates and times is wrong in
2 of 60, and only in Ukrainian.

Consequences for the next steps:

- The ceiling on the *structure* of a command is high (action 60/60, every option from the Desk 60/60). The frame
  is not asking for more than a large model can read.
- A reader that chooses spans cannot make the 8 "errors" at all, and cannot make the name nominative either. The
  name to store is a separate question (A3): say back the span, and let the master correct it on confirm; a
  nominative form, if wanted, is a job for an exact organ or a table, not for the reader.
- Relative days and spoken times ("післязавтра", "пів на першу") are the place to compute and not to read: the
  frame offers days and times as options from the Desk, and the words for them belong to `Words`, per language.
- The set stays sealed. The prompt is not tuned after this run.

### A2 (run 2026-11-18)

**Not done by its criterion.** Two readers agreeing, on the 60 sealed phrases: right 32–34 (53–57%), wrong 5–8
(8–13%); the criterion is right ≥ 80% and wrong ≤ 2%. Every answer is in `scripts/commands/reader-*.tsv`.

The texts. The teacher was asked 3515 times (4320 were planned; the run was stopped there, another session's
training on the same GPU): **2273 kept (65%)**, 1242 rejected — the teacher reading its own phrase back to another
day 581, time 251, service 108, action 71, master 28, booking 15, way 10 (by the first line that differs; in all,
the day differs in 633 and the time in 417); a new booking without its one mark 172 (41 of them in the first 320
attempts, before the second asking); a phrase that does not close (an amount not in digits) 6; none for a span
that is no option, a name that is not the given one, a reference said. Kept by language: en 686, ru 539, pl 534,
uk 514; by action: book 561, paid 411, cancel 409, move 320, cannot 229, list 175, dayoff 168 — a booking and a
move, which name a day and a time, are rejected most, so they are a smaller share than they were asked for.

The training. 2179 of the teacher's texts and 1033 canonical ones (94 of the teacher's held out: the desks of one
seed in twenty), 723 tokens a text, 3212 steps an epoch, about 0.72 s a step alone on this GPU.

| adapter | training loss | held-out loss | held-out texts right (94) |
| --- | --- | --- | --- |
| seed 1, epoch 1 | 0.0496 | 0.0305 | 72 |
| seed 1, epoch 2 | 0.0244 | 0.0401 | 65 |
| seed 2, epoch 1 | 0.0451 | 0.0609 | 53 |
| seed 3, epoch 1 | 0.0473 | 0.0390 | 68 |

One epoch was chosen on seed 1's held-out loss (0.0305 against 0.0401), before any sealed phrase was read; the
held-out readings, made later, say the same (72 against 65). A flaw found afterwards: by the way attempts are
numbered, a held-out desk has only bookings, moves and lists — the held-out slice has no cancel, payment, day off
or `cannot`, and is small.

The measure on the sealed 60 (all 60 hand-written commands are reachable by choices, `Laws.reach`; a reading takes
0.42–0.45 s a phrase):

| reader | right | wrong | unread | en | pl | ru | uk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Qwen3 0.6B untrained | 4 | 55 | 1 | 1/15 | 1/15 | 2/15 | 0/15 |
| seed 1 | 42 | 18 | 0 | 13/15 | 11/15 | 11/15 | 7/15 |
| seed 2 | 38 | 22 | 0 | 10/15 | 10/15 | 10/15 | 8/15 |
| seed 3 | 41 | 19 | 0 | 11/15 | 10/15 | 12/15 | 8/15 |
| the ceiling (35B, A0) | 49 | 11 | 0 | 15/15 | 12/15 | 11/15 | 11/15 |

By line — the action over all 60, another line over the phrases whose action was read right:

| reader | action | client | service | master | day | time | booking | amount | way |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| untrained | 19/60 | 3/4 | 4/4 | 4/8 | 1/9 | 3/5 | 6/11 | 8/8 | 6/8 |
| seed 1 | 54/60 | 14/18 | 18/18 | 26/26 | 32/35 | 21/27 | 24/24 | 7/7 | 7/7 |
| seed 2 | 49/60 | 20/20 | 20/20 | 24/24 | 25/31 | 22/27 | 21/21 | 8/8 | 7/8 |
| seed 3 | 51/60 | 18/19 | 19/19 | 27/27 | 31/34 | 22/26 | 20/22 | 8/8 | 8/8 |

Readers agreeing — the same command from each, else the master is asked:

| readers | right | asked | wrong | en r/a/w | pl r/a/w | ru r/a/w | uk r/a/w |
| --- | --- | --- | --- | --- | --- | --- | --- |
| seeds 1 + 2 | 33 | 22 | 5 | 10/4/1 | 9/4/2 | 8/7/0 | 6/7/2 |
| seeds 1 + 3 | 34 | 20 | 6 | 11/3/1 | 9/4/2 | 10/4/1 | 4/9/2 |
| seeds 2 + 3 | 32 | 20 | 8 | 10/2/3 | 8/4/3 | 9/6/0 | 5/8/2 |
| seeds 1 + 2 + 3 | 27 | 29 | 4 | 10/4/1 | 7/6/2 | 7/8/0 | 3/11/1 |

Two more adapters, each read once, outside the three seeds:

| reader | right | wrong | unread |
| --- | --- | --- | --- |
| the pilot: 186 of the teacher's texts and 85 canonical, four epochs | 40 | 18 | 2 |
| seed 1 after its second epoch | 50 | 10 | 0 |

What the tables say, plainly:

- **The training works; more texts of the same kind did not add.** Untrained, the model answers nearly everything
  `list, today, olena` (4/60). Two hundred texts seen four times (about 1100 steps) give 40/60; twelve times as
  many seen once (3212 steps) give 38–42.
- **What the shop names is read**: the service 57/57, the master 77/77, the booking 65/67, the amount and the way
  45/46 over the three seeds. The client's span 52/57 — the misses are the phrase's first word taken for the name
  ("Завтра", "Борода", "Фарбування"), four of the five by seed 1.
- **11 phrases are missed by all three seeds**, and they are of three kinds:
  - a time in words, 4: "полшестого", "на дев'яту", "пів на першу", "wpół do dziesiątej" — the last read 10:30 by
    all three ("half past ten" for "half to ten");
  - a day by its distance, 3: "післязавтра" read tomorrow by all three (the ceiling missed it too), "w przyszły
    poniedziałek" read today or tomorrow, "Wednesday next week" read this Wednesday by all three;
  - a move said as a difference, 4: "на годину пізніше", "pół godziny wcześniej", "an hour earlier", "в то же
    время" — read as a cancel, a day off, `cannot` or a wrong time. The texts have next to none of these: a move
    was drawn by its new day and time, and the teacher said them.
- **The other 22 phrases are missed by one or two seeds**: a list or a cancel read `cannot` (seed 2: six of them,
  "Что у меня завтра?", "Co mam w piątek?"), a surname matched to another booking (seed 3, twice), a booking in
  a terse order of words read a day off ("Koloryzacja, Ewa Lis, sobota, 11:00, u Oleny").
- **Agreement is a doubt that works, and not enough.** It takes the wrong answers from 18–22 down to 5–8 at the
  price of 20–22 questions. What is left is what the readers misread ALIKE — the words for times and days above:
  no number of seeds asks about those.
- **The epochs.** The second epoch of seed 1 reads 50/60 here and 65/94 on the held-out texts, the first 42/60
  and 72/94: the two sets disagree, and the held-out one is small and of three actions. The seeds were trained
  for one epoch, as chosen before the sealed set was read; whether two epochs are better is NOT settled by one
  adapter, and choosing them now would be choosing on the sealed set.

What follows for A3 and for the reader:

- A reader that is right two times in three is a draft, not an answer. A3's page must make a wrong line cheap:
  the command said back with every line a choice among the same options (`Commands.next`), one tap to change,
  then Confirm. That page is worth building as it is — nothing runs without the confirmation either way.
- Days and times are to be COMPUTED, as A0 already said: an exact organ per language for "післязавтра",
  "wpół do dziesiątej", "next week", which either fills the line or narrows its options before the reader weighs
  them. They are 7 of the 11 common misses, and the only misses that agreement cannot see.
- A move by a difference ("an hour later", "the same time") needs its own texts, and perhaps its own line (a
  shift from the booking's time): computing the new time from the booking is exact, reading it is not.
- The next measure of the reader itself, in this order: a held-out slice with every action in it; texts for the
  relative moves and for lists and cancels in everyday words; three seeds at two epochs against one. The sealed
  set stays sealed: these are chosen on the held-out texts.
- Not tried: the options weighed as index tokens; a model larger than 0.6B (okay-desk: 1.7B halves the errors).
