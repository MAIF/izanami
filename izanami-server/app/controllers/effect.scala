package controllers
import akka.actor.ActorSystem
import cats.effect.IO
import controllers.actions.{AuthContext, SecuredAuthContext}
import domains.abtesting.{ExperimentService, ExperimentVariantEventService}
import domains.apikey.ApikeyService
import domains.config.ConfigService
import domains.events.EventStore
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.script.Script.ScriptCache
import domains.user.UserService
import domains.webhook.WebhookService
import env.{Env, IzanamiConfig}
import metrics.Metrics
import play.api.mvc.{ActionBuilder, AnyContent, ControllerComponents}
import store.Healthcheck

package object effect {

  type Effect[F] = IO[F]

  class ApikeyControllerEff(apikeyStore: ApikeyService[Effect],
                            system: ActorSystem,
                            AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                            cc: ControllerComponents)
      extends ApikeyController[Effect](apikeyStore, system, AuthAction, cc)

  class AuthControllerEff(_env: Env,
                          userStore: UserService[Effect],
                          AuthAction: ActionBuilder[AuthContext, AnyContent],
                          system: ActorSystem,
                          cc: ControllerComponents)
      extends AuthController[Effect](_env, userStore, AuthAction, system, cc)

  class ConfigControllerEff(configStore: ConfigService[Effect],
                            system: ActorSystem,
                            AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                            cc: ControllerComponents)
      extends ConfigController[Effect](configStore, system, AuthAction, cc)

  class SpringConfigControllerEff(configStore: ConfigService[Effect],
                                  izanamiConfig: IzanamiConfig,
                                  system: ActorSystem,
                                  AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                  cc: ControllerComponents)
      extends SpringConfigController[Effect](configStore, izanamiConfig, system, AuthAction, cc)

  class EventsControllerEff(eventStore: EventStore[Effect],
                            system: ActorSystem,
                            AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                            cc: ControllerComponents)
      extends EventsController[Effect](eventStore, system, AuthAction, cc)

  class ExperimentControllerEff(experimentStore: ExperimentService[Effect],
                                eVariantEventStore: ExperimentVariantEventService[Effect],
                                system: ActorSystem,
                                AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                cc: ControllerComponents)
      extends ExperimentController[Effect](experimentStore, eVariantEventStore, system, AuthAction, cc)

  class FeatureControllerEff(env: Env,
                             featureStore: FeatureService[Effect],
                             system: ActorSystem,
                             AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                             cc: ControllerComponents)
      extends FeatureController[Effect](env, featureStore, system, AuthAction, cc)

  class GlobalScriptControllerEff(env: Env,
                                  globalScriptStore: GlobalScriptService[Effect],
                                  system: ActorSystem,
                                  AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                                  cc: ControllerComponents)(implicit s: ScriptCache[Effect])
      extends GlobalScriptController[Effect](env, globalScriptStore, system, AuthAction, cc)

  class HealthCheckControllerEff(healthcheck: Healthcheck[Effect],
                                 system: ActorSystem,
                                 AuthAction: ActionBuilder[AuthContext, AnyContent],
                                 cc: ControllerComponents)
      extends HealthCheckController[Effect](healthcheck, system, AuthAction, cc)

  class SearchControllerEff(configStore: ConfigService[Effect],
                            featureStore: FeatureService[Effect],
                            experimentStore: ExperimentService[Effect],
                            globalScriptStore: GlobalScriptService[Effect],
                            webhookStore: WebhookService[Effect],
                            system: ActorSystem,
                            AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                            cc: ControllerComponents)
      extends SearchController[Effect](configStore,
                                       featureStore,
                                       experimentStore,
                                       globalScriptStore,
                                       webhookStore,
                                       system,
                                       AuthAction,
                                       cc)

  class UserControllerEff(userStore: UserService[Effect],
                          system: ActorSystem,
                          AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                          cc: ControllerComponents)
      extends UserController[Effect](userStore, system, AuthAction, cc)

  class WebhookControllerEff(webhookStore: WebhookService[Effect],
                             system: ActorSystem,
                             AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
                             cc: ControllerComponents)
      extends WebhookController[Effect](webhookStore, system, AuthAction, cc)

  class MetricControllerEff(metrics: Metrics[Effect],
                            AuthAction: ActionBuilder[AuthContext, AnyContent],
                            cc: ControllerComponents)
      extends MetricController[Effect](metrics, AuthAction, cc)

}
