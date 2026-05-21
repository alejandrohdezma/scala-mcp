import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import laika.ast.InlineSVGIcon
import laika.ast.Path.Root
import laika.helium.config.Favicon
import laika.helium.config.IconLink
import laika.helium.config.TextLink
import laika.theme.config.Font
import laika.theme.config.FontDefinition
import laika.theme.config.FontStyle
import laika.theme.config.FontWeight
import laika.theme.config.Color.hex
import org.typelevel.scalacoptions.ScalacOptions

ThisBuild / scalaVersion := "3.3.4"

lazy val commonSettings = Seq(
  organization := "net.andimiller.mcp",
  version      := "0.12.0"
)

// scoverage instrumentation produces JVM-only bytecode, so coverage must be disabled on every
// non-JVM platform — otherwise dependent Scala.js builds (notably the explorer) fail to link.
lazy val noCoverage: Seq[Setting[?]] = Seq(coverageEnabled := false)

lazy val publishSettings = Seq(
  useGpg               := true,
  pomIncludeRepository := { _ => false },
  publishMavenStyle    := true,
  publishTo            := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if (isSnapshot.value) Some("central-snapshots".at(centralSnapshots))
    else localStaging.value
  },
  licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT")),
  scmInfo  := Some(
    ScmInfo(url("https://github.com/andimiller/scala-mcp"), "scm:git@github.com:andimiller/scala-mcp.git")
  ),
  homepage   := Some(url("https://github.com/andimiller/scala-mcp")),
  developers := List(
    Developer(
      id = "andimiller",
      name = "Andi Miller",
      email = "andi@andimiller.net",
      url = url("http://andimiller.net")
    )
  )
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-core",
    libraryDependencies ++= Seq(
      "org.typelevel"                 %%% "cats-effect"       % "3.7.0",
      "co.fs2"                        %%% "fs2-core"          % "3.13.0",
      "io.circe"                      %%% "circe-core"        % "0.14.14",
      "io.circe"                      %%% "circe-generic"     % "0.14.14",
      "io.circe"                      %%% "circe-parser"      % "0.14.14",
      "com.softwaremill.sttp.apispec" %%% "apispec-model"     % "0.11.10",
      "com.softwaremill.sttp.apispec" %%% "jsonschema-circe"  % "0.11.10",
      "com.lihaoyi"                   %%% "sourcecode"        % "0.4.2",
      "org.scodec"                    %%% "scodec-core"       % "2.3.3",
      "org.scalameta"                 %%% "munit"             % "1.0.0" % Test,
      "org.typelevel"                 %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jvmSettings(
    // JVM-specific settings
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)

lazy val stdio = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/stdio"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-stdio",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"             % "1.0.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)
  .dependsOn(core)
  .settings(libraryDependencies ++= Seq("co.fs2" %%% "fs2-io" % "3.13.0"))

lazy val http4s = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/http4s"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-http4s",
    libraryDependencies ++= Seq(
      "org.http4s"         %%% "http4s-dsl"          % "0.23.34",
      "org.http4s"         %%% "http4s-ember-server" % "0.23.34",
      "org.http4s"         %%% "http4s-client"       % "0.23.34",
      "org.http4s"         %%% "http4s-circe"        % "0.23.34",
      "org.http4s"         %%% "http4s-ember-client" % "0.23.34" % Test,
      "org.scalameta"      %%% "munit"               % "1.0.0"   % Test,
      "org.typelevel"      %%% "munit-cats-effect"   % "2.2.0"   % Test,
      "com.alejandrohdezma" %% "http4s-munit"        % "2.0.0"   % Test
    )
  )
  .jvmSettings(
    // Bundle the Scala.js explorer SPA into the JVM build's resources so the embedded http4s
    // server can serve it under /explorer. Native and JS builds don't ship the SPA.
    Compile / resourceGenerators += Def.task {
      val _ = (LocalRootProject / buildExplorer).value

      val distDir   = (explorer / baseDirectory).value / "dist"
      val targetDir = (Compile / resourceManaged).value / "explorer"
      IO.delete(targetDir)
      IO.copyDirectory(distDir, targetDir)

      Path.allSubpaths(targetDir).map(_._1).toSeq
    }.taskValue
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)
  .dependsOn(core)

lazy val exampleDice = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/example-dice-mcp"))
  .settings(commonSettings)
  .settings(
    name                 := "example-dice-mcp",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-parse"      % "1.1.0",
      "org.typelevel" %%% "cats-effect-std" % "3.7.0",
      "org.scalameta" %%% "munit"           % "1.0.0" % Test
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)
  .dependsOn(core, stdio, goldenMunit % Test)

