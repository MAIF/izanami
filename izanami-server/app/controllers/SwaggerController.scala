package controllers

import env.Env
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents}

object Implicits {
  implicit class EnhancedJsValue(private val value: JsValue) extends AnyVal {
    def ~~>(description: String): JsValue =
      value.as[JsObject] ++ Json.obj(
        "description" -> description
      )
  }
}

class SwaggerController(_env: Env, val cc: ControllerComponents) extends AbstractController(cc) {

  import Implicits._

  def swagger = Action {
    Ok(swaggerDescriptor()).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def swaggerUi = Action {
    Ok(views.html.swagger(_env, s"/api/swagger.json"))
  }

  def Operation(
      summary: String,
      tag: String,
      description: String = "",
      operationId: String = "",
      produces: JsArray = Json.arr("application/json"),
      parameters: JsArray = Json.arr(),
      goodCode: String = "200",
      goodResponse: JsObject
  ): JsValue =
    Json.obj(
      "deprecated"  -> false,
      "tags"        -> Json.arr(tag),
      "summary"     -> summary,
      "description" -> description,
      "operationId" -> operationId,
      "produces"    -> produces,
      "parameters"  -> parameters,
      "responses" -> Json.obj(
        "401" -> Json.obj(
          "description" -> "You're not authorized"
        ),
        "403" -> Json.obj(
          "description" -> "You're not allowed to access this resource"
        ),
        "400" -> Json.obj(
          "description" -> "Validation errors"
        ),
        "404" -> Json.obj(
          "description" -> "Resource not found or does not exist"
        ),
        goodCode -> goodResponse
      ),
      "security" -> Json.arr(
        Json.obj(
          "otoroshi_auth" -> Json.arr(
            "write:admins",
            "read:admins"
          )
        )
      )
    )

  def swaggerDescriptor(): JsValue = {
    Json.obj(
      "swagger" -> "2.0",
      "info" -> Json.obj(
        "version"     -> "1.0.0",
        "title"       -> "Izanami API",
        "description" -> "API for izanami",
        "contact" -> Json.obj(
          "name"  -> "Izanami Support",
          "email" -> "oss@maif.fr"
        ),
        "license" -> Json.obj(
          "name" -> "Apache 2.0",
          "url"  -> "http://www.apache.org/licenses/LICENSE-2.0.html"
        )
      ),
      "tags" -> Json.arr(
        Json.obj(
          "name"        -> "configuration",
          "description" -> "Everything about Izanami shared configuration"
        ),
        Json.obj(
          "name"        -> "feature",
          "description" -> "Everything about Izanami feature flipping"
        ),
        Json.obj(
          "name"        -> "experiment",
          "description" -> "Everything about Izanami feature flipping"
        ),
        Json.obj(
          "name"        -> "user",
          "description" -> "Everything about Izanami user management"
        ),
        Json.obj(
          "name"        -> "webhook",
          "description" -> "Everything about Izanami webhooks"
        ),
        Json.obj(
          "name"        -> "apiKey",
          "description" -> "Everything about Izanami api keys"
        )
      ),
      "externalDocs" -> Json.obj(
        "description" -> "Find out more about Izanami",
        "url"         -> "https://www.izanami.fr/#izanami"
      ),
      "host"     -> "",
      "basePath" -> "",
      "schemes"  -> Json.arr("https", "http"),
      "paths" -> Json.obj(
        "/api/configs"                              -> AllConfigs,
        "/api/configs/{configId}"                   -> Configs,
        "/api/counts/configs"                       -> CountConfigs,
        "/api/tree/configs"                         -> TreeConfigs,
        "/api/features"                             -> AllFeatures,
        "/api/features/{featureId}"                 -> Features,
        "/api/tree/features"                        -> TreeFeatures,
        "/api/counts/features"                      -> CountFeatures,
        "/api/scripts"                              -> AllScripts,
        "/api/scripts/{scriptId}"                   -> Scripts,
        "/api/experiments"                          -> AllExperiments,
        "/api/experiments/{experimentId}"           -> Experiments,
        "/api/experiments/{experimentId}/variant"   -> ExperimentsGetVariant,
        "/api/experiments/{experimentId}/displayed" -> ExperimentsDisplayed,
        "/api/experiments/{experimentId}/won"       -> ExperimentsWon,
        "/api/experiments/{experimentId}/results"   -> ExperimentsResults,
        "/api/counts/experiments"                   -> CountExperiments,
        "/api/tree/experiments"                     -> TreeExperiments,
        "/api/webhooks"                             -> AllWebhooks,
        "/api/webhooks/{webhookId}"                 -> Webhooks,
        "/api/counts/webhooks"                      -> CountWebhooks,
        "/api/users"                                -> AllUsers,
        "/api/users/{userId}"                       -> Users,
        "/api/counts/users"                         -> CountUsers,
        "/api/apikeys"                              -> AllApiKeys,
        "/api/apikeys/{apikeyId}"                   -> ApiKeys,
        "/api/counts/apikeys"                       -> CountApiKeys,
        "/api/_search" -> Json.obj(
          "get" -> Operation(
            tag = "config",
            summary = "Search on all domains",
            description = "Get all config, features, experiments, scripts and webhook depending on patterns.",
            operationId = "listConfigs",
            parameters = Json.arr(
              Json.obj(
                "in"          -> "query",
                "name"        -> "pattern",
                "required"    -> false,
                "type"        -> "string",
                "description" -> "Patterns on differents keys"
              )
            ),
            goodResponse = Json.obj(
              "description" -> "Successful operation",
              "schema"      -> ArrayOf(Ref("Search"))
            )
          )
        ),
        "/api/login" -> Json.obj(
          "post" -> Operation(
            tag = "login",
            summary = "Login",
            description = "Login operation.",
            operationId = "login",
            parameters = Json.arr(
              Json.obj(
                "in"          -> "body",
                "name"        -> "body",
                "required"    -> true,
                "schema"      -> Ref("Auth"),
                "description" -> "The login informations"
              )
            ),
            goodResponse = Json.obj(
              "description" -> "Successful operation",
              "schema"      -> Ref("User")
            )
          )
        ),
        "/api/logout" -> Json.obj(
          "post" -> Operation(
            tag = "logout",
            summary = "Logout",
            description = "Logout operation.",
            operationId = "logout",
            goodResponse = Json.obj(
              "description" -> "Successful operation"
            )
          )
        )
      ),
      "securityDefinitions" -> Json.obj(
        "izanami_auth" -> Json.obj(
          "type" -> "jwt"
        )
      ),
      "definitions" -> Json.obj(
        "Config"         -> Config,
        "Feature"        -> Feature,
        "Experiment"     -> Experiment,
        "Script"         -> Script,
        "Webhook"        -> Webhook,
        "User"           -> User,
        "Auth"           -> Auth,
        "ApiKey"         -> ApiKey,
        "Search"         -> Search,
        "TreeConfig"     -> TreeConfig,
        "TreeFeature"    -> TreeFeature,
        "TreeExperiment" -> TreeExperiment,
        "Patch"          -> Patch
      )
    )
  }

  private val KeyType = Json.obj("type" -> "string", "example" -> "a string value", "description" -> "The key, ")
  private val SimpleObjectType = Json.obj("type" -> "object",
                                          "required"             -> Json.arr(),
                                          "example"              -> Json.obj("key" -> "value"),
                                          "additionalProperties" -> Json.obj("type" -> "string"))
  private val SimpleBooleanType =
    Json.obj("type" -> "boolean", "example" -> true)
  private val SimpleStringType =
    Json.obj("type" -> "string", "example" -> "a string value")
  private val OptionalStringType = Json.obj("type" -> "string", "required" -> false, "example" -> "a string value")
  private val OptionalNumberType = Json.obj("type" -> "number", "required" -> false, "example" -> "1")
  private val OptionalScriptType = Json.obj(
    "type"     -> "string",
    "required" -> false,
    "example"  -> """function enabled(context, enabled, disabled, http) {
                                                                                                    |  if (context.user === 'john.doe@gmail.com') {
                                                                                                    |    return enabled();
                                                                                                    |  }
                                                                                                    |  return disabled();
                                                                                                    |}""".stripMargin
  )
  private val ScriptType = Json.obj(
    "type"    -> "string",
    "example" -> """function enabled(context, enabled, disabled, http) {
                                                                        |  if (context.user === 'john.doe@gmail.com') {
                                                                        |    return enabled();
                                                                        |  }
                                                                        |  return disabled();
                                                                        |}""".stripMargin
  )
  private val SimpleUriType = Json.obj("type" -> "string", "format" -> "uri", "example"    -> "http://www.google.com")
  private val StringArray   = Json.obj("type" -> "array", "format"  -> "string", "example" -> Json.arr("value1", "value2"))
  Json.obj("type" -> "string", "format" -> "date", "example" -> "2017-07-21")
  private val OptionalDateType =
    Json.obj("type" -> "string", "required" -> false, "format" -> "date", "example" -> "2017-07-21")
  private val SimpleEmailType = Json.obj("type" -> "string", "format" -> "email", "example" -> "admin@otoroshi.io")

  def Ref(name: String): JsObject = Json.obj("$ref" -> s"#/definitions/$name")
  def ArrayOf(ref: JsValue) = Json.obj(
    "type"  -> "array",
    "items" -> ref
  )

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val TreeConfig: JsValue = Json.obj(
    "description" -> "Configs in tree form",
    "type"        -> "object",
    "example" -> Json.obj(
      "key1" -> Json.obj("key11" -> "value11", "key12" -> "value12", "key13" -> Json.obj("key131" -> "value131")),
      "key2" -> "value2"
    )
  )

  lazy val TreeFeature: JsValue = Json.obj(
    "description" -> "Features in tree form",
    "type"        -> "object",
    "example" -> Json.obj(
      "key1" -> Json.obj("active" -> true, "key12" -> Json.obj("key121" -> Json.obj("active" -> false))),
      "key2" -> Json.obj("active" -> true)
    )
  )

  lazy val TreeExperiment: JsValue = Json.obj(
    "description" -> "Experiment in tree form for a user id",
    "type"        -> "object",
    "example" -> Json.obj(
      "key1" -> Json.obj("variant" -> "A", "key12" -> Json.obj("key121" -> Json.obj("variant" -> "B"))),
      "key2" -> Json.obj("variant" -> "A")
    )
  )

  lazy val Patch: JsValue = Json.obj(
    "description" -> "Json Patch",
    "type"        -> "array",
    "example" ->
    Json.arr(Json.obj("op" -> "add", "path" -> "/path/in/json", "value" -> Json.obj("field" -> "To add")))
  )

  lazy val Auth: JsValue = Json.obj(
    "description" -> "An auth",
    "type"        -> "object",
    "required"    -> Json.arr("userId", "password"),
    "properties" -> Json.obj(
      "userId"   -> SimpleStringType ~~> "The id of the user to authenticate",
      "password" -> SimpleStringType ~~> "The password of the user to authenticate"
    )
  )

  lazy val Config: JsValue = Json.obj(
    "description" -> "A config",
    "type"        -> "object",
    "required"    -> Json.arr("id", "value"),
    "properties" -> Json.obj(
      "id"    -> KeyType,
      "value" -> SimpleStringType ~~> "The config. Could be a json object"
    )
  )

  lazy val Feature: JsValue = Json.obj(
    "description" -> "A feature",
    "type"        -> "object",
    "required"    -> Json.arr("id", "enabled", "activationStrategy"),
    "properties" -> Json.obj(
      "id"      -> KeyType,
      "enabled" -> SimpleBooleanType ~~> "The feature is active or not",
      "activationStrategy" -> Json.obj(
        "type"    -> "string",
        "example" -> "One of NO_STRATEGY, GLOBAL_SCRIPT, SCRIPT or RELEASE_DATE"
      ) ~~> "The strategy for the feature. Could be NO_STRATEGY, GLOBAL_SCRIPT, SCRIPT or RELEASE_DATE",
      "parameters" -> Json.obj(
        "type"     -> "object",
        "required" -> false,
        "properties" -> Json.obj(
          "releaseDate" -> OptionalDateType ~~> "The activation date if activationStrategy is RELEASE_DATE",
          "from"        -> OptionalDateType ~~> "The from date if activationStrategy is DATE_RANGE",
          "to"          -> OptionalDateType ~~> "The to date if activationStrategy is DATE_RANGE",
          "percentage"  -> OptionalNumberType ~~> "The percentage if activationStrategy is PERCENTAGE",
          "ref"         -> OptionalStringType ~~> "The reference to a script if activationStrategy is GLOBAL_SCRIPT",
          "script"      -> OptionalScriptType ~~> "Javascript code to execute if activationStrategy is SCRIPT",
          "type"        -> OptionalScriptType ~~> "Javascript script language (scala|javascript) if activationStrategy is SCRIPT"
        )
      )
    )
  )

  lazy val Experiment: JsValue = Json.obj(
    "description" -> "An experiment",
    "type"        -> "object",
    "required"    -> Json.arr("id", "description", "enabled", "variants"),
    "properties" -> Json.obj(
      "id"          -> KeyType,
      "description" -> SimpleStringType ~~> "The description of the experiment",
      "enabled"     -> SimpleBooleanType ~~> "The experiment is active or not",
      "variants"    -> ArrayOf(Variant)
    )
  )

  lazy val Variant = Json.obj(
    "description" -> "A variant",
    "type"        -> "object",
    "required"    -> Json.arr("id", "name", "description"),
    "properties" -> Json.obj(
      "id"          -> SimpleStringType ~~> "The id of the variant for exemple A",
      "name"        -> SimpleStringType ~~> "The name of the variant",
      "description" -> SimpleStringType ~~> "The description of the variant"
    )
  )

  lazy val Script = Json.obj(
    "description" -> "A script",
    "type"        -> "object",
    "required"    -> Json.arr("id", "name", "source"),
    "properties" -> Json.obj(
      "id"     -> KeyType ~~> "The id of the script",
      "name"   -> SimpleStringType ~~> "The name of the script",
      "source" -> ScriptType ~~> "Javascript code of the script"
    )
  )

  lazy val Webhook = Json.obj(
    "description" -> "A webhook record",
    "type"        -> "object",
    "required"    -> Json.arr("clientId", "callbackUrl"),
    "properties" -> Json.obj(
      "clientId"    -> KeyType ~~> "The id of the client",
      "callbackUrl" -> SimpleUriType ~~> "The url of the webhook",
      "domains"     -> StringArray ~~> "A list of domains. Required to filter the events. All events are sent if the list is empty",
      "patterns"    -> StringArray ~~> "A list of pattetns to filter the events sent by keys",
      "types"       -> StringArray ~~> "A list of types to filter the events sent by types",
      "headers"     -> SimpleObjectType ~~> "Headers to send to webhook",
      "created"     -> OptionalDateType ~~> "Creation date. This is generated when the webhook is created."
    )
  )

  lazy val User: JsValue = Json.obj(
    "description" -> "A user",
    "type"        -> "object",
    "required"    -> Json.arr("id", "name", "email", "password", "admin", "authorizedPattern"),
    "properties" -> Json.obj(
      "id"                -> KeyType,
      "name"              -> SimpleStringType ~~> "The name of the user",
      "email"             -> SimpleEmailType ~~> "The email of the user",
      "password"          -> SimpleStringType ~~> "The password of the user",
      "admin"             -> SimpleBooleanType ~~> "True if the user is admin",
      "authorizedPattern" -> SimpleStringType ~~> "patterns separated by ','. This patterns allowed or not the user to access data. "
    )
  )

  lazy val ApiKey: JsValue = Json.obj(
    "description" -> "An api key",
    "type"        -> "object",
    "required"    -> Json.arr("id", "description", "enabled", "variants"),
    "properties" -> Json.obj(
      "clientId"          -> SimpleStringType ~~> "The clientid",
      "name"              -> SimpleStringType ~~> "The name of the api key",
      "clientSecret"      -> SimpleEmailType ~~> "A secret",
      "authorizedPattern" -> SimpleStringType ~~> "patterns separated by ','. This patterns allowed or not the api to access data. "
    )
  )

  lazy val Search: JsValue = Json.obj(
    "description" -> "A search result",
    "type"        -> "object",
    "required"    -> Json.arr("id", "description", "enabled", "variants"),
    "properties" -> Json.obj(
      "configurations" -> ArrayOf(SearchItem),
      "features"       -> ArrayOf(SearchItem),
      "experiments"    -> ArrayOf(SearchItem),
      "scripts"        -> ArrayOf(SearchItem),
      "webhooks"       -> ArrayOf(SearchItem)
    )
  )

  lazy val SearchItem: JsValue = Json.obj(
    "description" -> "A search item",
    "type"        -> "object",
    "required"    -> Json.arr("id", "type"),
    "properties" -> Json.obj(
      "id"   -> KeyType ~~> "The id of the result",
      "type" -> SimpleStringType ~~> "The type of the result. Could be 'configurations', 'features', 'experiments', 'scripts' or 'webhooks'"
    )
  )

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val AllConfigs = Json.obj(
    "get" -> Operation(
      tag = "config",
      summary = "Get all config",
      description = "Get all config depending on the patterns associated to the current account.",
      operationId = "listConfigs",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on config key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("Config"))
      )
    ),
    "post" -> Operation(
      tag = "config",
      summary =
        "Create a new config. There is a restriction on the id depending on the authorized patterns of the connected user.",
      description = "Create a new config. ",
      operationId = "createConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Config"),
          "description" -> "The config to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    )
  )

  lazy val Configs = Json.obj(
    "get" -> Operation(
      tag = "config",
      summary = "Get a config",
      description = "Get a config if the id is allowed for the connected user.",
      operationId = "getConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "configId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the config"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    ),
    "put" -> Operation(
      tag = "config",
      summary = "Update a config",
      description =
        "Update a config. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "configId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the config"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Config"),
          "description" -> "The config to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    ),
    "patch" -> Operation(
      tag = "config",
      summary = "Patch a config",
      description =
        "Patch a config. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "configId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the config"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    ),
    "delete" -> Operation(
      tag = "config",
      summary = "Delete a config",
      description =
        "Delete a config. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "deleteConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "configId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the config"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    )
  )

  lazy val TreeConfigs = Json.obj(
    "get" -> Operation(
      tag = "config",
      summary = "Get config in a tree form",
      description = "Get all configs in a tree form filtered depending on the patterns allowed for the connected user.",
      operationId = "getConfig",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on config key"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("TreeConfig")
      )
    )
  )

  lazy val CountConfigs = Json.obj(
    "get" -> Operation(
      tag = "config",
      summary = "Count configs",
      description = "Count configs filtered depending on the patterns allowed for the connected user.",
      operationId = "countConfig",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    )
  )

  lazy val AllFeatures = Json.obj(
    "get" -> Operation(
      tag = "feature",
      summary = "Get all feature",
      description = "Get all feature depending on the patterns associated to the current account.",
      operationId = "listFeatures",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on feature key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("Feature"))
      )
    ),
    "post" -> Operation(
      tag = "feature",
      summary =
        "Create a new feature. There is a restriction on the id depending on the authorized patterns of the connected user.",
      description = "Create a new feature. ",
      operationId = "createFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Feature"),
          "description" -> "The feature to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Feature")
      )
    )
  )

  lazy val Features = Json.obj(
    "get" -> Operation(
      tag = "feature",
      summary = "Get a feature",
      description = "Get a feature if the id is allowed for the connected user.",
      operationId = "getFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "featureId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the feature"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Feature")
      )
    ),
    "put" -> Operation(
      tag = "feature",
      summary = "Update a feature",
      description =
        "Update a feature. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "featureId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the feature"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Feature"),
          "description" -> "The feature to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Feature")
      )
    ),
    "patch" -> Operation(
      tag = "feature",
      summary = "Update a feature",
      description =
        "Patch a feature. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "patchFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "featureId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the feature"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Feature")
      )
    ),
    "delete" -> Operation(
      tag = "feature",
      summary = "Delete a feature",
      description =
        "Delete a feature. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "deleteFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "featureId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the feature"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Feature")
      )
    )
  )

  lazy val TreeFeatures = Json.obj(
    "get" -> Operation(
      tag = "feature",
      summary = "Get feature in a tree form",
      description = "Get all features in a tree form filtered depending on the patterns allowed for the connected user.",
      operationId = "getFeature",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on feature key"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("TreeFeature")
      )
    )
  )

  lazy val CountFeatures = Json.obj(
    "get" -> Operation(
      tag = "feature",
      summary = "Count features",
      description = "Count features filtered depending on the patterns allowed for the connected user.",
      operationId = "countFeature",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Config")
      )
    )
  )

  lazy val AllScripts = Json.obj(
    "get" -> Operation(
      tag = "script",
      summary = "Get all script",
      description = "Get all script depending on the patterns associated to the current account.",
      operationId = "listScripts",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on script key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("Script"))
      )
    ),
    "post" -> Operation(
      tag = "script",
      summary =
        "Create a new script. There is a restriction on the id depending on the authorized patterns of the connected user.",
      description = "Create a new script. ",
      operationId = "createScript",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Script"),
          "description" -> "The script to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    )
  )

  lazy val Scripts = Json.obj(
    "get" -> Operation(
      tag = "script",
      summary = "Get a script",
      description = "Get a script if the id is allowed for the connected user.",
      operationId = "getScript",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "scriptId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the script"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    ),
    "put" -> Operation(
      tag = "script",
      summary = "Update a script",
      description =
        "Update a script. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateScript",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "scriptId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the script"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Script"),
          "description" -> "The script to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    ),
    "patch" -> Operation(
      tag = "script",
      summary = "Patch a script",
      description =
        "Patch a script. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "patchScript",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "scriptId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the script"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    ),
    "delete" -> Operation(
      tag = "script",
      summary = "Delete a script",
      description =
        "Delete a script. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "deleteScript",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "scriptId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the script"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    )
  )

  lazy val CountScripts = Json.obj(
    "get" -> Operation(
      tag = "script",
      summary = "Count scripts",
      description = "Count scripts filtered depending on the patterns allowed for the connected user.",
      operationId = "countScript",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    )
  )

  lazy val AllExperiments = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Get all experiment",
      description = "Get all experiment depending on the patterns associated to the current account.",
      operationId = "listExperiments",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on experiment key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("Experiment"))
      )
    ),
    "post" -> Operation(
      tag = "experiment",
      summary =
        "Create a new experiment. There is a restriction on the id depending on the authorized patterns of the connected user.",
      description = "Create a new experiment. ",
      operationId = "createExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Experiment"),
          "description" -> "The experiment to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val Experiments = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Get a experiment",
      description = "Get a experiment if the id is allowed for the connected user.",
      operationId = "getExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "experimentId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the experiment"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    ),
    "put" -> Operation(
      tag = "experiment",
      summary = "Update a experiment",
      description =
        "Update a experiment. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "experimentId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the experiment"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Experiment"),
          "description" -> "The experiment to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    ),
    "patch" -> Operation(
      tag = "experiment",
      summary = "Patch a experiment",
      description =
        "Update a experiment. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "patchExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "experimentId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the experiment"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    ),
    "delete" -> Operation(
      tag = "experiment",
      summary = "Delete a experiment",
      description =
        "Delete a experiment. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "deleteExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "experimentId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the experiment"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val ExperimentsGetVariant = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Get the variant for a client",
      description = "Get the variant (A or B) associated to this client.",
      operationId = "getExperimentVariant",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "id",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the experiment"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "clientId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the client"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val ExperimentsDisplayed = Json.obj(
    "post" -> Operation(
      tag = "experiment",
      summary = "Tell izanami that the variant is displayed",
      description = "Tell izanami that the variant is displayed.",
      operationId = "experimentDisplayed",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "id",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the experiment"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "clientId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the client"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val ExperimentsWon = Json.obj(
    "post" -> Operation(
      tag = "experiment",
      summary = "Tell izanami that the variant won",
      description = "Tell izanami that the variant won.",
      operationId = "experimentWon",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "id",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the experiment"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "clientId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the client"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val ExperimentsResults = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Get the result for an experiment",
      description = "Get the result for an experiment.",
      operationId = "experimentResults",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "id",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Id of the experiment"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Experiment")
      )
    )
  )

  lazy val TreeExperiments = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Get experiment in a tree form",
      description =
        "Get all experiments in a tree form filtered depending on the patterns allowed for the connected user.",
      operationId = "getExperiment",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Patterns on experiment key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "clientId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "The client id"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("TreeExperiment")
      )
    )
  )

  lazy val CountExperiments = Json.obj(
    "get" -> Operation(
      tag = "experiment",
      summary = "Count experiments",
      description = "Count experiments filtered depending on the patterns allowed for the connected user.",
      operationId = "countExperiment",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Script")
      )
    )
  )

  lazy val AllUsers = Json.obj(
    "get" -> Operation(
      tag = "user",
      summary = "Get all user",
      description = "Get all user depending on the patterns associated to the current account.",
      operationId = "listUsers",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on user key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("User"))
      )
    ),
    "post" -> Operation(
      tag = "user",
      summary =
        "Create a new user. There is a restriction on the id depending on the authorized patterns of the connected user.",
      description = "Create a new user. ",
      operationId = "createUser",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("User"),
          "description" -> "The user to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    )
  )

  lazy val Users = Json.obj(
    "get" -> Operation(
      tag = "user",
      summary = "Get a user",
      description = "Get a user if the id is allowed for the connected user.",
      operationId = "getUser",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "userId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the user"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    ),
    "put" -> Operation(
      tag = "user",
      summary = "Update a user",
      description =
        "Update a user. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateUser",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "userId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the user"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("User"),
          "description" -> "The user to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    ),
    "patch" -> Operation(
      tag = "user",
      summary = "Patch a user",
      description =
        "Patch a user. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "updateUser",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "userId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the user"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    ),
    "delete" -> Operation(
      tag = "user",
      summary = "Delete a user",
      description =
        "Delete a user. There is a restriction on the id depending on the authorized patterns of the connected user.",
      operationId = "deleteUser",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "userId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the user"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    )
  )

  lazy val CountUsers = Json.obj(
    "get" -> Operation(
      tag = "user",
      summary = "Count users",
      description = "Count users filtered depending on the patterns allowed for the connected user.",
      operationId = "countUser",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("User")
      )
    )
  )

  lazy val AllWebhooks = Json.obj(
    "get" -> Operation(
      tag = "webhook",
      summary = "Get all webhook",
      description = "Get all webhook depending on the patterns associated to the current account.",
      operationId = "listWebhooks",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on webhook key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("Webhook"))
      )
    ),
    "post" -> Operation(
      tag = "webhook",
      summary =
        "Create a new webhook. There is a restriction on the id depending on the authorized patterns of the connected webhook.",
      description = "Create a new webhook. ",
      operationId = "createWebhook",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Webhook"),
          "description" -> "The webhook to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    )
  )

  lazy val Webhooks = Json.obj(
    "get" -> Operation(
      tag = "webhook",
      summary = "Get a webhook",
      description = "Get a webhook if the id is allowed for the connected webhook.",
      operationId = "getWebhook",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "webhookId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the webhook"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    ),
    "put" -> Operation(
      tag = "webhook",
      summary = "Update a webhook",
      description =
        "Update a webhook. There is a restriction on the id depending on the authorized patterns of the connected webhook.",
      operationId = "updateWebhook",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "webhookId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the webhook"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Webhook"),
          "description" -> "The webhook to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    ),
    "patch" -> Operation(
      tag = "webhook",
      summary = "Patch a webhook",
      description =
        "Update a webhook. There is a restriction on the id depending on the authorized patterns of the connected webhook.",
      operationId = "patchWebhook",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "webhookId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the webhook"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    ),
    "delete" -> Operation(
      tag = "webhook",
      summary = "Delete a webhook",
      description =
        "Delete a webhook. There is a restriction on the id depending on the authorized patterns of the connected webhook.",
      operationId = "deleteWebhook",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "webhookId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the webhook"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    )
  )

  lazy val CountWebhooks = Json.obj(
    "get" -> Operation(
      tag = "webhook",
      summary = "Count webhooks",
      description = "Count webhooks filtered depending on the patterns allowed for the connected webhook.",
      operationId = "countWebhook",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("Webhook")
      )
    )
  )

  lazy val AllApiKeys = Json.obj(
    "get" -> Operation(
      tag = "apiKey",
      summary = "Get all apiKey",
      description = "Get all apiKey depending on the patterns associated to the current account.",
      operationId = "listApiKeys",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "query",
          "name"        -> "pattern",
          "required"    -> false,
          "type"        -> "string",
          "description" -> "Patterns on apiKey key"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "page",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The page number"
        ),
        Json.obj(
          "in"          -> "query",
          "name"        -> "nbElementPerPage",
          "required"    -> false,
          "type"        -> "integer",
          "description" -> "The number of elements per page"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> ArrayOf(Ref("ApiKey"))
      )
    ),
    "post" -> Operation(
      tag = "apiKey",
      summary =
        "Create a new apiKey. There is a restriction on the id depending on the authorized patterns of the connected apiKey.",
      description = "Create a new apiKey. ",
      operationId = "createApiKey",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("ApiKey"),
          "description" -> "The apiKey to create"
        )
      ),
      goodCode = "201",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    )
  )

  lazy val ApiKeys = Json.obj(
    "get" -> Operation(
      tag = "apiKey",
      summary = "Get a apiKey",
      description = "Get a apiKey if the id is allowed for the connected apiKey.",
      operationId = "getApiKey",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "apiKeyId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the apiKey"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    ),
    "put" -> Operation(
      tag = "apiKey",
      summary = "Update a apiKey",
      description =
        "Update a apiKey. There is a restriction on the id depending on the authorized patterns of the connected apiKey.",
      operationId = "updateApiKey",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "apiKeyId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the apiKey"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("ApiKey"),
          "description" -> "The apiKey to create"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    ),
    "patch" -> Operation(
      tag = "apiKey",
      summary = "Patch a apiKey",
      description =
        "Patch a apiKey. There is a restriction on the id depending on the authorized patterns of the connected apiKey.",
      operationId = "patchApiKey",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "apiKeyId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the apiKey"
        ),
        Json.obj(
          "in"          -> "body",
          "name"        -> "body",
          "required"    -> true,
          "schema"      -> Ref("Patch"),
          "description" -> "The json patch payload to apply see : http://jsonpatch.com/"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    ),
    "delete" -> Operation(
      tag = "apiKey",
      summary = "Delete a apiKey",
      description =
        "Delete a apiKey. There is a restriction on the id depending on the authorized patterns of the connected apiKey.",
      operationId = "deleteApiKey",
      parameters = Json.arr(
        Json.obj(
          "in"          -> "path",
          "name"        -> "apiKeyId",
          "required"    -> true,
          "type"        -> "string",
          "description" -> "Key of the apiKey"
        )
      ),
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    )
  )

  lazy val CountApiKeys = Json.obj(
    "get" -> Operation(
      tag = "apiKey",
      summary = "Count apiKeys",
      description = "Count apiKeys filtered depending on the patterns allowed for the connected apiKey.",
      operationId = "countApiKey",
      goodResponse = Json.obj(
        "description" -> "Successful operation",
        "schema"      -> Ref("ApiKey")
      )
    )
  )

}
