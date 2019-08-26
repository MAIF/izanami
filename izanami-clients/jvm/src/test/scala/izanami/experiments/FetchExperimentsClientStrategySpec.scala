package izanami.experiments

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import izanami.scaladsl.{ExperimentClient, IzanamiClient}
import izanami._
import org.scalatest.BeforeAndAfterAll
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.{Failure, Success}

class FetchExperimentsClientStrategySpec extends IzanamiSpec with BeforeAndAfterAll with ExperimentMockServer {
  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchExperimentStrategy" should {

    "List experiments" in {

      //#experiment-client
      val experimentClient = IzanamiClient(ClientConfig(host))
        .experimentClient(Strategies.fetchStrategy())
      //#experiment-client

      val variantA            = Variant("A", "Variant A", "The A variant")
      val variantB            = Variant("B", "Variant B", "The B variant")
      val expectedExperiments = Experiment("test", "test", "An experiment", true, Seq(variantA, variantB))

      getExperimentList("*", Seq(expectedExperiments))

      val experimentsList = experimentClient.list("*").futureValue

      experimentsList must have size 1

      val experimentHead = experimentsList.head

      experimentHead.id must be(expectedExperiments.id)
      experimentHead.name must be(expectedExperiments.name)
      experimentHead.description must be(expectedExperiments.description)
      experimentHead.enabled must be(expectedExperiments.enabled)
      experimentHead.variants must be(expectedExperiments.variants)

      getExperiment(expectedExperiments)
      //#get-experiment
      val futureExperiment: Future[Option[ExperimentClient]] = experimentClient.experiment("test")
      futureExperiment.onComplete {
        case Success(Some(exp)) => println(s"Experiment is $exp")
        case Success(None)      => println("Experiment not Found")
        case Failure(e)         => e.printStackTrace()
      }
      //#get-experiment
      val mayBeExperiment: Option[ExperimentClient] = futureExperiment.futureValue

      getVariantNotFound("test", "client1")
      //#get-variant
      val mayBeFutureVariant: Future[Option[Variant]] = experimentClient.getVariantFor("test", "client1")
      mayBeFutureVariant.onComplete {
        case Success(mayBeVariant) => println(mayBeVariant)
        case Failure(e)            => e.printStackTrace()
      }
      //#get-variant

      experimentHead.getVariantFor("client1").futureValue must be(None)

      getVariant("test", "client1", variantA)
      variantWon("test", "client1", variantA)
      variantDisplayed("test", "client1", variantA)
      //#an-experiment
      val experiment: ExperimentClient                  = mayBeExperiment.get
      val futureVariant: Future[Option[Variant]]        = experiment.getVariantFor("client1")
      val displayed: Future[ExperimentVariantDisplayed] = experiment.markVariantDisplayed("client1")
      val won: Future[ExperimentVariantWon]             = experiment.markVariantWon("client1")
      //#an-experiment
      displayed.futureValue.variant must be(variantA)
      futureVariant.futureValue must be(Some(variantA))
      won.futureValue.variant must be(variantA)

      //#displayed-variant
      val futureDisplayed: Future[ExperimentVariantDisplayed] =
        experimentClient.markVariantDisplayed("test", "client1")
      futureDisplayed.onComplete {
        case Success(event) => println(event)
        case Failure(e)     => e.printStackTrace()
      }
      //#displayed-variant
      //#won-variant
      val futureWon: Future[ExperimentVariantWon] = experimentClient.markVariantWon("test", "client1")
      futureWon.onComplete {
        case Success(event) => println(event)
        case Failure(e)     => e.printStackTrace()
      }
      //#won-variant

    }

    "Tree experiments" in {
      val client = IzanamiClient(ClientConfig(host))
        .experimentClient(Strategies.fetchStrategy())

      val variantA            = Variant("A", "Variant A", "The A variant")
      val variantB            = Variant("B", "Variant B", "The B variant")
      val expectedExperiments = Experiment("izanami:ab:test", "test", "An experiment", true, Seq(variantA, variantB))

      getExperimentTree("*", "client1", "A", Seq(expectedExperiments))

      //#experiment-tree
      val experimentsTree = client.tree("*", "client1").futureValue
      experimentsTree must be(
        Json.obj(
          "izanami" -> Json.obj(
            "ab" -> Json.obj(
              "test" -> Json.obj(
                "variant" -> "A"
              )
            )
          )
        )
      )
      //#experiment-tree
    }

    "Tree experiments with disabled experiment" in {

      val variantA = Variant("A", "Variant A", "The A variant")
      val variantB = Variant("B", "Variant B", "The B variant")

      val client = IzanamiClient(ClientConfig(host))
        .experimentClient(
          Strategies.fetchStrategy(),
          Experiments(ExperimentFallback("izanami:ab:test", "test", "An experiment", false, variantA))
        )

      val expectedExperiments = Experiment("izanami:ab:test", "test", "An experiment", false, Seq(variantA, variantB))

      getExperimentTree("*", "client1", "A", Seq(expectedExperiments))

      //#experiment-tree
      val experimentsTree = client.tree("*", "client1").futureValue
      experimentsTree must be(
        Json.obj()
      )
      //#experiment-tree
    }
  }

}
