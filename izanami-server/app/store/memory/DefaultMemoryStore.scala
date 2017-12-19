package store.memory

import akka.Done
import akka.actor.{Actor, ActorSystem, Cancellable, PoisonPill, Props}
import akka.util.Timeout
import cats.syntax.option._
import domains.Key
import env.DbDomainConfig
import play.api.Logger
import play.api.libs.json.{JsValue, _}
import store.Result.{ErrorMessage, Result}
import store.memory.JsonDataStoreActor._
import store._

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object InMemoryJsonDataStore {
  def apply(system: ActorSystem, name: String): InMemoryJsonDataStore =
    new InMemoryJsonDataStore(system, name)

  def apply(dbDomainConfig: DbDomainConfig,
            actorSystem: ActorSystem): InMemoryJsonDataStore = {
    val namespace = dbDomainConfig.conf.namespace

    Logger.info(s"Load store InMemory for namespace $namespace")
    InMemoryJsonDataStore(actorSystem, namespace)
  }
}

class InMemoryJsonDataStore(system: ActorSystem, name: String)
    extends JsonDataStore {

  import akka.pattern._
  import system.dispatcher

  private implicit val timeout = Timeout(1.second)
  private implicit val scheduler = system.scheduler

  private val store =
    system.actorOf(Props[JsonDataStoreActor](new JsonDataStoreActor()), name)

  override def createWithTTL(id: Key,
                             data: JsValue,
                             ttl: FiniteDuration): Future[Result[JsValue]] =
    applyCommand(CreateJsonDataWithTTL(id, data, ttl)).collect {
      case CommandResponse(r) => r
    }

  override def updateWithTTL(oldId: Key,
                             id: Key,
                             data: JsValue,
                             ttl: FiniteDuration): Future[Result[JsValue]] =
    applyCommand(UpdateJsonDataWithTTL(oldId, id, data, ttl)).collect {
      case CommandResponse(r) => r
    }

  override def create(id: Key, data: JsValue): Future[Result[JsValue]] =
    applyCommand(CreateJsonData(id, data)).collect {
      case CommandResponse(r) => r
    }

  override def update(oldId: Key,
                      id: Key,
                      data: JsValue): Future[Result[JsValue]] =
    applyCommand(UpdateJsonData(oldId, id, data)).collect {
      case CommandResponse(r) => r
    }

  override def delete(id: Key): Future[Result[JsValue]] =
    applyCommand(DeleteJsonData(id)).collect {
      case CommandResponse(r) => r
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    applyCommand(DeleteJsonDatasByPatterns(patterns)).collect {
      case CommandResponseBatch(r) => r
    }

  override def getById(id: Key): FindResult[JsValue] =
    find(GetById(id))

  override def getByIdLike(
      patterns: Seq[String],
      page: Int,
      nbElementPerPage: Int): Future[PagingResult[JsValue]] =
    (store ? Find(patterns, page, nbElementPerPage))
      .mapTo[ActorStoreResponse]
      .collect {
        case QueryResponse(r, c) =>
          DefaultPagingResult(r, page, nbElementPerPage, c)
      }

  override def getByIdLike(patterns: Seq[String]): FindResult[JsValue] =
    find(FindAll(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    find(FindAll(patterns)).list.map(_.size)

  def find(query: JsonDataQuery): FindResult[JsValue] = {
    val future: Future[ActorStoreResponse] =
      (store ? query).mapTo[ActorStoreResponse]
    SimpleFindResult(future.collect {
      case QueryResponse(c, _) => c
    })
  }

  def count(query: JsonCountQuery): Future[Long] = {
    val future: Future[ActorStoreResponse] =
      (store ? query).mapTo[ActorStoreResponse]
    future.collect {
      case CountResponse(c) => c
    }
  }

  def applyCommand[E](command: JsonDataCommand)(
      implicit format: Format[JsValue]): Future[ActorStoreResponse] = {
    val future: Future[ActorStoreResponse] =
      (store ? command.asInstanceOf[JsonDataMessages]).mapTo[ActorStoreResponse]
    future
  }

  def close(): Unit =
    this.store ! PoisonPill

}

private[memory] class JsonDataStoreActor extends Actor {

  private var scheduler: Option[Cancellable] = None
  private var datas: Map[Key, (JsValue, Long, Option[FiniteDuration])] =
    Map.empty[Key, (JsValue, Long, Option[FiniteDuration])]
  import context.dispatcher

  override def receive: Receive = {
    case CleanTTL =>
      datas = datas.filter { d =>
        isExpired(d._2).isDefined
      }

    case cmd: JsonDataCommand =>
      cmd match {

        case CreateJsonData(id, value) =>
          datas.get(id) match {
            case Some(_) =>
              sender() ! CommandResponse(
                Result.errors(ErrorMessage("error.data.exists", id.key)))

            case None =>
              sender() ! CommandResponse(Result.ok(value))
              val storedData =
                (value, System.currentTimeMillis(), none[FiniteDuration])
              datas = datas + (id -> storedData)
          }

        case CreateJsonDataWithTTL(id, value, ttl) =>
          datas.get(id) match {
            case Some(_) =>
              sender() ! CommandResponse(
                Result.errors(ErrorMessage("error.data.exists", id.key)))
            case None =>
              sender() ! CommandResponse(Result.ok(value))
              val storedData = (value, System.currentTimeMillis(), ttl.some)
              datas = datas + (id -> storedData)
          }

        case UpdateJsonData(oldId, id, value) =>
          sender() ! CommandResponse(Result.ok(value))
          val storedData =
            (value, System.currentTimeMillis(), none[FiniteDuration])
          datas = (datas - oldId) + (id -> storedData)

        case UpdateJsonDataWithTTL(oldId, id, value, ttl) =>
          sender() ! CommandResponse(Result.ok(value))
          val storedData = (value, System.currentTimeMillis(), ttl.some)
          datas = (datas - oldId) + (id -> storedData)

        case DeleteJsonData(id) =>
          datas.get(id) match {
            case Some((value, _, _)) =>
              sender() ! CommandResponse(Result.ok(value))
              datas = datas - id
            case None =>
              sender() ! CommandResponse(Result.error(s"error.data.missing"))
          }

        case DeleteJsonDatasByPatterns(patterns) =>
          val keysToDelete = datas.keys.filter { matchPatterns(patterns) }.toList
          val newDatas = keysToDelete.foldLeft(datas) { _ - _ }
          sender() ! CommandResponseBatch(Result.ok(Done))
          datas = newDatas
      }

    /* Queries */
    case query: JsonDataQuery =>
      query match {

        case GetById(id) =>
          val queriedDatas = datas.get(id).flatMap(isExpired).toList
          sender() ! QueryResponse(queriedDatas, 1)

        case Find(patterns, page, nbElementPerPage) =>
          Logger.debug(s"Applying pattern $patterns to keys during search")

          val position = (page - 1) * nbElementPerPage
          val keys = datas.keys
            .filter {
              matchPatterns(patterns)
            }
          val values = keys
            .flatMap(datas.get)
            .flatMap(isExpired)
            .slice(position, position + nbElementPerPage)
            .toList
          sender() ! QueryResponse(values, keys.size)

        case FindAll(patterns) =>
          Logger.debug(s"Applying pattern $patterns to keys during search ")

          val keys = datas
            .collect { case (k, v) if matchPatterns(patterns)(k) => v }
          val values = keys
            .flatMap(isExpired)
            .toList
          sender() ! QueryResponse(values, keys.size)

      }

    case query: JsonCountQuery =>
      sender() ! CountResponse(datas.count(_ => true))
  }

  override def preStart(): Unit =
    scheduler = Some(
      context.system.scheduler.schedule(1.minute, 1.minute, self, CleanTTL))

  override def postStop(): Unit =
    scheduler.foreach(_.cancel())
}

private[memory] object JsonDataStoreActor {

  case object TimerKey

  case object CleanTTL

  sealed trait JsonDataMessages
  sealed trait JsonDataCommand extends JsonDataMessages
  case class CreateJsonData(id: Key, data: JsValue) extends JsonDataCommand
  case class CreateJsonDataWithTTL(id: Key, data: JsValue, ttl: FiniteDuration)
      extends JsonDataCommand
  case class UpdateJsonData(oldId: Key, id: Key, data: JsValue)
      extends JsonDataCommand
  case class UpdateJsonDataWithTTL(oldId: Key,
                                   id: Key,
                                   data: JsValue,
                                   ttl: FiniteDuration)
      extends JsonDataCommand
  case class DeleteJsonData(id: Key) extends JsonDataCommand
  case class DeleteJsonDatasByPatterns(patterns: Seq[String])
      extends JsonDataCommand

  sealed trait JsonDataQuery extends JsonDataMessages
  case class GetById(id: Key) extends JsonDataQuery
  case class Find(patterns: Seq[String],
                  page: Int = 1,
                  nbElementPerPage: Int = 15)
      extends JsonDataQuery
  case class FindAll(patterns: Seq[String]) extends JsonDataQuery

  sealed trait JsonCountQuery extends JsonDataMessages
  case object CountAll extends JsonCountQuery

  sealed trait ActorStoreResponse
  case class CommandResponse(result: Result[JsValue]) extends ActorStoreResponse
  case class CommandResponseBatch(result: Result[Done])
      extends ActorStoreResponse
  case class QueryResponse(configs: List[JsValue], count: Int)
      extends ActorStoreResponse
  case class CountResponse(count: Long) extends ActorStoreResponse

  private def matchPatterns(patterns: Seq[String])(key: Key) =
    patterns.forall(r => key.matchPattern(r))

  private def isExpired(
      storedData: (JsValue, Long, Option[FiniteDuration])): Option[JsValue] = {
    val now = System.currentTimeMillis()
    storedData match {
      case (data, date, Some(ttl)) if (date + ttl.toMillis) < now =>
        none[JsValue]
      case (data, _, _) => data.some
    }
  }
}
