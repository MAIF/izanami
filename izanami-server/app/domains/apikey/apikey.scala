package domains

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import domains.AuthorizedPatterns
import domains.events.{EventStore, EventStoreContext}
import domains._
import domains.auth.AuthInfo
import libs.logs.ZLogger
import libs.ziohelper.JsResults.jsResultToError
import play.api.libs.json._
import store._
import store.datastore.{JsonDataStoreHelper, _}
import zio.{IO, RIO, URIO, ZIO}

package object apikey {

  case class Apikey(clientId: String,
                    name: String,
                    clientSecret: String,
                    authorizedPatterns: AuthorizedPatterns,
                    admin: Boolean = false)
      extends AuthInfo.Service {
    override def mayBeEmail: Option[String] = None
    override def id: String                 = clientId
  }

  object Apikey {
    type ApikeyKey = Key
  }

  type ApikeyDataStore = zio.Has[ApikeyDataStore.Service]

  object ApikeyDataStore {

    type Service = JsonDataStore.Service

    object > extends JsonDataStoreHelper[ApikeyDataStore with DataStoreContext]
  }

  type ApiKeyContext = ZLogger with ApikeyDataStore with EventStore with AuthInfo

  object ApikeyService {

    import cats.implicits._
    import libs.streams.syntax._
    import Apikey._
    import ApikeyInstances._
    import domains.events.Events._
    import errors._
    import IzanamiErrors._

    def create(id: ApikeyKey, data: Apikey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
      for {
        _        <- AuthInfo.isAdmin()
        _        <- IO.when(Key(data.id) =!= id)(IO.fail(IdMustBeTheSame(Key(data.id), id).toErrors))
        created  <- ApikeyDataStore.>.create(id, Json.toJson(data))
        apikey   <- jsResultToError(created.validate[Apikey])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(ApikeyCreated(id, apikey, authInfo = authInfo))
      } yield apikey

    def update(oldId: ApikeyKey, id: ApikeyKey, data: Apikey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
      for {
        _        <- AuthInfo.isAdmin()
        mayBeOld <- getById(oldId)
        oldValue <- ZIO.fromOption(mayBeOld).mapError(_ => DataShouldExists(oldId).toErrors)
        updated  <- ApikeyDataStore.>.update(oldId, id, Json.toJson(data))
        apikey   <- jsResultToError(updated.validate[Apikey])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(ApikeyUpdated(id, oldValue, apikey, authInfo = authInfo))
      } yield apikey

    def delete(id: ApikeyKey): ZIO[ApiKeyContext, IzanamiErrors, Apikey] =
      for {
        _          <- AuthInfo.isAdmin()
        deleted    <- ApikeyDataStore.>.delete(id)
        experiment <- jsResultToError(deleted.validate[Apikey])
        authInfo   <- AuthInfo.authInfo
        _          <- EventStore.publish(ApikeyDeleted(id, experiment, authInfo = authInfo))
      } yield experiment

    def deleteAll(patterns: Seq[String]): ZIO[ApiKeyContext, IzanamiErrors, Unit] =
      AuthInfo.isAdmin() *> ApikeyDataStore.>.deleteAll(patterns)

    def getByIdWithoutPermissions(id: ApikeyKey): RIO[ApiKeyContext, Option[Apikey]] =
      ApikeyDataStore.>.getById(id)
        .map(_.flatMap(_.validate[Apikey].asOpt))

    def getById(id: ApikeyKey): ZIO[ApiKeyContext, IzanamiErrors, Option[Apikey]] =
      AuthInfo.isAdmin() *> getByIdWithoutPermissions(id).orDie

    def findByQuery(query: Query,
                    page: Int,
                    nbElementPerPage: Int): ZIO[ApiKeyContext, IzanamiErrors, PagingResult[Apikey]] =
      AuthInfo
        .isAdmin() *> ApikeyDataStore.>.findByQuery(query, page, nbElementPerPage)
        .map(jsons => JsonPagingResult(jsons))
        .orDie

    def findByQuery(query: Query): ZIO[ApiKeyContext, IzanamiErrors, Source[(Key, Apikey), NotUsed]] =
      AuthInfo.isAdmin() *> ApikeyDataStore.>.findByQuery(query)
        .map(_.readsKV[Apikey])
        .orDie

    def countWithoutPermissions(query: Query): RIO[ApiKeyContext, Long] =
      ApikeyDataStore.>.count(query)

    def count(query: Query): ZIO[ApiKeyContext, IzanamiErrors, Long] =
      AuthInfo.isAdmin() *> countWithoutPermissions(query).orDie

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
}
