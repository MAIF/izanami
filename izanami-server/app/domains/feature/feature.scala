package domains.feature

import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Source}
import akka.{Done, NotUsed}
import cats.effect.Effect
import domains.events.EventStore
import domains.feature.Feature.FeatureKey
import domains.script.{GlobalScriptService, Script}
import domains.{ImportResult, Key}
import env.Env
import libs.functional.EitherTSyntax
import play.api.Logger
import play.api.libs.json._
import store.Result._
import store._

import scala.concurrent.ExecutionContext

sealed trait Strategy

sealed trait Feature {

  def id: FeatureKey

  def enabled: Boolean

  def toJson(active: Boolean) =
    FeatureInstances.format.writes(this).as[JsObject] ++ Json.obj("active" -> active)

}

object Feature {
  type FeatureKey = Key

  def withActive[F[_]: Effect](context: JsObject, env: Env)(
      implicit ec: ExecutionContext,
      isActive: IsActive[F, Feature]
  ): Flow[Feature, (Boolean, Feature), NotUsed] = {
    import cats.implicits._
    Flow[Feature]
      .mapAsyncUnordered(2) { feature =>
        import cats.effect.implicits._
        isActive
          .isActive(feature, context, env)
          .map(
            _.map(act => (act && feature.enabled, feature))
              .getOrElse((false, feature))
          )
          .toIO
          .unsafeToFuture()
          .recover {
            case _ => (false, feature)
          }
      }
  }

  def toGraph[F[_]: Effect](context: JsObject, env: Env)(
      implicit ec: ExecutionContext,
      isActive: IsActive[F, Feature]
  ): Flow[Feature, JsObject, NotUsed] =
    Flow[Feature]
      .via(withActive(context, env))
      .map {
        case (active, f) => FeatureInstances.graphWrites(active).writes(f)
      }
      .fold(Json.obj()) { (acc, js) =>
        acc.deepMerge(js.as[JsObject])
      }

  def flat[F[_]: Effect](
      context: JsObject,
      env: Env
  )(implicit ec: ExecutionContext, isActive: IsActive[F, Feature]): Flow[Feature, JsValue, NotUsed] =
    Flow[Feature]
      .via(withActive(context, env))
      .map {
        case (active, f) => f.toJson(active)
      }
      .fold(Seq.empty[JsValue]) { _ :+ _ }
      .map(JsArray(_))

}

trait IsActive[F[_], A <: Feature] {
  def isActive(feature: A, context: JsObject, env: Env): F[Result[Boolean]]
}

object IsActive {
  type FeatureActive[F[_]] = IsActive[F, Feature]
  def apply[F[_]](implicit A: IsActive[F, Feature]): IsActive[F, Feature] = A
}

case class DefaultFeature(id: FeatureKey, enabled: Boolean, parameters: JsValue = JsNull)             extends Feature
case class GlobalScriptFeature(id: FeatureKey, enabled: Boolean, ref: String)                         extends Feature
case class ScriptFeature(id: FeatureKey, enabled: Boolean, script: Script)                            extends Feature
case class DateRangeFeature(id: FeatureKey, enabled: Boolean, from: LocalDateTime, to: LocalDateTime) extends Feature
case class ReleaseDateFeature(id: FeatureKey, enabled: Boolean, date: LocalDateTime)                  extends Feature
case class PercentageFeature(id: FeatureKey, enabled: Boolean, percentage: Int)                       extends Feature

object FeatureType {
  val NO_STRATEGY   = "NO_STRATEGY"
  val RELEASE_DATE  = "RELEASE_DATE"
  val DATE_RANGE    = "DATE_RANGE"
  val SCRIPT        = "SCRIPT"
  val GLOBAL_SCRIPT = "GLOBAL_SCRIPT"
  val PERCENTAGE    = "PERCENTAGE"
}

trait FeatureService[F[_]] {
  def create(id: Key, data: Feature): F[Result[Feature]]
  def update(oldId: Key, id: Key, data: Feature): F[Result[Feature]]
  def delete(id: Key): F[Result[Feature]]
  def deleteAll(patterns: Seq[String]): F[Result[Done]]
  def getById(id: Key): F[Option[Feature]]
  def getByIdLike(patterns: Seq[String], page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[Feature]]
  def getByIdLike(patterns: Seq[String]): Source[(FeatureKey, Feature), NotUsed]
  def count(patterns: Seq[String]): F[Long]
  def getByIdLikeActive(env: Env,
                        context: JsObject,
                        patterns: Seq[String],
                        page: Int,
                        nbElementPerPage: Int): F[PagingResult[(Feature, Boolean)]]
  def getByIdLikeActive(env: Env,
                        context: JsObject,
                        patterns: Seq[String]): Source[(FeatureKey, Feature, Boolean), NotUsed]

  def getByIdActive(env: Env, context: JsObject, id: FeatureKey): F[Option[(Feature, Boolean)]]

