package fr.maif.izanami.errors

import io.vertx.pgclient.PgException

object PostgresErrorMapper {
  private val NULL_FIELD_ERROR_CODE = "23502";
  // TODO in dev mode scan constraints at startup to make sure they still exist
  val izanamiErrorsByConstraintName: Map[String, IzanamiError] = Map(
    // Features
    "featuresnamesize" -> FeatureFieldTooLong,
    "feature_result_type_value_check" -> IncompatibleResultTypeAndValue,
    "feature_type_xor" -> FeatureMustHaveConditionOrScript,
    "value_script_config" -> IncompatibleResultTypeAndValue,
    "features_pkey" -> FeatureWithThisIdAlreadyExist,
    "unique_feature_name_for_project" -> FeatureWithThisNameAlreadyExist,
    "features_project_fkey" -> FeatureProjectDoesNotExist,
    "features_script_config_fkey" -> FeatureScriptDoesNotExist,
    "features_tags_tag_fkey" -> FeatureTagDoesNotExist,
    // Projects
    "projectsnamesize" -> ProjectFieldTooLong,
    "projects_pkey" -> ProjectWithThisIdAlreadyExist,
    "projects_name_key" -> ProjectWithThisNameAlreadyExist,
    // Tags
    "features_tags_feature_fkey" -> TagFeatureDoesNotExist,
    "tagsnamesize" -> TagFieldTooLong,
    "tags_pkey" -> TagWithThisIdAlreadyExist,
    "tags_name_key" -> TagWithThisNameAlreadyExist,
    "features_tags_pkey" -> ThisTagIsAlreadyAssignedToThisFeature,
    // Api keys
    "apikeysnamesize" -> ApiKeyFieldTooLong,
    "apikeys_pkey" -> ApiKeyWithThisNameAlreadyExist,
    "apikeys_clientid_key" -> ApiKeyWithThisClientIdAlreadyExist,
    "apikeys_projects_pkey" -> ThisAPIKeyIsAlreadyAssignedToThisProject,
    // Webhooks
    "webhooks_pkey" -> WebhookWithThisIdAlreadyExist,
    "webhooks_name_key" -> WebhookWithThisNameAlreadyExist,
    "webhooksnamesize" -> WebhookFieldTooLong,
    "webhooks_features_pkey" -> FeatureWebhookAssociationAlreadyExist,
    "webhooks_features_feature_fkey" -> WebhookFeatureDoesNotExist,
    "webhooks_features_webhook_fkey" -> AssociatedWebhookDoesNotExist,
    "webhooks_projects_pkey" -> ProjectWebhookAssociationAlreadyExist,
    "webhooks_projects_project_fkey" -> WebhookProjectDoesNotExist,
    "webhooks_projects_webhook_fkey" -> AssociatedWebhookDoesNotExist,
    // Contexts
    "new_contexts_pkey" -> ContextWithThisNameAlreadyExist,
    "global_feature_contextsnamesize" -> GlobalContextNameTooLong,
    "feature_contextsnamesize" -> ContextNameTooLong,
    "local_context_must_have_project" -> LocalContextMustHaveProject,
    "new_contexts_parent_fkey" -> ParentContextDoesNotExist,
    "new_contexts_project_fkey" -> ContextProjectDoesNotExist,
    // Overloads
    "feature_contexts_strategies_pkey" -> FeatureOverloadIsAlreadyDefined,
    "feature_context_type_xor" -> OverloadMustHaveConditionOrScript,
    "feature_contexts_strategies_result_type_value_check" -> OverloadIncompatibleResultTypeAndValue,
    "feature_contexts_strategies_value_script_config" -> OverloadIncompatibleResultTypeAndValue,
    "feature_contexts_strategiesnamesize" -> ValueIsTooLong,
    "feature_contexts_strategies_feature_project_fkey" -> OverloadProjectResultTypeMustMatchFeature,
    "feature_contexts_strategies_project_fkey" -> OverloadProjectDoesNotExist,
    "feature_contexts_strategies_script_config_fkey" -> OverloadScriptDoesNotExist,
    "strategy_context" -> OverloadContextDoesNotExist,
    // Scripts
    "wasm_script_configurationsnamesize" -> WasmScriptNameTooLong,
    "wasm_script_configurations_pkey" -> WasmScriptIdAlreadyExist,
    // Tenant
    "tenantnamesize" -> TenantFieldTooLong,
    // User
    "invitationstextsize" -> EmailIsTooLong,
    "usertextsize" -> UsernameFieldTooLong,
    "configurationtextsize" -> ConfigurationFieldTooLong,
    "users_keys_rights_pkey" -> UserAlreadyHaveRightsForThisKey,
    "users_keys_rights_apikey_fkey" -> KeyDoesNotExist,
    "users_keys_rights_username_fkey" -> AssociatedUserDoesNotExist,
    "users_projects_rights_pkey" -> UserAlreadyHaveRightsForThisProject,
    "users_projects_rights_project_fkey" -> ProjectDoesNotExist,
    "users_projects_rights_username_fkey" -> AssociatedUserDoesNotExist,
    "users_webhooks_rights_pkey" -> UserAlreadyHaveRightsForThisWebhook,
    "users_webhooks_rights_username_fkey" -> AssociatedUserDoesNotExist,
    "users_webhooks_rights_webhook_fkey" -> WebhookDoesNotExist,
    // Personal acces token
    "personnal_access_tokenstextsize" -> PersonnalAccessTokenFieldTooLong
  )

  val partialConstraintNameToIzanamiError
      : PartialFunction[String, IzanamiError] = {
    case str if izanamiErrorsByConstraintName.contains(str) => {
      izanamiErrorsByConstraintName(str)
    }
    case _ => InternalServerError("An unexpected error occured")
  }

  def constraintNameToIzanamiError(constraint: String): IzanamiError = {
    partialConstraintNameToIzanamiError.applyOrElse(
      constraint,
      _ => InternalServerError("An unexpected error occured")
    )
  }

  def postgresErrorToIzanamiError(ex: Throwable): IzanamiError = {
    ex match {
      case f: PgException if f.getConstraint() != null => {
        PostgresErrorMapper.constraintNameToIzanamiError(f.getConstraint)
      }
      case f: PgException if f.getSqlState() == NULL_FIELD_ERROR_CODE => {
        MissingValueFor(f.getColumn())
      }
      case _ => InternalServerError("An unexpected error occured")
    }
  }
}
