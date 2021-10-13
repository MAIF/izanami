package specs.redis.store

import java.time.Duration
import env.{Location, Master}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle

import scala.jdk.CollectionConverters._
import store.redis.RedisWrapper
import store.redis.RedisClientBuilder
import store.redis.RedisJsonDataStore
import zio.{Exit, Reservation}

class RedisJsonDataStoreTest extends AbstractJsonDataStoreTest("Redis") with BeforeAndAfter with BeforeAndAfterAll {

  val redisWrapper: Reservation[Any, Throwable, Option[RedisWrapper]] = runtime.unsafeRun(
    RedisClientBuilder
      .redisClient(
        Some(Master("localhost", 6380, 5, None, None, false, None, Location(None), Location(None))),
        system
      )
      .reserve
  )
  private val maybeRedisWrapper: Option[RedisWrapper] = runtime.unsafeRun(redisWrapper.acquire)

  override def dataStore(name: String): RedisJsonDataStore =
    RedisJsonDataStore(maybeRedisWrapper.get, name)

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
    maybeRedisWrapper.get.connection.sync().del(maybeRedisWrapper.get.connection.sync().keys("*").asScala.toSeq: _*)

}
