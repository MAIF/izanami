resolvers += "ditaa-repo" at "https://raw.githubusercontent.com/larousso/sbt-ditaa/master/repository/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.6") // Apache 2.0

addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.12") // Apache 2.0

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC11") // Apache 2.0

addSbtPlugin("io.gatling" % "gatling-sbt" % "2.2.2") // Apache 2.0

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0") // Apache 2.0

addSbtPlugin("com.lightbend.paradox" % "sbt-paradox" % "0.3.1") // Apache 2.0

addSbtPlugin("com.adelegue" % "sbt-ditaa" % "0.1")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
