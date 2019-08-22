package domains.abtesting.impl

import java.time.Duration

import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import env.{DbDomainConfig, DbDomainConfigDetails, Master}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.redis.RedisClientBuilder
import test.FakeApplicationLifecycle

import scala.collection.JavaConverters._

class ExperimentVariantEventRedisServiceTest extends AbstractExperimentServiceTest("Postgresql") with BeforeAndAfter with BeforeAndAfterAll {

  import zio.interop.catz._

  val redisWrapper = RedisClientBuilder.redisClient(
    Some(Master("localhost", 6380, 5)),
    system,
    new FakeApplicationLifecycle()
  )

  override def dataStore(name: String): ExperimentVariantEventService = ExperimentVariantEventRedisService(
    DbDomainConfig(env.Redis, DbDomainConfigDetails(name, None), None), redisWrapper
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
    redisWrapper.get.underlying.shutdown(Duration.ZERO, Duration.ofSeconds(5))
  }

  private def deleteAllData = redisWrapper.get.connection.sync().del(redisWrapper.get.connection.sync().keys("*").asScala:_*)

}
