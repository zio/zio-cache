addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % "0.3.10+20-efa02d14-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-website"   % "0.3.10+20-efa02d14-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % "0.3.10+20-efa02d14-SNAPSHOT")

resolvers ++= Resolver.sonatypeOssRepos("public")
