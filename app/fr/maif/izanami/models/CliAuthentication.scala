package fr.maif.izanami.models

import java.time.Instant

/**
 * Represents a pending CLI authentication request waiting for OIDC completion.
 *
 * @param state        Cryptographically secure random string (32+ bytes, base64url encoded)
 * @param createdAt    Timestamp when the request was initiated
 * @param expiresAt    Timestamp when this pending auth expires (default: 5 minutes from creation)
 * @param codeVerifier Optional PKCE code verifier if PKCE is enabled
 */
case class PendingCliAuth(
    state: String,
    createdAt: Instant,
    expiresAt: Instant,
    codeVerifier: Option[String] = None
)

/**
 * Represents a completed CLI authentication with token ready for pickup.
 *
 * @param state     The state parameter used to correlate the request
 * @param token     The JWT token to be returned to the CLI
 * @param createdAt When the token was stored
 * @param expiresAt When this stored token expires (default: 2 minutes from creation)
 */
case class CompletedCliAuth(
    state: String,
    token: String,
    createdAt: Instant,
    expiresAt: Instant
)
