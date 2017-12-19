package izanami.features

import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import izanami.FeatureEvent.FeatureUpdated
import izanami.Strategy.{CacheWithPollingStrategy, CacheWithSseStrategy, FetchWithCacheStrategy}
import izanami._
import izanami.scaladsl.{Features, IzanamiClient}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json

import scala.collection.immutable
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

class SmartCacheFeatureClientSpec extends IzanamiSpec with BeforeAndAfterAll with MockitoSugar with FeatureServer {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FetchWithCacheFeatureStrategy" should {
    "Features by pattern with polling" in {
      runServer { ctx =>
        import akka.pattern
        //Init
        val initialFeatures = Seq(
          DefaultFeature("test1", true)
        )
        ctx.setValues(initialFeatures)

        val fallback = Seq(
          DefaultFeature("test2", true)
        )

        val initialValuesWithFb = fallback ++ initialFeatures

        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = CacheWithPollingStrategy(
            patterns = Seq("*"),
            pollingInterval = 3.second
          ),
          fallback = Features(fallback:_*)
        )


        //Waiting for the client to start polling
        val features: Features = pattern.after(2.second, system.scheduler){
          strategy.features("*")
        }.futureValue

        features.featuresSeq must be (initialValuesWithFb)

        //Only one call for the first fetch
        ctx.calls.size must be(1)
        features.isActive("test1") must be(true)
        features.isActive("test2") must be(true)
        features.isActive("other") must be(false)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(true)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy.checkFeature("other").futureValue must be(false)
        ctx.calls.size must be(1)

        //We update the feature on thebackend
        val updatedFeatures = Seq(
          DefaultFeature("test1", false)
        )
        ctx.setValues(updatedFeatures)

        //We wait for the polling
        val featuresUpdated: Features = pattern.after(2.second, system.scheduler){
          strategy.features("*")
        }.futureValue

        //Now we have two call : the first fetch and the polling
        ctx.calls.size must be(2)

        featuresUpdated.featuresSeq must be (fallback ++ updatedFeatures)
        featuresUpdated.isActive("test1") must be(false)
        featuresUpdated.isActive("test2") must be(true)
        featuresUpdated.isActive("other") must be(false)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(false)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy.checkFeature("other").futureValue must be(false)
        ctx.calls.size must be(2)

        // We ask for a data with context, a call is necessary as we don't cache for contexted features
        strategy.checkFeature("test2", Json.obj("withContext" -> true)).futureValue must be(true)
        ctx.calls.size must be(3)

      }
    }


    "Features events" in {
      runServer { ctx =>
        import akka.pattern
        //Init
        val initialFeatures = Seq(
          DefaultFeature("test1", true)
        )
        ctx.setValues(initialFeatures)

        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = CacheWithSseStrategy(
            patterns = Seq("*")
          ),
          fallback = Features(
            DefaultFeature("test1", false)
          )
        )

        val promise = Promise[Feature]
        strategy.onFeatureChanged("test1") { f =>
          promise.success(f)
        }

        val events = strategy.featuresSource("*").take(1).runWith(Sink.seq)

        events.futureValue must be(Seq(FeatureUpdated("test1", DefaultFeature("test1", true), DefaultFeature("test1", false))))
        promise.future.futureValue must be(DefaultFeature("test1", true))
      }
    }

    "Features by pattern with sse" in {
      runServer { ctx =>
        import akka.pattern
        //Init
        val initialFeatures = Seq(
          DefaultFeature("test1", true)
        )
        ctx.setValues(initialFeatures)

        val fallback = Seq(DefaultFeature("test2", true))

        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = CacheWithSseStrategy(
            patterns = Seq("*")
          ),
          fallback = Features(fallback:_*)
        )

        //Waiting for the client to start polling
        val features: Features = pattern.after(2.second, system.scheduler){
          strategy.features("*")
        }.futureValue

        features.featuresSeq must be (fallback ++ initialFeatures)

        //Only one call for the first fetch
        ctx.calls.size must be(1)
        features.isActive("test1") must be(true)
        features.isActive("test2") must be(true)
        features.isActive("other") must be(false)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(true)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy.checkFeature("other").futureValue must be(false)
        ctx.calls.size must be(1)

        // We update feature via sse
        ctx.push(FeatureUpdated("test1", DefaultFeature("test1", false), DefaultFeature("test1", true)))

        //We wait that the events arrive
        val featuresUpdated: Features = pattern.after(500.milliseconds, system.scheduler){
          strategy.features("*")
        }.futureValue

        //With SSE we should only have the first fetch
        ctx.calls.size must be(1)

        featuresUpdated.isActive("test1") must be(false)
        featuresUpdated.isActive("test2") must be(true)
        featuresUpdated.isActive("other") must be(false)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(false)
        strategy.checkFeature("test2").futureValue must be(true)
        strategy.checkFeature("other").futureValue must be(false)
        ctx.calls.size must be(1)

        // We ask for a data with context, a call is necessary as we don't cache for contexted features
        strategy.checkFeature("test2", Json.obj("withContext" -> true)).futureValue must be(true)
        ctx.calls.size must be(2)
        strategy.features("*", Json.obj("withContext" -> true)).futureValue
        ctx.calls.size must be(3)

      }
    }

    "Features by pattern with date with sse" in {
      runServer { ctx =>
        import akka.pattern
        // This feature should be active
        val releaseDateFeature = ReleaseDateFeature("test1", true, LocalDateTime.now().minusSeconds(50))
        //Init
        val initialFeatures = Seq(releaseDateFeature)
        ctx.setValues(initialFeatures)

        val strategy = IzanamiClient(
          ClientConfig(ctx.host)
        ).featureClient(
          strategy = CacheWithSseStrategy(
            patterns = Seq("*")
          )
        )

        //Waiting for the client to start polling
        val features: Features = pattern.after(2.second, system.scheduler){
          strategy.features("*")
        }.futureValue

        //Only one call for the first fetch
        ctx.calls.size must be(1)
        features.isActive("test1") must be(true)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(true)
        ctx.calls.size must be(1)

        // We update feature via sse => the date is in the past so the feature become inactive.
        ctx.push(FeatureUpdated("test1", ReleaseDateFeature("test1", true, LocalDateTime.now().plusSeconds(2)), releaseDateFeature))

        //We wait that the events arrive
        val featuresUpdated: Features = pattern.after(500.milliseconds, system.scheduler){
          strategy.features("*")
        }.futureValue

        //With SSE we should only have the first fetch
        ctx.calls.size must be(1)

        // The state of the feature should be calculated on the client side.
        featuresUpdated.isActive("test1") must be(false)

        // As the data are in cache for the pattern, this should not generate an http call
        strategy.checkFeature("test1").futureValue must be(false)
        ctx.calls.size must be(1)

        Thread.sleep(1600)
        strategy.checkFeature("test1").futureValue must be(true)
        ctx.calls.size must be(1)
        strategy.features("*", Json.obj("withContext" -> true)).futureValue
        ctx.calls.size must be(2)
      }
    }
  }

}
