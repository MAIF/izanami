package domains.feature

import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import domains.events.{EventStore, EventStoreContext}
import domains.feature.Feature.FeatureKey
import domains.feature.FeatureInstances.isActive
import domains.script.{GlobalScriptContext, RunnableScriptContext, Script, ScriptCacheModule}
import domains.{AkkaModule, AuthInfo, AuthInfoModule, ImportData, ImportResult, ImportStrategy, Key}
import libs.logs.LoggerModule
import play.api.libs.json._
import store.Result._
import store._
import zio.{RIO, ZIO}
import java.time.LocalTime

import cats.data.NonEmptyList
import metrics.MetricsService

sealed trait Strategy

sealed trait Feature {

  def id: FeatureKey

  def enabled: Boolean

  def description: Option[String]

  def toJson(active: Boolean): JsValue =
    FeatureInstances.format.writes(this).as[JsObject] ++ Json.obj("active" -> active)
}

object Feature {
  type FeatureKey = Key

  def withActive(context: JsObject): RIO[IsActiveContext, Flow[Feature, (Boolean, Feature), NotUsed]] =
    for {
      runtime <- ZIO.runtime[IsActiveContext]
      res <- RIO.access[IsActiveContext] { _ =>
              Flow[Feature]
                .mapAsyncUnordered(2) { feature =>
                  val testIfisActive: RIO[IsActiveContext, (Boolean, Feature)] =
                    isActive(feature, context).either
                      .map {
                        _.map(act => (act && feature.enabled, feature))
                          .getOrElse((false, feature))
                      }
                  runtime.unsafeRunToFuture(testIfisActive)
                }
            }
    } yield res

  def toGraph(context: JsObject): RIO[IsActiveContext, Flow[Feature, JsObject, NotUsed]] =
    withActive(context).map { flow =>
      Flow[Feature]
        .via(flow)
        .map {
          case (active, f) => FeatureInstances.graphWrites(active).writes(f)
        }
        .fold(Json.obj()) { (acc, js) =>
          acc.deepMerge(js.as[JsObject])
        }
    }

  def flat(context: JsObject): RIO[IsActiveContext, Flow[Feature, JsValue, NotUsed]] =
    withActive(context).map { flow =>
      Flow[Feature]
        .via(flow)
        .map {
          case (active, f) => f.toJson(active)
        }
        .fold(Seq.empty[JsValue]) { _ :+ _ }
        .map(JsArray(_))
    }
}

trait IsActiveContext extends ScriptCacheModule with GlobalScriptContext with RunnableScriptContext

trait IsActive[A <: Feature] {
  def isActive(feature: A, context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean]
}

case class DefaultFeature(id: FeatureKey, enabled: Boolean, description: Option[String], parameters: JsValue = JsNull)
    extends Feature
case class GlobalScriptFeature(id: FeatureKey, enabled: Boolean, description: Option[String], ref: String)
    extends Feature
case class ScriptFeature(id: FeatureKey, enabled: Boolean, description: Option[String], script: Script) extends Feature
case class DateRangeFeature(id: FeatureKey,
                            enabled: Boolean,
                            description: Option[String],
                            from: LocalDateTime,
                            to: LocalDateTime)
    extends Feature
case class ReleaseDateFeature(id: FeatureKey, enabled: Boolean, description: Option[String], date: LocalDateTime)
    extends Feature
case class HourRangeFeature(id: FeatureKey,
                            enabled: Boolean,
                            description: Option[String],
                            startAt: LocalTime,
                            endAt: LocalTime)
    extends Feature
case class PercentageFeature(id: FeatureKey, enabled: Boolean, description: Option[String], percentage: Int)
    extends Feature

object FeatureType {
  val NO_STRATEGY   = "NO_STRATEGY"
  val RELEASE_DATE  = "RELEASE_DATE"
  val DATE_RANGE    = "DATE_RANGE"
  val SCRIPT        = "SCRIPT"
  val GLOBAL_SCRIPT = "GLOBAL_SCRIPT"
  val PERCENTAGE    = "PERCENTAGE"
  val HOUR_RANGE    = "HOUR_RANGE"
}

