val zioSbtVersion      = "0.4.0-alpha.28"
val scalaNativeVersion = "0.4.17"

addSbtPlugin(("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion).exclude("org.scala-native", "sbt-scala-native"))
addSbtPlugin("dev.zio"  % "zio-sbt-website"   % zioSbtVersion)
addSbtPlugin("dev.zio"  % "zio-sbt-ci"        % zioSbtVersion)

addSbtPlugin("org.scala-native" % "sbt-scala-native" % scalaNativeVersion)

resolvers ++= Resolver.sonatypeOssRepos("public")
