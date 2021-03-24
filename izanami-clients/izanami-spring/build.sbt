scalaVersion := Dependencies.scalaVersion

val springbootVersion = "2.2.2.RELEASE"

organization := Publish.organization

name := "izanami-spring"

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

crossPaths := false
