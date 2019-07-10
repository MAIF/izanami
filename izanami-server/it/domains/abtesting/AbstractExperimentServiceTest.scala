package domains.abtesting

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import cats.data.NonEmptyList
import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory}
import domains.Key
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec

import scala.concurrent.ExecutionContext
import scala.util.Random

abstract class AbstractExperimentServiceTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  def akkaConfig: Option[Config] = None

  implicit val system: ActorSystem = ActorSystem("Test", akkaConfig.map(c => c.withFallback(ConfigFactory.load())).getOrElse(ConfigFactory.load()))
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat : Materializer = ActorMaterializer()

  private val random = Random

  def dataStore(name: String) : ExperimentVariantEventService[IO]


  s"$name ExperimentServiceTest" must {

    "crud" in {

      import cats.implicits._

      val ds = dataStore(s"events_t1_${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      val generatedEvents = (1 to 10).toList.traverse { id =>
        val experimentKey = Key(s"t1expid:${id % 2}")
        val keyA = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id1-$id")
        val keyAw = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id2-$id")
        val keyB = ExperimentVariantEventKey(experimentKey, "variantB", "clientId", "namespace", s"id3-$id")
        val eventADisplayed = ExperimentVariantDisplayed(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
        val eventBDisplayed = ExperimentVariantDisplayed(keyB, experimentKey, "clientId", variantB, transformation = 0, variantId = "variantB")
        val eventAWon = ExperimentVariantWon(keyAw, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")

        ds.create(keyA, eventADisplayed) *>
          ds.create(keyAw, eventAWon) *>
          ds.create(keyB, eventBDisplayed) *>
          IO(List(eventADisplayed, eventBDisplayed, eventAWon))
      }.unsafeRunSync().flatten

      val expId = Key(s"t1expid:0")
      val experiment = Experiment(expId, "Experiment 0", enabled = true, variants = NonEmptyList.of(variantA, variantB))
      val eventsFromDb = ds.listAll().runWith(Sink.seq).futureValue

      eventsFromDb.map(_.id) must contain theSameElementsAs generatedEvents.map(_.id)

      ds.deleteEventsForExperiment(experiment).unsafeRunSync()

      val eventsFromDbAfterDelete = ds.listAll().runWith(Sink.seq).futureValue

      val expectedEventsAfterDelete = generatedEvents.filter {
        case evt: ExperimentVariantDisplayed => evt.experimentId != expId
        case evt: ExperimentVariantWon => evt.experimentId != expId
      }

      eventsFromDbAfterDelete.map(_.id) must contain theSameElementsAs expectedEventsAfterDelete.map(_.id)
    }

    "Find results" in {

      import cats.implicits._

      val ds = dataStore(s"events_t2_${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      val generatedEvents = (1 to 10).toList.traverse { id =>
        val experimentKey = Key(s"t2expid:${id % 2}")
        val keyA = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id1-$id")
        val keyAw = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id2-$id")
        val keyB = ExperimentVariantEventKey(experimentKey, "variantB", "clientId", "namespace", s"id3-$id")
        val eventADisplayed = ExperimentVariantDisplayed(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
        val eventAWon = ExperimentVariantWon(keyAw, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
        val eventBDisplayed = ExperimentVariantDisplayed(keyB, experimentKey, "clientId", variantB, transformation = 0, variantId = "variantB")

        ds.create(keyA, eventADisplayed) *>
          ds.create(keyB, eventBDisplayed) *>
          ds.create(keyAw, eventAWon) *>
          IO(List(eventADisplayed, eventBDisplayed, eventAWon))
      }.unsafeRunSync().flatten

      val expId = Key(s"t2expid:0")
      val experiment = Experiment(expId, "Experiment 0", enabled = true, variants = NonEmptyList.of(variantA, variantB))

      val results = ds.findVariantResult(experiment).runWith(Sink.seq).futureValue

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
