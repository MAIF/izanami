package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{
  FeatureNotFound,
  IncorrectKey,
  IzanamiError,
  TagDoesNotExists
}
import fr.maif.izanami.models.*
import fr.maif.izanami.models.Feature.*
import fr.maif.izanami.models.FeatureCall.FeatureCallOrigin
import fr.maif.izanami.models.LightWeightFeatureWithUsageInformation.writeLightWeightFeatureWithUsageInformation
import fr.maif.izanami.models.ProjectRightLevel.Write
import fr.maif.izanami.models.features.*
import fr.maif.izanami.requests.{BaseFeatureUpdateRequest, FeatureUpdateRequest}
import fr.maif.izanami.services.{FeatureService, FeatureUsageService}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import play.api.libs.json.*
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.Json.JsValueWrapper
import play.api.mvc.*

import java.time.Instant
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class FeatureController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val projectAuthAction: ProjectAuthActionFactory,
    val authenticatedAction: AuthenticatedAction,
    val detailledRightForTenanFactory: DetailledRightForTenantFactory,
    val personnalAccessTokenAuth: PersonnalAccessTokenFeatureAuthActionFactory,
    featureService: FeatureService,
    featureUsageService: FeatureUsageService,
    workerAction: WorkerActionBuilder
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext

  def testFeature(
      tenant: String,
      user: String,
      date: Instant
  ): Action[JsValue] =
    authenticatedAction.async(parse.json) { implicit request =>
      {
        Feature.readCompleteFeature(
          ((request.body \ "feature")
            .as[JsObject])
            .applyOn(json => {
              val hasName = (json \ "name").asOpt[String].exists(_.nonEmpty)
              if (!hasName) {
                json + ("name" -> JsString("test"))
              } else {
                json
              }
            })
        ) match {
          case JsError(e) =>
            BadRequest(Json.obj("message" -> "bad body format")).future
          case JsSuccess(feature, _) => {
            val featureToEval = feature match {
              case w: CompleteWasmFeature
                  if w.wasmConfig.source.kind == WasmSourceKind.Local =>
                w.copy(wasmConfig =
                  w.wasmConfig.copy(source =
                    w.wasmConfig.source
                      .copy(path = s"${tenant}/${w.wasmConfig.source.path}")
                  )
                )
              case f => f
            }
            Feature
              .writeFeatureForCheck(
                featureToEval,
                RequestContext(
                  tenant = "_test_",
                  user = user,
                  now = date,
                  data = (request.body \ "payload")
                    .asOpt[JsObject]
                    .getOrElse(Json.obj())
                ),
                env
              )
              .map {
                case Left(value) => value.toHttpResponse
                case Right(json) => Ok(json)
              }
          }

        }
      }
    }

  def testExistingFeatureWithoutContext(
      tenant: String,
      id: String,
      user: String,
      date: Instant
  ): Action[JsValue] =
    testExistingFeature(tenant, FeatureContextPath(Seq()), id, user, date)

  def testExistingFeature(
      tenant: String,
      context: FeatureContextPath,
      id: String,
      user: String,
      date: Instant
  ): Action[JsValue] = authenticatedAction.async(parse.json) {
    implicit request =>
      {
        lazy val data: JsObject = Option(request.body)
          .flatMap(json => json.asOpt[JsObject])
          .getOrElse(JsObject.empty)
        env.datastores.features
          .findById(
            tenant,
            id
          )
          .flatMap(eitherFeature => {
            eitherFeature.fold(
              err => Future(Results.Status(err.status)(Json.toJson(err))),
              maybeFeature => {
                maybeFeature
                  .map(feature =>
                    env.datastores.featureContext
                      .readStrategyForContext(tenant, context, feature)
                      .flatMap {
                        case Some(strategy) => {
                          strategy
                            .value(
                              RequestContext(
                                tenant = tenant,
                                user,
                                context = context,
                                now = date,
                                data = data
                              ),
                              env
                            )
                            .map {
                              case Left(value)   => value.toHttpResponse
                              case Right(active) =>
                                Ok(
                                  Json.obj(
                                    "active" -> active,
                                    "project" -> feature.project,
                                    "name" -> feature.name
                                  )
                                )
                            }
                        }
                        case None =>
                          Feature
                            .writeFeatureForCheck(
                              feature,
                              RequestContext(
                                tenant = tenant,
                                user = user,
                                now = date,
                                context = context,
                                data = data
                              ),
                              env
                            )
                            .map {
                              case Left(error) => error.toHttpResponse
                              case Right(json) => Ok(json)
                            }
                      }
                  )
                  .getOrElse(
                    Future(
                      NotFound(
                        Json.obj("message" -> s"Feature $id does not exist")
                      )
                    )
                  )
              }
            )
          })

      }
  }

  def checkFeatureForContext(
      id: String,
      user: String,
      context: fr.maif.izanami.web.FeatureContextPath
  ): Action[AnyContent] = workerAction.async { implicit request =>
    {
      val maybeBody =
        request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject])
      val basicAuth: Option[(String, String)] = request.headers
        .get("Authorization")
        .map(header => header.split("Basic "))
        .filter(splitted => splitted.length == 2)
        .map(splitted => splitted(1))
        .map(header => {
          Base64.getDecoder.decode(header.getBytes)
        })
        .map(bytes => new String(bytes))
        .map(header => header.split(":"))
        .filter(arr => arr.length == 2)
        .map(arr => (arr(0), arr(1)))
      val customHeaders: Option[(String, String)] = for {
        clientId <- request.headers.get("Izanami-Client-Id")
        clientSecret <- request.headers.get("Izanami-Client-Secret")
      } yield (clientId, clientSecret)
      val authTuple: Option[(String, String)] = basicAuth.orElse(customHeaders)

      authTuple match {
        case Some((clientId, clientSecret)) => {
          val futureTenant = ApiKey.extractTenant(clientId) match {
            case None => env.datastores.apiKeys.findLegacyKeyTenant(clientId)
            case s @ Some(_) => s.future
          }

          futureTenant
            .flatMap {
              case Some(tenant) =>
                queryFeatures(
                  conditions = false,
                  RequestContext(
                    tenant = tenant,
                    user = user,
                    context = context,
                    data = maybeBody.getOrElse(Json.obj())
                  ),
                  FeatureRequest(
                    features = Set(id),
                    context = context.elements
                  ),
                  clientId,
                  clientSecret,
                  origin = FeatureCall.Sse // FIXME context is passed twice
                ).map {
                  case Left(err)   => err.toHttpResponse
                  case Right(json) =>
                    (json \ id)
                      .asOpt[JsValue]
                      .map(json => Ok(json))
                      .getOrElse(NotFound)
                }
              case None =>
                Unauthorized(
                  Json.obj(
                    "message" -> "Key does not authorize read for this feature"
                  )
                ).future
            }
        }
        case None =>
          Unauthorized(
            Json.obj("message" -> "Missing or incorrect authorization headers")
          ).future
      }
    }
  }

  private def queryFeatures(
      conditions: Boolean,
      requestContext: RequestContext,
      featureRequest: FeatureRequest,
      clientId: String,
      clientSecret: String,
      origin: FeatureCallOrigin
  ): Future[Either[IzanamiError, JsValue]] = {

    val evaluatedFeatures =
      featureService.evaluateFeatures(
        conditions,
        requestContext,
        featureRequest,
        clientId,
        clientSecret
      )
    evaluatedFeatures.map {
      case Left(error)                      => Left(error)
      case Right(evaluatedCompleteFeatures) =>
        val response =
          formatFeatureResponse(evaluatedCompleteFeatures, conditions)
        featureUsageService.registerCalls(
          requestContext.tenant,
          clientId,
          evaluatedCompleteFeatures,
          requestContext.context,
          origin
        )
        // TODO handle error
        Right(response)
    }
  }

  private def formatFeatureResponse(
      evaluatedCompleteFeatures: Seq[EvaluatedCompleteFeature],
      conditions: Boolean
  ): JsValue = {
    val fields = evaluatedCompleteFeatures
      .map(evaluated => {
        val active: JsValueWrapper = evaluated.result
        var baseJson = Json.obj(
          "name" -> evaluated.baseFeature.name,
          "active" -> active,
          "project" -> evaluated.baseFeature.project
        )

        if (conditions) {
          val jsonStrategies = Json
            .toJson(evaluated.featureStrategies.strategies.map {
              case (ctx, feature) => {
                (
                  ctx,
                  writeConditions(feature)
                )
              }
            })
            .as[JsObject]
          baseJson = baseJson + ("conditions" -> jsonStrategies)
        }
        (evaluated.baseFeature.id, baseJson)
      })
      .toMap
    Json.toJson(fields)
  }

  def writeConditions(f: CompleteFeature): JsObject = {
    val resultType: JsValueWrapper =
      Json.toJson(f.resultType)(ResultType.resultTypeWrites)
    val baseJson = Json.obj(
      "enabled" -> f.enabled,
      "resultType" -> resultType
    )
    f match {
      case w: CompleteWasmFeature =>
        baseJson + ("wasmConfig" -> Json.obj("name" -> w.wasmConfig.name))
      case f => {
        val conditions = f match {
          case s: SingleConditionFeature =>
            s.toModernFeature.resultDescriptor.conditions
          case f: Feature => f.resultDescriptor.conditions
          case _ => throw new RuntimeException("This should never happen")
        }

        val maybeValue = f match {
          case f: Feature =>
            f.resultDescriptor match {
              case descriptor: ValuedResultDescriptor =>
                Option(descriptor.jsonValue)
              case _ => None
            }
          case _ => None
        }

        baseJson.applyOnWithOpt(maybeValue)((json, v) =>
          json + ("value" -> v)
        ) + ("conditions" -> Json.toJson(
          conditions
        )(Writes.seq(ActivationCondition.activationConditionWrite)))
      }
    }
  }

  def processInputSeqString(input: Seq[String]): Set[String] = {
    input.filter(str => str.nonEmpty).flatMap(str => str.split(",")).toSet
  }

  def searchFeatures(tenant: String, tag: String): Action[AnyContent] =
    detailledRightForTenanFactory(tenant).async { implicit request =>
      env.datastores.features
        .searchFeature(tenant, if (tag.isBlank) Set() else Set(tag))
        .flatMap(features => {
          featureUsageService.determineStaleStatus(tenant, features).map {
            case Left(err)                           => err.toHttpResponse
            case Right(featuresWithUsageInformation) => {
              Ok(
                Json.toJson(featuresWithUsageInformation)(
                  Writes.seq(writeLightWeightFeatureWithUsageInformation)
                )
              )
            }
          }
        })
    }

  def evaluateFeaturesForContext(
      user: String,
      conditions: Boolean,
      date: Option[Instant],
      featureRequest: FeatureRequest
  ): Action[AnyContent] = workerAction.async { implicit request =>
    {
      val maybeBody =
        request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject])
      val basicAuth: Option[(String, String)] = request.headers
        .get("Authorization")
        .map(header => header.split("Basic "))
        .filter(splitted => splitted.length == 2)
        .map(splitted => splitted(1))
        .map(header => {
          Base64.getDecoder.decode(header.getBytes)
        })
        .map(bytes => new String(bytes))
        .map(header => header.split(":"))
        .filter(arr => arr.length == 2)
        .map(arr => (arr(0), arr(1)))
      val customHeaders: Option[(String, String)] = for {
        clientId <- request.headers.get("Izanami-Client-Id")
        clientSecret <- request.headers.get("Izanami-Client-Secret")
      } yield (clientId, clientSecret)

      val authTuple: Option[(String, String)] = basicAuth.orElse(customHeaders)

      authTuple match {
        case None =>
          Unauthorized(
            Json.obj("message" -> "Missing or incorrect authorization headers")
          ).future
        case Some((clientId, clientSecret)) => {

          val futureMaybeTenant = ApiKey
            .extractTenant(clientId)
            .map(t => Future.successful(Some(t)))
            .getOrElse(env.datastores.apiKeys.findLegacyKeyTenant(clientId))

          futureMaybeTenant
            .flatMap {
              case None         => Left(IncorrectKey()).future
              case Some(tenant) =>
                val requestContext = RequestContext(
                  tenant = tenant,
                  user = user,
                  now = date.getOrElse(Instant.now()),
                  context = FeatureContextPath(featureRequest.context),
                  data = maybeBody.getOrElse(Json.obj())
                )
                queryFeatures(
                  conditions,
                  requestContext,
                  featureRequest,
                  clientId,
                  clientSecret,
                  FeatureCall.Http
                )
            }
            .map {
              case Left(err)    => err.toHttpResponse
              case Right(value) => Ok(value)
            }
        }
      }
    }
  }

  def testFeaturesForContext(
      tenant: String,
      user: String,
      date: Option[Instant],
      featureRequest: FeatureRequest
  ): Action[AnyContent] = authenticatedAction.async { implicit request =>
    val futureFeaturesByProject =
      env.datastores.features.findByRequestV2(
        tenant,
        featureRequest,
        contexts = FeatureContextPath(featureRequest.context),
        request.user.username
      )

    futureFeaturesByProject.flatMap(featuresByProjects => {
      val resultingFeatures = featuresByProjects.values
        .flatMap(featSeq => featSeq.map(f => f.id))
        .toSet
      if (!featureRequest.projects.subsetOf(featuresByProjects.keySet)) {
        val missing = featureRequest.projects.diff(featuresByProjects.keySet)
        Forbidden(
          Json.obj(
            "message" -> s"You're not allowed for projects ${missing.mkString(",")}"
          )
        ).future
      } else if (!featureRequest.features.subsetOf(resultingFeatures)) {
        val missing = featureRequest.features.diff(resultingFeatures)
        Forbidden(
          Json.obj(
            "message" -> s"You're not allowed for features ${missing.mkString(",")}, you don't have right for this project"
          )
        ).future
      } else {
        Future
          .sequence(
            featuresByProjects.values.flatten
              .map(feature =>
                feature
                  .value(
                    RequestContext(
                      tenant = tenant,
                      user = user,
                      now = date.getOrElse(Instant.now()),
                      context = FeatureContextPath(featureRequest.context)
                    ),
                    env
                  )
                  .map(either => (feature, either))
                  .map {
                    case (feature, Left(error)) =>
                      feature.id -> Json
                        .obj(
                          "error" -> error.message,
                          "name" -> feature.name,
                          "project" -> feature.project
                        )
                    case (feature, Right(active)) =>
                      feature.id -> Json.obj(
                        "active" -> active,
                        "name" -> feature.name,
                        "project" -> feature.project
                      )
                  }
              )
          )
          .map(_.toMap)
          .map(map => Ok(Json.toJson(map)))
      }
    })
  }

  def patchFeatures(tenant: String): Action[JsValue] =
    detailledRightForTenanFactory(
      tenant
    ).async(parse.json) { implicit request =>
      request.body
        .asOpt[Seq[FeaturePatch]]
        .map(fs => {
          featureService
            .patchFeatureV2(tenant, fs, request.user, request.authentication)
            .toResult(r => {
              NoContent
            })
        })
        .getOrElse(BadRequest("").future)
    }

  def createFeature(tenant: String, project: String): Action[JsValue] =
    projectAuthAction(tenant, project, ProjectRightLevel.Write).async(
      parse.json
    ) { implicit request =>
      Feature.readCompleteFeature(request.body, project) match {
        case JsError(e) =>
          BadRequest(Json.obj("message" -> "bad body format")).future
        case JsSuccess(f: AbstractFeature, _)
            if env.typedConfiguration.feature.forceLegacy && !f
              .isInstanceOf[SingleConditionFeature] =>
          BadRequest(
            Json.obj(
              "message" -> "Modern feature creation is disabled on this instance, only legacy features are permitted"
            )
          ).future
        case JsSuccess(f: CompleteWasmFeature, _)
            if f.resultType != BooleanResult && f.wasmConfig.opa =>
          BadRequest(
            Json.obj("message" -> "OPA feature must have boolean result type")
          ).future
        case JsSuccess(feature, _) => {
          env.datastores.tags
            .readTags(tenant, feature.tags)
            .flatMap {
              case Right(tags) if tags.size < feature.tags.size => {
                val tagsToCreate =
                  feature.tags.diff(tags.map(t => t.name).toSet)
                env.datastores.tags.createTags(
                  tagsToCreate
                    .map(name => TagCreationRequest(name = name))
                    .toList,
                  tenant
                )
              }
              case either => either.toFuture
            }
            .flatMap(_ =>
              env.datastores.features
                .create(tenant, project, feature, request.user)
                .flatMap { either =>
                  {
                    either match {
                      case Right(id) =>
                        env.datastores.features
                          .findById(tenant, id)
                          .map(either =>
                            either.flatMap(o =>
                              o.toRight(FeatureNotFound(id.toString))
                            )
                          )
                      case Left(err) => Future.successful(Left(err))
                    }
                  }
                }
                .map(maybeFeature =>
                  maybeFeature
                    .fold(
                      err =>
                        err match {
                          case e: TagDoesNotExists =>
                            Results.Status(BAD_REQUEST)(Json.toJson(err))
                          case e => Results.Status(e.status)(Json.toJson(e))
                        },
                      (feat: AbstractFeature) =>
                        Created(Json.toJson(feat)(featureWrite))
                    )
                )
            )
        }
      }
    }

  def updateFeature(
      tenant: String,
      id: String,
      preserveProtectedContexts: Boolean
  ): Action[JsValue] =
    detailledRightForTenanFactory(tenant).async(parse.json) {
      implicit request =>
        Feature.readCompleteFeature(request.body) match {
          case JsError(e) =>
            BadRequest(Json.obj("message" -> "bad body format")).future
          case _ if !Tenant.isTenantValid(tenant) =>
            BadRequest(Json.obj("message" -> "Incorrect tenant")).future
          case JsSuccess(feature, _) => {
            featureService
              .updateFeature(
                BaseFeatureUpdateRequest(
                  tenant = tenant,
                  feature = feature,
                  user = request.user,
                  authentication = request.authentication,
                  preserveProtectedContexts = preserveProtectedContexts,
                  id = id
                )
              )
              .toResult(feature => Ok(Json.toJson(feature)(featureWrite)))
          }
        }
    }

  def isKeyAccreditedForFeature(
      feature: AbstractFeature,
      apiKey: ApiKeyWithCompleteRights
  ): Boolean =
    apiKey.enabled && apiKey.projects.exists(p => p.name == feature.project)

  def convertReadResult(
      either: Either[IzanamiError, Option[AbstractFeature]],
      callback: AbstractFeature => Result,
      id: String = ""
  ): Result = {
    either
      .flatMap(o => o.toRight(FeatureNotFound(id)))
      .fold(
        err => Results.Status(err.status)(Json.toJson(err)),
        feat => callback(feat)
      )
  }

  def findFeature(tenant: String, id: String): Action[AnyContent] =
    detailledRightForTenanFactory(tenant).async { implicit request =>
      featureService
        .findLightWeightFeature(tenant, id, request.user)
        .toResult(f => Ok(Json.toJson(f)(Feature.lightweightFeatureWrite)))
    }

  def deleteFeature(tenant: String, id: String): Action[AnyContent] =
    personnalAccessTokenAuth(
      tenant,
      featureId = id,
      minimumLevel = Write,
      operation = DeleteFeature
    ).async { implicit request =>
      featureService
        .deleteFeature(tenant, id, request.user, request.authentication)
        .toResult(_ => NoContent)
    }
}
