package domains.apikey

import akka.http.scaladsl.util.FastFuture
import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.AuthorizedPattern.AuthorizedPattern
import domains.abtesting.Experiment.ExperimentKey
import domains.events.{EventStore, EventStoreContext}
import domains._
import libs.logs.LoggerModule
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import store._
import zio.{IO, RIO, Task, ZIO}
import akka.stream.Materializer

case class Apikey(clientId: String, name: String, clientSecret: String, authorizedPattern: AuthorizedPattern)
    extends AuthInfo {
  override def mayBeEmail: Option[String] = None
  override def id: String                 = clientId
}

object Apikey {
  type ApikeyKey = Key
}

trait ApikeyDataStoreModule {
  def apikeyDataStore: JsonDataStore
}

trait ApiKeyContext
    extends LoggerModule
    with DataStoreContext
    with ApikeyDataStoreModule
    with EventStoreContext
    with AuthInfoModule[ApiKeyContext]

object ApiKeyDataStore extends JsonDataStoreHelper[ApiKeyContext] {
  override def accessStore = _.apikeyDataStore
}

object ApikeyService {

  import cats.implicits._
  import libs.streams.syntax._
  import Apikey._
  import ApikeyInstances._
  import domains.events.Events._
  import store.Result._

  def create(id: ApikeyKey, data: Apikey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
    for {
      _        <- IO.when(Key(data.id) =!= id)(IO.fail(IdMustBeTheSame(Key(data.id), id)))
      created  <- ApiKeyDataStore.create(id, Json.toJson(data))
      apikey   <- jsResultToError(created.validate[Apikey])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ApikeyCreated(id, apikey, authInfo = authInfo))
    } yield apikey

  def update(oldId: ApikeyKey, id: ApikeyKey, data: Apikey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
    for {
      mayBeOld <- getById(oldId).refineToOrDie[IzanamiErrors]
      oldValue <- ZIO.fromOption(mayBeOld).mapError(_ => DataShouldExists(oldId))
      updated  <- ApiKeyDataStore.update(oldId, id, Json.toJson(data))
      apikey   <- jsResultToError(updated.validate[Apikey])
      authInfo <- AuthInfo.authInfo
      _        <- EventStore.publish(ApikeyUpdated(id, oldValue, apikey, authInfo = authInfo))
    } yield apikey

  def delete(id: ApikeyKey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
    for {
      deleted    <- ApiKeyDataStore.delete(id)
      experiment <- jsResultToError(deleted.validate[Apikey])
      authInfo   <- AuthInfo.authInfo
      _          <- EventStore.publish(ApikeyDeleted(id, experiment, authInfo = authInfo))
    } yield experiment

  def deleteAll(patterns: Seq[String]): ZIO[ApiKeyContext, IzanamiErrors, Unit] =
    ApiKeyDataStore.deleteAll(patterns)

  def getById(id: ApikeyKey): RIO[ApiKeyContext, Option[Apikey]] =
    ApiKeyDataStore.getById(id).map(_.flatMap(_.validate[Apikey].asOpt))

  def findByQuery(query: Query, page: Int, nbElementPerPage: Int): RIO[ApiKeyContext, PagingResult[Apikey]] =
    ApiKeyDataStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  def findByQuery(query: Query): RIO[ApiKeyContext, Source[(Key, Apikey), NotUsed]] =
    ApiKeyDataStore.findByQuery(query).map(_.readsKV[Apikey])

  def count(query: Query): RIO[ApiKeyContext, Long] =
    ApiKeyDataStore.count(query)

  def importData(
      strategy: ImportStrategy = ImportStrategy.Keep
  ): RIO[ApiKeyContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
    ImportData
      .importDataFlow[ApiKeyContext, ApikeyKey, Apikey](
        strategy,
        data => Key(data.clientId),
        key => getById(key),
        (key, data) => create(key, data),
        (key, data) => update(key, key, data)
      )(ApikeyInstances.format)
}
