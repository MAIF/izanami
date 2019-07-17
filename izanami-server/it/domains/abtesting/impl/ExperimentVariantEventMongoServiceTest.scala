package domains.abtesting.impl

import cats.effect.IO
import domains.abtesting.{AbstractExperimentServiceTest, ExperimentVariantEventService}
import domains.events.impl.BasicEventStore
import env.{DbDomainConfig, DbDomainConfigDetails, Mongo}
import org.scalactic.source.Position
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import play.api.Configuration
import play.modules.reactivemongo.DefaultReactiveMongoApi
import reactivemongo.api.MongoConnection
import test.FakeApplicationLifecycle
import scala.concurrent.duration.DurationLong

import scala.concurrent.Await
import scala.util.Random

class ExperimentVariantEventMongoServiceTest extends AbstractExperimentServiceTest("Mongo") with BeforeAndAfter with BeforeAndAfterAll {

  val mongoApi = new DefaultReactiveMongoApi(
    MongoConnection.parseURI("mongodb://localhost:27017").get,
    s"dbtest-${Random.nextInt(50)}", false, Configuration.empty,
    new FakeApplicationLifecycle()
  )

  override def dataStore(name: String): ExperimentVariantEventService[IO] = ExperimentVariantEventMongoService[IO](
    DbDomainConfig(Mongo, DbDomainConfigDetails(name, None), None), mongoApi, new BasicEventStore[IO]
  )

  override protected def before(fun: => Any)(implicit pos: Position): Unit = {
    super.before(fun)
    deleteAllData
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    deleteAllData
  }

  private def deleteAllData = {
    Await.result(mongoApi.database.flatMap { _.drop()}, 30.seconds)
  }

}