lazy val exampleClient = project
  .in(file("modules/example-client"))
  .settings(commonSettings)
  .settings(
    name                 := "example-client",
    publish / skip       := true,
    Compile / mainClass  := Some("net.andimiller.mcp.examples.client.McpCliClient"),
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline-effect"      % "2.6.1",
      "org.http4s"   %% "http4s-ember-client" % "0.23.34"
    )
  )
  .dependsOn(core.jvm, stdio.jvm, http4s.jvm)

lazy val exampleHarness = crossProject(JVMPlatform, NativePlatform)
  .in(file("modules/example-harness"))
  .settings(commonSettings)
  .settings(
    name                 := "example-harness",
    publish / skip       := true,
    Compile / mainClass  := Some("net.andimiller.mcp.examples.harness.Main"),
    libraryDependencies ++= Seq(
      "com.monovore" %%% "decline-effect"      % "2.6.1",
      "org.http4s"   %%% "http4s-ember-client" % "0.23.34",
      "com.lihaoyi"  %%% "fansi"               % "0.5.1"
    )
  )
  .nativeSettings(noCoverage *)
  .dependsOn(core, stdio, http4s)

lazy val examplePomodoro = project
  .in(file("modules/example-pomodoro-mcp"))
  .settings(commonSettings)
  .settings(
    name                 := "example-pomodoro-mcp",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    ),
    assembly / mainClass                  := Some("net.andimiller.mcp.examples.pomodoro.PomodoroMcpServer"),
    assembly / assemblyJarName            := "pomodoro-mcp",
    assembly / assemblyPrependShellScript := Some(sbtassembly.AssemblyPlugin.defaultShellScript),
    assembly / assemblyMergeStrategy      := {
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.first
      case x if x.endsWith("module-info.class")                     => MergeStrategy.discard
      case x                                                        =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
  .dependsOn(core.jvm, http4s.jvm, redis, goldenMunit.jvm % Test)

lazy val exampleNotebook = project
  .in(file("modules/example-shared-notebook-mcp"))
  .settings(commonSettings)
  .settings(
    name                 := "example-shared-notebook-mcp",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, goldenMunit.jvm % Test)

lazy val exampleChat = project
  .in(file("modules/example-chat-mcp"))
  .settings(commonSettings)
  .settings(
    name                 := "example-chat-mcp",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, redis)

lazy val exampleRpgCharacterCreator = project
  .in(file("modules/example-rpg-character-creator"))
  .settings(commonSettings)
  .settings(
    name                 := "example-rpg-character-creator",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback"  % "logback-classic" % "1.5.6",
      "net.andimiller" %% "enumerive-circe" % "1.0.0"
    )
  )
  .dependsOn(core.jvm, http4s.jvm, goldenMunit.jvm % Test)

lazy val exampleDns = project
  .in(file("modules/example-dns-mcp"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    name           := "example-dns-mcp",
    publish / skip := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true
  )
  .dependsOn(core.js, http4s.js)

lazy val explorer = project
  .in(file("modules/explorer"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    scalaVersion   := "3.6.4",
    name           := "mcp-explorer",
    publish / skip := true,
    // scoverage instrumentation produces JVM-only bytecode that fails to link under Scala.js.
    // The explorer is a Scala.js UI; we don't measure its coverage anyway.
    coverageEnabled := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass             := Some("net.andimiller.mcp.explorer.Main"),
    libraryDependencies            ++= Seq(
      "io.indigoengine" %%% "tyrian-io"    % "0.14.0",
      "org.http4s"      %%% "http4s-dom"   % "0.2.1",
      "org.http4s"      %%% "http4s-circe" % "0.23.34"
    )
  )
  .dependsOn(core.js)

lazy val buildExplorer = taskKey[Unit]("Build explorer for production")

buildExplorer := {
  val log = streams.value.log

  // 1. Compile Scala.js (fast link — Parcel handles minification)
  log.info("Compiling explorer Scala.js...")
  (explorer / Compile / fastLinkJS).value

  // 2. Run yarn build in explorer directory
  log.info("Bundling with Parcel...")
  val explorerDir = (explorer / baseDirectory).value
  val exitCode    = scala.sys.process.Process("yarn" :: "build" :: Nil, explorerDir).!
  if (exitCode != 0) sys.error("yarn build failed")

  log.info("Explorer build complete.")
}

lazy val goldenMunit = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/golden-munit"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-golden-munit",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit"             % "1.0.0",
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0",
      "co.fs2"        %%% "fs2-io"            % "3.13.0"
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)
  .dependsOn(core)

lazy val openapi = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("modules/openapi"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-openapi",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %%% "openapi-model"     % "0.11.10",
      "com.softwaremill.sttp.apispec" %%% "openapi-circe"     % "0.11.10",
      "org.scalameta"                 %%% "munit"             % "1.0.0" % Test,
      "org.typelevel"                 %%% "munit-cats-effect" % "2.2.0" % Test
    )
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "io.circe"                    %% "circe-yaml"         % "1.15.0"  % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.40" % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % "1.11.40" % Test
    )
  )
  .jsSettings(noCoverage *)
  .nativeSettings(noCoverage *)
  .dependsOn(core)

