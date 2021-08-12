package domains

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink}
import domains.auth.AuthInfo
import domains.configuration.PlayModule
import domains.errors.{DataShouldExists, IdMustBeTheSame, IzanamiErrors, UnauthorizedByLock}
import domains.events.EventStore
import domains.lock.Lock.LockKey
import env.IzanamiConfig
import libs.logs.ZLogger
import play.api.libs.json.JsValue
import store._
import store.datastore._
import store.memorywithdb.InMemoryWithDbStore
import zio.{Has, URIO, ZIO, ZLayer}

import scala.annotation.tailrec

package object lock {

  sealed trait Lock {
    def id: LockKey
    def locked: Boolean
  }

  object Lock {
    type LockKey = Key
  }

  case class IzanamiLock(id: LockKey, locked: Boolean) extends Lock

  object LockType {
    val FEATURE    = "feature"
    val EXPERIMENT = "experiment"
  }

  type LockDataStore = zio.Has[LockDataStore.Service]

  object LockDataStore {
    def value(lockDataStore: JsonDataStore.Service): ZLayer[Any, Nothing, LockDataStore] =
      ZLayer.succeed(LockDataStoreProd(lockDataStore))

    trait Service {
      def lockDataStore: JsonDataStore.Service
    }

    case class LockDataStoreProd(lockDataStore: JsonDataStore.Service) extends Service

    object > extends JsonDataStoreHelper[LockDataStore with DataStoreContext] {
      override def getStore: URIO[LockDataStore with DataStoreContext, JsonDataStore.Service] =
        ZIO.access[LockDataStore with DataStoreContext](_.get[LockDataStore.Service].lockDataStore)
    }

    def live(izanamiConfig: IzanamiConfig): ZLayer[DataStoreLayerContext, Throwable, LockDataStore] =
      JsonDataStore
        .live(izanamiConfig, c => c.lock.db, InMemoryWithDbStore.featureEventAdapter)
        .map(s => Has(LockDataStoreProd(s.get)))
  }

  type LockContext = LockDataStore with ZLogger with AuthInfo with EventStore with PlayModule with DataStoreContext

  object LockService {
    import IzanamiErrors._
    import LockInstances._
    import cats.implicits._
    import domains.events.Events._
    import libs.ziohelper.JsResults._
    import zio._

    def create(id: LockKey, data: IzanamiLock): ZIO[LockContext, IzanamiErrors, IzanamiLock] =
      for {
        _        <- AuthInfo.isAdmin()
        _        <- IO.when(data.id =!= id)(IO.fail(IdMustBeTheSame(data.id, id).toErrors))
        created  <- LockDataStore.>.create(id, format.writes(data))
        lock     <- jsResultToError(created.validate[IzanamiLock])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(LockCreated(id, lock, authInfo = authInfo))
      } yield lock

    def update(id: LockKey, data: IzanamiLock): ZIO[LockContext, IzanamiErrors, IzanamiLock] =
      for {
        _         <- AuthInfo.isAdmin()
        mayBeLock <- getBy(id)
        oldLock   <- ZIO.fromOption(mayBeLock).mapError(_ => DataShouldExists(id).toErrors)
        updated   <- LockDataStore.>.update(id, data.id, format.writes(data))
        lock      <- jsResultToError(updated.validate[IzanamiLock])
        authInfo  <- AuthInfo.authInfo
        _         <- EventStore.publish(LockUpdated(id, oldLock, lock, authInfo = authInfo))
      } yield lock

    def delete(id: LockKey): ZIO[LockContext, IzanamiErrors, IzanamiLock] =
      for {
        _        <- AuthInfo.isAdmin()
        deleted  <- LockDataStore.>.delete(id)
        lock     <- jsResultToError(deleted.validate[IzanamiLock])
        authInfo <- AuthInfo.authInfo
        _        <- EventStore.publish(LockDeleted(id, lock, authInfo = authInfo))
      } yield lock

    def getBy(id: LockKey): ZIO[LockContext, IzanamiErrors, Option[IzanamiLock]] =
      for {
        mayBeLock  <- LockDataStore.>.getById(id).orDie
        parsedLock = mayBeLock.flatMap(_.validate[IzanamiLock].asOpt)
      } yield parsedLock

    def findByQuery(query: Query): RIO[LockContext, List[IzanamiLock]] =
      for {
        source <- LockDataStore.>.findByQuery(query)
                   .map { s =>
                     s.map {
                       case (_, v) => v.validate[IzanamiLock].get
                     }
                   }
        list <- PlayModule.mat.flatMap(mat => ZIO.fromFuture(_ => source.runWith(Sink.seq)(mat)))
      } yield list.toList

    def importData(
        strategy: ImportStrategy = ImportStrategy.Keep
    ): RIO[LockContext, Flow[(String, JsValue), ImportResult, NotUsed]] =
      ImportData.importDataFlow[LockContext, LockKey, IzanamiLock](
        strategy,
        lock => lock.id,
        key => getBy(key),
        (key, data) => create(key, data),
        (key, data) => update(key, data)
      )(LockInstances.format)
  }

  type LockInfo = zio.Has[LockContext]

  object LockInfo {

    @tailrec
    private def findCommonSegments(lockKey: Key, itemKey: Key): Option[(Key, Key)] =
      if (lockKey.key.startsWith(itemKey.key)) {
        Some(Tuple2(lockKey, itemKey))
      } else if (itemKey.segments.length == 1) {
        None
      } else {
        findCommonSegments(lockKey, Key(itemKey.segments.dropRight(1)))
      }

    private def findClosestLock(locks: List[LockKey], itemKey: Key): Option[(Key, Key)] =
      locks
        .flatMap(lock => findCommonSegments(lock, itemKey))
        .maxByOption(a => a._2.segments.length)

    private def getFirstItemOutOfLock(commonSeg: Key, itemKey: Key): Option[Key] = {
      val itemSeg = itemKey.segments
      if (itemSeg.length <= 1) {
        None
      } else {
        Some(Key(itemSeg.dropRight(itemSeg.length - commonSeg.segments.length - 1)))
      }
    }

    private def findLock(
        itemKey: Key,
        itemType: String,
        itemDataStore: JsonDataStore.Service,
        delete: Boolean = false
    ): URIO[LockContext, Option[Key]] =
      for {
        allLocks           <- LockService.findByQuery(Query.oneOf(List(s"$itemType:*"))).orDie
        allLocked          = allLocks.filter(lock => lock.locked).map(lock => lock.id.drop(itemType))
        lockAndCommonSeg   = findClosestLock(allLocked, itemKey)
        firstItemOutOfLock = lockAndCommonSeg.flatMap(l => getFirstItemOutOfLock(l._2, itemKey))
        subItemNotExist <- firstItemOutOfLock // item can be created in existing child items
                            .map(lc =>
                              itemDataStore
                                .findByQuery(Query.oneOf(s"${lc.key}*"), 1, 1)
                                .orDie
                                .flatMap(d => URIO.succeed(d.count == 0))
                            )
                            .getOrElse(URIO.succeed(true))
        isLastItemOfLock <- if (delete) isLastItemWithLock(lockAndCommonSeg.map(l => l._1), itemDataStore)
                           else ZIO.succeed(false)
      } yield {

        /*
           Example of items tree. Item b is locked.
           (1) Create item on root and lock exist : forbidden
                 root
                 / \
                X   a
                   /
                 b(L)

           (2) create item on the locked path : forbidden
                 root
                   \
                    a
                   / \
                 b(L) X

           (3) Create item after the lock : allow
                 root
                   \
                    a
                   /
                 b(L)
                 /
                X

           (4) create item on the lock : forbidden
                 root
                   \
                    a
                   /
                 b(L) <----- X


           (5) if b1 doesn't exist, X can be created
                 root
                   \
                    a
                   / \
                 b(L) b1
                       \
                        X
         */
        if (delete && isLastItemOfLock) {
          lockAndCommonSeg.map(l => l._1)
        } else if (itemKey.segments.length == 1 && allLocked.nonEmpty) { // item on the root path & lock exist (1)
          Some(allLocked.head)
        } else {
          lockAndCommonSeg
            .flatMap { lc =>
              val lock: LockKey = lc._1
              val commonSegment = lc._2
              if (itemKey.key.startsWith(s"${lock.key}:")) { // item is a child of lock (3)
                None
              } else if (commonSegment.key.equals(itemKey.key)) { // item is on the lock (4)
                Some(lock)
              } else if (commonSegment.key.equals(Key(itemKey.segments.dropRight(1)).key)) { // item is on the lock path (2)
                Some(lock)
              } else if (subItemNotExist) { // sub item does not exist, item can be created (5)
                Some(lock)
              } else {
                None
              }
            }
        }
      }

    private def isLastItemWithLock(mayBeLock: Option[LockKey], itemDataStore: JsonDataStore.Service) =
      mayBeLock
        .map(lock =>
          itemDataStore
            .findByQuery(Query.oneOf(s"${lock.key}:*"), 1, 2)
            .orDie
            .flatMap(result => URIO.succeed(result.count == 1))
        )
        .getOrElse(URIO.succeed(false))

    def isDeletionAllowed(
        itemKey: Key,
        itemType: String,
        itemDataStore: JsonDataStore.Service
    ): ZIO[LockContext, IzanamiErrors, Unit] =
      for {
        mayBeLockItem      <- findLock(itemKey, itemType, itemDataStore, delete = true)
        isLastItemWithLock <- isLastItemWithLock(mayBeLockItem, itemDataStore)
        _ <- ZIO.when(mayBeLockItem.isDefined) {
              val lock = mayBeLockItem.get
              if (isLastItemWithLock) {
                ZLogger.debug(s"Release lock ${lock.key} before to delete ${itemKey.key}.") *> ZIO.fail(
                  IzanamiErrors(UnauthorizedByLock(itemKey, lock))
                )
              } else {
                ZLogger.debug(s"${lock.key} lock the deletion of ${itemKey.key} ") *> ZIO.fail(
                  IzanamiErrors(UnauthorizedByLock(itemKey, lock))
                )
              }
            }
      } yield {}

    def isMoveAllowed(
        itemKey: Key,
        itemType: String,
        oldItemKey: Key,
        itemDataStore: JsonDataStore.Service
    ): ZIO[LockContext, IzanamiErrors, Unit] =
      for {
        _ <- isCreationAllowed(itemKey, itemType, itemDataStore)
        _ <- isDeletionAllowed(oldItemKey, itemType, itemDataStore)
      } yield {}

    def isCreationAllowed(
        itemKey: Key,
        itemType: String,
        itemDataStore: JsonDataStore.Service
    ): ZIO[LockContext, IzanamiErrors, Unit] =
      findLock(itemKey, itemType, itemDataStore).flatMap { maybeLock =>
        val value = ZIO.when(maybeLock.isDefined) {
          val lock = maybeLock.get
          ZLogger.debug(s"${lock.key} lock the creation of ${itemKey.key} ") *> ZIO.fail(
            IzanamiErrors(UnauthorizedByLock(itemKey, lock))
          )
        }
        value
      }
  }
}
