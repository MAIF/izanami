package specs.redis.abtesting

import java.time.Duration
import domains.abtesting.events.impl.ExperimentVariantEventRedisService
import domains.abtesting.AbstractExperimentServiceTest
import domains.abtesting.events.ExperimentVariantEventService
import env.{DbDomainConfig, DbDomainConfigDetails, Location, Master}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.redis.{RedisClientBuilder, RedisWrapper}
import test.FakeApplicationLifecycle
import zio.{Exit, Reservation}

import scala.jdk.CollectionConverters._

class ExperimentVariantEventRedisServiceTest
    extends AbstractExperimentServiceTest("Redis")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  import zio.interop.catz._

  val redisWrapper: Reservation[Any, Throwable, Option[RedisWrapper]] = runtime.unsafeRun(
    RedisClientBuilder
      .redisClient(
        Some(Master("localhost", 6380, 5, None, None, false, None, Location(None), Location(None))),
        system
      )
      .reserve
  )
  private val maybeRedisWrapper: Option[RedisWrapper] = runtime.unsafeRun(redisWrapper.acquire)

  override def dataStore(name: String): ExperimentVariantEventService.Service =
    ExperimentVariantEventRedisService(DbDomainConfig(env.Redis, DbDomainConfigDetails(name, None), None),
                                       maybeRedisWrapper)

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
    runtime.unsafeRun(redisWrapper.release(Exit.unit))
  }

  private def deleteAllData =
    maybeRedisWrapper.get.connection
      .sync()
      .del(maybeRedisWrapper.get.connection.sync().keys("*").asScala.toSeq: _*)

}
