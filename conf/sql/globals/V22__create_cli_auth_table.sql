-- CLI Authentication state storage for clustered deployments
-- Stores pending and completed CLI OIDC authentication states
--
-- This table is used when cli-auth.storage = "postgresql" is configured.
-- For single-instance deployments, the default in-memory storage is recommended.

CREATE TYPE CLI_AUTH_STATUS AS ENUM ('pending', 'completed');

CREATE TABLE cli_auth (
    state TEXT PRIMARY KEY,
    status CLI_AUTH_STATUS NOT NULL DEFAULT 'pending',
    code_verifier TEXT,
    token TEXT,
    poll_count INTEGER NOT NULL DEFAULT 0,
    poll_window_start TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_cli_auth_expires ON cli_auth(expires_at);
CREATE INDEX idx_cli_auth_status ON cli_auth(status);
