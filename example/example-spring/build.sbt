organization := "fr.maif"
name := "example-spring"

scalaVersion := Dependencies.scalaVersion

mainClass in (Compile, run) := Some("izanami.example.Application")

resolvers ++= Seq(
  Resolver.jcenterRepo,
  ("Artifactory Realm" at "https://s01.oss.sonatype.org/content/repositories/snapshots/").withAllowInsecureProtocol(
    true
  )
)

val springVersion = "2.4.4"

libraryDependencies ++= Seq(
  "org.springframework.boot"   % "spring-boot-starter-web"        % springVersion,
  "org.springframework.boot"   % "spring-boot-starter-thymeleaf"  % springVersion,
  "org.springframework.boot"   % "spring-boot-starter-actuator"   % springVersion,
  "org.springframework.boot"   % "spring-boot-starter-validation" % springVersion,
  "org.springframework.cloud"  % "spring-cloud-starter-config"    % "3.0.3",
  "com.fasterxml.jackson.core" % "jackson-databind"               % "2.12.0",
  "io.vavr"                    % "vavr-jackson"                   % "0.10.0",
  "com.auth0"                  % "java-jwt"                       % "3.11.0",
  "fr.maif"                    % "izanami-spring"                 % version.value,
  "org.iq80.leveldb"           % "leveldb"                        % "0.10"
)
