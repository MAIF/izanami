package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{FeatureContextDoesNotExist, FeatureDoesNotExist, FeatureNotFound, IncorrectKey, InternalServerError, IzanamiError, ModernFeaturesForbiddenByConfig, NoProtectedContextAccess, NotEnoughRights, OPAResultMustBeBoolean}
import fr.maif.izanami.events.EventAuthentication
import fr.maif.izanami.models.*
import fr.maif.izanami.models.ProjectRightLevel.{Admin, Read, Update, Write, projectRightLevelReads}
import fr.maif.izanami.models.features.{ActivationCondition, BooleanResult, EnabledFeaturePatch, FeaturePatch, ProjectFeaturePatch, RemoveFeaturePatch, ResultType, TagsFeaturePatch, ValuedResultDescriptor}
import fr.maif.izanami.requests.{BaseFeatureUpdateRequest, FeatureUpdateRequest, OverloadFeatureUpdateRequest}
import fr.maif.izanami.services.FeatureService.{canCreateOrDeleteFeature, computeRootContexts, impactedProtectedContextsByUpdate, validateFeature}
import fr.maif.izanami.utils.{Done, FutureEither, Helpers}
import fr.maif.izanami.utils.syntax.implicits.{BetterEither, BetterFuture, BetterFutureEither, BetterSyntax}
import fr.maif.izanami.v1.OldFeature
import fr.maif.izanami.web.{FeatureContextPath, UserInformation}
import io.vertx.sqlclient.SqlConnection
import play.api.libs.json.{JsObject, JsValue, Json, Writes}
import play.api.libs.json.Json.JsValueWrapper

import scala.concurrent.{ExecutionContext, Future}

class FeatureService(env: Env) {
  private val datastore = env.datastores.features
  implicit val ec: ExecutionContext = env.executionContext

  private def hasProtectedOverload(
      tenant: String,
      featureIds: Set[String]
  ): FutureEither[Map[String, Boolean]] = {
    for (
      featureById <- datastore
        .findActivationStrategiesForFeatures(tenant, featureIds)
        .mapToFEither;
      projects = featureById.values.map(f => f.project).toSet;
      protectedContextsByProject <- Future
        .sequence(
          projects.map(p =>
            env.datastores.featureContext
              .readProtectedContexts(
                tenant = tenant,
                project = p
              )
              .map(protectedContects => (p, protectedContects.toSet))
          )
        )
        .map(s => s.toMap)
        .mapToFEither
    ) yield {
      featureById.map { (fid, feature) =>
        {
          val protectedContetxs =
            protectedContextsByProject.getOrElse(feature.project, Set())
          val hasProtectedOverload =
            protectedContetxs
              .map(c => c.fullyQualifiedName)
              .exists(p => feature.overloads.keySet.contains(p))

          (fid, hasProtectedOverload)
        }
      }
    }
  }

  private def hasProtectedOverload(
      tenant: String,
      feature: FeatureWithOverloads
  ): FutureEither[Boolean] = {
    env.datastores.featureContext
      .readProtectedContexts(
        tenant = tenant,
        project = feature.project
      )
      .map(protectedContetxs => {
        protectedContetxs
          .map(c => c.fullyQualifiedName)
          .exists(p => feature.overloads.keySet.contains(p))
      })
      .mapToFEither
  }

