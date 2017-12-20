package izanami.experiments

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import izanami.scaladsl.IzanamiClient
import izanami._
import org.scalatest.BeforeAndAfterAll
import play.api.libs.json.Json

class FetchExperimentsClientStrategySpec
    extends IzanamiSpec
    with BeforeAndAfterAll
    with ExperimentServer {
  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchExperimentStrategy" should {

    "List experiments" in {

      runServer { ctx =>
        val client = IzanamiClient(ClientConfig(ctx.host))
          .experimentClient(Strategies.fetchStrategy())

        val variantA = Variant("A", "Variant A", "The A variant")
        val variantB = Variant("B", "Variant B", "The B variant")
        val expectedExperiments = Experiment("test",
                                             "test",
                                             "An experiment",
                                             true,
                                             Seq(variantA, variantB))
        val experiments = Seq(
          expectedExperiments
        )
        ctx.setValues(experiments)

        val experimentsList = client.list("*").futureValue

        experimentsList must have size 1

        val experiment = experimentsList.head

        experiment.id must be(expectedExperiments.id)
        experiment.name must be(expectedExperiments.name)
        experiment.description must be(expectedExperiments.description)
        experiment.enabled must be(expectedExperiments.enabled)
        experiment.variants must be(expectedExperiments.variants)

        experiment.getVariantFor("client1").futureValue must be(None)
        experiment.markVariantDisplayed("client1").futureValue.variant must be(
          variantA)
        experiment.getVariantFor("client1").futureValue must be(Some(variantA))
        experiment.markVariantWon("client1").futureValue.variant must be(
          variantA)

      }
    }

    "Tree experiments" in {
      runServer { ctx =>
        val client = IzanamiClient(ClientConfig(ctx.host))
          .experimentClient(Strategies.fetchStrategy())

        val variantA = Variant("A", "Variant A", "The A variant")
        val variantB = Variant("B", "Variant B", "The B variant")
        val expectedExperiments = Experiment("izanami:ab:test",
                                             "test",
                                             "An experiment",
                                             true,
                                             Seq(variantA, variantB))
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
          ))

      }
    }
  }

}
