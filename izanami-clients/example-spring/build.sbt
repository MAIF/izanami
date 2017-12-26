organization := "fr.maif"
name := "example-spring"
version := "1.0.0"

mainClass := Some("izanami.example.Application")

libraryDependencies ++= Seq(
  "org.springframework.boot" % "spring-boot-starter-web"       % "1.5.8.RELEASE",
  "org.springframework.boot" % "spring-boot-starter-thymeleaf" % "1.5.8.RELEASE"
)
