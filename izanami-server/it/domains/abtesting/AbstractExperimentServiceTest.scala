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
import store.{JsonDataStore, Result}

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.Random

abstract class AbstractExperimentServiceTest(name: String) extends PlaySpec with ScalaFutures with IntegrationPatience {

  def akkaConfig: Option[Config] = None

  implicit val system: ActorSystem = ActorSystem("Test", akkaConfig.map(c => c.withFallback(ConfigFactory.load())).getOrElse(ConfigFactory.load()))
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat : Materializer = ActorMaterializer()

  private val random = Random

  def dataStore(dataStore: String) : ExperimentVariantEventService[IO]


  s"$name ExperimentServiceTest" must {

    "crud" in {

      import cats.implicits._

      val ds = dataStore(s"events:test:${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      val generatedEvents = (1 to 10).toList.traverse { id =>
        val experimentKey = Key(s"eventid:${id % 2}")
        val keyA = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id$id")
        val keyB = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id$id")
        val eventADisplayed = ExperimentVariantDisplayed(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
        val eventBDisplayed = ExperimentVariantDisplayed(keyB, experimentKey, "clientId", variantB, transformation = 0, variantId = "variantB")
        val eventAWon = ExperimentVariantWon(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")

        ds.create(keyA, eventADisplayed) *>
          ds.create(keyA, eventBDisplayed) *>
          ds.create(keyA, eventAWon) *>
          IO(List(eventADisplayed, eventBDisplayed, eventAWon))
      }.unsafeRunSync().flatten


      val eventsFromDb = ds.listAll().runWith(Sink.seq).futureValue

      eventsFromDb must contain theSameElementsAs generatedEvents

      val expId = Key(s"eventid:0")
      val experiment = Experiment(expId, "Experiment 0", enabled = true, variants = NonEmptyList.of(variantA, variantB))

      ds.deleteEventsForExperiment(experiment).unsafeRunSync()

      val eventsFromDbAfterDelete = ds.listAll().runWith(Sink.seq).futureValue

      val expectedEventsAfterDelete = generatedEvents.filter {
        case evt: ExperimentVariantDisplayed => evt.experimentId != expId
        case evt: ExperimentVariantWon => evt.experimentId != expId
      }

      eventsFromDbAfterDelete must contain theSameElementsAs expectedEventsAfterDelete
    }

    "Find results" in {

      import cats.implicits._

      val ds = dataStore(s"events:test:${random.nextInt(10000)}")

      val variantA = Variant("variantA", "name", traffic = Traffic(0.5))
      val variantB = Variant("variantB", "name", traffic = Traffic(0.5))
      val generatedEvents = (1 to 10).toList.traverse { id =>
        val experimentKey = Key(s"eventid:${id % 2}")
        val keyA = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id$id")
        val keyB = ExperimentVariantEventKey(experimentKey, "variantA", "clientId", "namespace", s"id$id")
        val eventADisplayed = ExperimentVariantDisplayed(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")
        val eventBDisplayed = ExperimentVariantDisplayed(keyB, experimentKey, "clientId", variantB, transformation = 0, variantId = "variantB")
        val eventAWon = ExperimentVariantWon(keyA, experimentKey, "clientId", variantA, transformation = 0, variantId = "variantA")

        ds.create(keyA, eventADisplayed) *>
          ds.create(keyA, eventBDisplayed) *>
          ds.create(keyA, eventAWon) *>
          IO(List(eventADisplayed, eventBDisplayed, eventAWon))
      }.unsafeRunSync().flatten

      val expId = Key(s"eventid:0")
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
