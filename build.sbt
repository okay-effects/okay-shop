/** okay-shop: a small business's site on effects — storefront, orders, tracking, a workshop (specs/shop.md) */
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-shop"))
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports,privates,locals")
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / resolvers ++= Seq("okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven")
/** RELEASES, ON GITHUB PAGES: `sbt publish` writes every module into `target/pages-maven/`, which goes to the
 * `gh-pages` branch under `maven/` (RELEASING.md) */
val pagesRepository = settingKey[File]("the Maven repository a release is written to, for GitHub Pages")
ThisBuild / pagesRepository := (ThisBuild / baseDirectory).value / "target" / "pages-maven"
ThisBuild / publishTo := Some(MavenCache("pages", (ThisBuild / pagesRepository).value))
ThisBuild / Compile / packageDoc / publishArtifact := false

val okayServerless = "0.1.0"
val okayUi = "0.1.0"
val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** the tests' content: two fictional sites — an atelier and a studio — (src/test/resources), as strings */
lazy val fixtures = Def.task {
  val base = (ThisBuild / baseDirectory).value / "shop" / "src" / "test" / "resources"
  def lit(f: File): String = "\"\"\"" + IO.read(f) + "\"\"\""
  val file = (Test / sourceManaged).value / "okay" / "shop" / "Fixtures.scala"
  IO.write(file, "package okay.shop\n\n/** written by the build: the two sites' content, for the tests */\n" +
    "object Fixtures:\n  val content: Map[String, (String, String)] = Map(\n" +
    Seq("atelier", "studio", "salon", "visits", "crew").map(id => "    \"" + id + "\" -> (" +
      lit(base / id / "site.md") + ", " + lit(base / id / "i18n.md") + ")").mkString(",\n") + ")\n" +
    "  def site(id: String): okay.shop.model.Site =\n" +
    "    okay.shop.content.Sites.of(id, content(id)._1, content(id)._2)\n")
  Seq(file)
}

/** THE ENGINE: the model, the content's reader, the effects and their handlers onto okay-serverless, the programs,
 * the pages of the storefront (given a site's look), of an order, the letters */
lazy val shop = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("shop"))
  .settings(name := "okay-shop",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-serverless" % okayServerless, munit.value),
    Test / sourceGenerators += fixtures.taskValue)

/** THE WORKSHOP: its program on okay-ui, `Staff` over `Fetch`; in a page `okay.shop.workshop.InThePage` */
lazy val workshop = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("workshop"))
  .dependsOn(shop % "compile->compile;test->test")
  .settings(name := "okay-shop-workshop",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % okayUi, munit.value))
  .jsSettings(Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-js")

/** THE PAGES' SCRIPT: `okay.shop.page.Site`, a site's `site.js` */
lazy val page = (project in file("page")).enablePlugins(ScalaJSPlugin).dependsOn(shop.js)
  .settings(name := "okay-shop-page", libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.1")

/** A SITE'S WORKER on okay-serverless' Cloudflare: `ShopWorker(site, look)` */
lazy val worker = (project in file("worker")).enablePlugins(ScalaJSPlugin).dependsOn(shop.js)
  .settings(name := "okay-shop-worker",
    libraryDependencies += "io.github.okay-effects" %%% "okay-serverless-cloudflare" % okayServerless)

lazy val root = (project in file(".")).aggregate(shop.jvm, shop.js, workshop.jvm, workshop.js, page, worker)
  .settings(publish / skip := true)
