# okay-shop — a small business's site on effects: storefront, orders, tracking, a workshop

## Overview

The engine of szykownia.pl and it.szykownia.pl (2026-10-28), made a library so each site's repository holds only
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
| `shop` | `okay-shop` | the model, the content's reader, the effects and their handlers, the programs (`Api`, `Front`, `Workshop` API, `Submit`), the pages of an order (`Track`), the letters, the storefront's markup and forms (`Storefront`, given a site's `Look`), the static pages (`StaticPages`) | JVM, Scala.js |
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

Everything specified and checked for the two sites (sergey-scherbina/sites, specs/sites.md, 2026-10-28), unchanged:
orders (validation, Turnstile, 5 per IP in 10 minutes, consent with its time and policy version, the owner's letter,
the client's confirmation with the ref the track page takes and a link with the order's key), the track page (by the
key, or by the ref and the email or phone; nothing told to a stranger), the receipt, the IT inquiry, the workshop
(sign-in, the moves `Workflow` offers, the letters on ready and parcel out, a free letter), the redirects of busi's
URLs. The tests move with the code and run on fixtures of both sites' content.

## Release

Scala 3.3, the JVM and Scala.js, on okay-serverless 0.1.0, okay-ui 0.1.0, okay-scala 0.1.1. A Maven repository on
GitHub Pages (RELEASING.md); while only the two sites use it, a change is republished under 0.1.0.
