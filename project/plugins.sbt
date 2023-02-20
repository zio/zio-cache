addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % "0.3.10+24-f21e95cf-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-website"   % "0.3.10+24-f21e95cf-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % "0.3.10+24-f21e95cf-SNAPSHOT")

resolvers ++= Resolver.sonatypeOssRepos("public")
