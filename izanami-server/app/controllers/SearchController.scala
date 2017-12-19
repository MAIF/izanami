package controllers

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{GraphDSL, Interleave, Sink, Source}
import akka.stream.{ActorMaterializer, SourceShape}
import controllers.actions.AuthContext
import domains.abtesting.ExperimentStore
import domains.config.ConfigStore
import domains.feature.FeatureStore
import domains.script.GlobalScriptStore
import domains.webhook.WebhookStore
import env.Env
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc.{
  AbstractController,
  ActionBuilder,
  AnyContent,
  ControllerComponents
}
import store.{DefaultPagingResult, PagingResult}

import scala.concurrent.Future

class SearchController(env: Env,
                       configStore: ConfigStore,
                       featureStore: FeatureStore,
                       experimentStore: ExperimentStore,
                       globalScriptStore: GlobalScriptStore,
                       webhookStore: WebhookStore,
                       system: ActorSystem,
                       AuthAction: ActionBuilder[AuthContext, AnyContent],
                       cc: ControllerComponents)
    extends AbstractController(cc) {

  import system.dispatcher
  implicit val mat = ActorMaterializer()(system)

  private def emptyResult[T] =
    FastFuture.successful(DefaultPagingResult(Seq.empty[T], 1, 0, 0))

  implicit class SourceConversion[T](elt: Future[PagingResult[T]]) {
    def source(): Source[T, NotUsed] =
      Source
        .fromFuture(elt)
        .mapConcat(_.results.toList)
  }

  def search(pattern: String,
             features: Boolean,
             configs: Boolean,
             experiments: Boolean,
             scripts: Boolean) =
    AuthAction.async { ctx =>
      val allPatterns: Seq[String] = ctx.authorizedPatterns :+ pattern

      val all = Source.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val featuresRes: Source[JsValue, NotUsed] =
          if (features)
            featureStore
              .getByIdLike(allPatterns, 1, 10)
              .source()
              .map(value =>
                Json.obj("type" -> "features", "id" -> Json.toJson(value.id)))
          else Source.empty[JsValue]

        val configsRes: Source[JsValue, NotUsed] =
          if (configs)
            configStore
              .getByIdLike(allPatterns, 1, 10)
              .source()
              .map(value =>
                Json.obj("type" -> "configurations",
                         "id" -> Json.toJson(value.id)))
          else Source.empty[JsValue]

        val experimentsRes: Source[JsValue, NotUsed] =
          if (experiments)
            experimentStore
              .getByIdLike(allPatterns, 1, 10)
              .source()
              .map(value =>
                Json.obj("type" -> "experiments",
                         "id" -> Json.toJson(value.id)))
          else Source.empty[JsValue]

        val scriptsRes: Source[JsValue, NotUsed] =
          if (scripts)
            globalScriptStore
              .getByIdLike(allPatterns, 1, 10)
              .source()
              .map(value =>
                Json.obj("type" -> "scripts", "id" -> Json.toJson(value.id)))
          else Source.empty[JsValue]

        val interleave = builder.add(Interleave[JsValue](4, 1))

        featuresRes ~> interleave.in(0)
        configsRes ~> interleave.in(1)
        experimentsRes ~> interleave.in(2)
        scriptsRes ~> interleave.in(3)

        SourceShape(interleave.out)
      })

      all.take(10).runWith(Sink.seq) map { jsons =>
        Ok(JsArray(jsons))
      }
    }

}
