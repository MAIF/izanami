package store.redis

import java.time.Duration

import cats.effect.IO
import env.Master
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.{AbstractJsonDataStoreTest, JsonDataStore}
import test.FakeApplicationLifecycle
import scala.collection.JavaConverters._

class RedisJsonDataStoreTest extends AbstractJsonDataStoreTest("Redis") with BeforeAndAfter with BeforeAndAfterAll {

  val redisWrapper: RedisWrapper = RedisClientBuilder.redisClient(
    Some(Master("localhost", 6380, 5)),
    system,
    new FakeApplicationLifecycle()
  ).get

  override def dataStore(name: String): JsonDataStore[IO] =
    RedisJsonDataStore[IO](redisWrapper, name)


  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
    redisWrapper.underlying.shutdown(Duration.ZERO, Duration.ofSeconds(5))
  }

  private def deleteAllData = redisWrapper.connection.sync().del(redisWrapper.connection.sync().keys("*").asScala:_*)

}