  def patchFeatureV2(
      tenant: String,
      patches: Seq[FeaturePatch],
      user: UserWithCompleteRightForOneTenant,
      authentication: EventAuthentication
  ): FutureEither[Done] = {
    datastore
      .findByIds(
        tenant,
        patches.map(_.id).toSet
      )
      .flatMap(features => {
        env.postgresql.executeInTransaction(conn => {
          patches.foldLeft(FutureEither.success(Done.done()))((acc, next) => {
            acc.flatMap(_ => {
              next match {
                case EnabledFeaturePatch(value, id, preserveProtectedContexts) => {
                  features
                    .get(id)
                    .map(feature => {
                      val featureToUpdate = feature.withEnabled(value)
                      updateFeature(
                        BaseFeatureUpdateRequest(
                          feature = featureToUpdate,
                          tenant = tenant,
                          id = id,
                          authentication = authentication,
                          user = user,
                          preserveProtectedContexts = preserveProtectedContexts
                        ),
                        conn = Some(conn)
                      ).map(_ => Done.done())
                    })
                    .getOrElse(FutureEither.failure(FeatureDoesNotExist(id)))
                }
                case ProjectFeaturePatch(value, id) => {
                  features
                    .get(id)
                    .map(feature => {
                      val featureToUpdate = feature.withProject(value)
                      updateFeature(
                        BaseFeatureUpdateRequest(
                          feature = featureToUpdate,
                          tenant = tenant,
                          id = id,
                          authentication = authentication,
                          user = user,
                          preserveProtectedContexts = false
                        ),
                        conn = Some(conn)
                      ).map(_ => Done.done())
                    })
                    .getOrElse(FutureEither.failure(FeatureDoesNotExist(id)))
                }
                case TagsFeaturePatch(value, id) => {
                  features
                    .get(id)
                    .map(feature => {
                      val featureToUpdate = feature.appendedTags(value)
                      updateFeature(
                        BaseFeatureUpdateRequest(
                          feature = featureToUpdate,
                          tenant = tenant,
                          id = id,
                          authentication = authentication,
                          user = user,
                          preserveProtectedContexts = false
                        ),
                        conn = Some(conn)
                      ).map(_ => Done.done())
                    })
                    .getOrElse(FutureEither.failure(FeatureDoesNotExist(id)))
                }
                case RemoveFeaturePatch(id) =>
                  deleteFeature(tenant, id, user, authentication, conn = Some(conn))
                    .map(_ => Done.done())
              }
            })
          })
        })
      })
  }

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

  def deleteOverload(
      tenant: String,
      project: String,
      name: String,
      contextPath: FeatureContextPath,
      preserveProtectedContexts: Boolean,
      user: UserWithCompleteRightForOneTenant,
      userInformation: UserInformation
  ): FutureEither[Done] = {
    for (
      context <- env.datastores.featureContext
        .readContext(tenant = tenant, path = contextPath)
        .map(o => o.toRight(FeatureContextDoesNotExist(contextPath.toUserPath)))
        .toFEither;
      _ <- if (
        context.isProtected && !user.hasRightForProject(
          project,
          ProjectRightLevel.Admin
        )
      ) {
        FutureEither.failure(NoProtectedContextAccess(contextPath.toUserPath))
      } else {
        FutureEither.success(())
      };
      _ <- if (!user.hasRightForProject(project, ProjectRightLevel.Write)) {
        FutureEither.failure(NotEnoughRights())
      } else {
        FutureEither.success(())
      };
      oldFeature <- datastore
        .findActivationStrategiesForFeatureByName(
          tenant = tenant,
          name = name,
          project = project
        )
        .map(maybeFeature => maybeFeature.toRight(FeatureNotFound(name)))
        .toFEither;
      protectedContexts <- env.datastores.featureContext
        .readProtectedContexts(
          tenant,
          project,
          Some(contextPath)
        )
        .map(ctxs => ctxs.map(_.fullyQualifiedName))
        .mapToFEither;
      impactedProtectedContexts = impactedProtectedContextsByUpdate(
        protectedContexts = protectedContexts.toSet,
        currentOverloads = oldFeature.overloads.keySet,
        updatedContext = contextPath
      );
      _ <- if (
        impactedProtectedContexts.nonEmpty && !preserveProtectedContexts && !user
          .hasRightForProject(
            project = project,
            level = ProjectRightLevel.Admin
          ) && LightWeightFeature.hasStrategyChanged(
          oldFeature.strategyFor(contextPath),
          oldFeature.removeOverload(contextPath).strategyFor(contextPath)
        )
      ) {
        FutureEither.failure(NotEnoughRights()) // FIXME better message
      } else if (
        impactedProtectedContexts.nonEmpty && preserveProtectedContexts
      ) {
        val protectedContextToUpdate = computeRootContexts(
          impactedProtectedContexts
        );
        env.postgresql.executeInTransaction(conn => {
          for (
            oldStrategy <- oldFeature
              .strategyFor(contextPath)
              .toCompleteFeature(tenant, env)
              .toFEither
              .map(completeFeature =>
                completeFeature.toCompleteContextualStrategy
              );
            _ <- protectedContextToUpdate
              .foldLeft(FutureEither.success(()))((res, ctx) => {
                res.flatMap(_ =>
                  env.datastores.featureContext
                    .updateFeatureStrategy(
                      tenant = tenant,
                      project = oldFeature.project,
                      path = ctx,
                      feature = oldFeature.name,
                      strategy = oldStrategy,
                      user = UserInformation(
                        username = user.username,
                        authentication = userInformation.authentication
                      ),
                      conn = Some(conn)
                    )
                    .toFEither
                )
              });
            res <- env.datastores.featureContext
              .deleteFeatureStrategy(
                tenant,
                project,
                contextPath,
                name,
                userInformation,
                conn = Some(conn)
              )
              .toFEither
          ) yield res
        })

      } else {
        // TODO handle preserveProtectedContexts
        env.datastores.featureContext
          .deleteFeatureStrategy(
            tenant,
            project,
            contextPath,
            name,
            userInformation
          )
          .toFEither
      }
    ) yield Done.done()
  }

