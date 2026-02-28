package fr.maif.izanami.datastores

import fr.maif.izanami.env.Env
import fr.maif.izanami.errors._
import fr.maif.izanami.models.PendingCliAuth
import fr.maif.izanami.utils.Datastore

import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.Future

/**
 * Storage interface for CLI OIDC authentication state.
 *
 * This trait defines the contract for storing and retrieving CLI authentication state
 * during the browser-based OIDC flow. Implementations must handle:
 * - Pending authentication entries (waiting for user to complete browser auth)
 * - Completed authentication entries (token ready for CLI pickup)
 * - Rate limiting to prevent brute force attacks
 * - Cleanup of expired entries
 *
 * == Available Implementations ==
 *
 * - [[InMemoryCliAuthStorage]]: Default, suitable for single-instance deployments
 * - [[PostgresCliAuthStorage]]: For clustered deployments behind a load balancer
 *
 * @see [[CliAuthDatastore]] for the factory that selects the appropriate implementation
 */
trait CliAuthStorage {
  /**
   * Creates a pending CLI authentication entry.
   *
   * @param state The cryptographically secure state parameter
   * @param codeVerifier Optional PKCE code verifier for secure token exchange
   * @return Either an error or the created pending auth entry
   */
  def createPendingAuth(state: String, codeVerifier: Option[String] = None): Future[Either[IzanamiError, PendingCliAuth]]

  /**
   * Retrieves and removes a pending auth entry.
   * Used when the OIDC callback completes to get the code verifier.
   *
   * @param state The state parameter to look up
   * @return The pending auth if found and not expired, None otherwise
   */
  def consumePendingAuth(state: String): Future[Option[PendingCliAuth]]

  /**
   * Checks if a state corresponds to a pending CLI authentication.
   *
   * @param state The state parameter to check
   * @return True if a pending auth exists for this state
   */
  def isPendingCliAuth(state: String): Future[Boolean]

  /**
   * Stores a completed authentication token for CLI retrieval.
   *
   * @param state The state parameter identifying the auth flow
   * @param token The authentication token to store
   * @return Either an error or success
   */
  def storeCompletedAuth(state: String, token: String): Future[Either[IzanamiError, Unit]]

  /**
   * Attempts to claim the token for a given state.
   * Includes rate limiting to prevent brute force attacks.
   *
   * @param state The state parameter to look up
   * @return Either an error, None (still pending), or Some(token) on success
   */
  def claimToken(state: String): Future[Either[IzanamiError, Option[String]]]

  /**
   * Called when the storage is started. Used for initializing cleanup tasks.
   */
  def onStart(): Future[Unit]

  /**
   * Called when the storage is stopped. Used for cleanup.
   */
  def onStop(): Future[Unit]
}

