import Versions._
import BuildHelper._

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name               := "ZIO Cache",
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
  .settings(
    stdSettings(name = "zio-cache", packageName = Some("zio.cache"), enableCrossProject = true)
  )
  .settings(silencerSettings)
  .settings(enableZIO())
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCollectionCompatVersion
    )
  )

lazy val zioCacheJS = zioCache.js
  .settings(
    name := "zio-cache-js",
    crossScalaVersions -= scala211.value,
    libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test,
    scalaJSUseMainModuleInitializer   := true
  )

lazy val zioCacheJVM = zioCache.jvm
  .settings(scala3Settings)
  .settings(libraryDependencies += "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test)
  .settings(scalaReflectTestSettings)

lazy val zioCacheNative = zioCache.native
  .settings(nativeSettings)
  .settings(crossScalaVersions -= scala211.value)

lazy val benchmarks = project
  .in(file("zio-cache-benchmarks"))
  .settings(stdSettings(name = "zio-cache-benchmarks", packageName = Some("zio.cache")))
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
    crossScalaVersions -= scala211.value,
    projectName                                := (ThisBuild / name).value,
    mainModuleName                             := (zioCacheJVM / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioCacheJVM),
    publish / skip                             := true
  )
  .dependsOn(zioCacheJVM)
  .enablePlugins(WebsitePlugin)