  def canUpdateContext(
      tenant: String,
      user: UserWithCompleteRightForOneTenant,
      contextPath: FeatureContextPath
  ): Future[Either[IzanamiError, Context]] = {
    env.datastores.featureContext
      .readContext(tenant, contextPath)
      .map(o => o.toRight(FeatureContextDoesNotExist(contextPath.toUserPath)))
      .map(e =>
        e.flatMap {
          case c: GlobalContext
              if c.isProtected && user.hasRightForTenant(RightLevel.Admin) =>
            Right(c)
          case c: GlobalContext if c.isProtected =>
            Left(NoProtectedContextAccess(contextPath.toUserPath))
          case c: GlobalContext if user.hasRightForTenant(RightLevel.Write) =>
            Right(c)
          case c: GlobalContext => Left(NotEnoughRights)
          case c: LocalContext
              if c.isProtected && user
                .hasRightForProject(c.project, ProjectRightLevel.Admin) =>
            Right(c)
          case c: LocalContext if c.isProtected =>
            Left(NoProtectedContextAccess(contextPath.toUserPath))
          case c: LocalContext
              if user.hasRightForProject(c.project, ProjectRightLevel.Write) =>
            Right(c)
          case _ => Left(NotEnoughRights)
        }
      )
  }

  def deleteFeature(
      tenant: String,
      id: String,
      user: UserWithCompleteRightForOneTenant,
      authentification: EventAuthentication,
      conn: Option[SqlConnection] = None
  ): FutureEither[Unit] = {
    env.postgresql.executeInOptionalTransaction(conn, conn => {
      for (
        maybeFeature <- datastore
          .findActivationStrategiesForFeature(tenant, id)
          .mapToFEither;
        feature <- maybeFeature
          .map(f => FutureEither.success(f))
          .getOrElse(FutureEither.failure(FeatureDoesNotExist(id)));
        hasProtectedOverload <- hasProtectedOverload(
          tenant,
          feature = feature
        );
        hasDeleteRightOnProject = canCreateOrDeleteFeature(feature.project, user);
        res <- if (!hasDeleteRightOnProject) {
          FutureEither.failure(
            NotEnoughRights(
              "You don't have right to delete feature for this project"
            )
          )
        } else if (
          hasProtectedOverload && !user.hasRightForProject(feature.project, Admin)
        ) {
          FutureEither.failure(
            NotEnoughRights(
              "You don't have right to delete a feature with protected overload for this project"
            )
          )
        } else {
          datastore
            .delete(
              tenant,
              id,
              UserInformation(
                username = user.username,
                authentication = authentification
              ),
              conn=Some(conn)
            )
            .map(maybeFeature =>
              maybeFeature
                .map(_ => Right(()))
                .getOrElse(Left(FeatureDoesNotExist(id)))
            )
            .toFEither
        }
      ) yield res
    })

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
      request: BaseFeatureUpdateRequest,
      conn: Option[SqlConnection] = None
  ): FutureEither[AbstractFeature] = {
    env.postgresql.executeInOptionalTransaction(
      conn,
      conn => {
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
              .hasRightForProject(
                request.feature.project,
                Write
              ) || !request.user
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
      }
    )
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
          impactedProtectedContexts = impactedProtectedContextsByUpdate(
            protectedContexts = protectedContexts.toSet,
            currentOverloads = oldFeature.overloads.keySet,
            updatedContext =
              request.maybeContext.getOrElse(FeatureContextPath(Seq()))
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
            oldFeature = oldFeature,
            updateRequest = request,
            impactedRootProtectedContexts = protectedContextToUpdate
          );
          f <- if (
            request.preserveProtectedContexts && protectedContextToUpdate.nonEmpty
          ) {
            for (
              oldStrategy <- oldFeature
                .strategyFor(
                  request.maybeContext.getOrElse(FeatureContextPath())
                )
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
      oldFeature: FeatureWithOverloads,
      updateRequest: FeatureUpdateRequest,
      impactedRootProtectedContexts: Set[FeatureContextPath]
  ): FutureEither[Unit] = {
    (
      updateRequest,
      updateRequest.user.rightLevelForProject(updateRequest.project),
      oldFeature
    ) match {
      case (_, None, _)       => FutureEither.failure(NotEnoughRights)
      case (_, Some(Read), _) => FutureEither.failure(NotEnoughRights)
      case (f: BaseFeatureUpdateRequest, _, oldF)
          if oldF.project != f.project && !f.user.hasRightForProject(
            oldF.project,
            Admin
          ) =>
        FutureEither.failure(
          NotEnoughRights(
            "You are not allowed to transfer a feature with protected context overloads, since this operation would destroy protected overloads"
          )
        )
      case (_, Some(Admin), _) => FutureEither.success(())
      case (f: BaseFeatureUpdateRequest, r, oldF)
          if (!LightWeightFeature.hasStrategyChanged(
            f.feature.toLightWeightFeature,
            oldF.baseFeature
          )) && r.exists(l =>
            ProjectRightLevel.superiorOrEqualLevels(Update).contains(l)
          ) =>
        FutureEither.success(())
      case (f, _, _)
          if !f.preserveProtectedContexts && impactedRootProtectedContexts.nonEmpty => {
        FutureEither.failure(
          NoProtectedContextAccess(
            impactedRootProtectedContexts
              .map(ctx => ctx.toUserPath)
              .mkString(",")
          )
        )
      }
      case (f: OverloadFeatureUpdateRequest, _, _) => {
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
  def formatFeatureResponse(
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
                  FeatureService.writeConditions(feature)
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

  def impactedProtectedContextsByUpdate(
      protectedContexts: Set[FeatureContextPath],
      currentOverloads: Set[FeatureContextPath],
      updatedContext: FeatureContextPath
  ): Set[FeatureContextPath] = {
    protectedContexts
      .filter(protectedContext =>
        updatedContext.isAscendantOf(protectedContext)
      )
      .filterNot(protectedContext => {
        currentOverloads.exists(overloadContext => {
          overloadContext == protectedContext || overloadContext.isAscendantOf(
            protectedContext
          ) && overloadContext != updatedContext
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
      project: String,
      user: UserWithCompleteRightForOneTenant
  ): Boolean = {
    if (user.admin) {
      true
    } else {
      val projectRight =
        user.tenantRight.flatMap(tr =>
          tr.projects
            .get(project)
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