trait FeatureDataStoreModule {
  def featureDataStore: JsonDataStore
}

trait FeatureContext
    extends FeatureDataStoreModule
    with AkkaModule
    with LoggerModule
    with DataStoreContext
    with EventStoreContext
    with GlobalScriptContext
    with ScriptCacheModule
    with IsActiveContext
    with AuthInfoModule[FeatureContext]

object FeatureDataStore extends JsonDataStoreHelper[FeatureContext] {
  override def accessStore = _.featureDataStore
}

object FeatureService {
  import zio._
  import zio.interop.catz._
  import cats.implicits._
  import libs.ziohelper.JsResults._
  import FeatureInstances._
  import domains.events.Events._
  import IzanamiErrors._

  def create(id: FeatureKey, data: Feature): ZIO[FeatureContext, IzanamiErrors, Feature] =
    for {
      _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id).toErrors))
      created  <- FeatureDataStore.create(id, format.writes(data))
      feature  <- jsResultToError(created.validate[Feature])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(FeatureCreated(id, feature, authInfo = authInfo))
      _        <- MetricsService.incFeatureCreated(id.key)
    } yield feature

  def update(oldId: FeatureKey, id: FeatureKey, data: Feature): ZIO[FeatureContext, IzanamiErrors, Feature] =
    for {
      mayBeFeature <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue     <- ZIO.fromOption(mayBeFeature).mapError(_ => DataShouldExists(oldId).toErrors)
      updated      <- FeatureDataStore.update(oldId, id, format.writes(data))
      feature      <- jsResultToError(updated.validate[Feature])
      authInfo     <- AuthInfo.authInfo
      _            <- EventStore.publish(FeatureUpdated(id, oldValue, feature, authInfo = authInfo))
      _            <- MetricsService.incFeatureUpdated(id.key)
    } yield feature

  def delete(id: FeatureKey): ZIO[FeatureContext, IzanamiErrors, Feature] =
    for {
      deleted  <- FeatureDataStore.delete(id)
      feature  <- jsResultToError(deleted.validate[Feature])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(FeatureDeleted(id, feature, authInfo = authInfo))
      _        <- MetricsService.incFeatureDeleted(id.key)
    } yield feature

  def deleteAll(query: Query): ZIO[FeatureContext, IzanamiErrors, Unit] =
    FeatureDataStore.deleteAll(query) <* MetricsService.incFeatureCreated(query.toString())

  def getById(id: FeatureKey): RIO[FeatureContext, Option[Feature]] =
    for {
      mayBeConfig  <- FeatureDataStore.getById(id)
      parsedConfig = mayBeConfig.flatMap(_.validate[Feature].asOpt)
    } yield parsedConfig

  def getByIdActive(context: JsObject, id: FeatureKey): ZIO[FeatureContext, IzanamiErrors, Option[(Feature, Boolean)]] =
    for {
      mayBeFeature <- getById(id).refineToOrDie[IzanamiErrors]
      result <- mayBeFeature
                 .traverse { f =>
                   isActive(f, context)
                     .map { active =>
                       (f, active)
                     }
                     .catchSome {
                       case _: IzanamiErrors => IO.succeed((f, false))
                     }
                 }
    } yield result

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[FeatureContext, PagingResult[Feature]] =
    FeatureDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQueryActive(
      context: JsObject,
      query: Query,
      page: Int,
      nbElementPerPage: Int
  ): ZIO[FeatureContext, IzanamiErrors, PagingResult[(Feature, Boolean)]] =
    for {
      pages <- findByQuery(query, page, nbElementPerPage).refineToOrDie[IzanamiErrors]
      pagesWithActive <- pages.results.toList
                          .traverse { f =>
                            isActive(f, context)
                              .map { active =>
                                (f, active)
                              }
                              .catchSome {
                                case _: IzanamiErrors => IO.succeed((f, false))
                              }
                          }
    } yield DefaultPagingResult(pagesWithActive, pages.page, pages.pageSize, pages.count)

  def findByQuery(query: Query): RIO[FeatureContext, Source[(FeatureKey, Feature), NotUsed]] =
    FeatureDataStore.findByQuery(query).map { s =>
      s.map {
        case (k, v) => (k, v.validate[Feature].get)
      }
    }

  def findAllByQuery(query: Query): RIO[FeatureContext, List[(FeatureKey, Feature)]] =
    FeatureDataStore
      .findByQuery(query)
      .map { s =>
        s.map {
          case (k, v) => (k, v.validate[Feature].get)
        }
      }
      .flatMap { source =>
        ZIO.accessM[FeatureContext] { ctx =>
          ZIO.fromFuture { _ =>
            source.runWith(Sink.seq)(ctx.mat)
          }
        }
      }
      .map { _.toList }

  def findByQueryActive(context: JsObject,
                        query: Query): RIO[FeatureContext, Source[(FeatureKey, Feature, Boolean), NotUsed]] =
    for {
      runtime <- ZIO.runtime[FeatureContext]
      res <- ZIO.accessM[FeatureContext] { _ =>
              val value: RIO[FeatureContext, Source[(FeatureKey, Feature, Boolean), NotUsed]] = FeatureDataStore
                .findByQuery(query)
                .map { source =>
                  source
                    .map {
                      case (k, v) => (k, v.validate[Feature].get)
                    }
                    .mapAsyncUnordered(4) {
                      case (k, f) =>
                        val testIfisActive: RIO[IsActiveContext, (FeatureKey, Feature, Boolean)] =
                          isActive(f, context)
                            .map { active =>
                              (k, f, active)
                            }
                            .either
                            .map {
                              _.getOrElse((k, f, false))
                            }
                        runtime.unsafeRunToFuture(testIfisActive)
                    }
                }
              value
            }
    } yield res

  def count(query: Query): RIO[FeatureContext, Long] =
    FeatureDataStore.count(query)

  def getFeatureTree(query: Query, flat: Boolean, context: JsObject): RIO[FeatureContext, Source[JsValue, NotUsed]] =
    for {
      s    <- findByQuery(query)
      flow <- tree(flat)(context)
    } yield s.map(_._2).via(flow)

  def copyNode(from: Key, to: Key): ZIO[FeatureContext, IzanamiErrors, Unit] = {
    import zio.interop.catz._
    import cats.implicits._
    import IzanamiErrors._
    for {
      _      <- ZIO.fromEither(validateKeys(from, to))
      values <- findAllByQuery(Query.oneOf((from / "*").key)).refineToOrDie[IzanamiErrors]
      _      <- values.parTraverse { case (_, v) => copyOne(from, to, v) }.mapError(_.reduce)
    } yield ()
  }

  private case class InvalidCopyKey(id: Key) {
    def message = ErrorMessage("invalid.key.format", id.key)
  }

  private def validateKeys(key1: Key, key2: Key): Either[IzanamiErrors, (Key, Key)] = {
    import cats.implicits._
    import IzanamiErrors._
    (key1, key2)
      .parTraverse(k => validateKey(k).leftMap(err => NonEmptyList.of(ValidationError(Seq(err.message)).toErrors)))
      .leftMap(_.reduce)
  }

  private def validateKey(key: Key): Either[InvalidCopyKey, Key] =
    Either.cond(!key.segments.exists(_ === "*"), key, InvalidCopyKey(key))

  private def copyOne(from: Key, to: Key, feature: Feature): ZIO[FeatureContext, NonEmptyList[IzanamiErrors], Feature] =
    FeatureService
      .create(to / feature.id.drop(from.key), feature)
      .mapError(err => NonEmptyList.of(err))

  def importData(
      strategy: ImportStrategy = ImportStrategy.Keep
  ): RIO[FeatureContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ImportData
      .importDataFlow[FeatureContext, FeatureKey, Feature](
        strategy,
        _.id,
        key => getById(key),
        (key, data) => create(key, data),
        (key, data) => update(key, key, data)
      )(FeatureInstances.format)

  private def tree(
      flatRepr: Boolean
  )(context: JsObject): RIO[FeatureContext, Flow[Feature, JsValue, NotUsed]] =
    ZIO.accessM[FeatureContext] { _ =>
      if (flatRepr) Feature.flat(context)
      else Feature.toGraph(context)
    }
}
