resolvers ++= Seq(
  Resolver.jcenterRepo,
  Resolver.bintrayIvyRepo("sohoffice", "sbt-plugins")
)

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.1") // Apache 2.0

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.3.2") // Apache 2.0

addSbtPlugin("io.gatling" % "gatling-sbt" % "3.0.0") // Apache 2.0

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2") // Apache 2.0

addSbtPlugin("com.lightbend.paradox" % "sbt-paradox" % "0.6.9") // Apache 2.0

addSbtPlugin("com.adelegue" % "sbt-ditaa" % "0.2") // Apache 2.0

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10") // Apache 2.0

addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1") // Apache 2.0

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.2") // Apache 2.0

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0") // Apache 2.0

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3") // Apache 2.0

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.7") // Apache 2.0

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.0") // Apache 2.0

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.4")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.15")

//addSbtPlugin("com.sohoffice" %% "sbt-descriptive-play-swagger" % "0.7.5")
addSbtPlugin("com.iheart" % "sbt-play-swagger" % "0.9.1-PLAY2.8")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
