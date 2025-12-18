package fr.maif.izanami.datastores

import org.apache.pekko.actor.Cancellable
import fr.maif.izanami.env.Env
import fr.maif.izanami.errors._
import fr.maif.izanami.models.{CompletedCliAuth, PendingCliAuth}

import play.api.Logger

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * In-memory implementation of CLI OIDC authentication state storage.
 *
 * This implementation is suitable for single-instance deployments where all requests
 * are handled by the same server. It uses ConcurrentHashMap for thread-safe state management.
 *
 * == Limitations ==
 *
 * - State is lost on server restart
 * - Does not work in clustered environments with multiple Izanami instances
 *   behind a load balancer (CLI login might hit instance A, callback hits instance B)
 *
 * For clustered deployments, use [[PostgresCliAuthStorage]] instead by setting
 * `IZANAMI_CLI_AUTH_STORAGE=postgresql`.
 *
 * @see [[CliAuthStorage]] for the storage interface
 * @see [[PostgresCliAuthStorage]] for the PostgreSQL-backed implementation
 */
class InMemoryCliAuthStorage(env: Env) extends CliAuthStorage {

  private implicit val ec: scala.concurrent.ExecutionContext = env.executionContext
  private val logger = Logger("izanami.cli-auth")

  private val pendingAuths   = new ConcurrentHashMap[String, PendingCliAuth]()
  private val completedAuths = new ConcurrentHashMap[String, CompletedCliAuth]()

  // Rate limiting: track poll attempts per state
  private val pollAttempts = new ConcurrentHashMap[String, (Int, Instant)]()

  private var cleanupTask: Cancellable = Cancellable.alreadyCancelled

  // Configuration constants
  private val PENDING_AUTH_TTL_SECONDS: Long    = 300  // 5 minutes
  private val COMPLETED_AUTH_TTL_SECONDS: Long  = 120  // 2 minutes
  private val MAX_POLL_ATTEMPTS_PER_MINUTE: Int = 60   // Rate limit

  override def onStart(): Future[Unit] = {
    // Schedule cleanup task every 30 seconds
    cleanupTask = env.actorSystem.scheduler.scheduleAtFixedRate(
      30.seconds,
      30.seconds
    )(() => cleanupExpiredEntries())
    Future.successful(())
  }

  override def onStop(): Future[Unit] = {
    cleanupTask.cancel()
    pendingAuths.clear()
    completedAuths.clear()
    pollAttempts.clear()
    Future.successful(())
  }

  override def createPendingAuth(state: String, codeVerifier: Option[String] = None): Future[Either[IzanamiError, PendingCliAuth]] = {
    val now     = Instant.now()
    val pending = PendingCliAuth(
      state = state,
      createdAt = now,
      expiresAt = now.plusSeconds(PENDING_AUTH_TTL_SECONDS),
      codeVerifier = codeVerifier
    )
    pendingAuths.put(state, pending)
    logger.debug(s"[InMemory] Created pending auth - state: $state, pendingAuths size: ${pendingAuths.size()}")
    Future.successful(Right(pending))
  }

  override def consumePendingAuth(state: String): Future[Option[PendingCliAuth]] = {
    logger.debug(s"[InMemory] consumePendingAuth - state: $state, pendingAuths contains key: ${pendingAuths.containsKey(state)}")
    val pending      = Option(pendingAuths.remove(state))
    val validPending = pending.filter(p => Instant.now().isBefore(p.expiresAt))
    logger.debug(s"[InMemory] consumePendingAuth - found: ${pending.isDefined}, valid: ${validPending.isDefined}")
    Future.successful(validPending)
  }

  override def isPendingCliAuth(state: String): Future[Boolean] = {
    Future.successful(pendingAuths.containsKey(state))
  }

  override def storeCompletedAuth(state: String, token: String): Future[Either[IzanamiError, Unit]] = {
    val now       = Instant.now()
    val completed = CompletedCliAuth(
      state = state,
      token = token,
      createdAt = now,
      expiresAt = now.plusSeconds(COMPLETED_AUTH_TTL_SECONDS)
    )
    completedAuths.put(state, completed)
    logger.debug(s"[InMemory] Stored completed auth - state: $state, completedAuths size: ${completedAuths.size()}")
    Future.successful(Right(()))
  }

  override def claimToken(state: String): Future[Either[IzanamiError, Option[String]]] = {
    logger.debug(s"[InMemory] claimToken - state: $state, completedAuths contains: ${completedAuths.containsKey(state)}, pendingAuths contains: ${pendingAuths.containsKey(state)}")
    // Rate limiting check
    val now                     = Instant.now()
    val (attempts, windowStart) = Option(pollAttempts.get(state)).getOrElse((0, now))

    if (now.isAfter(windowStart.plusSeconds(60))) {
      // Reset window
      pollAttempts.put(state, (1, now))
    } else if (attempts >= MAX_POLL_ATTEMPTS_PER_MINUTE) {
      logger.debug(s"[InMemory] claimToken - rate limited for state: $state")
      return Future.successful(Left(CliAuthRateLimited()))
    } else {
      pollAttempts.put(state, (attempts + 1, windowStart))
    }

    Option(completedAuths.remove(state)) match {
      case Some(completed) if Instant.now().isBefore(completed.expiresAt) =>
        pollAttempts.remove(state) // Clean up rate limit tracking
        logger.debug(s"[InMemory] claimToken - returning token for state: $state")
        Future.successful(Right(Some(completed.token)))
      case Some(_) =>
        logger.debug(s"[InMemory] claimToken - expired for state: $state")
        Future.successful(Left(CliAuthStateExpired(state)))
      case None if pendingAuths.containsKey(state) =>
        // Still pending - token not ready yet
        logger.debug(s"[InMemory] claimToken - still pending for state: $state")
        Future.successful(Right(None))
      case None =>
        logger.debug(s"[InMemory] claimToken - not found for state: $state")
        Future.successful(Left(CliAuthStateNotFound(state)))
    }
  }

  private def cleanupExpiredEntries(): Unit = {
    val now = Instant.now()

    pendingAuths.entrySet().removeIf(e => now.isAfter(e.getValue.expiresAt))
    completedAuths.entrySet().removeIf(e => now.isAfter(e.getValue.expiresAt))
    pollAttempts.entrySet().removeIf(e => now.isAfter(e.getValue._2.plusSeconds(120)))
  }
}
