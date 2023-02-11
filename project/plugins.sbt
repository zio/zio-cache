addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % "0.3.10+30-fb898d62-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-website"   % "0.3.10+30-fb898d62-SNAPSHOT")
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % "0.3.10+30-fb898d62-SNAPSHOT")

resolvers ++= Resolver.sonatypeOssRepos("public")
