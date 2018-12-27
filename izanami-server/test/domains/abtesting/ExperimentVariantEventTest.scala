package domains.abtesting
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import domains.Key
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import test.IzanamiSpec

class ExperimentVariantEventTest extends IzanamiSpec with ScalaFutures with IntegrationPatience {

  "ExperimentVariantEvent" must {
    "aggregate event" in {

      implicit val system: ActorSystem        = ActorSystem()
      implicit val materializer: Materializer = ActorMaterializer()

      val variantId = "vId"
      val variant   = Variant(variantId, "None", None, Traffic(0), None)
      val flow: Flow[ExperimentVariantEvent, VariantResult, NotUsed] =
        ExperimentVariantEvent.eventAggregation("experiment.id", 1, ChronoUnit.HOURS)

      val firstDate = LocalDateTime.now().minus(5, ChronoUnit.HOURS)

      val experimentKey = Key(s"experiment:id")
      def experimentVariantEventKey(counter: Int): ExperimentVariantEventKey =
        ExperimentVariantEventKey(experimentKey, variantId, s"client:id:$counter", "namespace", s"$counter")
      def clientId(i: Int): String    = s"client:id:$i"
      def date(i: Int): LocalDateTime = firstDate.plus(15 * i, ChronoUnit.MINUTES)

      val source = (1 to 20)
        .flatMap { counter =>
          val d   = date(counter)
          val key = experimentVariantEventKey(counter)

          counter match {
            case i if i % 2 > 0 =>
              List(ExperimentVariantDisplayed(key, experimentKey, clientId(i), variant, d, 0, variantId))
            case i =>
              List(
                ExperimentVariantDisplayed(key, experimentKey, clientId(i), variant, d, 0, variantId),
                ExperimentVariantWon(key, experimentKey, clientId(i), variant, d, 0, variantId)
              )
          }
        }

      val expectedEvents = Seq(
        ExperimentResultEvent(experimentKey, variant, date(1), 0.0, "vId"),
        ExperimentResultEvent(experimentKey, variant, date(5), 40.0, "vId"),
        ExperimentResultEvent(experimentKey, variant, date(9), 44.44444444444444, "vId"),
        ExperimentResultEvent(experimentKey, variant, date(13), 46.15384615384615, "vId"),
        ExperimentResultEvent(experimentKey, variant, date(17), 47.05882352941177, "vId")
      )

      val evts      = Source(source).via(flow).runWith(Sink.seq).futureValue
      val allEvents = evts.flatMap(_.events)

      allEvents must be(expectedEvents)
    }
  }

}
