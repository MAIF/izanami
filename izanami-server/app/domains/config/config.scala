package domains.config

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Source}
import domains.config.Config.ConfigKey
import domains.events.{EventStore, EventStoreContext}
import domains.events.Events.{ConfigCreated, ConfigDeleted, ConfigUpdated}
import domains.{AuthInfo, AuthInfoModule, ImportData, ImportResult, ImportStrategy, Key}
import libs.logs.LoggerModule
import play.api.libs.json._
import store.Result.{AppErrors, ErrorMessage, IzanamiErrors}
import store._
import libs.logs.Logger
import store.Result.DataShouldExists
import store.Result.IdMustBeTheSame

case class Config(id: ConfigKey, value: JsValue)

object Config {
  type ConfigKey = Key
}

trait ConfigDataStoreModule {
  def configDataStore: JsonDataStore
}

trait ConfigContext
    extends LoggerModule
    with DataStoreContext
    with ConfigDataStoreModule
    with EventStoreContext
    with AuthInfoModule[ConfigContext]

object ConfigDataStore extends JsonDataStoreHelper[ConfigContext] {
  override def accessStore = _.configDataStore
}

object ConfigService {

  import cats.implicits._
  import zio._
  import libs.ziohelper.JsResults._
  import ConfigInstances._
  import libs.streams.syntax._

  def create(id: ConfigKey, data: Config): ZIO[ConfigContext, IzanamiErrors, Config] =
    for {
      _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id)))
      created  <- ConfigDataStore.create(id, ConfigInstances.format.writes(data))
      apikey   <- fromJsResult(created.validate[Config]) { handleJsError }
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ConfigCreated(id, apikey, authInfo = authInfo))
    } yield apikey

  def update(oldId: ConfigKey, id: ConfigKey, data: Config): ZIO[ConfigContext, IzanamiErrors, Config] =
    // format: off
    for {
      mayBeConfig <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue    <- ZIO.fromOption(mayBeConfig).mapError(_ => DataShouldExists(oldId))
      updated     <- ConfigDataStore.update(oldId, id, ConfigInstances.format.writes(data))
      experiment  <- fromJsResult(updated.validate[Config]) { handleJsError }
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(ConfigUpdated(id, oldValue, experiment, authInfo = authInfo))
    } yield experiment
    // format: on

  def delete(id: ConfigKey): ZIO[ConfigContext, IzanamiErrors, Config] =
    // format: off
    for {
      deleted     <- ConfigDataStore.delete(id)
      experiment  <- fromJsResult(ConfigInstances.format.reads(deleted)){ handleJsError }
      authInfo    <- AuthInfo.authInfo
      _           <- EventStore.publish(ConfigDeleted(id, experiment, authInfo = authInfo))
    } yield experiment
    // format: on

  def deleteAll(query: Query): ZIO[ConfigContext, IzanamiErrors, Unit] =
    ConfigDataStore.deleteAll(query)

  def getById(id: ConfigKey): RIO[ConfigContext, Option[Config]] =
    for {
      mayBeConfig  <- ConfigDataStore.getById(id)
      parsedConfig = mayBeConfig.flatMap(_.validate[Config].asOpt)
    } yield parsedConfig

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[ConfigContext, PagingResult[Config]] =
    ConfigDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[ConfigContext, Source[(Key, Config), NotUsed]] =
    ConfigDataStore.findByQuery(query).map(_.readsKV[Config])

  def count(query: Query): RIO[ConfigContext, Long] =
    ConfigDataStore.count(query)

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
