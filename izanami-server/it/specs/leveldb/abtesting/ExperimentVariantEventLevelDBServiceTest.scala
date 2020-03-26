package specs.leveldb.abtesting

import java.io.File

import domains.abtesting.events.impl.ExperimentVariantEventLevelDBService
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import env.{DbDomainConfig, DbDomainConfigDetails, LevelDbConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import test.FakeApplicationLifecycle

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Random

class ExperimentVariantEventLevelDBServiceTest
    extends AbstractExperimentServiceTest("LevelDb")
    with BeforeAndAfter
    with BeforeAndAfterAll {

  private val lifecycle: FakeApplicationLifecycle = new FakeApplicationLifecycle()

  override def dataStore(name: String): ExperimentVariantEventService = ExperimentVariantEventLevelDBService(
    LevelDbConfig(s"./target/leveldb-test/data-${Random.nextInt(1000)}"),
    DbDomainConfig(env.LevelDB, DbDomainConfigDetails(name, None), None),
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
