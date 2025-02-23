package fr.maif.izanami.services

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IncorrectKey, IzanamiError}
import fr.maif.izanami.models._
import fr.maif.izanami.utils.Helpers
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax

import scala.concurrent.{ExecutionContext, Future}

class FeatureService(env: Env) {

  implicit val ec: ExecutionContext = env.executionContext

  /**
   * Evaluates features for a given request context.
   *
   * @param conditions whether to return the activation condition in the evaluation result
   * @param requestContext the request context
   * @param featureRequest the feature request
   * @param clientId the client id
   * @param clientSecret the client secret
   * @return a future of either an error or a sequence of evaluated features
   */
  def evaluateFeatures(
      conditions: Boolean,
      requestContext: RequestContext,
      featureRequest: FeatureRequest,
      clientId: String,
      clientSecret: String
  ): Future[Either[IzanamiError, Seq[EvaluatedCompleteFeature]]] = {

    val features = retrieveFeatureFromQuery(conditions, requestContext, featureRequest, clientId, clientSecret)
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
        env.datastores.features.doFindByRequestForKey(requestContext.tenant, featureRequest, clientId, clientSecret, true)
      futureFeaturesByProject.map {
        case Left(error)                                             => Left(error)
        case Right(featuresByProjects) if featuresByProjects.isEmpty => Left(IncorrectKey())
        case Right(featuresByProjects)                               => {
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
      val futureFeaturesByProject = env.datastores.features.findByRequestForKey(
        requestContext.tenant,
        featureRequest,
        clientId,
        clientSecret
      )

      futureFeaturesByProject.map {
        case Left(error)                                             => Left(error)
        case Right(featuresByProjects) if featuresByProjects.isEmpty => Left(IncorrectKey())
        case Right(featuresByProjects)                               => {
          // TODO turn return type of findByRequestForKey to FeatureStrategies so that we don't have to use
          //  an empty context here
          val strategies = featuresByProjects.values.flatten.map(v => FeatureStrategies(v)).toSeq
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
    val evaluatedFeatures = Future.sequence(features.map(f => f.evaluate(requestContext, env)))
    evaluatedFeatures.map(Helpers.sequence(_))
  }
}
