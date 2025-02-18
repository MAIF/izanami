package fr.maif.izanami.models

sealed trait ExportedType {
  def order: Int
  def table: String
  def displayName: String
}
case object ScriptType         extends ExportedType {
  override def order: Int          = 0
  override def table: String       = "wasm_script_configurations"
  override def displayName: String = "WASM Script"
}
case object ProjectType        extends ExportedType {
  override def order: Int          = 1
  override def table: String       = "projects"
  override def displayName: String = "Project"
}
case object KeyType            extends ExportedType {
  override def order: Int          = 2
  override def table: String       = "apikeys"
  override def displayName: String = "Key"
}
case object KeyProjectType     extends ExportedType {
  override def order: Int          = 3
  override def table: String       = "apikeys_projects"
  override def displayName: String = "Key right on project"
}

case object ContextType extends ExportedType {
  override def order: Int          = 4
  override def table: String       = "new_contexts"
  override def displayName: String = "Context"
}

case object GlobalContextType  extends ExportedType {
  override def order: Int          = 5
  override def table: String       = "global_feature_contexts"
  override def displayName: String = "Global context"
}
case object LocalContextType   extends ExportedType {
  override def order: Int          = 6
  override def table: String       = "feature_contexts"
  override def displayName: String = "Local context"
}

case object WebhookType        extends ExportedType {
  override def order: Int          = 7
  override def table: String       = "webhooks"
  override def displayName: String = "Webhook"
}
case object TagType            extends ExportedType {
  override def order: Int          = 8
  override def table: String       = "tags"
  override def displayName: String = "Tag"
}
case object FeatureType        extends ExportedType {
  override def order: Int          = 9
  override def table: String       = "features"
  override def displayName: String = "Feature"
}
case object FeatureTagType     extends ExportedType {
  override def order: Int          = 10
  override def table: String       = "features_tags"
  override def displayName: String = "Feature tags link"
}
case object OverloadType       extends ExportedType {
  override def order: Int          = 11
  override def table: String       = "feature_contexts_strategies"
  override def displayName: String = "Feature overload"
}
case object ProjectRightType   extends ExportedType {
  override def order: Int          = 12
  override def table: String       = "users_projects_rights"
  override def displayName: String = "User right on project"
}
case object KeyRightType       extends ExportedType {
  override def order: Int = 13

  override def table: String       = "users_keys_rights"
  override def displayName: String = "User right on key"
}
case object WebhookRightType   extends ExportedType {
  override def order: Int = 14

  override def table: String       = "users_webhooks_rights"
  override def displayName: String = "User right on webhook"
}
case object WebhookFeatureType extends ExportedType {
  override def order: Int = 15

  override def table: String       = "webhooks_features"
  override def displayName: String = "Webhook feature link"
}
case object WebhookProjectType extends ExportedType {
  override def order: Int          = 16
  override def table: String       = "webhooks_projects"
  override def displayName: String = "Webhook project link"
}

object ExportedType {
  val exportedTypeToExportedNameAssociation: Seq[(String, ExportedType)] = Seq(
    ("project", ProjectType),
    ("feature", FeatureType),
    ("tag", TagType),
    ("feature_tag", FeatureTagType),
    ("overload", OverloadType),
    ("local_context", LocalContextType), // Legacy, for compat reasons with old export format
    ("global_context", GlobalContextType),  // Legacy, for compat reasons with old export format
    ("context", ContextType),
    ("key", KeyType),
    ("apikey_project", KeyProjectType),
    ("webhook", WebhookType),
    ("webhook_feature", WebhookFeatureType),
    ("webhook_project", WebhookProjectType),
    ("user_webhook_right", WebhookRightType),
    ("project_right", ProjectRightType),
    ("key_right", KeyRightType),
    ("script", ScriptType)
  )

  def parseExportedType(typestr: String): Option[ExportedType] = {
    exportedTypeToExportedNameAssociation.find(t => t._1 == typestr).map(t => t._2)
  }

  def exportedTypeToString(exportedType: ExportedType): Option[String] = {
    exportedTypeToExportedNameAssociation.find(t => t._2 == exportedType).map(t => t._1)
  }
}
