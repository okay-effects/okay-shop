# okay-shop

A small business's site on [okay-scala](https://github.com/okay-effects/okay-scala) effects and
[okay-serverless](https://github.com/okay-effects/okay-serverless): a storefront in four languages (pre-rendered
pages), orders with tracking by a short ref, letters to the owner and the client, and a workshop (okay-ui) where staff
signed in by a mailed link move orders through their statuses. On Cloudflare: a Worker per site, its state in a
Durable Object's SQLite.

```scala
// the site's Worker, one line (its own repository): the engine, the site's content and look
@JSExportTopLevel("worker") val worker = ShopWorker(MyShop.site, MyShopLook)
```

| module | what |
| --- | --- |
| `okay-shop` | the model, the content's reader (`Sites.of`), the effects and handlers, `Api`, `Front`, `Pages`, `Track`, `Letters`, `StaticPages` |
| `okay-shop-workshop` | the workshop on okay-ui; `okay.shop.workshop.InThePage` (a page's main) |
| `okay-shop-page` | the forms' script, `okay.shop.page.Site` (a page's main) |
| `okay-shop-worker` | `ShopWorker(site, look)`: the app and the site's actor on okay-serverless-cloudflare |

```scala-sbt
resolvers ++= Seq("okay-shop" at "https://okay-effects.github.io/okay-shop/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven")
libraryDependencies += "io.github.okay-effects" %%% "okay-shop" % "0.1.0"
```

Spec: [specs/shop.md](specs/shop.md). Apache 2.0.
