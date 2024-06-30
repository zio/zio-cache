import Versions.*
import BuildHelper.*
import zio.sbt.githubactions.Step

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

lazy val scalaV    = "2.13.14"
lazy val allScalas = List("2.12", "2.13", "3.3")

inThisBuild(
  List(
    name             := "ZIO Cache",
    zioVersion       := "2.1.4",
    scalaVersion     := scalaV,
    ciBackgroundJobs := Seq("free --si -tmws 10"),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),
    ciEnabledBranches    := Seq("series/2.x"),
    ciTargetJavaVersions := List("11", "21"),
    ciTargetScalaVersions := Map(
      (zioCacheJVM / thisProject).value.id    -> allScalas,
      (zioCacheJS / thisProject).value.id     -> allScalas,
      (zioCacheNative / thisProject).value.id -> allScalas
    ),
    versionScheme := Some("early-semver")
  )
)

addCommandAlias("benchmark", "benchmarks/Jmh/run")

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library")
  )
  .aggregate(
    zioCacheJVM,
    zioCacheJS,
    zioCacheNative,
    benchmarks,
    docs
  )

lazy val zioCache = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("zio-cache"))
  .settings(
    stdSettings(name = Some("zio-cache"), packageName = Some("zio.cache"), enableCrossProject = true),
    silencerSettings,
    enableZIO(),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCollectionCompatVersion
    ),
    scalacOptions ++=
      (if (scalaBinaryVersion.value == "3")
         Seq()
       else
         Seq(
           "-opt:l:method",
           "-opt:l:inline",
           "-opt-inline-from:scala.**"
         ))
  )

lazy val zioCacheJVM = zioCache.jvm
  .settings(
    scala3Settings,
    scalaReflectTestSettings,
    enableMimaSettingsJVM
  )

lazy val zioCacheJS = zioCache.js
  .settings(
    scalaJSUseMainModuleInitializer := true,
    enableMimaSettingsJS
  )

lazy val zioCacheNative = zioCache.native
  .settings(
    nativeSettings,
    enableMimaSettingsNative
  )

lazy val benchmarks = project
  .in(file("zio-cache-benchmarks"))
  .settings(stdSettings(name = Some("zio-cache-benchmarks"), packageName = Some("zio.cache")))
  .settings(
    publish / skip := true,
    enableZIO()
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("zio-cache-docs"))
  .settings(
    moduleName := "zio-cache-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := (ThisBuild / name).value,
    mainModuleName                             := (zioCacheJVM / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioCacheJVM),
    crossScalaVersions                         := Seq(scalaV),
    publish / skip                             := true
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(WebsitePlugin)

lazy val enforceMimaCompatibility = true // Enable / disable failing CI on binary incompatibilities

lazy val enableMimaSettingsJVM =
  Def.settings(
    mimaFailOnProblem     := enforceMimaCompatibility,
    mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet,
    mimaBinaryIssueFilters ++= Seq()
  )

lazy val enableMimaSettingsJS =
  Def.settings(
    mimaFailOnProblem     := enforceMimaCompatibility,
    mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %%% moduleName.value % _).toSet,
    mimaBinaryIssueFilters ++= Seq()
  )

lazy val enableMimaSettingsNative =
  Def.settings(
    mimaFailOnProblem     := enforceMimaCompatibility,
    mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %%% moduleName.value % _).toSet,
    mimaBinaryIssueFilters ++= Seq()
  )

ThisBuild / ciCheckArtifactsBuildSteps +=
  Step.SingleStep(
    "Check binary compatibility",
    run = Some(
      "sbt \"+zioCacheJVM/mimaReportBinaryIssues; +zioCacheJS/mimaReportBinaryIssues; +zioCacheNative/mimaReportBinaryIssues\""
    )
  )
