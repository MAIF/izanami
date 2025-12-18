package fr.maif.izanami.web

import fr.maif.izanami.RoleRightMode.{Initial, Supervised}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{
  FailedToReadTokenClaims,
  IzanamiError,
  MissingOIDCConfigurationError,
  RightComplianceError
}
import fr.maif.izanami.models.*
import fr.maif.izanami.models.OAuth2Configuration.OAuth2BASICMethod
import fr.maif.izanami.models.User.userRightsWrites
import fr.maif.izanami.services.RightService.RightsByRole
import fr.maif.izanami.services.{
  CompleteRights,
  MaxRightComplianceResult,
  MaxRights,
  RightService
}
import fr.maif.izanami.utils.{Done, FutureEither}
import fr.maif.izanami.utils.syntax.implicits.{BetterFutureEither, BetterSyntax}
import fr.maif.izanami.web.AuthAction.delayResponse
import pdi.jwt.{JwtJson, JwtOptions}
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.*
import play.api.mvc.Cookie.SameSite

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class LoginController(
    val env: Env,
    val controllerComponents: ControllerComponents,
    val rightService: RightService,
    sessionAuthAction: AuthenticatedSessionAction
) extends BaseController {
  implicit val ec: ExecutionContext = env.executionContext
  private val logger = Logger("izanami.login")

  /**
   * Initiates browser-based OIDC authentication flow.
   *
   * == Why State Parameter is Required (CSRF Protection) ==
   *
   * The `state` parameter is essential for preventing Cross-Site Request Forgery (CSRF) attacks
   * on the OAuth callback. Without it, an attacker could:
   * 1. Initiate an OAuth flow with their own account
   * 2. Trick a victim into completing the callback
   * 3. Link the attacker's identity to the victim's session
   *
   * == Why PKCE Code Verifier is Not Sufficient ==
   *
   * While PKCE (code_verifier/code_challenge) provides some incidental CSRF protection
   * because the verifier is stored in the session, it is NOT a replacement for state:
   *
   * 1. '''Timing''': State is validated before processing; PKCE fails during token exchange
   * 2. '''Purpose''': PKCE protects against authorization code interception, not CSRF
   * 3. '''Standards''': OAuth 2.0 Security BCP explicitly states PKCE doesn't replace state
   *
   * @see [[https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics#section-4.7 OAuth 2.0 Security Best Current Practice - CSRF Protection]]
   * @see [[https://datatracker.ietf.org/doc/html/rfc6749#section-10.12 RFC 6749 - Cross-Site Request Forgery]]
   * @see [[https://danielfett.de/2020/05/16/pkce-vs-nonce-equivalent-or-not/ PKCE vs State - Security Analysis]]
   */
  def openIdConnect: Action[AnyContent] = Action.async { implicit request =>
    env.datastores.configuration
      .readFullConfiguration()
      .value
      .map(e => e.toOption.flatMap(_.oidcConfiguration))
      .map {
        case None => MissingOIDCConfigurationError().toHttpResponse
        case Some(
              oAuth2Configuration
            ) => {

          if (!oAuth2Configuration.enabled) {
            BadRequest(Json.obj("message" -> "Something wrong happened"))
          } else {
            val hasOpenIdInScope =
              oAuth2Configuration.scopes
                .split(" ")
                .toSet
                .exists(s => s.equalsIgnoreCase("openid"))
            val actualScope =
              (if (!hasOpenIdInScope) oAuth2Configuration.scopes + " openid"
               else oAuth2Configuration.scopes).replace(" ", "%20")

            // Generate state for CSRF protection - required even when PKCE is enabled.
            // See method documentation for why code_verifier alone is insufficient.
            val state = generateBrowserState()

            if (oAuth2Configuration.pkce.exists(_.enabled)) {
              val (codeVerifier, codeChallenge, codeChallengeMethod) =
                generatePKCECodes(oAuth2Configuration.pkce.get.algorithm.some)

              Redirect(
                s"${oAuth2Configuration.authorizeUrl}?scope=$actualScope&client_id=${oAuth2Configuration.clientId}&response_type=code&redirect_uri=${oAuth2Configuration.callbackUrl}&state=$state&code_challenge=$codeChallenge&code_challenge_method=$codeChallengeMethod"
              ).addingToSession(
                "code_verifier" -> codeVerifier,
                "oauth_state" -> state
              )
            } else {
              Redirect(
                s"${oAuth2Configuration.authorizeUrl}?scope=$actualScope&client_id=${oAuth2Configuration.clientId}&response_type=code&redirect_uri=${oAuth2Configuration.callbackUrl}&state=$state"
              ).addingToSession(
                "oauth_state" -> state
              )
            }
          }
        }
      }
  }

  private def generatePKCECodes(
      codeChallengeMethod: Option[String] = Some("S256")
  ) = {
    val code = new Array[Byte](120)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(code)

    val codeVerifier = new String(
      Base64.getUrlEncoder.withoutPadding().encodeToString(code)
    ).slice(0, 120)

    val bytes = codeVerifier.getBytes("US-ASCII")
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes, 0, bytes.length)
    val digest = md.digest

    codeChallengeMethod match {
      case Some("S256") =>
        (
          codeVerifier,
          org.apache.commons.codec.binary.Base64
            .encodeBase64URLSafeString(digest),
          "S256"
        )
      case _ => (codeVerifier, codeVerifier, "plain")
    }
  }

  private def generateBrowserState(): String = {
    val bytes = new Array[Byte](32)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /**
   * Handles the OIDC authorization code callback.
   *
   * Supports two flows distinguished by the `state` parameter:
   * - '''Browser flow''': State from callback must match the one stored in session.
   * - '''CLI flow''': State is prefixed with "cli:" and validated via the pending auth store.
   *
   * The state is URL-decoded before processing since OIDC providers may encode special characters.
   *
   * @see [[CliAuthDatastore]] for the prefix design decision
   */
  def openIdCodeReturn: Action[AnyContent] = Action.async { implicit request =>
    val maybeRawState = request.body.asJson.flatMap(json => (json \ "state").asOpt[String])
    val maybeState    = maybeRawState.map(s => java.net.URLDecoder.decode(s, "UTF-8"))
    val isCliFlow     = maybeState.exists(_.startsWith("cli:"))
    val actualState   = maybeState.map(s => if (s.startsWith("cli:")) s.drop(4) else s)

    val sessionState      = request.session.get("oauth_state")
    val browserStateValid = isCliFlow || (maybeState.isDefined && sessionState == maybeState)

    if (!isCliFlow && !browserStateValid) {
      logger.warn(
        s"OIDC callback state mismatch - possible CSRF attempt. Session: ${sessionState
            .getOrElse("none")}, callback: ${maybeState.getOrElse("none")}"
      )
      Future.successful(
        BadRequest(Json.obj("message" -> "Invalid state parameter - possible CSRF attack"))
      )
    } else {
      val cliPendingAuthFuture: Future[Option[fr.maif.izanami.models.PendingCliAuth]] =
        if (isCliFlow && actualState.isDefined)
          env.datastores.cliAuth.consumePendingAuth(actualState.get)
        else Future.successful(None)

      cliPendingAuthFuture.flatMap { maybeCliPendingAuth =>
        if (isCliFlow && maybeCliPendingAuth.isEmpty) {
          logger.error("CLI authentication state not found or expired")
          Future.successful(
            BadRequest(Json.obj("message" -> "CLI authentication state not found or expired"))
          )
        } else {
          processOidcCallback(isCliFlow, actualState, maybeCliPendingAuth)
        }
      }
    }
  }

  private def processOidcCallback(
      isCliFlow: Boolean,
      actualState: Option[String],
      maybeCliPendingAuth: Option[fr.maif.izanami.models.PendingCliAuth]
  )(implicit request: Request[AnyContent]): Future[Result] = {
    {
      for (
        code <- request.body.asJson
          .flatMap(json => (json \ "code").get.asOpt[String])
          .asFuture;
        oauth2ConfigurationOpt <-
          env.datastores.configuration
            .readFullConfiguration()
            .value
            .map(_.toOption.flatMap(_.oidcConfiguration))
      )
        yield {
          if (code.isEmpty) {
            logger.error(
              "Izanami failed to extract code from oauth provider call"
            )
            InternalServerError(
              Json.obj("message" -> "Failed to extract code from token")
            ).asFuture
          } else if (
            oauth2ConfigurationOpt.isEmpty || oauth2ConfigurationOpt.exists(
              _.enabled == false
            )
          ) {
            logger.error(
              "Izanami received oauth provider call however oauth configuration is either disabled or not set"
            )
            InternalServerError(
              Json.obj(
                "message" -> "OAuth2 configuration is either absent or disabled"
              )
            ).asFuture
          } else {
            val oauth2Configuration = oauth2ConfigurationOpt.get

            var builder = env.Ws
              .url(oauth2Configuration.tokenUrl)
              .withHttpHeaders(
                ("content-type", "application/x-www-form-urlencoded")
              )
            var body = Map(
              "grant_type" -> "authorization_code",
              "code" -> code.get,
              "redirect_uri" -> oauth2Configuration.callbackUrl
            )

            if (oauth2Configuration.method == OAuth2BASICMethod) {
              builder = builder
                .withAuth(
                  oauth2Configuration.clientId,
                  oauth2Configuration.clientSecret,
                  WSAuthScheme.BASIC
                )
            } else {
              body = Map(
                "grant_type" -> "authorization_code",
                "code" -> code.get,
                "redirect_uri" -> oauth2Configuration.callbackUrl,
                "client_id" -> oauth2Configuration.clientId,
                "client_secret" -> oauth2Configuration.clientSecret
              )
            }

            if (oauth2Configuration.pkce.exists(_.enabled)) {
              val maybeCodeVerifier =
                if (isCliFlow) maybeCliPendingAuth.flatMap(_.codeVerifier)
                else request.session.get(s"code_verifier")
              if (maybeCodeVerifier.isEmpty) {
                logger.error(
                  "PKCE flow is required but no code_verifier was found"
                )
              }
              body = body + ("code_verifier" -> maybeCodeVerifier.getOrElse(""))
            }

            builder
              .post(body)
              .flatMap(r => {
                val maybeToken = if (r.status >= 400) {
                  logger.error(
                    s"Failed to retrieve id token from distant authentication provider. Return code is ${r.status}."
                  )
                  logger.debug(
                    s"Failed to retrieve id token from distant authentication provider. Return code is ${r.status}, response body is ${r.body}"
                  )
                  None
                } else {
                  val jsonBody = r.json
                  val token = (jsonBody \ "id_token").asOpt[String]

                  if (token.isEmpty) {
                    logger.error(
                      s"Failed to read id token from distant authentication provider response."
                    )
                    logger.debug(
                      s"Failed to read id token from distant authentication provider response. Response body is ${jsonBody}"
                    )
                  }
                  token
                }

                maybeToken.fold(
                  Future(
                    InternalServerError(
                      Json.obj("message" -> "Failed to retrieve token")
                    )
                  )
                )(token => {
                  val maybeClaims = JwtJson
                    .decode(token, JwtOptions(signature = false, leeway = 10))
                  if (maybeClaims.isFailure) {
                    logger.error("Failed to decode id token")
                    logger.debug(
                      s"Failed to decode id token ${token}",
                      maybeClaims.failed.get
                    )
                  }
                  maybeClaims.toOption
                    .flatMap(claims => {
                      val maybeJsonClaims =
                        Json.parse(claims.content).asOpt[JsObject]
                      if (maybeJsonClaims.isEmpty) {
                        logger.error(s"Failed to read json claims")
                        logger.debug(
                          s"Failed to read json claims from ${claims}"
                        )
                      }
                      maybeJsonClaims
                    })
                    .flatMap(json => {
                      val roles =
                        extractRoles(json, oauth2Configuration.roleClaim)
                          .getOrElse(Set())
                      logger.debug(
                        s"Extracted roles [${roles.mkString(",")}] from id_token. "
                      )

                      for (
                        username <- (json \ oauth2Configuration.nameField)
                          .asOpt[String];
                        email <- (json \ oauth2Configuration.emailField)
                          .asOpt[String]
                      )
                        yield {
                          env.datastores.users
                            .findUserWithCompleteRights(username)
                            .flatMap(maybeUser => {
                              def createOrUpdateUser(
                                  rs: Option[RightsByRole],
                                  maxRightsByRoles: Option[
                                    Map[String, MaxRights]
                                  ]
                              ): FutureEither[
                                Option[MaxRightComplianceResult]
                              ] = {
                                val maxRights = maxRightsByRoles.map(mr =>
                                  CompleteRights.maxRightsToApply(roles, mr)
                                )
                                env.postgresql
                                  .executeInTransactionF(conn =>
                                    maybeUser
                                      .fold {
                                        val rights = rightService.generateRightForNewUser(roles)
                                          env.datastores.users
                                            .createUser(
                                              User(
                                                username,
                                                email = email,
                                                userType = OIDC,
                                                admin = rights.admin,
                                                roles = roles
                                              )
                                                .withRights(
                                                  Rights(rights.tenants)
                                                ),
                                              conn = Some(conn)
                                            )
                                            .toFEither
                                            .map(_ => {
                                              val r: Option[
                                                MaxRightComplianceResult
                                              ] = Option.empty
                                              r
                                            })
                                      }(user => rightService.updateUserRightsIfNeeded(user, roles, Some(conn)))
                                  )
                              }
                              val rightByRolesFromEnvIfAny =
                                env.typedConfiguration.openid
                                  .flatMap(_.toIzanamiOAuth2Configuration)
                                  .flatMap(_.userRightsByRoles)
                                  .orElse(oauth2Configuration.userRightsByRoles)

                              createOrUpdateUser(
                                rightByRolesFromEnvIfAny.map(
                                  _.view.mapValues(_.completeRights).toMap
                                ),
                                oauth2Configuration.maxRightsByRoles
                              ).value
                                .flatMap {
                                  case Left(err: RightComplianceError) => {
                                    Future.successful(Left(err))
                                  }
                                  case Left(err)
                                      if (rightByRolesFromEnvIfAny.isDefined) => {
                                    env.datastores.configuration
                                      .updateOIDCRightByRolesIfNeeded(
                                        rightByRolesFromEnvIfAny.get
                                      )
                                      .flatMap(newRights =>
                                        createOrUpdateUser(
                                          Some(
                                            newRights.view
                                              .mapValues(_.completeRights)
                                              .toMap
                                          ),
                                          oauth2Configuration.maxRightsByRoles
                                        )
                                      ).value
                                  }
                                  case Left(err) => Future.successful(Left(err))
                                  case Right(value) =>
                                    Future.successful(Right(value))
                                }
                            })
                            .map(either =>
                              either.map(maybeRightCompliance =>
                                (username, maybeRightCompliance)
                              )
                            )
                        }
                    })
                    .getOrElse(
                      Future(
                        Left(FailedToReadTokenClaims)
                      )
                    )
                    .flatMap {
                      case Right((username, maybeRightCompliance)) => {
                        env.datastores.users
                          .createSession(username)
                          .map(id => Right((id, maybeRightCompliance)))
                      }
                      case Left(err) => Future(Left(err))
                    }
                    .flatMap(maybeId => {
                      maybeId
                        .map((id, maybeRightCompliance) => {
                          (
                            env.jwtService.generateToken(id),
                            maybeRightCompliance
                          )
                        })
                        .fold(
                          err => Future.successful(err.toHttpResponse),
                          (token, maybeRightCompliance) => {
                            if (isCliFlow && actualState.isDefined) {
                              handleCliOidcCompletion(actualState.get, token)
                            } else {
                              handleBrowserOidcCompletion(token, maybeRightCompliance)
                            }
                          }
                        )
                    })
                })
              })
          }
        }
    }.flatten
  }

  private def handleCliOidcCompletion(state: String, token: String): Future[Result] = {
    env.datastores.cliAuth.storeCompletedAuth(state, token).map {
      case Right(_) =>
        Ok(
          Json.obj(
            "cliAuth" -> true,
            "message" -> "Authentication successful. You can close this window and return to your terminal."
          )
        )
      case Left(err) =>
        err.toHttpResponse
    }
  }

  private def handleBrowserOidcCompletion(
      token: String,
      maybeRightCompliance: Option[MaxRightComplianceResult]
  ): Future[Result] = {
    val cookie = Cookie(
      name = "token",
      value = token,
      httpOnly = false,
      sameSite = Some(SameSite.Strict)
    )
    val response =
      if (maybeRightCompliance.exists(!_.isEmpty)) {
        Ok(
          Json.obj(
            "rightUpdates" -> Json.toJson(maybeRightCompliance)(
              Writes.optionWithNull(MaxRightComplianceResult.writes)
            )
          )
        )
      } else {
        NoContent
      }
    Future.successful(response.withCookies(cookie))
  }

  /**
   * Initiates CLI OIDC authentication flow.
   *
   * The CLI generates a cryptographically secure state parameter and opens the browser
   * to this endpoint. The backend stores the state and redirects to the OIDC provider
   * with the state prefixed by `cli:` so [[openIdCodeReturn]] can distinguish CLI from
   * browser flows.
   */
  def cliOpenIdConnect(state: String): Action[AnyContent] = Action.async { implicit request =>
    if (!env.datastores.cliAuth.isValidState(state)) {
      Future.successful(BadRequest(Json.obj("message" -> "Invalid state parameter format")))
    } else {
      env.datastores.configuration
        .readFullConfiguration()
        .value
        .map(e => e.toOption.flatMap(_.oidcConfiguration))
        .flatMap {
          case None => Future.successful(MissingOIDCConfigurationError().toHttpResponse)
          case Some(oauth2Config) if !oauth2Config.enabled =>
            Future.successful(BadRequest(Json.obj("message" -> "OIDC is not enabled")))
          case Some(oauth2Config) =>
            val hasOpenIdInScope =
              oauth2Config.scopes.split(" ").exists(_.equalsIgnoreCase("openid"))
            val actualScope =
              (if (!hasOpenIdInScope) oauth2Config.scopes + " openid"
               else oauth2Config.scopes).replace(" ", "%20")

            val (codeVerifier, pkceParams) =
              if (oauth2Config.pkce.exists(_.enabled)) {
                val (verifier, challenge, method) =
                  generatePKCECodes(oauth2Config.pkce.get.algorithm.some)
                (Some(verifier), s"&code_challenge=$challenge&code_challenge_method=$method")
              } else {
                (None, "")
              }

            env.datastores.cliAuth.createPendingAuth(state, codeVerifier).map {
              case Right(_) =>
                val cliState = s"cli:$state"
                val redirectUrl = s"${oauth2Config.authorizeUrl}?scope=$actualScope" +
                  s"&client_id=${oauth2Config.clientId}" +
                  s"&response_type=code" +
                  s"&redirect_uri=${oauth2Config.callbackUrl}" +
                  s"&state=$cliState" +
                  pkceParams
                Redirect(redirectUrl)
              case Left(err) =>
                err.toHttpResponse
            }
        }
    }
  }

  /**
   * CLI polls this endpoint to retrieve the authentication token.
   *
   * Responses:
   * - 200 OK with token: Authentication complete, token returned
   * - 202 Accepted: Authentication in progress, continue polling
   * - 400 Bad Request: Invalid state format
   * - 429 Too Many Requests: Rate limited
   */
  def cliTokenPoll(state: String): Action[AnyContent] = Action.async { implicit request =>
    if (!env.datastores.cliAuth.isValidState(state)) {
      Future.successful(BadRequest(Json.obj("message" -> "Invalid state parameter format")))
    } else {
      env.datastores.cliAuth.claimToken(state).map {
        case Right(Some(token)) =>
          Ok(Json.obj("token" -> token))
        case Right(None) =>
          Accepted(
            Json.obj(
              "status" -> "pending",
              "message" -> "Authentication in progress, please continue polling"
            )
          )
        case Left(_: fr.maif.izanami.errors.CliAuthRateLimited) =>
          TooManyRequests(
            Json.obj(
              "message" -> "Too many requests, please slow down",
              "retryAfter" -> 5
            )
          ).withHeaders("Retry-After" -> "5")
        case Left(err) =>
          err.toHttpResponse
      }
    }
  }

  private def extractRoles(
      claims: JsObject,
      roleClaim: Option[String]
  ): Option[Set[String]] = {
    if (roleClaim.isEmpty) {
      logger.debug("No role claim defined, skipping role extraction")
      None
    } else {
      val claimName = roleClaim.get

      val maybeRoleClaimContent = (claims \ claimName).toOption

      if (maybeRoleClaimContent.isEmpty) {
        logger.debug(
          s"Missing claim $claimName in token, no role were extracted"
        )
      }

      val roles = maybeRoleClaimContent match {
        case Some(JsString(value)) => Set(value)
        case Some(arr: JsArray)    =>
          arr.value.map((el: JsValue) => el.as[String]).toSet
        case _ => Set(): Set[String]
      }

      Some(roles)
    }
  }

  /*maybeJson.toOption.flatMap(roleClaim => roleClaim.)


    val roles      =
      JwtJson.decode(token, JwtOptions(signature = false, leeway = 1))
      .flatMap(_.toOption)
      .map(claims => claims.toJson)
      .map(json => Json.parse(json))
      .flatMap(json => roleClaim.flatMap(roleClaimeName => (json \ roleClaimeName).toOption))
      .map {
        case JsString(value) => Set(value)
        case arr: JsArray    => arr.value.map((el: JsValue) => el.as[String]).toSet
        case _               => Set(): Set[String]
      }
      .getOrElse(Set(): Set[String])*/

  def fetchOpenIdConnectConfiguration: Action[AnyContent] = Action.async {
    implicit request =>
      request.body.asJson.flatMap(body => (body \ "url").asOpt[String]) match {
        case None => BadRequest(Json.obj("error" -> "missing field")).future
        case Some(url) =>
          env.Ws
            .url(url)
            .withRequestTimeout(10.seconds)
            .get()
            .map { resp =>
              val result: Option[OAuth2Configuration] =
                if (resp.status == 200) {
                  val body = Json.parse(resp.body)
                  val tokenUrl = (body \ "token_endpoint").asOpt[String];
                  val authorizeUrl =
                    (body \ "authorization_endpoint").asOpt[String];

                  val scope = (body \ "scopes_supported")
                    .asOpt[Seq[String]]
                    .map(_.mkString(" "))
                    .getOrElse("openid email profile")

                  OAuth2Configuration(
                    clientId = null,
                    clientSecret = null,
                    tokenUrl = tokenUrl.getOrElse(""),
                    authorizeUrl = authorizeUrl.getOrElse(""),
                    scopes = scope,
                    pkce = None,
                    callbackUrl = s"${env.expositionUrl}/login",
                    method = OAuth2BASICMethod,
                    enabled = true,
                    userRightsByRoles = None,
                    roleClaim = None,
                    roleRightMode = None
                  ).some
                } else {
                  None
                }

              result match {
                case Some(value) => Ok(OAuth2Configuration._fmt.writes(value))
                case None        => NotFound(Json.obj())
              }
            }
      }
  }

  def logout(): Action[AnyContent] = sessionAuthAction.async {
    implicit request =>
      env.datastores.users
        .deleteSession(request.sessionId)
        .map(_ => {
          NoContent.withCookies(
            Cookie(
              name = "token",
              value = "",
              httpOnly = false,
              sameSite = Some(SameSite.Strict),
              maxAge = Some(0)
            )
          )
        })
  }

  def login(rights: Boolean = false): Action[AnyContent] = Action.async {
    implicit request =>
      request.headers
        .get("Authorization")
        .map(header => header.split("Basic "))
        .filter(splitted => splitted.length == 2)
        .map(splitted => splitted(1))
        .map(header => {
          Base64.getDecoder.decode(header.getBytes)
        })
        .map(bytes => new String(bytes))
        .map(header => header.split(":"))
        .filter(arr => arr.length == 2) match {
        case Some(Array(username, password, _*)) =>
          env.datastores.users.isUserValid(username, password).flatMap {
            case None =>
              delayResponse(
                Forbidden(Json.obj("message" -> "Incorrect credentials"))
              )
            case Some(user) =>
              for {
                _ <-
                  if (user.legacy)
                    env.datastores.users.updateLegacyUser(username, password)
                  else Future.successful(())
                sessionId <- env.datastores.users.createSession(user.username)
                token <- env.jwtService.generateToken(sessionId).future
                response <-
                  if (rights)
                    env.datastores.users
                      .findUserWithCompleteRights(user.username)
                      .map {
                        case Some(user) =>
                          Ok(Json.toJson(user)(userRightsWrites))
                        case None =>
                          InternalServerError(
                            Json.obj("message" -> "Failed to read rights")
                          )
                      }
                  else Future.successful(Ok)
              } yield response.withCookies(
                Cookie(
                  name = "token",
                  value = token,
                  httpOnly = false,
                  sameSite = Some(SameSite.Strict),
                  maxAge = Some(env.typedConfiguration.sessions.ttl - 120)
                )
              )
          }
        case _ =>
          delayResponse(
            Unauthorized(Json.obj("message" -> "Missing credentials"))
          )
      }
  }
}
