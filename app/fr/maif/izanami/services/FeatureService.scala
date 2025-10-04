package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{FeatureDoesNotExist, FeatureNotFound, IncorrectKey, InternalServerError, IzanamiError, ModernFeaturesForbiddenByConfig, NoProtectedContextAccess, NotEnoughRights, OPAResultMustBeBoolean}
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.models.*
import fr.maif.izanami.models.ProjectRightLevel.Admin
import fr.maif.izanami.models.features.BooleanResult
import fr.maif.izanami.services.FeatureService.{canCreateOrDeleteFeature, impactedProtectedContextsByRootUpdate, isUpdateRequestValid, validateFeature}
import fr.maif.izanami.utils.{FutureEither, Helpers}
import fr.maif.izanami.utils.syntax.implicits.{BetterEither, BetterFuture, BetterFutureEither, BetterSyntax}
import fr.maif.izanami.web.{FeatureContextPath, UserInformation}
import io.vertx.sqlclient.SqlConnection

import scala.concurrent.{ExecutionContext, Future}

class FeatureService(env: Env) {
  private val datastore = env.datastores.features
  implicit val ec: ExecutionContext = env.executionContext

  def findLightWeightFeature(
      tenant: String,
      id: String,
      user: UserWithCompleteRightForOneTenant
  ): FutureEither[LightWeightFeature] = {
    datastore
      .findByIdLightweight(tenant, id)
      .flatMap(o =>
        o
          .filter(f =>
            user.hasRightForProject(f.project, ProjectRightLevel.Read)
          )
          .fold(
            Left(FeatureDoesNotExist(id)).toFEither: FutureEither[
              LightWeightFeature
            ]
          )(f => Right(f).toFEither)
      )
  }

  def deleteFeature(
      tenant: String,
      id: String,
      user: UserWithCompleteRightForOneTenant,
      authentification: EventAuthentication
  ): FutureEither[Unit] = {
    datastore
      .findById(tenant, id)
      .toFEither
      .flatMap {
        case None          => Left(FeatureDoesNotExist(id)).toFEither
        case Some(feature) => {
          if (canCreateOrDeleteFeature(feature, user)) {
            datastore
              .delete(
                tenant,
                id,
                UserInformation(
                  username = user.username,
                  authentication = authentification
                )
              )
              .map(maybeFeature =>
                maybeFeature
                  .map(_ => Right(()))
                  .getOrElse(Left(FeatureDoesNotExist(id)))
              )
              .toFEither
          } else {
            Left(NotEnoughRights).toFEither
          }
        }
      }
  }

  def updateFeature(
      tenant: String,
      id: String,
      feature: CompleteFeature,
      user: UserWithCompleteRightForOneTenant,
      authentication: EventAuthentication,
      maybeConn: Option[SqlConnection] = None
  ): FutureEither[AbstractFeature] = {
    env.postgresql.executeInOptionalTransaction(
      maybeConn,
      conn => {
        for (
          _ <- if (feature.tags.nonEmpty)
            env.datastores.tags
              .createTags(
                feature.tags
                  .map(name => TagCreationRequest(name = name))
                  .toList,
                tenant,
                Some(conn)
              )
              .toFEither
              .map(_ => ())
          else FutureEither.success(());
          _ <- validateFeature(feature).toFEither;
          oldFeature <- datastore
            .findActivationStrategiesForFeature(tenant = tenant, id = id)
            .map(maybeFeature => maybeFeature.toRight(FeatureNotFound(id)))
            .toFEither;
          protectedContexts <- env.datastores.featureContext
            .readProtectedContexts(tenant, feature.project).map(ctxs => ctxs.map(_.fullyQualifiedName)).mapToFEither;
          /*impactedGlobalContexts = impactedProtectedContextsByRootUpdate(
            protectedContexts.map(_.fullyQualifiedName).toSet,
            oldFeature
          );*/
          _ <- isUpdateRequestValid(
            newFeature = feature,
            oldFeature = oldFeature,
            user = user,
            forceLegacy = env.typedConfiguration.feature.forceLegacy,
            protectedContexts = protectedContexts.toSet
          ).toFEither;
          _ <- datastore
            .update(
              tenant = tenant,
              id = id,
              feature = feature,
              user = UserInformation(
                username = user.username,
                authentication = authentication
              ),
              conn = Some(conn)
            )
            .toFEither;
          maybeRes <- datastore
            .findById(tenant, id, conn = Some(conn))
            .toFEither;
          res <- maybeRes
            .toRight(
              InternalServerError("Failed to read feature after its update")
            )
            .toFEither
        ) yield res
      }
    )
  }

  /** Evaluates features for a given request context.
    *
    * @param conditions
    *   whether to return the activation condition in the evaluation result
    * @param requestContext
    *   the request context
    * @param featureRequest
    *   the feature request
    * @param clientId
    *   the client id
    * @param clientSecret
    *   the client secret
    * @return
    *   a future of either an error or a sequence of evaluated features
    */
  def evaluateFeatures(
      conditions: Boolean,
      requestContext: RequestContext,
      featureRequest: FeatureRequest,
      clientId: String,
      clientSecret: String
  ): Future[Either[IzanamiError, Seq[EvaluatedCompleteFeature]]] = {

    val features = retrieveFeatureFromQuery(
      conditions,
      requestContext,
      featureRequest,
      clientId,
      clientSecret
    )
    features.flatMap {
      case Left(error) => Left(error).future
      case Right(f)    => evaluate(f, requestContext, env)
    }

  }

