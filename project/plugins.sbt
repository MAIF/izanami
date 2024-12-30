addSbtPlugin("com.typesafe.play" % "sbt-plugin"    % "2.9.0")
// addSbtPlugin("org.foundweekends.giter8" % "sbt-giter8-scaffold" % "0.13.1")
addDependencyTreePlugin
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"  % "0.14.5")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("com.github.sbt"    % "sbt-release"   % "1.3.0")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"       % "2.1.2")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype"  % "3.11.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scala3-migrate" % "0.6.2")

// See https://github.com/scala/bug/issues/12632
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
