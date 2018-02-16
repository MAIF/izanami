package izanami.commons

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import izanami.Strategy.{CacheWithPollingStrategy, CacheWithSseStrategy}
import izanami._
import izanami.commons.SmartCacheStrategyActor.CacheEvent

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble
import scala.util.Failure

object SmartCacheStrategyActor {
  case object Init
  case object Stop
  case object RefreshCache
  case class SetValues[T](values: Seq[(String, T)], triggerEvent: Boolean)
  case class RemoveValues[T](values: Seq[String], triggerEvent: Boolean)
  case class Get(key: String)
  case class GetByPattern(key: String)

  sealed trait CacheEvent[T]
  case class ValueUpdated[T](key: String, newValue: T, oldValue: T) extends CacheEvent[T]
  case class ValueCreated[T](key: String, newValue: T)              extends CacheEvent[T]
  case class ValueDeleted[T](key: String, oldValue: T)              extends CacheEvent[T]

  def props[T](
      dispatcher: IzanamiDispatcher,
      initialPatterns: Seq[String],
      fetchData: Seq[String] => Future[Seq[(String, T)]],
      config: SmartCacheStrategy,
      fallback: Map[String, T],
      onValueUpdated: Seq[CacheEvent[T]] => Unit
  ): Props =
    Props(
      new SmartCacheStrategyActor[T](dispatcher: IzanamiDispatcher,
                                     initialPatterns,
                                     fetchData,
                                     config,
                                     fallback,
                                     onValueUpdated)
    )

}

