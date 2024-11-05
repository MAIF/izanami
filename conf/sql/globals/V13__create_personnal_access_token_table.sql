CREATE TYPE TOKEN_RIGHT AS ENUM ('EXPORT', 'IMPORT');

CREATE TABLE personnal_access_tokens (
    id UUID NOT NULL DEFAULT gen_random_uuid () PRIMARY KEY,
    username TEXT NOT NULL REFERENCES izanami.users(username) ON DELETE CASCADE ON UPDATE CASCADE,
    name TEXT NOT NULL,
    token TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    expiration_timezone TEXT,
    all_rights BOOLEAN NOT NULL,
    CONSTRAINT unique_name_for_user UNIQUE (username, name),
    CONSTRAINT expiration_integrity CHECK (
        (expires_at IS NULL AND expiration_timezone IS NULL) OR
        (expires_at IS NOT NULL AND expiration_timezone IS NOT NULL)
    )
);

CREATE TABLE personnal_access_token_rights (
    token UUID NOT NULL REFERENCES izanami.personnal_access_tokens(id) ON DELETE CASCADE ON UPDATE CASCADE,
    tenant TEXT NOT NULL REFERENCES izanami.tenants(name) ON DELETE CASCADE ON UPDATE CASCADE,
    value TOKEN_RIGHT NOT NULL,
    PRIMARY KEY (token, tenant, value)
);