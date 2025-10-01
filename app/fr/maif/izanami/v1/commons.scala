package fr.maif.izanami.v1

import fr.maif.izanami.models.{ProjectAtomicRight, ProjectRightLevel, RightLevel, TenantRight}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsError, JsSuccess, Reads}

sealed trait Right
case object Read   extends Right
case object Update extends Right
case object Create extends Right
case object Delete extends Right

case class AuthorizedPattern(pattern: String, rights: Seq[Right])

object OldCommons {
  def toNewRights(
      admin: Boolean,
      patterns: Seq[AuthorizedPattern],
      projects: Set[String],
      shouldFilterProjects: Boolean
  ): TenantRight = {
    // TODO handle wildcard in rights (by allowing to read db with regexp ? if so features must be inserted first)
    val projectRights = patterns
      .map(ap => {
        for (
          filteredProjects <- if (shouldFilterProjects) filterProjects(ap.pattern, projects) else projects;
          right            <- oldRightToNewProjectRight(ap.rights.toSet)
        ) yield (filteredProjects, right)
      })
      .flatMap(_.toSeq)
      .groupBy { case (key, _) => key }
      .view
      .mapValues(s =>
        s.map { case (_, level) => level }
          .reduce((r1, r2) => if (ProjectRightLevel.superiorOrEqualLevels(r1).contains(r2)) r2 else r1)
      )
      .view
      .mapValues(ProjectAtomicRight.apply)

    val tenantRight = if (admin) {
      RightLevel.Admin
    } else if (patterns.map(p => p.pattern).contains("*")) {
      RightLevel.Write
    } else {
      RightLevel.Read
    }

    TenantRight(level = tenantRight, projects = projectRights.toMap)
  }

  def filterProjects(pattern: String, projects: Set[String]): Set[String] = {
    if (pattern.trim == "*") {
      projects
    } else if (pattern.contains("*")) {
      var regexp = pattern.replace("*", ".*")
      if (regexp.endsWith(":.*")) {
        regexp = regexp.dropRight(3).concat("(:.*)?")
      }
      projects.filter(p => regexp.r.matches(p))
    } else {
      // TODO people with right on only one feature should have right on create project if feature is alone in it
      Set()
    }
  }

  implicit val rightReads: Reads[Right] = { json =>
    (for (
      str <- json.asOpt[String];
      if str.length == 1;
      c   <- str.charAt(0).toUpper.option
    ) yield c match {
      case 'R' => JsSuccess(Read)
      case 'U' => JsSuccess(Update)
      case 'C' => JsSuccess(Create)
      case 'D' => JsSuccess(Delete)
      case _   => JsError(s"Unknown right level letter : ${c}")
    }).getOrElse(JsError(s"Right string too long ${json}"))
  }

  def oldRightToNewProjectRight(right: Set[Right]): Option[ProjectRightLevel] = {
    val OLD_ALL_RIGHTS: Set[Right] = Set(Read, Update, Delete, Create)

    if (right.isEmpty) {
      None
    } else if (OLD_ALL_RIGHTS.subsetOf(right)) {
      ProjectRightLevel.Admin.some
    } else if (right.contains(Read) && right.size == 1) {
      ProjectRightLevel.Read.some
    } else if (right.contains(Update) && !right.contains(Create)) {
      ProjectRightLevel.Update.some
    } else {
      ProjectRightLevel.Write.some
    }
  }

  def oldRightToNewRight(right: Set[Right]): Option[RightLevel] = {
    val OLD_ALL_RIGHTS: Set[Right] = Set(Read, Update, Delete, Create)

    if (right.isEmpty) {
      None
    } else if (OLD_ALL_RIGHTS.subsetOf(right)) {
      RightLevel.Admin.some
    } else if (right.contains(Read) && right.size == 1) {
      RightLevel.Read.some
    } else {
      RightLevel.Write.some
    }
  }

  implicit val authorizedPatternReads: Reads[AuthorizedPattern] = { json =>
    (for (
      pattern <- (json \ "pattern").asOpt[String];
      rights  <- (json \ "rights").asOpt[Seq[Right]]
    ) yield JsSuccess(AuthorizedPattern(pattern = pattern, rights = rights))).getOrElse(JsError("Bad right format"))
  }
}
