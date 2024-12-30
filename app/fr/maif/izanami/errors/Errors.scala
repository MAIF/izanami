package fr.maif.izanami.errors

import fr.maif.izanami.models.ExportedType
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.FORBIDDEN
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.UNAUTHORIZED
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Writes
import play.api.mvc.Result
import play.api.mvc.Results

import java.util.Objects
import scala.collection.immutable.Iterable

sealed abstract class IzanamiError(val message: String, val status: Int) extends RuntimeException(message) {
  // TODO rework controllers to use this
  def toHttpResponse: Result = Results.Status(status)(Json.obj("message" -> message))
}
case class TenantAlreadyExists(name: String)
    extends IzanamiError(message = s"Tenant ${name} already exists", status = BAD_REQUEST)
case class ProjectAlreadyExists(name: String, tenant: String)
    extends IzanamiError(message = s"Project ${name} already exists in tenant ${tenant}", status = BAD_REQUEST)
case class TenantDoesNotExists(id: String)
    extends IzanamiError(message = s"Tenant ${id} does not exist", status = NOT_FOUND)
case class TagDoesNotExists(id: String)
    extends IzanamiError(message = s"Tag ${id} does not exist in tenant", status = NOT_FOUND)
case class OneTagDoesNotExists(names: Set[String])
    extends IzanamiError(
      message =
        s"""One or more of the following tags does not exist : [${names.map(n => s""""${n}"""").mkString(",")}]""",
      status = BAD_REQUEST
    )
case class ProjectDoesNotExists(id: String)
    extends IzanamiError(message = s"Project ${id} does not exist", status = NOT_FOUND)
case class ProjectOrFeatureDoesNotExists(project: String, feature: String)
    extends IzanamiError(message = s"Project ${project} or feature ${feature} does not exist", status = NOT_FOUND)
case class OneProjectDoesNotExists(names: Iterable[String])
    extends IzanamiError(
      message =
        s"""One or more of the following project does not exist : [${names.map(n => s""""${n}"""").mkString(",")}]""",
      status = BAD_REQUEST
    )
case class TokenWithThisNameAlreadyExists(name: String)
    extends IzanamiError(
      message = s"""A personnal access token with name $name already exists for this user""",
      status = BAD_REQUEST
    )

case class TokenDoesNotExist(id: String, name: String)
  extends IzanamiError(
    message = s"""Token with id $id does not exist for user $name""",
    status = NOT_FOUND
  )
case class MissingFeatureFields()
    extends IzanamiError(message = "Some fields are missing for feature object", status = BAD_REQUEST)
case class FeatureNotFound(id: String)
    extends IzanamiError(message = s"Feature ${id} does not exists", status = NOT_FOUND)
case class KeyNotFound(name: String)                                     extends IzanamiError(message = s"Key ${name} does not exists", status = NOT_FOUND)
case class ProjectContextOrFeatureDoesNotExist(project: String, context: String, feature: String)
    extends IzanamiError(
      message = s"Project ${project}, context ${context} or feature ${feature} does not exist",
      status = NOT_FOUND
    )
case class FeatureContextDoesNotExist(context: String)
    extends IzanamiError(message = s"Context ${context} does not exist", status = NOT_FOUND)

case class NoFeatureMatchingOverloadDefinition(tenant: String, project: String, feature: String, resultType: String)
    extends IzanamiError(
      message = s"No feature $feature for project $project, tenant $tenant and resultType $resultType found",
      status = BAD_REQUEST
    )

case class ConflictWithSameNameGlobalContext(name: String, parentCtx: String = null)
    extends IzanamiError(
      message =
        s"A global context with this name ($name) already exist ${if (Objects.nonNull(parentCtx)) s"as child of $parentCtx"
        else "at root level"}",
      status = BAD_REQUEST
    )
case class ConflictWithSameNameLocalContext(name: String, parentCtx: String = null)
    extends IzanamiError(
      message =
        s"A local context with this name ($name) already exist ${if (Objects.nonNull(parentCtx)) s"as child of $parentCtx"
        else "at root level"}",
      status = BAD_REQUEST
    )

case class UserNotFound(user: String) extends IzanamiError(message = s"User ${user} does not exist", status = NOT_FOUND)
case class SessionNotFound(session: String)
    extends IzanamiError(message = s"Session ${session} does not exist", status = UNAUTHORIZED)
case class UserAlreadyExist(user: String, email: String)
    extends IzanamiError(
      message = s"User ${user} already exists (or email ${email} is already used by another user)",
      status = BAD_REQUEST
    )
case class EmailAlreadyUsed(email: String)
    extends IzanamiError(message = s"Email ${email} is already used by another user", status = BAD_REQUEST)
case class InternalServerError(msg: String = "")
    extends IzanamiError(message = s"Something went wrong $msg", status = INTERNAL_SERVER_ERROR)
case class MailSendingError(err: String, override val status: Int = INTERNAL_SERVER_ERROR)
    extends IzanamiError(message = s"Failed to send mail : ${err}", status)