  private def retrieveFeatureFromQuery(
      conditions: Boolean,
      requestContext: RequestContext,
      featureRequest: FeatureRequest,
      clientId: String,
      clientSecret: String
  ): Future[Either[IzanamiError, Seq[FeatureStrategies]]] = {

    if (conditions) {
      val futureFeaturesByProject =
        datastore.doFindByRequestForKey(
          requestContext.tenant,
          featureRequest,
          clientId,
          clientSecret,
          conditions = true
        )
      futureFeaturesByProject.map {
        case Left(error) => Left(error)
        case Right(featuresByProjects) if featuresByProjects.isEmpty =>
          Left(IncorrectKey())
        case Right(featuresByProjects) => {
          val strategies = featuresByProjects.toSeq.flatMap {
            case (_, features) => {
              features.map {
                case (_, featureAndContexts) => {
                  // TODO turn featureAndContext into instance of FeatureStrategies
                  val strategyByCtx = featureAndContexts.map {
                    case (Some(ctx), feat) => (ctx, feat)
                    case (None, feat)      => ("", feat)
                  }.toMap

                  FeatureStrategies(strategyByCtx)
                }
              }
            }
          }
          Right(strategies)
        }
      }
    } else {
      val futureFeaturesByProject = datastore.findByRequestForKey(
        requestContext.tenant,
        featureRequest,
        clientId,
        clientSecret
      )

      futureFeaturesByProject.map {
        case Left(error) => Left(error)
        case Right(featuresByProjects) if featuresByProjects.isEmpty =>
          Left(IncorrectKey())
        case Right(featuresByProjects) => {
          // TODO turn return type of findByRequestForKey to FeatureStrategies so that we don't have to use
          //  an empty context here
          val strategies = featuresByProjects.values.flatten
            .map(v => FeatureStrategies(v))
            .toSeq
          Right(strategies)
        }
      }
    }
  }

  private def evaluate(
      features: Seq[FeatureStrategies],
      requestContext: RequestContext,
      env: Env
  ): Future[Either[IzanamiError, Seq[EvaluatedCompleteFeature]]] = {
    val evaluatedFeatures =
      Future.sequence(features.map(f => f.evaluate(requestContext, env)))
    evaluatedFeatures.map(Helpers.sequence(_))
  }
}

object FeatureService {

  def impactedProtectedContextsByRootUpdate(
      protectedContexts: Set[FeatureContextPath],
      feature: FeatureWithOverloads
  ): Set[FeatureContextPath] = {
    val contextWithOverloads = feature.overloads.keySet
    protectedContexts.diff(contextWithOverloads)
  }

  private def isUpdateRequestValid(
      newFeature: CompleteFeature,
      oldFeature: FeatureWithOverloads,
      user: UserWithCompleteRightForOneTenant,
      forceLegacy: Boolean,
      protectedContexts: Set[FeatureContextPath]
  ): Either[IzanamiError, CompleteFeature] = {
    if (!canUpdateFeatureForProject(oldFeature.project, user)) {
      NotEnoughRights.left
    } else if(
      newFeature.resultType != oldFeature.baseFeature.resultType &&
        protectedContexts.nonEmpty &&
      !user.hasRightForProject(oldFeature.project, Admin)
    ) {
      Left(NoProtectedContextAccess(protectedContexts.map(_.toUserPath).mkString(",")))
    } else if (
      oldFeature.baseFeature.isInstanceOf[SingleConditionFeature] && !newFeature
        .isInstanceOf[SingleConditionFeature] && forceLegacy
    ) {
      ModernFeaturesForbiddenByConfig.left
    } else {
      newFeature.right
    }
  }

  private def canUpdateFeatureForProject(
      project: String,
      user: UserWithCompleteRightForOneTenant
  ): Boolean = {
    if (user.admin) {
      true
    } else {
      val projectRight =
        user.tenantRight.flatMap(tr =>
          tr.projects.get(project).map(_.level).orElse(tr.defaultProjectRight)
        )
      projectRight.exists(currentRight =>
        ProjectRightLevel
          .superiorOrEqualLevels(ProjectRightLevel.Update)
          .contains(currentRight)
      )
    }
  }

  private def validateFeature(
      feature: CompleteFeature
  ): Either[IzanamiError, CompleteFeature] = {
    feature match {
      case f: CompleteWasmFeature
          if f.resultType != BooleanResult && f.wasmConfig.opa =>
        Left(OPAResultMustBeBoolean)
      case _ => Right(feature)
    }
  }

  private def canCreateOrDeleteFeature(
      feature: AbstractFeature,
      user: UserWithCompleteRightForOneTenant
  ): Boolean = {
    if (user.admin) {
      true
    } else {
      val projectRight =
        user.tenantRight.flatMap(tr =>
          tr.projects
            .get(feature.project)
            .map(_.level)
            .orElse(tr.defaultProjectRight)
        )
      projectRight.exists(currentRight =>
        ProjectRightLevel
          .superiorOrEqualLevels(ProjectRightLevel.Write)
          .contains(currentRight)
      )
    }
  }

}
