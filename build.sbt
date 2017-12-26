name := """izanami"""
organization := "fr.maif"
version := "0.0.1"
scalaVersion := "2.12.4"

lazy val root = (project in file("."))
  .aggregate(
    `izanami-server`,
    `izanami-clients`
  )
  .enablePlugins(NoPublish, GitVersioning, GitBranchPrompt)
  .disablePlugins(BintrayPlugin)

lazy val `izanami-documentation` = project
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

lazy val `izanami-server` = project

lazy val `izanami-clients` = project
