ALTER TABLE users_tenants_rights
    ADD COLUMN default_project_right izanami.PROJECT_RIGHT_LEVEL,
    ADD COLUMN default_webhook_right izanami.RIGHT_LEVEL,
    ADD COLUMN default_key_right izanami.RIGHT_LEVEL;


UPDATE izanami.configuration
SET oidc_configuration = (
    (COALESCE(oidc_configuration, '{}') || jsonb_build_object(
        'userRightsByRoles',
        (
            SELECT CASE
                WHEN oidc_configuration ? 'defaultOIDCUserRights'
                AND (
                NOT (oidc_configuration ? 'userRightsByRoles')
                    OR NOT ((oidc_configuration -> 'userRightsByRoles') ? '')
                )
            THEN jsonb_build_object (
                    '',
                    oidc_configuration -> 'defaultOIDCUserRights' || jsonb_build_object('admin', false)
                ) || COALESCE(
                    oidc_configuration -> 'userRightsByRoles',
                    '{}'
                )
            ELSE COALESCE(oidc_configuration -> 'userRightsByRoles', '{}')
            END
        )
    )) - 'defaultOIDCUserRights'
);