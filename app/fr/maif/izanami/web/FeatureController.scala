package fr.maif.izanami.web

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.FeatureNotFound
import fr.maif.izanami.errors.IzanamiError
import fr.maif.izanami.models.*
import fr.maif.izanami.models.Feature.*
import fr.maif.izanami.models.FeatureCall.FeatureCallOrigin
import fr.maif.izanami.models.LightWeightFeatureWithUsageInformation.writeLightWeightFeatureWithUsageInformation
import fr.maif.izanami.models.ProjectRightLevel.Write
import fr.maif.izanami.models.features.*
import fr.maif.izanami.requests.BaseFeatureUpdateRequest
import fr.maif.izanami.services.FeatureService
import fr.maif.izanami.services.FeatureUsageService
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
import io.otoroshi.wasm4s.scaladsl.WasmSourceKind
import play.api.libs.json.*
import play.api.libs.json.Format.GenericFormat
import play.api.mvc.*

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FeatureController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val authenticatedAction: AuthenticatedAction,
    val projectAuthAction: ProjectAuthActionFactory,
    val detailledRightForTenanFactory: DetailledRightForTenantFactory,
    val personnalAccessTokenDetailledRightForTenantFactory: PersonnalAccessTokenDetailledRightForTenantFactory,
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
        request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject]);

      request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject])
      queryFeatures(
        conditions = false,
        RequestContext(
          tenant = request.tenant,
          user = user,
          context = context,
          data = maybeBody.getOrElse(Json.obj())
        ),
        FeatureRequest(
          features = Set(id),
          context = context.elements
        ),
        request.clientId,
        request.clientSecret,
        origin = FeatureCall.Sse // FIXME context is passed twice
      ).map {
        case Left(err)   => err.toHttpResponse
        case Right(json) =>
          (json \ id)
            .asOpt[JsValue]
            .map(json => Ok(json))
            .getOrElse(NotFound)
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
          FeatureService.formatFeatureResponse(
            evaluatedCompleteFeatures,
            conditions
          )
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
        request.body.asJson.flatMap(jsValue => jsValue.asOpt[JsObject]);

      val requestContext = RequestContext(
        tenant = request.tenant,
        user = user,
        now = date.getOrElse(Instant.now()),
        context = FeatureContextPath(featureRequest.context),
        data = maybeBody.getOrElse(Json.obj())
      )
      queryFeatures(
        conditions,
        requestContext,
        featureRequest,
        request.clientId,
        request.clientSecret,
        FeatureCall.Http
      ).map {
        case Left(err)    => err.toHttpResponse
        case Right(value) => Ok(value)
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

  def patchFeatures(
      tenant: String,
      preserveProtectedContexts: Boolean
  ): Action[JsValue] =
    personnalAccessTokenDetailledRightForTenantFactory(
      tenant
    ).async(parse.json) { implicit request =>
      request.body
        .asOpt[Seq[FeaturePatch]]
        .map(fs => {
          val neededRights = fs.foldLeft(Set(): Set[TenantTokenRights])(
            (necessaryRights, patch) => {
              patch match {
                case EnabledFeaturePatch(value, id) =>
                  necessaryRights + UpdateFeature
                case ProjectFeaturePatch(value, id) =>
                  necessaryRights + UpdateFeature
                case TagsFeaturePatch(value, id) =>
                  necessaryRights + UpdateFeature
                case RemoveFeaturePatch(id) => necessaryRights + DeleteFeature
              }
            }
          )
          val hasRights = neededRights.forall(right => {
            request match {
              case r: UserRequestWithCompleteRightForOneTenantRealUser[_] =>
                true
              case r: UserRequestWithCompleteRightForOneTenantTokenUser[_] =>
                r.token.hasTenantRight(tenant = tenant, right = right)
            }
          })

          if (hasRights) {
            featureService
              .patchFeature(
                tenant,
                fs,
                request.user,
                request.authentication,
                preserveProtectedContexts
              )
              .toResult(_ => {
                NoContent
              })
          } else {
            Future.successful(Unauthorized(Json.obj(
              "message" -> "Your token doesn't have enough right to perform this operation"
            )))
          }
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
        case JsSuccess(feature, _) =>
          featureService.createFeature(
            tenant = tenant,
            project = project,
            feature = feature,
            user = request.user
          )
            .toResult(feat => Created(Json.toJson(feat)(featureWrite)))
      }
    }

  def updateFeature(
      tenant: String,
      id: String,
      preserveProtectedContexts: Boolean
  ): Action[JsValue] =
    personnalAccessTokenDetailledRightForTenantFactory(
      tenant,
      UpdateFeature
    ).async(parse.json) {
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
