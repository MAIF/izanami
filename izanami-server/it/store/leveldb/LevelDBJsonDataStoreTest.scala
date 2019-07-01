package store.leveldb

import java.io.File

import cats.effect.IO
import env.{DbDomainConfig, DbDomainConfigDetails, InMemory, LevelDbConfig}
import org.scalatest.BeforeAndAfterAll
import store.{AbstractJsonDataStoreTest, JsonDataStore}
import test.FakeApplicationLifecycle

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Random

class LevelDBJsonDataStoreTest extends AbstractJsonDataStoreTest("LevelDb") with BeforeAndAfterAll {

  implicit val dbStores: DbStores[IO] = new DbStores

  private val lifecycle: FakeApplicationLifecycle = new FakeApplicationLifecycle()

  override def dataStore(name: String): JsonDataStore[IO] = LevelDBJsonDataStore[IO](
    LevelDbConfig(s"./target/leveldb-test/data-${Random.nextInt(1000)}"),
    DbDomainConfig(InMemory, DbDomainConfigDetails(name, None), None),
    lifecycle
  )


  override protected def afterAll(): Unit = {
    super.afterAll()

    Await.result(Future.traverse(lifecycle.hooks) {
      _.apply()
    }, 5.seconds)

    import scala.reflect.io.Directory
    val directory = new Directory(new File("./target/leveldb-test/"))
    directory.deleteRecursively()

  }
}