import Versions._
import BuildHelper._

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  List(
    name               := "ZIO Cache",
    scalaVersion       := Scala213,
    crossScalaVersions := Seq(Scala211, Scala212, Scala213),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),
    ciEnabledBranches := Seq("series/2.x"),
    supportedScalaVersions :=
      Map(
        (zioCacheJVM / thisProject).value.id    -> (zioCacheJVM / crossScalaVersions).value,
        (zioCacheJS / thisProject).value.id     -> (zioCacheJS / crossScalaVersions).value,
        (zioCacheNative / thisProject).value.id -> (zioCacheNative / crossScalaVersions).value
      )
  )
)

addCommandAlias("benchmark", "benchmarks/Jmh/run")

addCommandAlias("testJVM", ";zioCacheJVM/test")
addCommandAlias("testJS", ";zioCacheJS/test")
addCommandAlias("testNative", ";zioCacheNative/test:compile")

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
  .settings(stdSettings(packageName = "zio.cache", enableCrossProject = true, enableSilencer = true))
  .settings(silencerSettings)
  .settings(enableZIO(zioVersion, enableTesting = true))
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCollectionCompatVersion
    )
  )

lazy val zioCacheJS = zioCache.js
  .settings(
    name := "zio-cache-js",
    crossScalaVersions -= Scala211,
    libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
    scalaJSUseMainModuleInitializer   := true
  )

lazy val zioCacheJVM = zioCache.jvm
  .settings(crossScalaVersions += Scala3, libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion % Test)
  .settings(scalaReflectTestSettings(Scala213))

lazy val zioCacheNative = zioCache.native
  .settings(
    crossScalaVersions -= Scala211,
    Test / test             := (Test / compile).value,
    doc / skip              := true,
    Compile / doc / sources := Seq.empty
  )

lazy val benchmarks = project
  .in(file("zio-cache-benchmarks"))
  .settings(stdSettings(packageName = "zio.cache"))
  .settings(
    publish / skip := true
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
    projectName                                := (ThisBuild / name).value,
    mainModuleName                             := (zioCacheJVM / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioCacheJVM),
    publish / skip                             := true
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(WebsitePlugin)
