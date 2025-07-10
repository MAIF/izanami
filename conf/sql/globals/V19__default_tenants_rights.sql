ALTER TABLE users_tenants_rights
    ADD COLUMN default_project_right izanami.PROJECT_RIGHT_LEVEL,
    ADD COLUMN default_webhook_right izanami.RIGHT_LEVEL,
    ADD COLUMN default_key_right izanami.RIGHT_LEVEL;