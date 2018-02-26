package filters

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import com.auth0.jwt._
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces._
import domains.apikey.ApikeyStore
import domains.user.User
import domains.{AuthInfo, AuthorizedPattern, Key}
import env._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.typedmap._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

class IzanamiDefaultFilter(env: Env, config: DefaultFilter, apikeyConfig: ApikeyConfig, apikeyStore: ApikeyStore)(
    implicit ec: ExecutionContext,
    val mat: Materializer
) extends Filter {

  private val logger = Logger("filter")

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = {
    val startTime: Long = System.currentTimeMillis
    val maybeClaim      = Try(requestHeader.cookies.get(config.cookieClaim).get.value).toOption

    val maybeClientId = requestHeader.headers.get(config.apiKeys.headerClientId)
    val maybeClientSecret =
      requestHeader.headers.get(config.apiKeys.headerClientSecret)

    val t = Try(env.env match {
      // dev or test mode :
      case devOrTest if devOrTest == "test" || devOrTest == "dev" =>
        nextFilter(
          requestHeader.addAttr(
            FilterAttrs.Attrs.AuthInfo,
            Some(
              User(id = "id",
                   name = "Ragnard",
                   email = "ragnard@viking.com",
                   admin = false,
                   authorizedPattern = AuthorizedPattern(""))
            )
          )
        ).map { result =>
          val requestTime = System.currentTimeMillis - startTime
          logger.debug(
            s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
          )
          result
        }
      // Prod && Api key :
      case prod if prod == "prod" && maybeClientId.isDefined && maybeClientSecret.isDefined =>
        apikeyStore
          .getById(Key(maybeClientId.get))
          .one
          .map { mayBeKey =>
            Logger.debug(s"$mayBeKey: ${apikeyConfig.keys}")
            mayBeKey
              .orElse(apikeyConfig.keys)
              .filter(_.clientSecret == maybeClientSecret.get)
          }
          .flatMap {
            case Some(apikey) if apikey.clientSecret == maybeClientSecret.get =>
              nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, Some(apikey)))
                .map { result =>
                  val requestTime = System.currentTimeMillis - startTime
                  logger.debug(
                    s"Request api key => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                      .map(h => s"""   "${h._1}": "${h._2}"\n""")
                      .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
                  )
                  result
                }
            case _ =>
              FastFuture.successful(
                Results.Unauthorized(
                  Json.obj("error" -> "Bad request !!!")
                )
              )
          }
      // Prod && Exclusions :
      case prod
          if prod == "prod" && maybeClaim.isDefined && config.allowedPaths
            .exists(path => requestHeader.path.matches(path)) => {

        val tryDecode = Try {
          val algorithm = Algorithm.HMAC512(config.sharedKey)
          val verifier =
            JWT.require(algorithm).withIssuer(config.issuer).build()
          val decoded: DecodedJWT = verifier.verify(maybeClaim.get)

          nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, User.fromJwtToken(decoded))).map { result =>
            val requestTime = System.currentTimeMillis - startTime
            logger.debug(
              s"Request claim with exclusion => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                .map(h => s"""   "${h._1}": "${h._2}"\n""")
                .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
            )
            result
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .Unauthorized(
                    Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
                  )
              )
            )
        }
        tryDecode.get
      }
      case prod if prod == "prod" && config.allowedPaths.exists(path => requestHeader.path.matches(path)) => {
        nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, None))
          .map {
            result =>
              val requestTime = System.currentTimeMillis - startTime
              logger.debug(
                s"Request no claim with exclusion => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                  .map(h => s"""   "${h._1}": "${h._2}"\n""")
                  .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
              )
              result
          }
      }
      // Prod && Claim empty :
      case prod if prod == "prod" && maybeClaim.isEmpty =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad claim !!!")
            )
        )
      // Prod && Claim => decoding jwt :
      case prod if prod == "prod" =>
        val tryDecode = Try {
          val algorithm = Algorithm.HMAC512(config.sharedKey)
          val verifier =
            JWT.require(algorithm).withIssuer(config.issuer).build()
          val decoded: DecodedJWT = verifier.verify(maybeClaim.get)

          nextFilter(requestHeader.addAttr(FilterAttrs.Attrs.AuthInfo, User.fromJwtToken(decoded))).map { result =>
            val requestTime = System.currentTimeMillis - startTime
            logger.debug(
              s"Request claim => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                .map(h => s"""   "${h._1}": "${h._2}"\n""")
                .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
            )
            result
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .Unauthorized(
                    Json.obj("error" -> "Claim error !!!", "m" -> e.getMessage)
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
        )
    }) recoverWith {
      case e =>
        Success(
          Future.successful(
            Results
              .InternalServerError(
                Json.obj("error" -> e.getMessage)
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
    }
    result
  }
}
