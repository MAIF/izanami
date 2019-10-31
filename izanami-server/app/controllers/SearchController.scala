package controllers

import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, Interleave, Sink, Source}
import akka.stream.{ActorMaterializer, SourceShape}
import controllers.actions.SecuredAuthContext
import domains.abtesting.ExperimentService
import domains.config.ConfigService
import domains.feature.FeatureService
import domains.script.GlobalScriptService
import domains.GlobalContext
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}
import store.Query
import zio.{Runtime, ZIO}

class SearchController(
    system: ActorSystem,
    AuthAction: ActionBuilder[SecuredAuthContext, AnyContent],
    cc: ControllerComponents
)(implicit R: Runtime[GlobalContext])
    extends AbstractController(cc) {

  import libs.http._
  implicit val mat = ActorMaterializer()(system)

  def search(pattern: String, features: Boolean, configs: Boolean, experiments: Boolean, scripts: Boolean) =
    AuthAction.asyncTask[GlobalContext] { ctx =>
      val query: Query = Query.oneOf(ctx.authorizedPatterns).and(pattern.split(",").toList)

      for {
        featuresRes <- if (features)
                        FeatureService
                          .findByQuery(query, 1, 10)
                          .map(_.results.map(value => Json.obj("type" -> "features", "id" -> Json.toJson(value.id))))
                          .map(value => Source(value.toList))
                      else ZIO.succeed(Source.empty[JsValue])

        configsRes <- if (configs)
                       ConfigService
                         .findByQuery(query, 1, 10)
                         .map(
                           _.results.map(value => Json.obj("type" -> "configurations", "id" -> Json.toJson(value.id)))
                         )
                         .map(value => Source(value.toList))
                     else ZIO.succeed(Source.empty[JsValue])

        experimentsRes <- if (experiments)
                           ExperimentService
                             .findByQuery(query, 1, 10)
                             .map(
                               _.results.map(value => Json.obj("type" -> "experiments", "id" -> Json.toJson(value.id)))
                             )
                             .map(value => Source(value.toList))
                         else ZIO.succeed(Source.empty[JsValue])

        scriptsRes <- if (scripts)
                       GlobalScriptService
                         .findByQuery(query, 1, 10)
                         .map(_.results.map(value => Json.obj("type" -> "scripts", "id" -> Json.toJson(value.id))))
                         .map(value => Source(value.toList))
                     else ZIO.succeed(Source.empty[JsValue])

        res <- ZIO.fromFuture { implicit ec =>
                val all = Source.fromGraph(GraphDSL.create() { implicit builder =>
                  import GraphDSL.Implicits._

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
      } yield res
    }

}
