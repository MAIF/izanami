scalaVersion := "2.13.1"

val springbootVersion = "2.2.2.RELEASE"

libraryDependencies ++= Seq(
  "org.springframework" % "spring-context" % "5.2.2.RELEASE",
//  "org.springframework.boot" % "spring-boot-starter"       % "2.2.2.RELEASE",
  "org.springframework.boot" % "spring-boot-autoconfigure"           % springbootVersion,
  "org.springframework.boot" % "spring-boot-autoconfigure-processor" % springbootVersion,
  "org.hibernate"            % "hibernate-validator"                 % "6.0.16.Final",
  "io.projectreactor"        % "reactor-core"                        % "3.3.1.RELEASE" % Optional,
  "org.springframework.boot" % "spring-boot-starter-test"            % springbootVersion % Test,
  "org.assertj"              % "assertj-core"                        % "3.8.0" % Test
)
