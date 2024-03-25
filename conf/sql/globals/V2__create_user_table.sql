CREATE TYPE USER_TYPE AS ENUM ('INTERNAL', 'OIDC', 'OTOROSHI');

CREATE TABLE users (
    username TEXT NOT NULL PRIMARY KEY,
    password TEXT,
    email TEXT UNIQUE,
    admin BOOLEAN NOT NULL DEFAULT false,
    user_type USER_TYPE NOT NULL DEFAULT 'INTERNAL',
    default_tenant TEXT DEFAULT NULL REFERENCES tenants(name) ON DELETE SET NULL ON UPDATE CASCADE,
    legacy BOOLEAN NOT NULL DEFAULT false,
    CHECK ((user_type='OTOROSHI' OR user_type='OIDC') OR (user_type='INTERNAL' AND password IS NOT NULL AND email IS NOT NULL))
);

INSERT INTO users(email, username, password, admin) VALUES ('foo.bar@somemail.com', '${default_admin}', '${default_password}', true);