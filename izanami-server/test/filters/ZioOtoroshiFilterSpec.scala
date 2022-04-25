package filters

import akka.actor.ActorSystem
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import env.OtoroshiFilterConfig
import libs.http.HttpContext
import libs.logs.ZLogger
import play.api.Mode
import play.api.libs.json.Json
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import test.IzanamiSpec
import zio.{Runtime, Task, ZIO}

class ZioOtoroshiFilterSpec extends IzanamiSpec {

  implicit val system = ActorSystem("test")

  implicit val httpContext: HttpContext[ZLogger] = ZLogger.live

  implicit val r = Runtime.default
  private val config = OtoroshiFilterConfig(
    Seq("/api/_health"),
    "key",
    "Otoroshi",
    "Otoroshi-Claim",
    "Otoroshi-Request-Id",
    "Otoroshi-State",
    "Otoroshi-State-Resp"
  )
  def run(z: ZIO[ZLogger, Throwable, Result]): Result =
    r.unsafeRun(z.provideLayer(httpContext))

  "ZioOtoroshiFilter" must {

    "Test or dev mode" in {
      val filter         = new ZioOtoroshiFilter(Mode.Dev, "/", config)
      val result: Result = run(filter.filter(h => Task(Results.Ok("Done")))(FakeRequest()))

      val expected = Results
        .Ok("Done")
        .withHeaders(
          config.headerGatewayStateResp -> "--"
        )

      result must be(expected)
    }

    "filter ok in prod mode" in {
      val algorithm: Algorithm = Algorithm.HMAC512(config.sharedKey)
      val token: String = JWT
        .create()
        .withIssuer(config.issuer)
        .withClaim("name", "johndoe")
        .withClaim("user_id", "johndoe")
        .withClaim("email", "johndoe@gmail.com")
        .withClaim("izanami_authorized_patterns", "*")
        .withClaim("izanami_admin", "false")
        .sign(algorithm)
      val filter = new ZioOtoroshiFilter(Mode.Prod, "/", config)

      val result: Result = run(
        filter.filter(_ => Task(Results.Ok("Done")))(
          FakeRequest().withHeaders(
            config.headerClaim        -> token,
            config.headerGatewayState -> "State"
          )
        )
      )

      val expected = Results
        .Ok("Done")
        .withHeaders(
          config.headerGatewayStateResp -> "State"
        )
      result must be(expected)
    }
  }

  "filter OK if no claim but target url is in allowed paths" in {
    val filter = new ZioOtoroshiFilter(Mode.Prod, "/", config)

    val result: Result = run(
      filter.filter(_ => Task(Results.Ok("Done")))(
        FakeRequest.apply("GET", "/api/_health")
      )
    )

    val expected = Results
      .Ok("Done")
    result must be(expected)
  }

  "filter KO shared key is different in prod mode" in {
    val algorithm: Algorithm = Algorithm.HMAC512("otherkey")
    val token: String = JWT
      .create()
      .withIssuer(config.issuer)
      .withClaim("name", "johndoe")
      .withClaim("user_id", "johndoe")
      .withClaim("email", "johndoe@gmail.com")
      .withClaim("izanami_authorized_patterns", "*")
      .withClaim("izanami_admin", "false")
      .sign(algorithm)
    val filter = new ZioOtoroshiFilter(Mode.Prod, "/", config)

    val result: Result = run(
      filter.filter(_ => Task(Results.Ok("Done")))(
        FakeRequest().withHeaders(
          config.headerClaim        -> token,
          config.headerGatewayState -> "State"
        )
      )
    )

    val expected = Results
      .Unauthorized(Json.obj("error" -> "Claim error !!!"))
      .withHeaders(config.headerGatewayStateResp -> "State")
    result must be(expected)
  }

}
