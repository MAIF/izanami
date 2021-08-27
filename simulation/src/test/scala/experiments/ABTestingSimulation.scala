package experiments

import java.util.concurrent.atomic.AtomicLong

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration.DurationLong

class ABTestingSimulation extends Simulation {
  val sentHeaders = Map(HttpHeaderNames.ContentType -> "application/json")

  val userEmailFeeder = Iterator.from(0).map(i => Map("email" -> s"user-$i@gmail.com"))

  val counter = new AtomicLong(0)
  def nextMail() = {
    val count = counter.incrementAndGet()
    s"user-$count@gmail.com"
  }

  private val headers = Map(
    "Izanami-Client-Id"     -> "xxxx",
    "Izanami-Client-Secret" -> "xxxx"
  )

  private val experimentId = "mytvshows:gotoepisodes:button"

  private val host = "http://localhost:9000"

  val httpConf = http
    .baseUrl(host)
    .acceptHeader("application/json")
    .headers(headers)
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")

  val scn = scenario("A/B test")
    .repeat(1000, "n") {
      randomSwitch(
        100d ->
        exec(
          http("Displayed A request ")
            .post(s"/api/experiments/$experimentId/displayed?id=A&clientId=user-$${n}@maif.fr")
            .body(StringBody("{}"))
            .headers(sentHeaders)
        ).randomSwitch(
          30d -> exec(
            http("Won A request")
              .post(s"/api/experiments/$experimentId/won?id=A&clientId=user-$${n}@maif.fr")
              .body(StringBody("{}"))
              .headers(sentHeaders)
          )
        )
      )
    }

  setUp(scn.inject(atOnceUsers(50)).protocols(httpConf))

}
