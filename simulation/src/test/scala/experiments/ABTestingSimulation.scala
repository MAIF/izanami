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

  private val experimentId = "project:experiments:name"

  private val host = "http://localhost:9000"

  val httpConf = http
    .baseUrl(host)
    .acceptHeader("application/json")
    .headers(headers)
    .acceptEncodingHeader("gzip, deflate")
    .acceptLanguageHeader("en-US,en;q=0.5")

  val scn = scenario("A/B test")
    .repeat(100, "n") {
      randomSwitch(
        50d ->
        exec(
          http("Displayed A request ")
            .post(s"/api/experiments/$experimentId/displayed?id=A&clientId=user-$${n}@maif.fr")
            .body(StringBody("{}"))
            .headers(sentHeaders)
        ).pause(1.second)
          .randomSwitch(
            30d -> exec(
              http("Won A request")
                .post(s"/api/experiments/$experimentId/won?id=A&clientId=user-$${n}@maif.fr")
                .body(StringBody("{}"))
                .headers(sentHeaders)
            ).pause(1.second)
          ),
        30d ->
        exec(
          http("Displayed B request")
            .post(s"/api/experiments/$experimentId/displayed?id=B&clientId=user-$${n}@maif.fr")
            .body(StringBody("{}"))
            .headers(sentHeaders)
        ).pause(1.second)
          .randomSwitch(
            70d -> exec(
              http("Won B request")
                .post(s"/api/experiments/$experimentId/won?id=B&clientId=user-$${n}@gmail.com")
                .body(StringBody("{}"))
                .headers(sentHeaders)
            ).pause(1.second)
          ),
        20d ->
        exec(
          http("Displayed C request")
            .post(s"/api/experiments/$experimentId/displayed?id=C&clientId=user-$${n}@maif.fr")
            .body(StringBody("{}"))
            .headers(sentHeaders)
        ).pause(1.second)
          .randomSwitch(
            70d -> exec(
              http("Won C request")
                .post(s"/api/experiments/$experimentId/won?id=C&clientId=user-$${n}@gmail.com")
                .body(StringBody("{}"))
                .headers(sentHeaders)
            ).pause(1.second)
          )
      )
    }

  setUp(scn.inject(atOnceUsers(2)).protocols(httpConf))

}
