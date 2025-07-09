CREATE TABLE new_project_rights (
    username TEXT NOT NULL REFERENCES izanami.users(username) ON DELETE CASCADE ON UPDATE CASCADE,
    level izanami.RIGHT_LEVEL,
    PRIMARY KEY (username)
);