package fr.maif.izanami.models.features

import fr.maif.izanami.models.ProjectRightLevel
import fr.maif.izanami.models.ProjectRightLevel.{Update, Write}
import play.api.libs.json.{JsError, JsSuccess, Reads}

import scala.util.matching.Regex

sealed trait PatchOperation
case class PatchPath(id: String, path: PatchPathField) {}
sealed trait PatchPathField

case object Replace extends PatchOperation
case object Remove extends PatchOperation

case object Enabled extends PatchPathField
case object ProjectFeature extends PatchPathField
case object TagsFeature extends PatchPathField

case object RootFeature extends PatchPathField

sealed trait FeaturePatch {
  def op: PatchOperation
  def path: PatchPathField
  def id: String
  def requiredRight: ProjectRightLevel
}

case class EnabledFeaturePatch(
    value: Boolean,
    id: String,
    preserveProtectedContexts: Boolean
) extends FeaturePatch {
  override def op: PatchOperation = Replace
  override def path: PatchPathField = Enabled
  override def requiredRight: ProjectRightLevel = Update
}

case class ProjectFeaturePatch(value: String, id: String) extends FeaturePatch {
  override def op: PatchOperation = Replace
  override def path: PatchPathField = ProjectFeature
  override def requiredRight: ProjectRightLevel = Write
}

case class TagsFeaturePatch(value: Set[String], id: String)
    extends FeaturePatch {
  override def op: PatchOperation = Replace
  override def path: PatchPathField = TagsFeature
  override def requiredRight: ProjectRightLevel = Update
}

case class RemoveFeaturePatch(id: String) extends FeaturePatch {
  override def op: PatchOperation = Remove
  override def path: PatchPathField = RootFeature
  override def requiredRight: ProjectRightLevel = Write
}

object FeaturePatch {
  val ENABLED_PATH_PATTERN: Regex = "^/(?<id>\\S+)/enabled$".r
  val PROJECT_PATH_PATTERN: Regex = "^/(?<id>\\S+)/project$".r
  val TAGS_PATH_PATTERN: Regex = "^/(?<id>\\S+)/tags$".r
  val FEATURE_PATH_PATTERN: Regex = "^/(?<id>\\S+)$".r

  implicit val patchPathReads: Reads[PatchPath] = Reads[PatchPath] { json =>
    json
      .asOpt[String]
      .map {
        case ENABLED_PATH_PATTERN(id) =>
          PatchPath(id, Enabled)
        case PROJECT_PATH_PATTERN(id) => PatchPath(id, ProjectFeature)
        case TAGS_PATH_PATTERN(id)    => PatchPath(id, TagsFeature)
        case FEATURE_PATH_PATTERN(id) => PatchPath(id, RootFeature)
      }
      .map(path => JsSuccess(path))
      .getOrElse(JsError("Bad patch path"))
  }

  implicit val patchOpReads: Reads[PatchOperation] = Reads[PatchOperation] {
    json =>
      json
        .asOpt[String]
        .map {
          case "replace" => Replace
          case "remove"  => Remove
        }
        .map(op => JsSuccess(op))
        .getOrElse(JsError("Bad patch operation"))
  }

  implicit val featurePatchReads: Reads[FeaturePatch] = Reads[FeaturePatch] {
    json =>
      val maybeResult =
        for (
          op <- (json \ "op").asOpt[PatchOperation];
          path <- (json \ "path").asOpt[PatchPath]
        ) yield (op, path) match {
          case (Replace, PatchPath(id, Enabled)) =>
            (json \ "value").asOpt[Boolean].map(b => EnabledFeaturePatch(b, id, false)) // TODO hard coded preserveProtectedContext
          case (Replace, PatchPath(id, ProjectFeature)) =>
            (json \ "value").asOpt[String].map(b => ProjectFeaturePatch(b, id))
          case (Replace, PatchPath(id, TagsFeature)) =>
            (json \ "value")
              .asOpt[Set[String]]
              .map(b => TagsFeaturePatch(b, id))
          case (Remove, PatchPath(id, RootFeature)) =>
            Some(RemoveFeaturePatch(id))
          case (_, _) => None
        }
      maybeResult.flatten
        .map(r => JsSuccess(r))
        .getOrElse(JsError("Failed to read patch operation"))
  }
}
