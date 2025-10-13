package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{
  FeatureContextDoesNotExist,
  FeatureDoesNotExist,
  FeatureNotFound,
  IncorrectKey,
  InternalServerError,
  IzanamiError,
  ModernFeaturesForbiddenByConfig,
  NoProtectedContextAccess,
  NotEnoughRights,
  OPAResultMustBeBoolean
}
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.models.*
import fr.maif.izanami.models.ProjectRightLevel.{Admin, Read, Update, Write}
import fr.maif.izanami.models.features.BooleanResult
import fr.maif.izanami.requests.{
  BaseFeatureUpdateRequest,
  FeatureUpdateRequest,
  OverloadFeatureUpdateRequest
}
import fr.maif.izanami.services.FeatureService.{
  canCreateOrDeleteFeature,
  computeRootContexts,
  impactedProtectedContextsByRootUpdate,
  validateFeature
}
import fr.maif.izanami.utils.{FutureEither, Helpers}
import fr.maif.izanami.utils.syntax.implicits.{
  BetterEither,
  BetterFuture,
  BetterFutureEither,
  BetterSyntax
}
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
  ): FutureEither[String] = {
    for (
      maybeFeature <- datastore
        .findActivationStrategiesForFeature(tenant, id)
        .mapToFEither;
      feature <- maybeFeature.toRight(FeatureDoesNotExist(id)).toFEither;
      protectedContexts <- env.datastores.featureContext
        .readProtectedContexts(tenant = tenant, project = feature.project)
        .mapToFEither;
      _ <- if !canCreateOrDeleteFeature(feature.baseFeature, user) then
        FutureEither.failure(NotEnoughRights)
      else FutureEither.success(());
      _ <- if protectedContexts
          .map(_.fullyQualifiedName)
          .exists(feature.overloads.contains) && !user.hasRightForProject(
          feature.project,
          Admin
        )
      then {
        val problematicContexts = protectedContexts
          .map(_.fullyQualifiedName)
          .intersect(feature.overloads.toSeq)
        FutureEither.failure(
          NoProtectedContextAccess(
            problematicContexts.map(_.toUserPath).mkString(",")
          )
        );
      } else {
        FutureEither.success(())
      };
      res <- datastore
        .delete(
          tenant,
          id,
          UserInformation(
            username = user.username,
            authentication = authentification
          )
        )
        .toFEither
    ) yield res
  }

  /** Data container providing information about an update impact on context
    * @param hasProtectedContext
    *   indicate whether updated feature project has at least one protected
    *   context
    * @param impactedProtectedContexts
    *   indicate whether an update of the given feature will impact any
    *   protected context
    */
  case class UpdateContextInformation(
      hasProtectedContext: Boolean,
      impactedProtectedContexts: Set[FeatureContextPath]
  )

  private def computeUpdateContextInformation(
      tenant: String,
      project: String,
      contextsWithOverloads: Set[FeatureContextPath]
  ): FutureEither[UpdateContextInformation] = {
    for (
      protectedContexts <- env.datastores.featureContext
        .readProtectedContexts(tenant, project)
        .map(ctxs => ctxs.map(_.fullyQualifiedName))
        .mapToFEither;
      impactedProtectedContexts = impactedProtectedContextsByRootUpdate(
        protectedContexts = protectedContexts.toSet,
        currentOverloads = contextsWithOverloads
      )
    )
      yield UpdateContextInformation(
        hasProtectedContext = protectedContexts.nonEmpty,
        impactedProtectedContexts = impactedProtectedContexts
      )
  }

  def upsertOverload(
      request: OverloadFeatureUpdateRequest
  ): FutureEither[Unit] = {
    for (
      oldFeature <- datastore
        .findActivationStrategiesForFeatureByName(
          tenant = request.tenant,
          name = request.featureName,
          project = request.project
        )
        .map(maybeFeature =>
          maybeFeature.toRight(FeatureNotFound(request.featureName))
        )
        .toFEither;
      res <- doUpdateFeature(request, oldFeature)
    ) yield res

  }

  def updateFeature(
      request: BaseFeatureUpdateRequest
  ): FutureEither[AbstractFeature] = {
    env.postgresql.executeInTransaction(conn => {
      for (
        _ <- env.datastores.tags
          .createTags(
            request.tags
              .map(name => TagCreationRequest(name = name))
              .toList,
            request.tenant,
            Some(conn)
          )
          .toFEither;
        oldFeature <- datastore
          .findActivationStrategiesForFeature(
            tenant = request.tenant,
            id = request.id
          )
          .map(maybeFeature =>
            maybeFeature.toRight(FeatureNotFound(request.featureName))
          )
          .toFEither;
        _ <- if (
          request.feature.project != oldFeature.project &&
          (!request.user
            .hasRightForProject(request.feature.project, Write) || !request.user
            .hasRightForProject(oldFeature.project, Write))
        ) {
          FutureEither.failure(
            NotEnoughRights
          )
        } else {
          FutureEither.success(())
        };
        _ <- if (
          oldFeature.baseFeature
            .isInstanceOf[SingleConditionFeature] && !request.feature
            .isInstanceOf[
              SingleConditionFeature
            ] && env.typedConfiguration.feature.forceLegacy
        ) {
          FutureEither.failure(ModernFeaturesForbiddenByConfig)
        } else {
          FutureEither.success(())
        };
        _ <- doUpdateFeature(request, oldFeature, Some(conn));
        id = request.id;
        maybeRes <- datastore
          .findById(request.tenant, id, conn = Some(conn))
          .toFEither;
        res <- maybeRes
          .toRight(
            InternalServerError("Failed to read feature after its update")
          )
          .toFEither
      ) yield res
    })
  }

  private def doUpdateFeature(
      request: FeatureUpdateRequest,
      oldFeature: FeatureWithOverloads,
      maybeConn: Option[SqlConnection] = None
  ): FutureEither[Unit] = {

    env.postgresql.executeInOptionalTransaction(
      maybeConn,
      conn => {
        for (
          _ <- validateFeature(
            request.strategy
          ).toFEither; // TODO replace by validation on Reads[AbstractFeature]
          protectedContexts <- env.datastores.featureContext
            .readProtectedContexts(
              request.tenant,
              request.project,
              request.maybeContext
            )
            .map(ctxs => ctxs.map(_.fullyQualifiedName))
            .mapToFEither;
          impactedProtectedContexts = impactedProtectedContextsByRootUpdate(
            protectedContexts = protectedContexts.toSet,
            currentOverloads = oldFeature.overloads.keySet
          );
          _ <- if (
            request.strategy.resultType != oldFeature.baseFeature.resultType && protectedContexts.nonEmpty && !request.user
              .hasRightForProject(request.project, Admin)
          ) {
            FutureEither.failure(
              NoProtectedContextAccess(
                impactedProtectedContexts.map(_.toUserPath).mkString(",")
              )
            )
          } else { FutureEither.success(()) };
          protectedContextToUpdate = computeRootContexts(
            impactedProtectedContexts
          );
          _ <- hasRightToUpdate(
            updateRequest = request,
            impactedRootProtectedContexts = protectedContextToUpdate
          );
          f <- if (
            request.preserveProtectedContexts && protectedContextToUpdate.nonEmpty
          ) {
            for (
              oldStrategy <- oldFeature.baseFeature
                .toCompleteFeature(request.tenant, env)
                .toFEither
                .map(completeFeature =>
                  completeFeature.toCompleteContextualStrategy
                );
              res <- protectedContextToUpdate
                .foldLeft(FutureEither.success(()))((res, ctx) => {
                  res.flatMap(_ =>
                    env.datastores.featureContext
                      .updateFeatureStrategy(
                        tenant = request.tenant,
                        project = oldFeature.project,
                        path = ctx,
                        feature = oldFeature.name,
                        strategy = oldStrategy,
                        user = UserInformation(
                          username = request.user.username,
                          authentication = request.authentication
                        ),
                        conn = Some(conn)
                      )
                      .toFEither
                  )
                })
            ) yield res
          } else FutureEither.success(());
          res <- request match {
            case r: BaseFeatureUpdateRequest =>
              datastore
                .update(
                  tenant = request.tenant,
                  id = oldFeature.id,
                  feature = r.feature,
                  user = r.userInformation,
                  conn = Some(conn)
                )
                .toFEither
                .map(_ => ());
            case r: OverloadFeatureUpdateRequest =>
              env.datastores.featureContext
                .updateFeatureStrategy(
                  request.tenant,
                  request.project,
                  r.context,
                  r.featureName,
                  request.strategy,
                  UserInformation(
                    username = request.user.username,
                    authentication = request.authentication
                  ),
                  conn = Some(conn)
                )
                .toFEither
          }
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

  private def hasRightToUpdate(
      updateRequest: FeatureUpdateRequest,
      impactedRootProtectedContexts: Set[FeatureContextPath]
  ): FutureEither[Unit] = {
    (
      updateRequest,
      updateRequest.user.rightLevelForProject(updateRequest.project)
    ) match {
      case (_, None)        => FutureEither.failure(NotEnoughRights)
      case (_, Some(Read))  => FutureEither.failure(NotEnoughRights)
      case (_, Some(Admin)) => FutureEither.success(())
      case (f, _)
          if !f.preserveProtectedContexts && impactedRootProtectedContexts.nonEmpty => {
        FutureEither.failure(
          NoProtectedContextAccess(
            impactedRootProtectedContexts
              .map(ctx => ctx.toUserPath)
              .mkString(",")
          )
        )
      }
      case (f: OverloadFeatureUpdateRequest, _) => {
        env.datastores.featureContext
          .readContext(f.tenant, f.context)
          .mapToFEither
          .flatMap {
            case None =>
              FutureEither.failure(
                FeatureContextDoesNotExist(f.context.toUserPath)
              )
            case Some(ctx) if ctx.isProtected =>
              FutureEither.failure(
                NoProtectedContextAccess(ctx.fullyQualifiedName.toUserPath)
              )
            case Some(_) => FutureEither.success(())
          }
      }
      case _ => FutureEither.success(())
    }
  }
}

object FeatureService {
  def impactedProtectedContextsByRootUpdate(
      protectedContexts: Set[FeatureContextPath],
      currentOverloads: Set[FeatureContextPath]
  ): Set[FeatureContextPath] = {
    protectedContexts.filterNot(protectedContext => {
      currentOverloads.exists(overloadContext => {
        overloadContext == protectedContext || overloadContext.isAscendantOf(
          protectedContext
        )
      })
    })
  }

  def computeRootContexts(
      contexts: Set[FeatureContextPath]
  ): Set[FeatureContextPath] = {
    contexts
      .filter(_.elements.nonEmpty)
      .groupBy(ctx => ctx.elements.head)
      .map(ctxGroup => ctxGroup._2.minBy(_.elements.length))
      .toSet
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
      strategy: CompleteContextualStrategy
  ): Either[IzanamiError, Unit] = {
    strategy match {
      case f: CompleteWasmFeatureStrategy
          if f.resultType != BooleanResult && f.wasmConfig.opa =>
        Left(OPAResultMustBeBoolean)
      case _ => Right(())
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
