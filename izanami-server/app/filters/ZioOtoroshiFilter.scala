package filters

import akka.stream.Materializer
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.{JWT, JWTVerifier}
import domains.user.User
import env.OtoroshiFilterConfig
import libs.http.HttpContext
import libs.logs.ZLogger
import play.api.Mode
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result, Results}
import zio.{Runtime, Task, ZEnv, ZIO, ZLayer}

class ZioOtoroshiFilter(env: Mode, config: OtoroshiFilterConfig)(implicit val r: HttpContext[ZLogger],
                                                                 override val mat: Materializer)
    extends ZioFilter[ZLogger] {

  private val algorithm: Algorithm = Algorithm.HMAC512(config.sharedKey)
  private val verifier: JWTVerifier = JWT
    .require(algorithm)
    .withIssuer(config.issuer)
    .acceptLeeway(5000)
    .build()

  private val getLogger: ZIO[ZLogger, Nothing, ZLogger.Service] = ZLogger("filter")

  override def filter(
      nextFilter: RequestHeader => Task[Result]
  )(requestHeader: RequestHeader): ZIO[ZLogger, Throwable, Result] = {
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
      ctx    <- ZIO.environment[ZLogger]
      logger <- getLogger
      resp <- t.onError { cause =>
                 cause.failureOption.fold(ZIO.unit) { e =>
                   logger
                     .error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e) *>
                   logger
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
      _ <- logger.debug(s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
    } yield resp
  }

  private def filterDevOrTest(
      nextFilter: RequestHeader => Task[Result]
  )(requestHeader: RequestHeader): ZIO[ZLogger, Throwable, Result] = {
    val startTime: Long            = System.currentTimeMillis
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)

    for {
      ctx               <- ZIO.environment[ZLogger]
      logger            <- getLogger
      result            <- nextFilter(requestHeader)
      requestTime: Long = System.currentTimeMillis - startTime
      _ <- logger.debug(
            s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
          )
    } yield
      result.withHeaders(
        config.headerGatewayStateResp -> maybeState.getOrElse("--")
      )
  }

  private def filterProdWithClaim(claim: String, nextFilter: RequestHeader => Task[Result])(
      requestHeader: RequestHeader
  ): ZIO[ZLogger, Throwable, Result] = {
    import scala.jdk.CollectionConverters._
    val startTime: Long            = System.currentTimeMillis
    val maybeReqId: Option[String] = requestHeader.headers.get(config.headerRequestId)
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)
    val res: ZIO[ZLogger, Result, Result] = for {
      logger <- getLogger
      decoded <- ZIO(verifier.verify(claim)).mapError { _ =>
                  Results
                    .Unauthorized(Json.obj("error" -> "Claim error !!!"))
                    .withHeaders(config.headerGatewayStateResp -> maybeState.getOrElse("--"))
                }
      maybeUser = User.fromOtoroshiJwtToken(decoded)
      _ <- ZIO.when(maybeUser.isEmpty) {
            logger.debug(
              s"Decoded user is empty for ${decoded.getClaims.asScala.map { case (k, v) => (k, v.asString()) }}"
            )
          }
      result      <- nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, maybeUser)).orDie
      requestTime = System.currentTimeMillis - startTime
      _ <- logger.debug(
            s"Request from Gateway with id : ${maybeReqId.getOrElse("")} => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
              .map(h => s"""   "${h._1}": "${h._2}"\n""")
              .mkString(",")} took ${requestTime} ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}, auth $maybeUser"
          )
    } yield
      result.withHeaders(
        config.headerGatewayStateResp -> maybeState.getOrElse("--")
      )

    res.either.map(_.merge)
  }

}
