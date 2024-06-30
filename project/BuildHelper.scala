import sbt.Keys.*
import sbt.{Def, *}

object BuildHelper {
  val silencerSettings: Seq[Def.Setting[Task[Seq[String]]]] =
    Seq(scalacOptions ++= {
      if (Keys.scalaVersion.value.startsWith("3"))
        Seq.empty
      else
        Seq("-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
    })
}
