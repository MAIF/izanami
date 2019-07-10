package domains.abtesting.impl

import java.io.File

import cats.effect.IO
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import domains.events.impl.BasicEventStore
import env.{DbDomainConfig, DbDomainConfigDetails, LevelDbConfig}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import test.FakeApplicationLifecycle

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Random

class ExperimentVariantEventLevelDBServiceTest  extends AbstractExperimentServiceTest("LevelDb") with BeforeAndAfter with BeforeAndAfterAll {

  private val lifecycle: FakeApplicationLifecycle = new FakeApplicationLifecycle()

  override def dataStore(name: String): ExperimentVariantEventService[IO] = ExperimentVariantEventLevelDBService[IO](
    LevelDbConfig(s"./target/leveldb-test/data-${Random.nextInt(1000)}"),
    DbDomainConfig(env.LevelDB, DbDomainConfigDetails(name, None), None),
    new BasicEventStore[IO],
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
