package domains

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.auth.AuthInfo
import domains.apikey.ApikeyDataStore
import domains.config.Config.ConfigKey
import domains.config.ConfigDataStore
import domains.events.EventStore
import domains.events.Events.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import domains.configuration._
import libs.logs.ZLogger
import play.api.libs.json._
import domains.errors.IzanamiErrors
import store._
import store.datastore._
import domains.errors.DataShouldExists
import domains.errors.IdMustBeTheSame
import env.IzanamiConfig
import env.configuration.IzanamiConfigModule
import libs.database.Drivers
import store.memorywithdb.InMemoryWithDbStore
import zio.{Has, URIO, ZIO, ZLayer}

package object config {

  object Config {
    type ConfigKey = Key
  }

  case class Config(id: ConfigKey, value: JsValue)

  type ConfigDataStore = zio.Has[ConfigDataStore.Service]

  object ConfigDataStore {
    trait Service {
      def configDataStore: JsonDataStore.Service
    }

    case class ConfigDataStoreProd(configDataStore: JsonDataStore.Service) extends Service

    object > extends JsonDataStoreHelper[ConfigDataStore with DataStoreContext] {
      override def getStore: URIO[ConfigDataStore with DataStoreContext, JsonDataStore.Service] =
        ZIO.access[ConfigDataStore with DataStoreContext](_.get[ConfigDataStore.Service].configDataStore)
    }

    def value(store: JsonDataStore.Service): ZLayer[Any, Nothing, ConfigDataStore] =
      ZLayer.succeed(ConfigDataStoreProd(store))

    def live(izanamiConfig: IzanamiConfig): ZLayer[DataStoreLayerContext, Throwable, ConfigDataStore] =
      JsonDataStore
        .live(izanamiConfig, c => c.config.db, InMemoryWithDbStore.configEventAdapter)
        .map(s => Has(ConfigDataStoreProd(s.get)))
  }

  type ConfigContext = ZLogger with ConfigDataStore with EventStore with AuthInfo

  object ConfigService {

    import cats.implicits._
    import zio._
    import libs.ziohelper.JsResults._
    import ConfigInstances._
    import libs.streams.syntax._
    import IzanamiErrors._

    def create(id: ConfigKey, data: Config): ZIO[ConfigContext, IzanamiErrors, Config] =
      for {
        _        <- AuthorizedPatterns.isAllowed(id, PatternRights.C)
        _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id).toErrors))
        created  <- ConfigDataStore.>.create(id, ConfigInstances.format.writes(data))
        apikey   <- fromJsResult(created.validate[Config]) { handleJsError }
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(ConfigCreated(id, apikey, authInfo = authInfo))
      } yield apikey

    def update(oldId: ConfigKey, id: ConfigKey, data: Config): ZIO[ConfigContext, IzanamiErrors, Config] =
      // format: off
      for {
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.U)
        mayBeConfig <- getById(oldId)
        oldValue    <- ZIO.fromOption(mayBeConfig).mapError(_ => DataShouldExists(oldId).toErrors)
        updated     <- ConfigDataStore.>.update(oldId, id, ConfigInstances.format.writes(data))
        experiment  <- fromJsResult(updated.validate[Config]) { handleJsError }
        authInfo    <- AuthInfo.authInfo
        _           <- EventStore.publish(ConfigUpdated(id, oldValue, experiment, authInfo = authInfo))
      } yield experiment
      // format: on

    def delete(id: ConfigKey): ZIO[ConfigContext, IzanamiErrors, Config] =
      // format: off
      for {
        _           <- AuthorizedPatterns.isAllowed(id, PatternRights.D)
        deleted     <- ConfigDataStore.>.delete(id)
        experiment  <- fromJsResult(ConfigInstances.format.reads(deleted)){ handleJsError }
        authInfo    <- AuthInfo.authInfo
        _           <- EventStore.publish(ConfigDeleted(id, experiment, authInfo = authInfo))
      } yield experiment
      // format: on

    def deleteAll(query: Query): ZIO[ConfigContext, IzanamiErrors, Unit] =
      ConfigDataStore.>.deleteAll(query)

    def getById(id: ConfigKey): ZIO[ConfigContext, IzanamiErrors, Option[Config]] =
      for {
        _            <- AuthorizedPatterns.isAllowed(id, PatternRights.R)
        mayBeConfig  <- ConfigDataStore.>.getById(id).orDie
        parsedConfig = mayBeConfig.flatMap(_.validate[Config].asOpt)
      } yield parsedConfig

    def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[ConfigContext, PagingResult[Config]] =
      ConfigDataStore.>.findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))

    def findByQuery(query: Query): RIO[ConfigContext, Source[(Key, Config), NotUsed]] =
      ConfigDataStore.>.findByQuery(query).map(_.readsKV[Config])

    def count(query: Query): RIO[ConfigContext, Long] =
      ConfigDataStore.>.count(query)

    def importData(
        strategy: ImportStrategy = ImportStrategy.Keep
    ): RIO[ConfigContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ImportData
        .importDataFlow[ConfigContext, ConfigKey, Config](
          strategy,
          _.id,
          key => getById(key),
          (key, data) => create(key, data),
          (key, data) => update(key, key, data)
        )(ConfigInstances.format)
  }
}
