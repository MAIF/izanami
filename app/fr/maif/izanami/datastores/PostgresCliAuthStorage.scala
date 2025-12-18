package fr.maif.izanami.datastores

import org.apache.pekko.actor.Cancellable
import fr.maif.izanami.env.Env
import fr.maif.izanami.env.pgimplicits.EnhancedRow
import fr.maif.izanami.errors._
import fr.maif.izanami.models.{CompletedCliAuth, PendingCliAuth}

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * PostgreSQL-backed implementation of CLI OIDC authentication state storage.
 *
 * This implementation stores CLI authentication state in PostgreSQL, making it suitable
 * for clustered deployments where multiple Izanami instances run behind a load balancer.
 *
 * == When to Use ==
 *
 * Use this storage when:
 * - Running multiple Izanami instances in a cluster
 * - State persistence across restarts is required
 * - CLI login, OIDC callback, and token polling may hit different instances
 *
 * Configure by setting `IZANAMI_CLI_AUTH_STORAGE=postgresql`.
 *
 * == Security Features ==
 *
 * - Atomic rate limiting using PostgreSQL UPDATE with conditional logic
 * - Single-use tokens: DELETE on successful claim
 * - Automatic cleanup of expired entries via scheduled task
 *
 * @see [[CliAuthStorage]] for the storage interface
 * @see [[InMemoryCliAuthStorage]] for the in-memory implementation
 */
class PostgresCliAuthStorage(env: Env) extends CliAuthStorage {

  private implicit val ec: scala.concurrent.ExecutionContext = env.executionContext

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
    Future.successful(())
  }

  override def createPendingAuth(state: String, codeVerifier: Option[String] = None): Future[Either[IzanamiError, PendingCliAuth]] = {
    val now       = OffsetDateTime.now(ZoneOffset.UTC)
    val expiresAt = now.plusSeconds(PENDING_AUTH_TTL_SECONDS)

    env.postgresql.queryOne(
      """INSERT INTO izanami.cli_auth (state, status, code_verifier, created_at, expires_at)
         VALUES ($1, 'pending', $2, $3, $4)
         ON CONFLICT (state) DO UPDATE SET
           status = 'pending',
           code_verifier = EXCLUDED.code_verifier,
           created_at = EXCLUDED.created_at,
           expires_at = EXCLUDED.expires_at
         RETURNING state, created_at, expires_at, code_verifier""".stripMargin,
      List(state, codeVerifier.orNull, now, expiresAt)
    ) { row =>
      for {
        st        <- row.optString("state")
        createdAt <- row.optOffsetDatetime("created_at")
        expiresAt <- row.optOffsetDatetime("expires_at")
      } yield PendingCliAuth(
        state = st,
        createdAt = createdAt.toInstant,
        expiresAt = expiresAt.toInstant,
        codeVerifier = row.optString("code_verifier")
      )
    }.map {
      case Some(pending) => Right(pending)
      case None          => Left(InternalServerError("Failed to create pending CLI auth"))
    }
  }

  override def consumePendingAuth(state: String): Future[Option[PendingCliAuth]] = {
    env.postgresql.queryOne(
      """DELETE FROM izanami.cli_auth
         WHERE state = $1 AND status = 'pending'::izanami.CLI_AUTH_STATUS AND expires_at > now()
         RETURNING state, created_at, expires_at, code_verifier""".stripMargin,
      List(state)
    ) { row =>
      for {
        st        <- row.optString("state")
        createdAt <- row.optOffsetDatetime("created_at")
        expiresAt <- row.optOffsetDatetime("expires_at")
      } yield PendingCliAuth(
        state = st,
        createdAt = createdAt.toInstant,
        expiresAt = expiresAt.toInstant,
        codeVerifier = row.optString("code_verifier")
      )
    }
  }

  override def isPendingCliAuth(state: String): Future[Boolean] = {
    env.postgresql.queryOne(
      """SELECT 1 FROM izanami.cli_auth
         WHERE state = $1 AND status = 'pending'::izanami.CLI_AUTH_STATUS AND expires_at > now()""".stripMargin,
      List(state)
    ) { _ => Some(true) }.map(_.getOrElse(false))
  }

  override def storeCompletedAuth(state: String, token: String): Future[Either[IzanamiError, Unit]] = {
    val now       = OffsetDateTime.now(ZoneOffset.UTC)
    val expiresAt = now.plusSeconds(COMPLETED_AUTH_TTL_SECONDS)

    env.postgresql.queryOne(
      """INSERT INTO izanami.cli_auth (state, status, token, created_at, expires_at)
         VALUES ($1, 'completed', $2, $3, $4)
         ON CONFLICT (state) DO UPDATE SET
           status = 'completed',
           token = EXCLUDED.token,
           created_at = EXCLUDED.created_at,
           expires_at = EXCLUDED.expires_at
         RETURNING state""".stripMargin,
      List(state, token, now, expiresAt)
    ) { _ => Some(()) }.map {
      case Some(_) => Right(())
      case None    => Left(InternalServerError("Failed to store completed CLI auth"))
    }
  }

  override def claimToken(state: String): Future[Either[IzanamiError, Option[String]]] = {
    // First, check and update rate limiting atomically
    env.postgresql.queryOne(
      """UPDATE izanami.cli_auth
         SET poll_count = CASE
               WHEN poll_window_start IS NULL OR poll_window_start < now() - interval '1 minute'
               THEN 1
               ELSE poll_count + 1
             END,
             poll_window_start = CASE
               WHEN poll_window_start IS NULL OR poll_window_start < now() - interval '1 minute'
               THEN now()
               ELSE poll_window_start
             END
         WHERE state = $1
         RETURNING status, token, expires_at, poll_count""".stripMargin,
      List(state)
    ) { row =>
      val pollCount = row.optInt("poll_count").getOrElse(0)
      val status    = row.optString("status")
      val token     = row.optString("token")
      val expiresAt = row.optOffsetDatetime("expires_at")

      Some((pollCount, status, token, expiresAt))
    }.flatMap {
      case Some((pollCount, status, token, expiresAt)) =>
        if (pollCount > MAX_POLL_ATTEMPTS_PER_MINUTE) {
          Future.successful(Left(CliAuthRateLimited()))
        } else {
          status match {
            case Some("completed") =>
              val now = Instant.now()
              expiresAt match {
                case Some(exp) if exp.toInstant.isAfter(now) =>
                  // Token is valid - delete it and return
                  deleteAndReturnToken(state, token)
                case _ =>
                  // Token has expired
                  Future.successful(Left(CliAuthStateExpired(state)))
              }
            case Some("pending") =>
              // Still pending - token not ready yet
              Future.successful(Right(None))
            case _ =>
              Future.successful(Left(CliAuthStateNotFound(state)))
          }
        }
      case None =>
        // State not found at all
        Future.successful(Left(CliAuthStateNotFound(state)))
    }
  }

  private def deleteAndReturnToken(state: String, maybeToken: Option[String]): Future[Either[IzanamiError, Option[String]]] = {
    env.postgresql.queryOne(
      """DELETE FROM izanami.cli_auth WHERE state = $1 RETURNING token""".stripMargin,
      List(state)
    ) { row =>
      row.optString("token")
    }.map {
      case Some(token) => Right(Some(token))
      case None        => Right(maybeToken) // Fallback to previously read token if delete returned nothing
    }
  }

  private def cleanupExpiredEntries(): Unit = {
    env.postgresql.queryOne(
      """DELETE FROM izanami.cli_auth WHERE expires_at < now() RETURNING state""".stripMargin,
      List()
    ) { _ => Some(()) }
  }
}