private[izanami] class SmartCacheStrategyActor[T](
    dispatcher: IzanamiDispatcher,
    initialPatterns: Seq[String],
    fetchData: Seq[String] => Future[Seq[(String, T)]],
    config: SmartCacheStrategy,
    fallback: Map[String, T],
    onValueUpdated: Seq[CacheEvent[T]] => Unit
) extends Actor
    with ActorLogging {

  import SmartCacheStrategyActor._
  import akka.pattern._
  import dispatcher.ec

  private implicit val mat: Materializer =
    ActorMaterializer(
      ActorMaterializerSettings(context.system)
        .withDispatcher(dispatcher.name)
    )(context)

  override def receive: Receive =
    logic(
      patterns = initialPatterns.toSet,
      cache = fallback,
      pollingScheduler = None
    )

  private def logic(
      patterns: Set[String],
      cache: Map[String, T],
      pollingScheduler: Option[Cancellable]
  ): Receive = {

    case Init =>
      val scheduler: Option[Cancellable] = config match {
        case c: CacheWithPollingStrategy =>
          Some(
            context.system.scheduler
              .schedule(c.pollingInterval, c.pollingInterval, self, RefreshCache)
          )
        case _ => None
      }
      context.become(
        logic(
          cache = cache,
          patterns = patterns,
          pollingScheduler = scheduler
        ),
        discardOld = true
      )
      log.info(s"Initializing smart cache actor with strategy $config and patterns $patterns")
      val initValues: Future[SetValues[T]] =
        fetchData(patterns.toSeq).map { r =>
          SetValues[T](r, triggerEvent = true)
        }
      initValues.onComplete {
        case Failure(e) =>
          log.error(e, "Error initializing smart cache retrying in 5 seconds")
          context.system.scheduler
            .scheduleOnce(5.seconds, self, RefreshCache)
        case _ =>
      }
      pipe(initValues) to self

    //We return the actual state of the cache
    case GetByPattern(pattern) if patterns.contains(pattern) =>
      def matchP = PatternsUtil.matchPattern(pattern) _
      val values: Seq[T] = cache
        .filter {
          case (k, _) => matchP(k)
        }
        .values
        .toSeq
      sender() ! values

    //Unknow pattern, we fetch and update the cache
    case GetByPattern(pattern) =>
      val futureValues: Future[Seq[(String, T)]] = fetchData(List(pattern))
      context.become(
        logic(
          patterns = patterns + pattern,
          cache = cache,
          pollingScheduler = pollingScheduler
        ),
        discardOld = true
      )

      // Response to the sender
      pipe(futureValues.map(s => s.map(_._2))) to sender()

      // Update internal cache
      pipe(futureValues.map(r => SetValues[T](r, triggerEvent = true))) to self

    case Get(key) if PatternsUtil.matchOnePattern(patterns.toSeq)(key) =>
      sender() ! cache.get(key)

    case Get(key) =>
      val value: Option[T] = cache.get(key)
      value match {
        case Some(_) =>
          sender() ! value
        case None =>
          val fResult: Future[Seq[(String, T)]] = fetchData(Seq(key))
          pipe(fResult.map(r => r.find(_._1 == key).map(_._2))) to sender()
      }

    case RefreshCache =>
      log.debug(s"Refresh cache for patterns $patterns")
      val keysAndPatterns: Seq[String] = resolvePatterns(patterns.toSeq, cache.keys.toSeq)

      val call: Future[SetValues[T]] = fetchData(keysAndPatterns).map(r => SetValues[T](r, triggerEvent = true))
      call.onComplete {
        case Failure(e) if config.isInstanceOf[CacheWithSseStrategy] =>
              log.error(e, "Error refreshing cache, retrying in 5 seconds")
              context.system.scheduler
                .scheduleOnce(5.seconds, self, RefreshCache)
        case Failure(e) =>
          log.error(e, "Error refreshing cache")
        case _ =>
      }
      pipe(call) to self

    case SetValues(values, triggerEvent) =>
      log.debug("Updating cache with values {}", values)
      val valuesToUpdate: Seq[(String, T)] = values
        .asInstanceOf[Seq[(String, T)]]
        .filter(v => PatternsUtil.matchOnePattern(patterns.toSeq)(v._1))

      if (triggerEvent) {
        val updates: Seq[CacheEvent[T]] = cache.collect {
          case (k, v) if valuesToUpdate.exists {
                case (k1, v2) => k1 == k && v2 != v
              } =>
            val newValue: (String, T) = valuesToUpdate.find(_._1 == k).get
            ValueUpdated[T](k, newValue._2, v)
        }.toSeq
        val creates: Seq[CacheEvent[T]] = valuesToUpdate.collect {
          case (k, v) if !cache.contains(k) =>
            ValueCreated(k, v)
        }
        val events: Seq[CacheEvent[T]] = updates ++ creates
        onValueUpdated(events)
      }

      val cacheUpdated: Map[String, T] = cache ++ valuesToUpdate

      context.become(
        logic(
          cache = cacheUpdated,
          patterns = patterns,
          pollingScheduler = pollingScheduler
        ),
        discardOld = true
      )

    case RemoveValues(keys, triggerEvent) =>
      val deletes: Seq[CacheEvent[T]] = cache.collect {
        case (k, v) if keys.contains(k) =>
          ValueDeleted[T](k, v)
      }.toSeq
      if (triggerEvent) {
        onValueUpdated(deletes)
      }
      context.become(
        logic(
          cache = cache -- keys,
          patterns = patterns,
          pollingScheduler = pollingScheduler
        ),
        discardOld = true
      )

    case Stop =>
      pollingScheduler.foreach(_.cancel())
      context.stop(self)

  }

  override def preStart(): Unit =
    self ! Init

  override def postStop(): Unit =
    self ! Stop

  private def resolvePatterns(existingPatterns: Seq[String], keys: Seq[String]): Seq[String] =
    keys.filterNot(PatternsUtil.matchOnePattern(existingPatterns)) ++ existingPatterns

}

object PatternsUtil {

  def matchPattern(pattern: String)(key: String): Boolean = {
    val regex: String = buildPattern(pattern)
    key.matches(regex)
  }

  def buildPattern(str: String) = s"^${str.replaceAll("\\*", ".*")}$$"

  def matchOnePattern(existingPatterns: Seq[String])(str: String): Boolean =
    existingPatterns.exists(p => matchPattern(p)(str))

}
