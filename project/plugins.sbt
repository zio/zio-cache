val zioSbtVersion = "0.3.10+27-6fff2aa2-SNAPSHOT"

addSbtPlugin("dev.zio"      % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio"      % "zio-sbt-website"   % zioSbtVersion)
addSbtPlugin("dev.zio"      % "zio-sbt-ci"        % zioSbtVersion)

resolvers ++= Resolver.sonatypeOssRepos("public")
