addSbtPlugin("dev.zio" % "zio-sbt-website"   % "0.3.10+10-bdffacd2-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % "0.3.10+10-bdffacd2-SNAPSHOT")

resolvers ++= Resolver.sonatypeOssRepos("public")
