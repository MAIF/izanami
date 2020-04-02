package domains.abtesting

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import cats.data.NonEmptyList
import com.typesafe.config.{Config, ConfigFactory}
import domains.Key
import domains.events.EventStore
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import test.TestEventStore
import zio._
import zio.blocking.Blocking
import zio.internal.{Executor, PlatformLive}

import scala.concurrent.ExecutionContext
import scala.util.Random
import libs.logs.ProdLogger
import libs.logs.Logger
import domains.user.User
import domains.AuthInfo

abstract class AbstractExperimentServiceTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  def akkaConfig: Option[Config] = None

  implicit val system: ActorSystem =
    ActorSystem("Test", akkaConfig.map(c => c.withFallback(ConfigFactory.load())).getOrElse(ConfigFactory.load()))
  implicit val ec: ExecutionContext = system.dispatcher
  import zio.interop.catz._

  val context: ExperimentVariantEventServiceModule = new ExperimentVariantEventServiceModule {
    override val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      def blockingExecutor: ZIO[Any, Nothing, Executor] = ZIO.succeed(PlatformLive.Default.executor)
    }
    override def eventStore: EventStore                                                        = new TestEventStore()
    override def logger: Logger                                                                = new ProdLogger
    override def withAuthInfo(authInfo: Option[AuthInfo]): ExperimentVariantEventServiceModule = this
    override def authInfo: Option[AuthInfo]                                                    = None
  }
  implicit val runtime: Runtime[ExperimentVariantEventServiceModule] = Runtime(context, PlatformLive.Default)
  private val random                                                 = Random
  import zio._
  import zio.interop.catz._

  implicit class RunOps[T](t: zio.RIO[ExperimentVariantEventServiceModule, T]) {
    def unsafeRunSync(): T = runtime.unsafeRun(t)
  }

  def dataStore(name: String): ExperimentVariantEventService

  def initAndGetDataStore(name: String): ExperimentVariantEventService = {
    val store = dataStore(name)
    runtime.unsafeRun(store.start)
    store
  }

  s"$name ExperimentServiceTest" must {

    "crud" in {

      import cats.implicits._

      val ds = initAndGetDataStore(s"events_t1_${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      val generatedEvents: List[ExperimentVariantEvent] = (1 to 10).toList
        .traverse { id =>
          val experimentKey = Key(s"t1expid:${id % 2}")
          val keyA          = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id1-$id")
          val keyAw         = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id2-$id")
          val keyB          = ExperimentVariantEventKey(experimentKey, "variantB", "clientId", "namespace", s"id3-$id")
          val eventADisplayed = ExperimentVariantDisplayed(keyA,
                                                           experimentKey,
                                                           "clientId",
                                                           variantA,
                                                           transformation = 0,
                                                           variantId = "variantA")
          val eventBDisplayed = ExperimentVariantDisplayed(keyB,
                                                           experimentKey,
                                                           "clientId",
                                                           variantB,
                                                           transformation = 0,
                                                           variantId = "variantB")
          val eventAWon =
            ExperimentVariantWon(keyAw, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")

          (ds.create(keyA, eventADisplayed) *>
          ds.create(keyAw, eventAWon) *>
          ds.create(keyB, eventBDisplayed) *>
          ZIO(List(eventADisplayed, eventBDisplayed, eventAWon))).option
        }
        .unsafeRunSync()
        .flatMap(_.toList)
        .flatten

      val expId        = Key(s"t1expid:0")
      val experiment   = Experiment(expId, "Experiment 0", enabled = true, variants = NonEmptyList.of(variantA, variantB))
      val eventsFromDb = ds.listAll().unsafeRunSync().runWith(Sink.seq).futureValue

      eventsFromDb.map(_.id) must contain theSameElementsAs generatedEvents.map(_.id)

      ds.deleteEventsForExperiment(experiment).either.unsafeRunSync()

      val eventsFromDbAfterDelete = ds.listAll().unsafeRunSync().runWith(Sink.seq).futureValue

      val expectedEventsAfterDelete = generatedEvents.filter {
        case evt: ExperimentVariantDisplayed => evt.experimentId != expId
        case evt: ExperimentVariantWon       => evt.experimentId != expId
      }

      eventsFromDbAfterDelete.map(_.id) must contain theSameElementsAs expectedEventsAfterDelete.map(_.id)
    }

    "Find results" in {

      import cats.implicits._

      val ds = initAndGetDataStore(s"events_t2_${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      (1 to 10).toList
        .traverse { id =>
          val experimentKey = Key(s"t2expid:${id % 2}")
          val keyA          = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id1-$id")
          val keyAw         = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id2-$id")
          val keyB          = ExperimentVariantEventKey(experimentKey, "variantB", "clientId", "namespace", s"id3-$id")
          val eventADisplayed = ExperimentVariantDisplayed(keyA,
                                                           experimentKey,
                                                           "clientId",
                                                           variantA,
                                                           transformation = 0,
                                                           variantId = "variantA")
          val eventAWon =
            ExperimentVariantWon(keyAw, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
          val eventBDisplayed = ExperimentVariantDisplayed(keyB,
                                                           experimentKey,
                                                           "clientId",
                                                           variantB,
                                                           transformation = 0,
                                                           variantId = "variantB")

          (ds.create(keyA, eventADisplayed) *>
          ds.create(keyB, eventBDisplayed) *>
          ds.create(keyAw, eventAWon) *>
          ZIO(List(eventADisplayed, eventBDisplayed, eventAWon))).option
        }
        .unsafeRunSync()
        .flatMap(_.toList)
        .flatten

      val expId      = Key(s"t2expid:0")
      val experiment = Experiment(expId, "Experiment 0", enabled = true, variants = NonEmptyList.of(variantA, variantB))

      val results = ds.findVariantResult(experiment).unsafeRunSync().runWith(Sink.seq).futureValue

      results.size must be(2)

      val Some(variantResultA) = results.find(v => v.variant.get.id == "variantA")
      val Some(variantResultB) = results.find(v => v.variant.get.id == "variantB")

      variantResultA.displayed must be(5)
      variantResultA.won must be(5)
      variantResultA.transformation must be(100)

      variantResultB.displayed must be(5)
      variantResultB.won must be(0)
      variantResultB.transformation must be(0)

    }

  }

}
