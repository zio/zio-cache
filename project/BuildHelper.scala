import explicitdeps.ExplicitDepsPlugin.autoImport._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt.Keys._
import sbt.{Def, _}
import sbtbuildinfo.BuildInfoKeys._
import sbtbuildinfo._
import sbtcrossproject.CrossPlugin.autoImport._
import scalafix.sbt.ScalafixPlugin.autoImport._

object BuildHelper {
  val silencerSettings: Seq[Def.Setting[Task[Seq[String]]]] =
    Seq(scalacOptions ++= {
      if (Keys.scalaVersion.value.startsWith("3"))
        Seq.empty
      else
        Seq("-P:silencer:globalFilters=[zio.stacktracer.TracingImplicits.disableAutoTrace]")
    })
}