/**
 * Datastore for CLI OIDC authentication state management.
 *
 * This datastore manages the state-based token exchange flow that allows CLI tools
 * to authenticate users via browser-based OIDC without needing a local callback server.
 *
 * == Authentication Flow ==
 *
 * 1. CLI generates a cryptographically secure state (32 bytes, base64url encoded)
 * 2. CLI opens browser to `/api/admin/cli-login?state={state}`
 * 3. Backend stores pending auth and redirects to OIDC provider with `state=cli:{state}`
 * 4. User authenticates in browser
 * 5. OIDC callback detects CLI flow via `cli:` prefix, exchanges code for token
 * 6. Backend stores completed auth with token for CLI pickup
 * 7. CLI polls `/api/admin/cli-token?state={state}` to retrieve token
 *
 * == Why the `cli:` Prefix in State Parameter ==
 *
 * The OIDC callback endpoint (`/api/admin/openid-connect-callback`) receives the same
 * parameters for both browser and CLI authentication flows. The backend must distinguish
 * between them to know whether to:
 * - '''Browser flow''': Create a session cookie and redirect to the dashboard
 * - '''CLI flow''': Store the token for polling pickup and show a "close window" message
 *
 * The `cli:` prefix approach was chosen over alternatives:
 *
 * | Approach | Pros | Cons |
 * |----------|------|------|
 * | '''Prefix in state''' (`cli:xxx`) | Simple, no extra params, state survives redirect | Slightly unconventional |
 * | Separate callback URL | Clean separation | Requires OIDC provider to allow multiple redirect URIs |
 * | Query param (`?flow=cli`) | Explicit | May not survive OIDC redirect (provider might strip unknown params) |
 * | Store flow type in DB | Clean state param | Extra DB lookup before knowing flow type |
 *
 * === Is It Safe? ===
 *
 * Yes. The OAuth 2.0 spec (RFC 6749) defines the state parameter as:
 * ''"An opaque value used by the client to maintain state between the request and callback"''
 *
 * The OIDC provider passes it through unchanged without interpreting its contents.
 * We simply parse the prefix on return to determine the flow type.
 *
 * === Alternatives in Other Tools ===
 *
 * - '''GitHub CLI''' (`gh`): Uses a local HTTP server on localhost as callback
 * - '''AWS CLI''': Uses device authorization flow (different OAuth grant type)
 * - '''Google Cloud CLI''': Similar local server approach
 *
 * The prefix approach avoids requiring users to have a free local port, making it
 * simpler for CLI tools that may run in restricted environments.
 *
 * == Storage Backend Selection ==
 *
 * The storage backend is selected based on configuration:
 * - `app.cli-auth.storage = "in-memory"` (default): Uses [[InMemoryCliAuthStorage]]
 * - `app.cli-auth.storage = "database"`: Uses [[PostgresCliAuthStorage]]
 *
 * For clustered deployments, use database storage to ensure state is shared across instances.
 *
 * @see [[fr.maif.izanami.models.PendingCliAuth]]
 * @see [[fr.maif.izanami.models.CompletedCliAuth]]
 */
class CliAuthDatastore(val env: Env) extends Datastore {

  private val secureRandom: SecureRandom = new SecureRandom()
  private val STATE_SIZE_BYTES: Int      = 32 // 256 bits of entropy

  /**
   * The underlying storage implementation, selected based on configuration.
   */
  private val storage: CliAuthStorage = env.typedConfiguration.cliAuth.storage.toLowerCase match {
    case "database" | "db" =>
      env.logger.info("CLI auth storage: database")
      new PostgresCliAuthStorage(env)
    case _ =>
      env.logger.info("CLI auth storage: in-memory")
      new InMemoryCliAuthStorage(env)
  }

  override def onStart(): Future[Unit] = storage.onStart()

  override def onStop(): Future[Unit] = storage.onStop()

  /**
   * Generates a cryptographically secure state parameter.
   *
   * @return A base64url-encoded string with 256 bits of entropy
   */
  def generateState(): String = {
    val bytes = new Array[Byte](STATE_SIZE_BYTES)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /**
   * Validates state parameter format (base64url, correct length).
   *
   * @param state The state parameter to validate
   * @return True if the state matches the expected format
   */
  def isValidState(state: String): Boolean = {
    state != null &&
    state.length >= 40 &&
    state.length <= 50 &&
    state.matches("^[A-Za-z0-9_-]+$")
  }

  /**
   * Creates a pending CLI authentication entry.
   */
  def createPendingAuth(state: String, codeVerifier: Option[String] = None): Future[Either[IzanamiError, PendingCliAuth]] =
    storage.createPendingAuth(state, codeVerifier)

  /**
   * Retrieves and removes a pending auth entry, returning the code verifier if present.
   */
  def consumePendingAuth(state: String): Future[Option[PendingCliAuth]] =
    storage.consumePendingAuth(state)

  /**
   * Checks if a state corresponds to a CLI authentication flow.
   */
  def isPendingCliAuth(state: String): Future[Boolean] =
    storage.isPendingCliAuth(state)

  /**
   * Stores a completed authentication token for CLI retrieval.
   */
  def storeCompletedAuth(state: String, token: String): Future[Either[IzanamiError, Unit]] =
    storage.storeCompletedAuth(state, token)

  /**
   * Retrieves and deletes the token for a given state.
   * Includes rate limiting to prevent brute force attacks.
   */
  def claimToken(state: String): Future[Either[IzanamiError, Option[String]]] =
    storage.claimToken(state)
}
