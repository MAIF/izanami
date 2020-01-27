package filters

import akka.stream.Materializer
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.{JWT, JWTVerifier}
import domains.user.User
import env.OtoroshiFilterConfig
import libs.logs.LoggerModule
import play.api.Mode
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result, Results}
import zio.{Runtime, Task, ZIO}

class ZioOtoroshiFilter(env: Mode, config: OtoroshiFilterConfig)(implicit val r: Runtime[LoggerModule],
                                                                 override val mat: Materializer)
    extends ZioFilter[LoggerModule] {

  private val algorithm: Algorithm = Algorithm.HMAC512(config.sharedKey)
  private val verifier: JWTVerifier = JWT
    .require(algorithm)
    .withIssuer(config.issuer)
    .acceptLeeway(5000)
    .build()

  override def filter(
      nextFilter: RequestHeader => Task[Result]
  )(requestHeader: RequestHeader): ZIO[LoggerModule, Throwable, Result] = {
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)
    val maybeClaim: Option[String] = requestHeader.headers.get(config.headerClaim)
    val t = (env, maybeClaim) match {
      case (Mode.Dev, _) | (Mode.Test, _) => filterDevOrTest(nextFilter)(requestHeader)
      case (_, Some(claim))               => filterProdWithClaim(claim, nextFilter)(requestHeader)
      case _ =>
        ZIO(
          Results
            .Unauthorized(Json.obj("error" -> "Bad request !!!"))
            .withHeaders(config.headerGatewayStateResp -> maybeState.getOrElse("--"))
        )
    }

    for {
      ctx <- ZIO.environment[LoggerModule]
      resp <- t.onError { cause =>
                 cause.failureOption.fold(ZIO.unit) { e =>
                   ctx.logger
                     .logger("filter")
                     .error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e) *>
                   ctx.logger
                     .logger("filter")
                     .error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e.getCause)
                 }
               }
               .catchAll { e =>
                 ZIO(
                   Results
                     .InternalServerError(
                       Json.obj("error" -> e.getMessage)
                     )
                     .withHeaders(
                       config.headerGatewayStateResp -> maybeState.getOrElse("--")
                     )
                 )
               }
      _ <- ctx.logger.debug(s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
    } yield resp
  }

  private def filterDevOrTest(
      nextFilter: RequestHeader => Task[Result]
  )(requestHeader: RequestHeader): ZIO[LoggerModule, Throwable, Result] = {
    val startTime: Long            = System.currentTimeMillis
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)

    for {
      ctx               <- ZIO.environment[LoggerModule]
      result            <- nextFilter(requestHeader)
      requestTime: Long = System.currentTimeMillis - startTime
      _ <- ctx.logger.debug(
            s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
          )
    } yield
      result.withHeaders(
        config.headerGatewayStateResp -> maybeState.getOrElse("--")
      )
  }

  private def filterProdWithClaim(claim: String, nextFilter: RequestHeader => Task[Result])(
      requestHeader: RequestHeader
  ): ZIO[LoggerModule, Throwable, Result] = {
    val startTime: Long            = System.currentTimeMillis
    val maybeReqId: Option[String] = requestHeader.headers.get(config.headerRequestId)
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)

    val res: ZIO[LoggerModule, Result, Result] = for {
      ctx <- ZIO.environment[LoggerModule]
      decoded <- ZIO(verifier.verify(claim)).mapError { _ =>
                  Results
                    .Unauthorized(Json.obj("error" -> "Claim error !!!"))
                    .withHeaders(config.headerGatewayStateResp -> maybeState.getOrElse("--"))
                }
      maybeUser   = User.fromOtoroshiJwtToken(decoded)
      result      <- nextFilter(requestHeader).refineToOrDie[Result]
      requestTime = System.currentTimeMillis - startTime
      _ <- ZIO.when(maybeUser.isEmpty) {
            ctx.logger.debug(
              s"Request from Gateway with id : ${maybeReqId.getOrElse("")} => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                .map(h => s"""   "${h._1}": "${h._2}"\n""")
                .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
            )
          }
    } yield
      result.withHeaders(
        config.headerGatewayStateResp -> maybeState.getOrElse("--")
      )

    res.either.map(_.merge)
  }

}
