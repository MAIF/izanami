package filters

import akka.stream.Materializer
import com.auth0.jwt.{JWT, JWTVerifier}
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import domains.user.User
import env.{Env, OtoroshiFilterConfig}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Filter, RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class OtoroshiFilter[F[_]](env: Env, config: OtoroshiFilterConfig)(implicit ec: ExecutionContext, val mat: Materializer)
    extends Filter {

  private val logger = Logger("filter")

  val algorithm: Algorithm = Algorithm.HMAC512(config.sharedKey)
  val verifier: JWTVerifier = JWT
    .require(algorithm)
    .withIssuer(config.issuer)
    .acceptLeeway(5000)
    .build()

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val startTime: Long            = System.currentTimeMillis
    val maybeReqId: Option[String] = requestHeader.headers.get(config.headerRequestId)
    val maybeState: Option[String] = requestHeader.headers.get(config.headerGatewayState)
    val maybeClaim: Option[String] = requestHeader.headers.get(config.headerClaim)

    val t = Try((env.env, maybeClaim) match {
      case (("dev", _) | ("test", _)) =>
        nextFilter(requestHeader).map { result =>
          val requestTime: Long = System.currentTimeMillis - startTime
          logger.debug(
            s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
          )
          result.withHeaders(
            config.headerGatewayStateResp -> maybeState.getOrElse("--")
          )
        }
      case ("prod", None) =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad request !!!")
            )
            .withHeaders(
              config.headerGatewayStateResp -> maybeState.getOrElse("--")
            )
        )
      case ("prod", Some(claim)) =>
        import scala.collection.JavaConverters._
        val tryDecode: Try[Future[Result]] = Try {
          val decoded: DecodedJWT     = verifier.verify(claim)
          val maybeUser: Option[User] = User.fromOtoroshiJwtToken(decoded)
          if (maybeUser.isEmpty) Logger.debug(s"Empty auth for token ${decoded.getClaims.asScala}")
          nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, maybeUser)).map {
            result =>
              val requestTime = System.currentTimeMillis - startTime
              maybeReqId.foreach { id =>
                logger.debug(
                  s"Request from Gateway with id : $id => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                    .map(h => s"""   "${h._1}": "${h._2}"\n""")
                    .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
                )
              }
              result.withHeaders(
                config.headerGatewayStateResp -> maybeState.getOrElse("--")
              )
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .Unauthorized(
                    Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
                  )
                  .withHeaders(
                    config.headerGatewayStateResp -> maybeState.getOrElse("--")
                  )
              )
            )
        }
        tryDecode.get
      case _ =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad env !!!")
            )
            .withHeaders(
              config.headerGatewayStateResp -> maybeState.getOrElse("--")
            )
        )
    }) recoverWith {
      case e =>
        Success(
          Future.successful(
            Results
              .InternalServerError(
                Json.obj("error" -> e.getMessage)
              )
              .withHeaders(
                config.headerGatewayStateResp -> maybeState.getOrElse("--")
              )
          )
        )
    }
    val result: Future[Result] = t.get
    result.onComplete {
      case Success(resp) =>
        logger.debug(s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
      case Failure(e) =>
        logger.error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e)
        logger.error(s"Error for request ${requestHeader.method} ${requestHeader.uri}", e.getCause)
    }
    result
  }
}