  def getFeatureTree(patterns: Seq[String], flat: Boolean, context: JsObject, env: Env)(
      implicit ec: ExecutionContext
  ): Source[JsValue, NotUsed]

  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

class FeatureServiceImpl[F[_]: Effect](jsonStore: JsonDataStore[F],
                                       eventStore: EventStore[F],
                                       globalScriptStore: GlobalScriptService[F])
    extends FeatureService[F]
    with EitherTSyntax[F] {

  import FeatureInstances._
  import cats.data._
  import cats.syntax._
  import cats.implicits._
  import domains.events.Events._
  import libs.functional.syntax._
  import store.Result._

  implicit val gs = globalScriptStore

  override def create(id: FeatureKey, data: Feature): F[Result[Feature]] = {
    // format: off
    val r: EitherT[F, AppErrors, Feature] = for {
      created <- jsonStore.create(id, format.writes(data))        |> liftFEither
      feature <- created.validate[Feature]                        |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(FeatureCreated(id, feature))  |> liftF[AppErrors, Done]
    } yield feature
    // format: on
    r.value
  }

  override def update(oldId: FeatureKey, id: FeatureKey, data: Feature): F[Result[Feature]] = {
    // format: off
    val r: EitherT[F, AppErrors, Feature] = for {
      oldValue <- getById(oldId)                                    |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated  <- jsonStore.update(oldId, id, format.writes(data))  |> liftFEither
      feature  <- updated.validate[Feature]                         |> liftJsResult{ handleJsError }
      _        <- eventStore.publish(FeatureUpdated(id, oldValue, feature)) |> liftF[AppErrors, Done]
    } yield feature
    // format: on
    r.value
  }

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    Logger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }

  override def delete(id: FeatureKey): F[Result[Feature]] = {
    // format: off
    val r: EitherT[F, AppErrors, Feature] = for {
      deleted <- jsonStore.delete(id)                            |> liftFEither
      feature <- deleted.validate[Feature]                       |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(FeatureDeleted(id, feature)) |> liftF[AppErrors, Done]
    } yield feature
    // format: on
    r.value
  }

  override def deleteAll(patterns: Seq[String]): F[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: FeatureKey): F[Option[Feature]] =
    jsonStore
      .getById(id)
      .map(_.flatMap(_.validate[Feature].asOpt))

  override def getByIdActive(env: Env, context: JsObject, id: FeatureKey): F[Option[(Feature, Boolean)]] =
    getById(id).flatMap { f =>
      f.traverse { f =>
        isActive.isActive(f, context, env).map { active =>
          (f, active.getOrElse(false))
        }
      }
    }

  override def getByIdLike(patterns: Seq[String], page: Int, nbElementPerPage: Int): F[PagingResult[Feature]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLikeActive(
      env: Env,
      context: JsObject,
      patterns: Seq[String],
      page: Int,
      nbElementPerPage: Int
  ): F[PagingResult[(Feature, Boolean)]] =
    getByIdLike(patterns, page, nbElementPerPage)
      .flatMap { p =>
        p.results.toList
          .traverse { f =>
            isActive
              .isActive(f, context, env)
              .map { active =>
                (f, active.getOrElse(false))
              }
          }
          .map { r =>
            DefaultPagingResult(r, p.page, p.pageSize, p.count)
          }
      }

  override def getByIdLike(patterns: Seq[String]): Source[(FeatureKey, Feature), NotUsed] =
    jsonStore.getByIdLike(patterns).map {
      case (k, v) => (k, v.validate[Feature].get)
    }

  override def getByIdLikeActive(env: Env,
                                 context: JsObject,
                                 patterns: Seq[String]): Source[(FeatureKey, Feature, Boolean), NotUsed] =
    jsonStore
      .getByIdLike(patterns)
      .map {
        case (k, v) => (k, v.validate[Feature].get)
      }
      .mapAsyncUnordered(4) {
        case (k, f) =>
          import cats.effect.implicits._
          isActive
            .isActive(f, context, env)
            .map { active =>
              (k, f, active.getOrElse(false))
            }
            .toIO
            .unsafeToFuture()
      }

  override def count(patterns: Seq[String]): F[Long] =
    jsonStore.count(patterns)

  override def getFeatureTree(patterns: Seq[String], flat: Boolean, context: JsObject, env: Env)(
      implicit ec: ExecutionContext
  ): Source[JsValue, NotUsed] =
    getByIdLike(patterns).map(_._2).via(tree(flat)(context, env))

  override def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import libs.streams.syntax._
    Flow[(String, JsValue)]
      .map { case (s, json) => (s, json.validate[Feature]) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(obj.id, obj).map(ImportResult.fromResult _)
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  private def tree(
      flatRepr: Boolean
  )(context: JsObject, env: Env)(implicit ec: ExecutionContext,
                                 isActive: IsActive[F, Feature]): Flow[Feature, JsValue, NotUsed] =
    if (flatRepr) Feature.flat(context, env)
    else Feature.toGraph(context, env)

}