case class ConfigurationReadError()
    extends IzanamiError(message = s"Failed to read configuration from DB", status = INTERNAL_SERVER_ERROR)
case class MissingMailProviderConfigurationError(mailer: String)
    extends IzanamiError(message = s"Missing configuration for mail provider ${mailer}", status = BAD_REQUEST)
case class BadBodyFormat()            extends IzanamiError(message = "Bad body format", status = BAD_REQUEST)
case class NotEnoughRights()          extends IzanamiError(message = "Not enough rights for this operation", status = FORBIDDEN)
case class InvalidCredentials()       extends IzanamiError(message = "Incorrect username / password", status = FORBIDDEN)
case class FeatureOverloadDoesNotExist(project: String, path: String, feature: String)
    extends IzanamiError(
      message = s"No overload for feature ${feature} found at ${path} (project ${project})",
      status = NOT_FOUND
    )
case class WasmScriptAlreadyExists(path: String)
    extends IzanamiError(message = s"Script ${path} already exists", status = BAD_REQUEST)
case class FeatureDependsOnThisScript()
    extends IzanamiError(message = s"Can't delete a script used by existing features", status = BAD_REQUEST)
case class ApiKeyDoesNotExist(name: String)
    extends IzanamiError(message = s"Key ${name} does not exist", status = NOT_FOUND)
case class FeatureDoesNotExist(name: String)
    extends IzanamiError(message = s"Feature ${name} does not exist", status = NOT_FOUND)
case class NoWasmManagerConfigured()
    extends IzanamiError(message = s"No wasm manager is configured, can't handle wasm scripts", status = BAD_REQUEST)
case class FailedToReadEvent(event: String)
    extends IzanamiError(message = s"Failed to read event $event", status = INTERNAL_SERVER_ERROR)
case class MissingOIDCConfigurationError()
    extends IzanamiError(
      message = s"OIDC configuration is either missing or incomplete",
      status = INTERNAL_SERVER_ERROR
    )
case class WasmError()                extends IzanamiError(message = "Failed to parse wasm response", status = INTERNAL_SERVER_ERROR)
case class WasmResultParsingError(expected: String, found: JsValue)
    extends IzanamiError(
      message = s"""Declared feature result type ($expected) does not match script result $found""",
      status = BAD_REQUEST
    )
case class MissingProjectRight(projects: Set[String])
    extends IzanamiError(message = s"""You're not allowed for projects ${projects.mkString(",")}""", status = FORBIDDEN)
case class MissingFeatureRight(features: Set[String])
    extends IzanamiError(
      message = s"""You're not allowed for features ${features.mkString(",")}, you don't have right for this project""",
      status = FORBIDDEN
    )
case class IncorrectKey()             extends IzanamiError(message = "Incorrect key provided", status = FORBIDDEN)
case class BadEventFormat(override val message: String = "Bad event format")
    extends IzanamiError(message, status = FORBIDDEN)
case class WebhookCreationFailed(
    override val message: String =
      "Webhook creation failed, make sure that a webhook with the same name does not already exist"
)                                     extends IzanamiError(message, status = BAD_REQUEST)
case class WebhookDoesNotExists(id: String)
    extends IzanamiError(message = s"No webhook with id $id", status = NOT_FOUND)

case class WebhookCallError(callStatus: Int, body: Option[String], hookName: String)
    extends IzanamiError(
      message = s"Webhook $hookName call failed with status $callStatus and response body ${body.getOrElse("No body")}",
      status = callStatus
    )
case class EventNotFound(tenant: String, event: Long)
    extends IzanamiError(message = s"Event $event not found", status = 500)
case class WebhookRetryCountExceeded()                     extends IzanamiError(message = s"Exceeded webhook retry count", status = 500)
case class TableDoesNotExist(tenant: String, table: String)
    extends IzanamiError(message = s"Table $table does not exist for tenant $tenant", status = 500)
case class ConflictingName(tenant: String, entityTpe: String, row: JsObject)
    extends IzanamiError(
      message =
        s"An entity of type $entityTpe already exists in tenant $tenant with the same unique values but with different id. Row is ${row
          .toString()}",
      status = 400
    )
case class SearchFilterError()
  extends IzanamiError(message = s"Invalid filters provided. Please ensure your filters are correct.", status = BAD_REQUEST)
case class SearchQueryError()
  extends IzanamiError(message = s"Query parameter is missing.", status = BAD_REQUEST)
case class GenericBadRequest(override val message: String) extends IzanamiError(message = message, status = 400)
case class PartialImportFailure(failedElements: Map[ExportedType, Seq[JsObject]])
    extends IzanamiError(message = s"Some element couldn't be imported", status = 400)
case class ImportError(table: String, json: String, errorMessage: String)
    extends IzanamiError(
      message = s"Error key while inserting into table $table with error $errorMessage values $json : ",
      status = 400
    )
object IzanamiError {
  implicit val errorWrite: Writes[IzanamiError] = { err =>
    Json.obj(
      "message" -> err.message
    )
  }
}
