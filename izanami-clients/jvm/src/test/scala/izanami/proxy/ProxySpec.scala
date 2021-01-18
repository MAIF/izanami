package izanami.proxy

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.testkit.TestKit
import izanami.scaladsl._
import izanami._
import org.scalatest.BeforeAndAfterAll
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future
import scala.util.Success

class ProxySpec extends IzanamiSpec with BeforeAndAfterAll {

  implicit val system       = ActorSystem("test")
  implicit val materializer = Materializer.createMaterializer(system)

  import system.dispatcher

  "proxy" should {
    "work" in {

      //#proxy
      val client = IzanamiClient(
        ClientConfig("")
      )

      val featureClient: FeatureClient = client.featureClient(
        strategy = Strategies.dev(),
        fallback = Features(
          DefaultFeature("features:test1", true)
        )
      )

      val configClient: ConfigClient = client.configClient(
        Strategies.dev(),
        fallback = Configs(
          "configs:test" -> Json.obj("value" -> 2)
        )
      )

      val experimentsClient: ExperimentsClient = client.experimentClient(
        strategy = Strategies.dev(),
        fallback = Experiments(
          ExperimentFallback(
            "experiments:id",
            "Experiment",
            "An experiment",
            true,
            Variant("A", "Variant A", Some("Variant A"))
          )
        )
      )

      val proxy: Proxy = client
        .proxy()
        .withConfigClient(configClient)
        .withConfigPattern("configs:*")
        .withFeatureClient(featureClient)
        .withFeaturePattern("features:*")
        .withExperimentsClient(experimentsClient)
        .withExperimentPattern("experiments:*")

      val fResponseJson: Future[(Int, JsValue)] = proxy.statusAndJsonResponse()
      fResponseJson.onComplete {
        case Success((status, responseBody)) =>
          println(s"Izanami respond with status $status and json body $responseBody")
        case _ => println("Oups something wrong happened")
      }

      //Or for a string response and additional infos
      val fResponseString: Future[(Int, String)] = proxy.statusAndStringResponse(
        context = Some(Json.obj("user" -> "ragnard.lodbrock@gmail.com")),
        userId = Some("ragnard.lodbrock@gmail.com")
      )
      fResponseString.onComplete {
        case Success((status, responseBody)) =>
          println(s"Izanami respond with status $status and string body $responseBody")
        case _ => println("Oups something wrong happened")
      }

      // Experiment proxy

      val fDisplayed: Future[(Int, JsValue)] =
        proxy.markVariantDisplayed("experiments:id", "ragnard.lodbrock@gmail.com")
      val fWon: Future[(Int, JsValue)] = proxy.markVariantWon("experiments:id", "ragnard.lodbrock@gmail.com")

      //#proxy

      val (status, json): (Int, JsValue) = fResponseJson.futureValue
      status must be(200)
      json must be(
        Json.parse(
          """{"features":{"features":{"test1":{"active":true}}},"configurations":{"configs":{"test":{"value":2}}},"experiments":{}}"""
        )
      )

      val (code, value): (Int, String) = fResponseString.futureValue
      code must be(200)
      value must be(
        """{"features":{"features":{"test1":{"active":true}}},"configurations":{"configs":{"test":{"value":2}}},"experiments":{"experiments":{"id":{"variant":"A"}}}}"""
      )

    }
  }

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

}
