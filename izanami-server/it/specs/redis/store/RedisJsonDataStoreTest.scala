package specs.redis.store

import java.time.Duration

import env.Master
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle
import scala.jdk.CollectionConverters._
import store.redis.RedisWrapper
import store.redis.RedisClientBuilder
import store.redis.RedisJsonDataStore

class RedisJsonDataStoreTest extends AbstractJsonDataStoreTest("Redis") with BeforeAndAfter with BeforeAndAfterAll {

  val redisWrapper: RedisWrapper = RedisClientBuilder
    .redisClient(
      Some(Master("localhost", 6380, 5)),
      system,
      new FakeApplicationLifecycle()
    )
    .get

  override def dataStore(name: String): RedisJsonDataStore =
    RedisJsonDataStore(redisWrapper, name)

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
    redisWrapper.underlying.shutdown(Duration.ZERO, Duration.ofSeconds(5))
  }

  private def deleteAllData =
    redisWrapper.connection.sync().del(redisWrapper.connection.sync().keys("*").asScala.toSeq: _*)

}
