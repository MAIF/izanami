organization := "fr.maif"
name := "example-spring"

scalaVersion := "2.13.3"

mainClass := Some("izanami.example.Application")

resolvers ++= Seq(
  Resolver.jcenterRepo,
  ("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local").withAllowInsecureProtocol(true)
)

val springVersion = "2.4.1.RELEASE"

libraryDependencies ++= Seq(
  "org.springframework.boot"   % "spring-boot-starter-web"       % springVersion,
  "org.springframework.boot"   % "spring-boot-starter-thymeleaf" % springVersion,
  "org.springframework.boot"   % "spring-boot-starter-actuator"  % springVersion,
  "org.springframework.cloud"  % "spring-cloud-starter-config"   % "2.1.4.RELEASE",
  "com.fasterxml.jackson.core" % "jackson-databind"              % "2.12.0",
  "io.vavr"                    % "vavr-jackson"                  % "0.10.0",
  "com.auth0"                  % "java-jwt"                      % "3.11.0",
  "fr.maif"                    % "izanami-spring"                % version.value,
  "org.iq80.leveldb"           % "leveldb"                       % "0.10"
)
