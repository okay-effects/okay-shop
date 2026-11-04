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
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-facts" at "https://okay-effects.github.io/okay-facts/maven",
  "okay-llm" at "https://okay-effects.github.io/okay-llm/maven")
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

/** THE ASSISTANT'S ROUTER LANE AS DATA (specs/shop.md "The assistant"): assistant/intents.json, answers.json,
 * lexicon.json as strings, probe.bin (tools/fit.scala's) and combiner.bin (okay-mall's `Fit`) as Base64 — intents,
 * phrasings and answers are data, never code —, and the name of the static table they were fitted over (its
 * SHA-256's first 12 hex digits: a host fetches that table and no other) */
lazy val assistantData = Def.task {
  val base = (ThisBuild / baseDirectory).value / "assistant"
  def chunks(f: String): String = IO.read(base / f).grouped(20000).map(c => "\"\"\"" + c + "\"\"\"").mkString(",\n    ")
  val file = (Compile / sourceManaged).value / "okay" / "shop" / "assistant" / "AssistantData.scala"
  IO.write(file, "package okay.shop.assistant\n\n/** written by the build from assistant/ */\nobject AssistantData:\n" +
    Seq("intents" -> "intents.json", "answers" -> "answers.json", "lexicon" -> "lexicon.json").map { case (n, f) =>
      s"  val $n: String = Vector(\n    ${chunks(f)}).mkString\n" }.mkString +
    // the probe and the combiner as bytes (okay-facts' `Weights`), in Base64: read in well under a millisecond
    Seq("probe" -> "probe.bin", "combiner" -> "combiner.bin").map { case (n, f) =>
      val b64 = if ((base / f).exists) java.util.Base64.getEncoder.encodeToString(IO.readBytes(base / f)) else ""
      s"  val $n: String = Vector(\n    ${b64.grouped(20000).map(c => "\"" + c + "\"").mkString(",\n    ")}).mkString\n"
    }.mkString +
    "  val table: String = \"" + java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(base /
      "static.safetensors")).take(6).map(b => f"${b & 0xff}%02x").mkString + "\"\n")
  Seq(file)
}

/** THE ENGINE: the model, the content's reader, the effects and their handlers onto okay-serverless, the programs,
 * the pages of the storefront (given a site's look), of an order, the letters */
lazy val shop = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("shop"))
  .settings(name := "okay-shop",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-serverless" % okayServerless,
      // the assistant (specs/shop.md "The assistant"): the facts resolved, the model never handling a value
      "io.github.okay-effects" %%% "okay-facts" % "0.1.0",
      // its router lane (no model): rules, the static table, the probe (specs/shop.md "The assistant")
      "io.github.okay-effects" %%% "okay-facts-router" % "0.1.0", munit.value),
    Compile / sourceGenerators += assistantData.taskValue,
    Test / sourceGenerators += fixtures.taskValue)
  // the static table in the JVM's jar (a host on the JVM reads it there; a Worker's host puts it in its storage)
  .jvmSettings(Compile / resourceGenerators += Def.task {
    val to = (Compile / resourceManaged).value / "okay" / "shop" / "assistant" / "static.safetensors"
    IO.copyFile((ThisBuild / baseDirectory).value / "assistant" / "static.safetensors", to)
    Seq(to)
  }.taskValue)

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
