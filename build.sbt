import Versions._
import BuildHelper._

enablePlugins(EcosystemPlugin)

Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  List(
    organization       := "dev.zio",
    homepage           := Some(url("https://zio.dev/zio-cache/")),
    licenses           := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    crossScalaVersions := Seq(Scala211, Scala212, Scala213, Scala3),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    )
  )
)

addCommandAlias("benchmark", "benchmarks/Jmh/run")

addCommandAlias("testJVM", ";zioCacheJVM/test")
addCommandAlias("testJS", ";zioCacheJS/test")
addCommandAlias("testNative", ";zioCacheNative/test:compile")

val zioVersion = "2.0.6"

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
    stdSettings(
      name = "zio-cache",
      packageName = "zio.cache",
      scalaVersion = Scala213,
      crossScalaVersions = Seq(Scala211, Scala212, Scala213),
      enableCrossProject = true,
      enableSilencer = true
    )
  )
  .settings(silencerSettings)
  .settings(enableZIO(zioVersion, enableTesting = true))
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.9.0"
    )
  )
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))

lazy val zioCacheJS = zioCache.js
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .settings(crossScalaVersions := Seq(Scala211, Scala212, Scala213))
  .settings(
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library")
  )
  .settings(scalaJSUseMainModuleInitializer := true)

lazy val zioCacheJVM = zioCache.jvm
  .settings(enableScala3(Scala3, Scala213))
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .settings(scalaReflectTestSettings(Scala213))

lazy val zioCacheNative = zioCache.native
  .settings(nativeSettings)

lazy val benchmarks = project
  .in(file("zio-cache-benchmarks"))
  .settings(
    stdSettings(
      name = "zio-cache",
      packageName = "zio.cache.benchmark",
      scalaVersion = Scala213,
      crossScalaVersions = Seq(Scala211, Scala212, Scala213)
    )
  )
  .settings(
    publish / skip := true,
    moduleName     := "zio-cache-benchmark"
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("zio-cache-docs"))
  .settings(
    moduleName := "zio-cache-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    crossScalaVersions                         := Seq(Scala212, Scala213),
    projectName                                := "ZIO Cache",
    mainModuleName                             := (zioCacheJVM / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioCacheJVM),
    docsPublishBranch                          := "series/2.x"
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(WebsitePlugin)
