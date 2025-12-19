package fr.maif.izanami.web

import fr.maif.izanami.RoleRightMode.{Initial, Supervised}
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors.{IzanamiError, MissingOIDCConfigurationError}
import fr.maif.izanami.models.*
import fr.maif.izanami.models.OAuth2Configuration.OAuth2BASICMethod
import fr.maif.izanami.models.User.userRightsWrites
import fr.maif.izanami.services.RightService.RightsByRole
import fr.maif.izanami.services.{CompleteRights, RightService}
import fr.maif.izanami.utils.syntax.implicits.BetterSyntax
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
              OAuth2Configuration(
                enabled,
                method,
                clientId,
                _clientSecret,
                _tokenUrl,
                authorizeUrl,
                scopes,
                pkce,
                _nameField,
                _emailField,
                callbackUrl,
                defaultOIDCUserRights,
                userRightsByRoles,
                roleRightMode
              )
            ) => {

          if (!enabled) {
            BadRequest(Json.obj("message" -> "Something wrong happened"))
          } else {
            val hasOpenIdInScope =
              scopes.split(" ").toSet.exists(s => s.equalsIgnoreCase("openid"))
            val actualScope = (if (!hasOpenIdInScope) scopes + " openid"
                               else scopes).replace(" ", "%20")

            // Generate state for CSRF protection - required even when PKCE is enabled
            // See method documentation for why code_verifier alone is insufficient
            val state = generateBrowserState()

            if (pkce.exists(_.enabled)) {
              val (codeVerifier, codeChallenge, codeChallengeMethod) =
                generatePKCECodes(pkce.get.algorithm.some)

              Redirect(
                s"$authorizeUrl?scope=$actualScope&client_id=$clientId&response_type=code&redirect_uri=$callbackUrl&state=$state&code_challenge=$codeChallenge&code_challenge_method=$codeChallengeMethod"
              ).addingToSession(
                "code_verifier" -> codeVerifier,
                "oauth_state" -> state
              )
            } else {
              Redirect(
                s"$authorizeUrl?scope=$actualScope&client_id=$clientId&response_type=code&redirect_uri=$callbackUrl&state=$state"
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

  /**
   * Generates a cryptographically secure state parameter for browser OIDC flow.
   *
   * The state parameter provides CSRF protection by binding the authorization request
   * to the user's session. It must be:
   * - Cryptographically random (unpredictable)
   * - Stored in the session before redirect
   * - Validated on callback before processing
   *
   * @return A base64url-encoded random string (32 bytes / 256 bits of entropy)
   */
  private def generateBrowserState(): String = {
    val bytes = new Array[Byte](32)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /**
   * Handles the OIDC authorization code callback.
   *
   * This endpoint is called by the frontend after the OIDC provider redirects back with an authorization code.
   * It exchanges the code for tokens and creates a session.
   *
   * == State Validation (CSRF Protection) ==
   *
   * The state parameter MUST be validated before processing the callback:
   * - '''Browser flow''': State from callback must match the one stored in session
   * - '''CLI flow''': State is validated via the pending auth store (prefixed with "cli:")
   *
   * This validation is critical even when PKCE is enabled, because:
   * - PKCE protects against code interception, not CSRF
   * - State validation happens BEFORE token exchange (fail-fast)
   * - OAuth 2.0 Security BCP requires both mechanisms
   *
   * @see [[https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics#section-4.7 OAuth 2.0 Security BCP]]
   *
   * == CLI Flow Detection via `cli:` Prefix ==
   *
   * For CLI authentication, the state parameter is prefixed with "cli:" to distinguish it from
   * browser-based authentication. This prefix-based approach was chosen because:
   * - The OAuth 2.0 state parameter is opaque to OIDC providers (RFC 6749)
   * - Providers pass it through unchanged, making it reliable for flow detection
   * - No need for separate callback URLs or additional query parameters
   *
   * When the `cli:` prefix is detected:
   * - The PKCE code verifier is retrieved from the pending auth store (not browser session)
   * - After successful token exchange, the JWT is stored for CLI polling pickup
   * - The response indicates CLI auth success so the frontend shows a "close this window" message
   *
   * The state is URL-decoded before processing since OIDC providers may encode special characters.
   *
   * @see [[CliAuthDatastore]] for detailed documentation on the prefix design decision
   * @see [[cliOpenIdConnect]] for the CLI login initiation endpoint
   */
  def openIdCodeReturn: Action[AnyContent] = Action.async { implicit request =>
    // TODO handle refresh_token
    // Extract state parameter to detect CLI flow - URL decode since OIDC providers may encode it
    val maybeRawState = request.body.asJson.flatMap(json => (json \ "state").asOpt[String])
    val maybeState    = maybeRawState.map(s => java.net.URLDecoder.decode(s, "UTF-8"))
    val isCliFlow     = maybeState.exists(_.startsWith("cli:"))
    val actualState   = maybeState.map(s => if (s.startsWith("cli:")) s.drop(4) else s)

    logger.debug(s"OIDC callback - isCliFlow: $isCliFlow, actualState: ${actualState.getOrElse("none")}")

    // Validate state for browser flow (CSRF protection)
    val sessionState       = request.session.get("oauth_state")
    val browserStateValid  = isCliFlow || (maybeState.isDefined && sessionState == maybeState)

    if (!isCliFlow && !browserStateValid) {
      logger.warn(s"OIDC callback state mismatch - possible CSRF attempt. Session: ${sessionState.getOrElse("none")}, callback: ${maybeState.getOrElse("none")}")
      Future.successful(BadRequest(Json.obj("message" -> "Invalid state parameter - possible CSRF attack")))
    } else {
      // For CLI flow, retrieve pending auth to get code verifier
      val cliPendingAuthFuture: Future[Option[fr.maif.izanami.models.PendingCliAuth]] =
        if (isCliFlow && actualState.isDefined) env.datastores.cliAuth.consumePendingAuth(actualState.get)
        else Future.successful(None)

      cliPendingAuthFuture.flatMap { maybeCliPendingAuth =>
        // Extract code and config
        val maybeCode = request.body.asJson.flatMap(json => (json \ "code").asOpt[String])

        env.datastores.configuration.readFullConfiguration().value.map(_.toOption.flatMap(_.oidcConfiguration)).flatMap {
          case None =>
            logger.error("OIDC configuration is absent")
            Future.successful(InternalServerError(Json.obj("message" -> "OAuth2 configuration is either absent or disabled")))

          case Some(oauth2Config) if !oauth2Config.enabled =>
            logger.error("OIDC configuration is disabled")
            Future.successful(InternalServerError(Json.obj("message" -> "OAuth2 configuration is either absent or disabled")))

          case _ if maybeCode.isEmpty =>
            logger.error("Failed to extract code from OIDC callback")
            Future.successful(InternalServerError(Json.obj("message" -> "Failed to extract code from token")))

          case _ if isCliFlow && maybeCliPendingAuth.isEmpty =>
            logger.error("CLI authentication state not found or expired")
            Future.successful(BadRequest(Json.obj("message" -> "CLI authentication state not found or expired")))

          case Some(oauth2Config) =>
            // Determine code verifier source: CLI pending auth or browser session
            val codeVerifier =
              if (isCliFlow) maybeCliPendingAuth.flatMap(_.codeVerifier)
              else request.session.get("code_verifier")

            processOidcTokenExchange(maybeCode.get, oauth2Config, codeVerifier).flatMap {
              case Left(errorResult) =>
                Future.successful(errorResult)

              case Right(token) if isCliFlow && actualState.isDefined =>
                handleCliOidcCompletion(actualState.get, token)

              case Right(token) =>
                handleBrowserOidcCompletion(token)
            }
        }
      }
    }
  }

  /**
   * Handles CLI OIDC completion: stores token for CLI polling pickup.
   */
  private def handleCliOidcCompletion(state: String, token: String): Future[Result] = {
    logger.debug(s"CLI flow - storing token for state: $state")
    env.datastores.cliAuth.storeCompletedAuth(state, token).map {
      case Right(_) =>
        logger.debug(s"CLI flow - token stored successfully for state: $state")
        Ok(Json.obj(
          "cliAuth" -> true,
          "message" -> "Authentication successful. You can close this window and return to your terminal."
        ))
      case Left(err) =>
        err.toHttpResponse
    }
  }

  /**
   * Handles browser OIDC completion: returns token as cookie.
   */
  private def handleBrowserOidcCompletion(token: String): Future[Result] = {
    Future.successful(
      NoContent.withCookies(
        Cookie(
          name = "token",
          value = token,
          httpOnly = false,
          sameSite = Some(SameSite.Strict)
        )
      )
    )
  }

  /**
   * Initiates CLI OIDC authentication flow.
   *
   * The CLI generates a cryptographically secure state parameter and opens the browser
   * to this endpoint. The backend stores the state and redirects to the OIDC provider.
   *
   * == State Parameter and `cli:` Prefix ==
   *
   * When redirecting to the OIDC provider, the state is prefixed with `cli:` to mark this
   * as a CLI-initiated flow. This prefix allows the callback handler ([[openIdCodeReturn]])
   * to distinguish CLI auth from browser auth and handle them differently:
   * - CLI flow: Store token for polling, show "close window" message
   * - Browser flow: Set session cookie, redirect to dashboard
   *
   * The prefix approach was chosen because:
   * - The OAuth 2.0 state parameter is opaque to providers (RFC 6749) - they pass it through unchanged
   * - No need for separate callback URLs (which would require OIDC provider configuration changes)
   * - Query parameters might be stripped by some providers during redirect
   *
   * @param state Cryptographically secure random string generated by CLI (min 32 bytes, base64url)
   * @see [[CliAuthDatastore]] for detailed documentation on the prefix design decision
   */
  def cliOpenIdConnect(state: String): Action[AnyContent] = Action.async { implicit request =>
    logger.debug(s"CLI login initiated with state: $state")
    if (!env.datastores.cliAuth.isValidState(state)) {
      logger.debug(s"CLI login state invalid: $state (length=${state.length})")
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
            val hasOpenIdInScope = oauth2Config.scopes.split(" ").exists(_.equalsIgnoreCase("openid"))
            val actualScope = (if (!hasOpenIdInScope) oauth2Config.scopes + " openid" else oauth2Config.scopes)
              .replace(" ", "%20")

            // Generate PKCE codes if enabled
            val (codeVerifier, pkceParams) = if (oauth2Config.pkce.exists(_.enabled)) {
              val (verifier, challenge, method) = generatePKCECodes(oauth2Config.pkce.get.algorithm.some)
              (Some(verifier), s"&code_challenge=$challenge&code_challenge_method=$method")
            } else {
              (None, "")
            }

            // Store pending CLI auth with state and optional code verifier
            logger.debug(s"CLI login - creating pending auth with state: $state, codeVerifier present: ${codeVerifier.isDefined}")
            env.datastores.cliAuth.createPendingAuth(state, codeVerifier).map {
              case Right(pending) =>
                // Add CLI marker to state for callback detection
                val cliState = s"cli:$state"
                val redirectUrl = s"${oauth2Config.authorizeUrl}?scope=$actualScope" +
                    s"&client_id=${oauth2Config.clientId}" +
                    s"&response_type=code" +
                    s"&redirect_uri=${oauth2Config.callbackUrl}" +
                    s"&state=$cliState" +
                    pkceParams
                logger.debug(s"CLI login - redirecting to: $redirectUrl")

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
   * - 404 Not Found: State not found (invalid or expired)
   * - 410 Gone: State expired after token was generated
   * - 429 Too Many Requests: Rate limited
   *
   * @param state The same state parameter used in cli-login
   */
  def cliTokenPoll(state: String): Action[AnyContent] = Action.async { implicit request =>
    logger.debug(s"CLI token poll - state: $state")
    if (!env.datastores.cliAuth.isValidState(state)) {
      logger.debug(s"CLI token poll - state invalid format: $state (length=${state.length})")
      Future.successful(BadRequest(Json.obj("message" -> "Invalid state parameter format")))
    } else {
      env.datastores.cliAuth.claimToken(state).map {
        case Right(Some(token)) =>
          logger.debug(s"CLI token poll - token found for state: $state")
          Ok(Json.obj("token" -> token))
        case Right(None) =>
          logger.debug(s"CLI token poll - still pending for state: $state")
          // Still pending - instruct CLI to continue polling
          Accepted(Json.obj(
            "status" -> "pending",
            "message" -> "Authentication in progress, please continue polling"
          ))
        case Left(err: fr.maif.izanami.errors.CliAuthRateLimited) =>
          logger.debug(s"CLI token poll - rate limited for state: $state")
          TooManyRequests(Json.obj(
            "message" -> "Too many requests, please slow down",
            "retryAfter" -> 5
          )).withHeaders("Retry-After" -> "5")
        case Left(err) =>
          logger.debug(s"CLI token poll - error for state: $state - ${err.message}")
          err.toHttpResponse
      }
    }
  }

  /**
   * Exchanges an authorization code for tokens and creates/updates the user session.
   *
   * This method encapsulates the shared logic between browser and CLI OIDC flows:
   * - Token exchange with the OIDC provider
   * - ID token validation and claims extraction
   * - User creation or update based on claims
   * - Session creation and JWT generation
   *
   * @param code The authorization code from the OIDC provider
   * @param oauth2Config The OAuth2 configuration
   * @param codeVerifier Optional PKCE code verifier (from session for browser, from pending auth for CLI)
   * @return Either an error Result or the generated JWT token
   */
  private def processOidcTokenExchange(
      code: String,
      oauth2Config: OAuth2Configuration,
      codeVerifier: Option[String]
  )(implicit request: Request[AnyContent]): Future[Either[Result, String]] = {
    val OAuth2Configuration(
      _enabled,
      method,
      clientId,
      clientSecret,
      tokenUrl,
      _authorizeUrl,
      _scopes,
      pkce,
      nameField,
      emailField,
      callbackUrl,
      userRightsByRoles,
      roleClaim,
      roleRightMode
    ) = oauth2Config

    var builder = env.Ws
      .url(tokenUrl)
      .withHttpHeaders(("content-type", "application/x-www-form-urlencoded"))

    var body = Map(
      "grant_type"   -> "authorization_code",
      "code"         -> code,
      "redirect_uri" -> callbackUrl
    )

    if (method == OAuth2BASICMethod) {
      builder = builder.withAuth(clientId, clientSecret, WSAuthScheme.BASIC)
    } else {
      body = body ++ Map(
        "client_id"     -> clientId,
        "client_secret" -> clientSecret
      )
    }

    if (pkce.exists(_.enabled)) {
      if (codeVerifier.isEmpty) {
        logger.error("PKCE flow is required but no code_verifier was found")
      }
      body = body + ("code_verifier" -> codeVerifier.getOrElse(""))
    }

    builder.post(body).flatMap { response =>
      if (response.status >= 400) {
        logger.error(s"Failed to retrieve id token from OIDC provider. Status: ${response.status}")
        logger.debug(s"Token exchange failed. Status: ${response.status}, body: ${response.body}")
        Future.successful(Left(InternalServerError(Json.obj("message" -> "Failed to retrieve token"))))
      } else {
        val maybeToken = (response.json \ "id_token").asOpt[String]
        maybeToken match {
          case None =>
            logger.error("Failed to read id_token from OIDC provider response")
            logger.debug(s"Missing id_token in response: ${response.json}")
            Future.successful(Left(InternalServerError(Json.obj("message" -> "Failed to retrieve token"))))

          case Some(idToken) =>
            processIdToken(idToken, oauth2Config)
        }
      }
    }
  }

  /**
   * Processes an ID token: validates claims, creates/updates user, generates session JWT.
   */
  private def processIdToken(
      idToken: String,
      oauth2Config: OAuth2Configuration
  ): Future[Either[Result, String]] = {
    val maybeClaims = JwtJson.decode(idToken, JwtOptions(signature = false, leeway = 1))

    if (maybeClaims.isFailure) {
      logger.error("Failed to decode id token")
      logger.debug(s"Failed to decode id token $idToken", maybeClaims.failed.get)
    }

    maybeClaims.toOption
      .flatMap(claims => {
        val maybeJsonClaims = Json.parse(claims.content).asOpt[JsObject]
        if (maybeJsonClaims.isEmpty) {
          logger.error("Failed to parse JSON claims from id token")
          logger.debug(s"Failed to read json claims from $claims")
        }
        maybeJsonClaims
      })
      .flatMap(jsonClaims => {
        val roles = extractRoles(jsonClaims, oauth2Config.roleClaim).getOrElse(Set())
        logger.debug(s"Extracted roles [${roles.mkString(",")}] from id_token")

        for {
          username <- (jsonClaims \ oauth2Config.nameField).asOpt[String]
          email    <- (jsonClaims \ oauth2Config.emailField).asOpt[String]
        } yield (username, email, roles)
      })
      .map { case (username, email, roles) =>
        createOrUpdateOidcUser(username, email, roles, oauth2Config)
          .flatMap {
            case Left(err) => Future(Left(err.toHttpResponse))
            case Right(_)  => createSessionAndGenerateJwt(username)
          }
      }
      .getOrElse(
        Future(
          Left(
            InternalServerError(
              Json.obj("message" -> "Failed to read token claims")
            )
          )
        )
      )
  }

  /**
   * Creates or updates a user based on OIDC claims.
   */
  private def createOrUpdateOidcUser(
      username: String,
      email: String,
      roles: Set[String],
      oauth2Config: OAuth2Configuration
  ): Future[Either[IzanamiError, Unit]] = {
    env.datastores.users.findUserWithCompleteRights(username).flatMap { maybeUser =>
      def createOrUpdateWithRights(rs: Option[RightsByRole]): Future[Either[IzanamiError, Unit]] = {
        val rights = rs.map(r => RightService.effectiveRights(r, roles)).getOrElse(CompleteRights.EMPTY)

        env.postgresql.executeInTransaction { conn =>
          maybeUser.fold {
            env.datastores.users.createUser(
              User(username, email = email, userType = OIDC, admin = rights.admin)
                .withRights(Rights(rights.tenants)),
              conn = Some(conn)
            )
          } { user =>
            if (
              (user.admin != rights.admin || user.rights != rights.tenants) && oauth2Config
                .roleRightMode.contains(Supervised)
            ) {
              rightService.updateUserRights(
                user.username,
                UserRightsUpdateRequest.fromRights(rights),
                force = true,
                conn = Some(conn)
              )
            } else {
              Future.successful(Right(()))
            }
          }
        }
      }

      val rightByRolesFromEnvIfAny = env.typedConfiguration.openid
        .flatMap(_.toIzanamiOAuth2Configuration)
        .flatMap(_.userRightsByRoles)
        .orElse(oauth2Config.userRightsByRoles)

      createOrUpdateWithRights(rightByRolesFromEnvIfAny).flatMap {
        case Left(_) if rightByRolesFromEnvIfAny.isDefined =>
          env.datastores.configuration
            .updateOIDCRightByRolesIfNeeded(rightByRolesFromEnvIfAny.get)
            .flatMap(newRights => createOrUpdateWithRights(Some(newRights)))
        case other => Future.successful(other)
      }
    }
  }

  /**
   * Creates a session and generates a JWT token for the user.
   */
  private def createSessionAndGenerateJwt(username: String): Future[Either[Result, String]] = {
    env.datastores.users.createSession(username).map { sessionId =>
      Right(env.jwtService.generateToken(sessionId))
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
