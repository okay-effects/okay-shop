# AGENTS.md — okay-shop

Read `specs/shop.md` first: the spec is written before the code and kept true after it.

- The engine of a small business's site — storefront, orders, tracking, a workshop — on effects: `Store`, `Mail`,
  `Captcha`, `Ids` (and the workshop's `Staff`, `Human`), each handled onto okay-serverless' effects in the cloud
  (`StoreDurable`, `MailCloud`, `Turnstile`) and in memory in tests. Each site using it has its own repository: its
  content, its `Look` and a one-line Worker main.
- A site's look is the site's (`Look`: theme, SEO, sections, privacy, its own pages); the engine draws the storefront
  frame, the forms, the track pages and the letters. Never put a site's words or CSS here.
- No `@JSExportTopLevel` in the library: a site's main exports `ShopWorker(site, look)`.
- Tests on both platforms (`sbt test`) on two FICTIONAL sites (shop/src/test/resources: "Test Atelier", "Test
  Studio"; phones like +48 600 000 000, addresses @example.com) — never a real business's data here; the workshop's
  screens under okay-ui's `Play` against the real program on okay-serverless' `Memory`.
- Scala 3.3, lines at most 120 wide. Releases: RELEASING.md (republished under 0.1.0 while only the two sites use it).
- Commits: plain messages, no attribution trailers. Push what lands.
- OPEN CORE (operator, 2026-10-29): this public library is the engine of ONE site (storefront, orders, tracking,
  workshop; later booking with a calendar). The multi-tenant SaaS — sign-up and wizard, niche templates,
  auto-translation, themes, custom domains, subscriptions and billing, admin, abuse protection — lives in a separate
  PRIVATE repository on top of it and is never added here (specs/shop.md "Open core").
