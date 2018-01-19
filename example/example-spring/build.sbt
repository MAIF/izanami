organization := "fr.maif"
name := "example-spring"

mainClass := Some("izanami.example.Application")

resolvers ++= Seq(
  "Jfrog OSS" at "https://oss.jfrog.org/artifactory/libs-snapshot",
  "jsonlib-repo" at "https://raw.githubusercontent.com/mathieuancelin/json-lib-javaslang/master/repository/releases"
)

libraryDependencies ++= Seq(
  "org.springframework.boot"   % "spring-boot-starter-web"       % "1.5.8.RELEASE",
  "org.springframework.boot"   % "spring-boot-starter-thymeleaf" % "1.5.8.RELEASE",
  "com.fasterxml.jackson.core" % "jackson-databind"              % "2.9.3",
  "io.vavr"                    % "vavr-jackson"                  % "0.9.2",
  "fr.maif"                    %% "izanami-client"               % "1.0.1-SNAPSHOT",
  "org.iq80.leveldb"           % "leveldb"                       % "0.9"
)
