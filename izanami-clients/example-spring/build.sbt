organization := "fr.maif"
name := "example-spring"

mainClass := Some("izanami.example.Application")

libraryDependencies ++= Seq(
  "org.springframework.boot" % "spring-boot-starter-web"       % "1.5.8.RELEASE",
  "org.springframework.boot" % "spring-boot-starter-thymeleaf" % "1.5.8.RELEASE",
  "org.iq80.leveldb"         % "leveldb"                       % "0.9"
)
