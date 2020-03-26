package domains

import java.time.LocalDateTime

import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import domains.events.EventStore
import domains.feature.Feature.FeatureKey
import domains.feature.FeatureInstances.isActive
import domains.script.{GlobalScriptContext, GlobalScriptDataStore, RunnableScriptModule, Script, ScriptCache}
import domains.configuration.{AkkaModule, AuthInfoModule, PlayModule}
import domains.{AuthInfo, AuthorizedPatterns, ImportData, ImportResult, ImportStrategy, Key, PatternRights}
import libs.logs.ZLogger
import play.api.libs.json._
import domains.errors.{IzanamiErrors, _}
import store._
import store.datastore._
import zio.{RIO, URIO, ZIO}
import java.time.LocalTime

import cats.data.NonEmptyList
import metrics.MetricsService
import zio.blocking.Blocking

package object feature {

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

  type IsActiveContext = ScriptCache
    with ZLogger
    with EventStore
    with GlobalScriptDataStore
    with RunnableScriptModule
    with AuthInfoModule
    with Blocking
    with PlayModule

  trait IsActive[A <: Feature] {
    def isActive(feature: A, context: JsObject): ZIO[IsActiveContext, IzanamiErrors, Boolean]
  }

  case class DefaultFeature(id: FeatureKey, enabled: Boolean, description: Option[String], parameters: JsValue = JsNull)
      extends Feature
  case class GlobalScriptFeature(id: FeatureKey, enabled: Boolean, description: Option[String], ref: String)
      extends Feature
  case class ScriptFeature(id: FeatureKey, enabled: Boolean, description: Option[String], script: Script)
      extends Feature
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

  type FeatureDataStore = zio.Has[FeatureDataStore.Service]

  object FeatureDataStore {

    type Service = JsonDataStore.Service

    object > extends JsonDataStoreHelper[FeatureDataStore with DataStoreContext]
  }

  type FeatureContext = FeatureDataStore
    with AkkaModule
    with PlayModule
    with ZLogger
    with EventStore
    with GlobalScriptDataStore
    with ScriptCache
    with RunnableScriptModule
    with AuthInfoModule
    with Blocking

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
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.C)
        _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id).toErrors))
        created  <- FeatureDataStore.>.create(id, format.writes(data))
        feature  <- jsResultToError(created.validate[Feature])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(FeatureCreated(id, feature, authInfo = authInfo))
        _        <- MetricsService.incFeatureCreated(id.key)
      } yield feature

    def update(oldId: FeatureKey, id: FeatureKey, data: Feature): ZIO[FeatureContext, IzanamiErrors, Feature] =
      for {
        _            <- AuthorizedPatterns.isAllowed(id, PatternRights.U)
        mayBeFeature <- getById(oldId)
        oldValue     <- ZIO.fromOption(mayBeFeature).mapError(_ => DataShouldExists(oldId).toErrors)
        updated      <- FeatureDataStore.>.update(oldId, id, format.writes(data))
        feature      <- jsResultToError(updated.validate[Feature])
        authInfo     <- AuthInfo.authInfo
        _            <- EventStore.publish(FeatureUpdated(id, oldValue, feature, authInfo = authInfo))
        _            <- MetricsService.incFeatureUpdated(id.key)
      } yield feature

    def delete(id: FeatureKey): ZIO[FeatureContext, IzanamiErrors, Feature] =
      for {
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
        deleted  <- FeatureDataStore.>.delete(id)
        feature  <- jsResultToError(deleted.validate[Feature])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(FeatureDeleted(id, feature, authInfo = authInfo))
        _        <- MetricsService.incFeatureDeleted(id.key)
      } yield feature

    def deleteAll(query: Query): ZIO[FeatureContext, IzanamiErrors, Unit] =
      FeatureDataStore.>.deleteAll(query) <* MetricsService.incFeatureDeleted(query.toString())

    def getById(id: FeatureKey): ZIO[FeatureContext, IzanamiErrors, Option[Feature]] =
      for {
        _            <- AuthorizedPatterns.isAllowed(id, PatternRights.R)
        mayBeConfig  <- FeatureDataStore.>.getById(id).refineOrDie[IzanamiErrors](PartialFunction.empty)
        parsedConfig = mayBeConfig.flatMap(_.validate[Feature].asOpt)
      } yield parsedConfig

    def getByIdActive(context: JsObject,
                      id: FeatureKey): ZIO[FeatureContext, IzanamiErrors, Option[(Feature, Boolean)]] =
      for {
        mayBeFeature <- getById(id)
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
      FeatureDataStore.>.findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))

    def findByQueryActive(
        context: JsObject,
        query: Query,
        page: Int,
        nbElementPerPage: Int
    ): ZIO[FeatureContext, IzanamiErrors, PagingResult[(Feature, Boolean)]] =
      for {
        pages <- findByQuery(query, page, nbElementPerPage).refineOrDie[IzanamiErrors](PartialFunction.empty)
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
      FeatureDataStore.>.findByQuery(query).map { s =>
        s.map {
          case (k, v) => (k, v.validate[Feature].get)
        }
      }

    def findAllByQuery(query: Query): RIO[FeatureContext, List[(FeatureKey, Feature)]] =
      FeatureDataStore.>.findByQuery(query)
        .map { s =>
          s.map {
            case (k, v) => (k, v.validate[Feature].get)
          }
        }
        .flatMap { source =>
          AkkaModule.mat.flatMap { mat =>
            ZIO.fromFuture { _ =>
              source.runWith(Sink.seq)(mat)
            }
          }
        }
        .map { _.toList }

    def findByQueryActive(context: JsObject,
                          query: Query): RIO[FeatureContext, Source[(FeatureKey, Feature, Boolean), NotUsed]] =
      for {
        runtime <- ZIO.runtime[FeatureContext]
        res <- ZIO.accessM[FeatureContext] { _ =>
                val value: RIO[FeatureContext, Source[(FeatureKey, Feature, Boolean), NotUsed]] =
                  FeatureDataStore.>.findByQuery(query)
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
      FeatureDataStore.>.count(query)

    def getFeatureTree(query: Query, flat: Boolean, context: JsObject): RIO[FeatureContext, Source[JsValue, NotUsed]] =
      for {
        s    <- findByQuery(query)
        flow <- tree(flat)(context)
      } yield s.map(_._2).via(flow)

    def copyNode(from: Key, to: Key, default: Boolean): ZIO[FeatureContext, IzanamiErrors, List[Feature]] = {
      import zio.interop.catz._
      import cats.implicits._
      import IzanamiErrors._
      for {
        _ <- AuthorizedPatterns.isAllowed(from -> PatternRights.R, to -> PatternRights.C)
        _ <- ZIO.fromEither((validateKey(from), validateKey(to)).parTupled)
        values <- findAllByQuery(Query.oneOf(from.key, (from / "*").key))
                   .refineOrDie[IzanamiErrors](PartialFunction.empty)
        features <- values.parTraverse { case (_, v) => copyOne(from, to, v, default) }.mapError(_.reduce)
      } yield features
    }

    private def copyOne(from: Key,
                        to: Key,
                        feature: Feature,
                        default: Boolean): ZIO[FeatureContext, NonEmptyList[IzanamiErrors], Feature] = {
      val newId            = to / feature.id.drop(from.key)
      val featureToCreated = copyFeature(newId, feature, default)
      FeatureService
        .create(newId, featureToCreated)
        .catchSome {
          case NonEmptyList(DataShouldNotExists(_), Nil) => IO.succeed(featureToCreated)
        }
        .mapError(NonEmptyList.one)
    }

    def copyFeature(id: FeatureKey, feature: Feature, default: Boolean): Feature = feature match {
      case f: DefaultFeature      => f.copy(id = id, enabled = default)
      case f: GlobalScriptFeature => f.copy(id = id, enabled = default)
      case f: ScriptFeature       => f.copy(id = id, enabled = default)
      case f: DateRangeFeature    => f.copy(id = id, enabled = default)
      case f: ReleaseDateFeature  => f.copy(id = id, enabled = default)
      case f: HourRangeFeature    => f.copy(id = id, enabled = default)
      case f: PercentageFeature   => f.copy(id = id, enabled = default)
    }

    private def validateKey(key: Key): Either[IzanamiErrors, Key] =
      Either.cond(!key.segments.exists(_ === "*"), key, IzanamiErrors(InvalidCopyKey(key)))

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
}