lazy val redis = project
  .in(file("modules/redis"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-redis",
    libraryDependencies ++= Seq(
      "dev.profunktor" %% "redis4cats-effects" % "2.0.3",
      "dev.profunktor" %% "redis4cats-streams" % "2.0.3"
    )
  )
  .dependsOn(core.jvm, http4s.jvm)

lazy val trace4cats = project
  .in(file("modules/trace4cats"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-trace4cats",
    libraryDependencies ++= Seq(
      "io.janstenpickle" %% "trace4cats-core"    % "0.14.7",
      "io.janstenpickle" %% "trace4cats-iolocal" % "0.14.7" % Test,
      "io.janstenpickle" %% "trace4cats-testkit" % "0.14.7" % Test,
      "org.scalameta"    %% "munit"              % "1.0.0"  % Test,
      "org.typelevel"    %% "munit-cats-effect"  % "2.2.0"  % Test
    )
  )
  .dependsOn(core.jvm)

lazy val tapir = project
  .in(file("modules/tapir"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name                 := "mcp-tapir",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"         % "1.11.40",
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % "1.11.40",
      "org.scalameta"               %% "munit"              % "1.0.0" % Test,
      "org.typelevel"               %% "munit-cats-effect"  % "2.2.0" % Test
    )
  )
  .dependsOn(core.jvm)

lazy val openapiMcpProxy = project
  .in(file("modules/openapi-mcp-proxy"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    buildInfoKeys        := Seq[BuildInfoKey](name, version),
    buildInfoPackage     := "net.andimiller.mcp.openapi",
    name                 := "openapi-mcp-proxy",
    publish / skip       := true,
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-yaml"          % "1.15.0",
      "org.http4s"    %% "http4s-ember-client" % "0.23.34",
      "org.http4s"    %% "http4s-circe"        % "0.23.34",
      "com.monovore"  %% "decline-effect"      % "2.6.1",
      "ch.qos.logback" % "logback-classic"     % "1.5.6",
      "org.scalameta" %% "munit"               % "1.0.0" % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.2.0" % Test
    ),
    assembly / mainClass                  := Some("net.andimiller.mcp.openapi.OpenApiMcpServer"),
    assembly / assemblyJarName            := "openapi-mcp-proxy",
    assembly / assemblyPrependShellScript := Some(sbtassembly.AssemblyPlugin.defaultShellScript),
    assembly / assemblyMergeStrategy      := {
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.first
      case x if x.endsWith("module-info.class")                     => MergeStrategy.discard
      case x                                                        =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
  .dependsOn(core.jvm, stdio.jvm, openapi.jvm)

lazy val llmsTxtSiteUrl = settingKey[String]("Public site URL used in llms.txt links")

lazy val llmsTxtTagline = settingKey[String]("One-line project description used in llms.txt and llms-full.txt")

lazy val generateLlmsArtifacts =
  taskKey[Unit]("Generate llms.txt, llms-full.txt and copy raw markdown alongside the Laika site output")

lazy val docs = project
  .in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    mdocIn         := baseDirectory.value / "docs",
    tlSiteApiUrl   := Some(url("https://andimiller.github.io/scala-mcp/api/")),
    tlSiteHelium   := {
      val inter = Font.withWebCSS(
        "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap"
      )
      val jbMono = Font.withWebCSS(
        "https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&display=swap"
      )

      // Inline the logo as an SVG icon so Helium can render it alongside the
      // "scala-mcp" text in the top nav (IconLink supports icon + label;
      // ImageLink is image-only). currentColor on the stroke lets the ring
      // adapt to light vs dark theme.
      // class="svg-icon" + width/height="100%" matches Helium's built-in icons,
      // which CSS sizes to 1.7em × 1.7em. Without this the browser falls back
      // to the viewBox dimensions (180×180) and the logo overflows the nav.
      val logoSvg =
        """<svg class="svg-icon" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180" aria-hidden="true">
          |  <defs>
          |    <linearGradient id="scala-mcp-grad" x1="0" y1="0" x2="1" y2="1">
          |      <stop offset="0%" stop-color="#AA3DFA"/>
          |      <stop offset="100%" stop-color="#3A87D4"/>
          |    </linearGradient>
          |  </defs>
          |  <circle cx="90" cy="90" r="76" fill="none" stroke="currentColor" stroke-width="6"/>
          |  <circle cx="90" cy="56" r="11" fill="url(#scala-mcp-grad)"/>
          |  <circle cx="60.55" cy="107" r="11" fill="url(#scala-mcp-grad)"/>
          |  <circle cx="119.45" cy="107" r="11" fill="url(#scala-mcp-grad)"/>
          |</svg>""".stripMargin

      tlSiteHelium.value.site
        .metadata(
          title = Some("scala-mcp"),
          language = Some("en")
        )
        .site
        .internalCSS(Root / "css" / "scala-mcp.css")
        .site
        .topNavigationBar(
          homeLink = IconLink.internal(
            Root / "index.md",
            InlineSVGIcon(logoSvg, Some("scala-mcp")),
            text = Some("scala-mcp")
          ),
          navLinks = Seq(
            TextLink.external("https://andimiller.github.io/scala-mcp/llms.txt", "llms.txt")
          )
        )
        .site
        .favIcons(
          Favicon.internal(Root / "images" / "favicon-32.png", sizes = "32x32"),
          Favicon.internal(Root / "images" / "favicon-16.png", sizes = "16x16"),
          Favicon.internal(Root / "images" / "favicon-180.png", sizes = "180x180")
        )
        .site
        .clearFontResources
        .site
        .addFontResources(
          FontDefinition(inter, "Inter", FontWeight.Normal, FontStyle.Normal),
          FontDefinition(jbMono, "JetBrains Mono", FontWeight.Normal, FontStyle.Normal)
        )
        // fontFamilies args are positional: body, headlines, code.
        .site
        .fontFamilies("Inter", "Inter", "JetBrains Mono")
        // themeColors args are positional: primary, primaryMedium, primaryLight,
        // secondary, text, background, bgGradient. Named args fail under sbt's
        // Scala 2.12 due to overload ambiguity with the inherited ColorOps method.
        .site
        .themeColors(
          hex("266BB0"),                 // primary
          hex("71A8E0"),                 // primaryMedium
          hex("F7FAFD"),                 // primaryLight
          hex("8706E5"),                 // secondary
          hex("1A1A1A"),                 // text
          hex("FFFFFF"),                 // background
          (hex("AA3DFA"), hex("3A87D4")) // bgGradient (logo gradient)
        )
        .site
        .darkMode
        .themeColors(
          hex("71A8E0"),                 // primary
          hex("266BB0"),                 // primaryMedium
          hex("133658"),                 // primaryLight
          hex("CB88FC"),                 // secondary
          hex("FFFFFF"),                 // text
          hex("0A1C2E"),                 // background
          (hex("CB88FC"), hex("71A8E0")) // bgGradient
        )
    },
    laikaConfig := laikaConfig.value.withConfigValue("version", version.value),
    // Docs snippets often build values for illustration without using them.
    tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
    llmsTxtSiteUrl         := "https://andimiller.github.io/scala-mcp/",
    llmsTxtTagline         :=
      "Cross-platform Scala 3 library for building Model Context Protocol (MCP) servers. Cats Effect, automatic JSON Schema derivation, JVM/JS/Native.",
    generateLlmsArtifacts := {
      // .value on laikaSite forces the site build first; we read its target dir afterwards.
      val _          = laikaSite.value
      val mdocOutDir = mdocOut.value
      val siteOutDir = (laikaSite / target).value
      LlmsTxt.run(
        mdocOut = mdocOutDir,
        siteOut = siteOutDir,
        siteUrl = llmsTxtSiteUrl.value,
        projectName = (LocalRootProject / name).value,
        tagline = llmsTxtTagline.value,
        log = streams.value.log
      )
    },
    // tlSite (the user-facing build task) runs mdoc, then laikaSite. We extend it so the
    // LLM-friendly artifacts are produced as part of the same invocation.
    tlSite := Def.sequential(mdoc.toTask(""), laikaSite, generateLlmsArtifacts).value
  )
  .dependsOn(
    core.jvm, stdio.jvm, http4s.jvm, openapi.jvm, goldenMunit.jvm, tapir, redis, trace4cats
  )

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name           := "scala-mcp",
    publish / skip := true
  )
  .aggregate(
    core.jvm, core.js, core.native, stdio.jvm, stdio.js, stdio.native, exampleDice.jvm, exampleDice.js,
    exampleDice.native, http4s.jvm, http4s.js, http4s.native, redis, trace4cats, tapir, examplePomodoro, exampleChat,
    exampleClient, exampleHarness.jvm, exampleHarness.native, exampleDns, exampleNotebook, exampleRpgCharacterCreator,
    explorer, goldenMunit.jvm, goldenMunit.js, goldenMunit.native, openapi.jvm, openapi.js, openapi.native,
    openapiMcpProxy
  )
