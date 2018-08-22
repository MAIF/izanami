package controllers
import cats.effect.IO

package object effect {

  type Effect[F] = IO[F]

  type ApikeyControllerEff       = ApikeyController[Effect]
  type AuthControllerEff         = AuthController[Effect]
  type ConfigControllerEff       = ConfigController[Effect]
  type EventsControllerEff       = EventsController[Effect]
  type ExperimentControllerEff   = ExperimentController[Effect]
  type FeatureControllerEff      = FeatureController[Effect]
  type GlobalScriptControllerEff = GlobalScriptController[Effect]
  type HealthCheckControllerEff  = HealthCheckController[Effect]
  type SearchControllerEff       = SearchController[Effect]
  type UserControllerEff         = UserController[Effect]
  type WebhookControllerEff      = WebhookController[Effect]

}
