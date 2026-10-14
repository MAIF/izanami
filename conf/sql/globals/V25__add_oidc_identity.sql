ALTER TABLE izanami.users
    ADD COLUMN oidc_issuer TEXT,
    ADD COLUMN oidc_subject TEXT;

ALTER TABLE izanami.users
    ADD CONSTRAINT users_oidc_identity_consistency CHECK (
        (oidc_issuer IS NULL AND oidc_subject IS NULL)
        OR (
            user_type = 'OIDC'
            AND oidc_issuer IS NOT NULL
            AND oidc_subject IS NOT NULL
            AND oidc_issuer <> ''
            AND oidc_subject <> ''
        )
    ),
    ADD CONSTRAINT users_oidc_identity_unique UNIQUE (oidc_issuer, oidc_subject);
