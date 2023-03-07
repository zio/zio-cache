import Versions._
import BuildHelper._

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name := "ZIO Cache",
    crossScalaVersions -= scala3.value,
    sbtBuildOptions  := List("-J-XX:+UseG1GC", "-J-Xmx4g", "-J-Xms2g", "-J-Xss16m"),
    ciBackgroundJobs := Seq("free --si -tmws 10"),
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
    stdSettings(name = "zio-cache", packageName = Some("zio.cache"), enableCrossProject = true),
    silencerSettings,
    enableZIO(),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCollectionCompatVersion
    )
  )

lazy val zioCacheJS = zioCache.js
  .settings(crossScalaVersions -= scala211.value, scalaJSUseMainModuleInitializer := true)

lazy val zioCacheJVM = zioCache.jvm
  .settings(
    crossScalaVersions += scala3.value,
    scala3Settings,
    scalaReflectTestSettings
  )

lazy val zioCacheNative = zioCache.native
  .settings(
    nativeSettings,
    crossScalaVersions -= scala211.value
  )

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
