scalaVersion := "2.13.1"

val springbootVersion = "2.2.2.RELEASE"
val akkaVersion       = "2.5.23"

val disabledPlugins = if (sys.env.get("TRAVIS_TAG").filterNot(_.isEmpty).isDefined) {
  Seq()
} else {
  Seq(BintrayPlugin)
}

disablePlugins(disabledPlugins: _*)

libraryDependencies ++= Seq(
  "org.springframework"      % "spring-context"                      % "5.2.2.RELEASE",
  "org.springframework.boot" % "spring-boot-autoconfigure"           % springbootVersion,
  "org.springframework.boot" % "spring-boot-autoconfigure-processor" % springbootVersion,
  "org.hibernate"            % "hibernate-validator"                 % "6.0.16.Final",
  "javax.annotation"         % "javax.annotation-api"                % "1.3.2",
  "io.projectreactor"        % "reactor-core"                        % "3.3.1.RELEASE" % Optional,
  "org.springframework.boot" % "spring-boot-starter-test"            % springbootVersion % Test,
  "org.assertj"              % "assertj-core"                        % "3.8.0" % Test,
  "com.novocode"             % "junit-interface"                     % "0.11" % Test
)

resolvers ++= Seq(
  Resolver.jcenterRepo,
  Resolver.bintrayRepo("larousso", "maven")
)

crossPaths := false
