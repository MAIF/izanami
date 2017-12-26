package izanami.experiments

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import izanami.scaladsl.IzanamiClient
import izanami._
import org.scalatest.BeforeAndAfterAll
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.{Failure, Success}

class FetchExperimentsClientStrategySpec extends IzanamiSpec with BeforeAndAfterAll with ExperimentServer {
  implicit val system       = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchExperimentStrategy" should {

    "List experiments" in {

      runServer { ctx =>
        //#experiment-client
        val experimentClient = IzanamiClient(ClientConfig(ctx.host))
          .experimentClient(Strategies.fetchStrategy())
        //#experiment-client

        val variantA            = Variant("A", "Variant A", "The A variant")
        val variantB            = Variant("B", "Variant B", "The B variant")
        val expectedExperiments = Experiment("test", "test", "An experiment", true, Seq(variantA, variantB))
        val experiments = Seq(
          expectedExperiments
        )
        ctx.setValues(experiments)

        val experimentsList = experimentClient.list("*").futureValue

        experimentsList must have size 1

        val experiment = experimentsList.head

        experiment.id must be(expectedExperiments.id)
        experiment.name must be(expectedExperiments.name)
        experiment.description must be(expectedExperiments.description)
        experiment.enabled must be(expectedExperiments.enabled)
        experiment.variants must be(expectedExperiments.variants)

        //#get-variant
        val mayBeFutureVariant: Future[Option[Variant]] = experimentClient.getVariantFor("test", "client1")
        mayBeFutureVariant.onComplete {
          case Success(mayBeVariant) => println(mayBeVariant)
          case Failure(e)            => e.printStackTrace()
        }
        //#get-variant

        val futureVariant: Future[Option[Variant]] = experiment.getVariantFor("client1")

        futureVariant.futureValue must be(None)
        experiment.markVariantDisplayed("client1").futureValue.variant must be(variantA)
        experiment.getVariantFor("client1").futureValue must be(Some(variantA))
        experiment.markVariantWon("client1").futureValue.variant must be(variantA)

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
    }

    "Tree experiments" in {
      runServer { ctx =>
        val client = IzanamiClient(ClientConfig(ctx.host))
          .experimentClient(Strategies.fetchStrategy())

        val variantA            = Variant("A", "Variant A", "The A variant")
        val variantB            = Variant("B", "Variant B", "The B variant")
        val expectedExperiments = Experiment("izanami:ab:test", "test", "An experiment", true, Seq(variantA, variantB))
        val experiments = Seq(
          expectedExperiments
        )
        ctx.setValues(experiments)

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

      }
    }
  }

}
