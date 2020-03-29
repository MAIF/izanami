package specs.leveldb.store

import java.io.File

import env.{DbDomainConfig, DbDomainConfigDetails, InMemory, LevelDbConfig}
import org.scalatest.BeforeAndAfterAll
import store.AbstractJsonDataStoreTest
import test.FakeApplicationLifecycle

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Random
import store.leveldb._
import store.datastore.JsonDataStore

class LevelDBJsonDataStoreTest extends AbstractJsonDataStoreTest("LevelDb") with BeforeAndAfterAll {

  private val lifecycle: FakeApplicationLifecycle = new FakeApplicationLifecycle()

  override def dataStore(name: String): JsonDataStore.Service = LevelDBJsonDataStore(
    LevelDbConfig(s"./target/leveldb-storetest/data-${Random.nextInt(1000)}"),
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
