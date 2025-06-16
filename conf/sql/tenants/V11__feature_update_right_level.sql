ALTER TABLE users_projects_rights
    ADD COLUMN new_level izanami.PROJECT_RIGHT_LEVEL NOT NULL DEFAULT 'READ';

UPDATE users_projects_rights
    SET new_level=(level::text)::izanami.PROJECT_RIGHT_LEVEL;


ALTER TABLE users_projects_rights
    DROP COLUMN level;

ALTER TABLE users_projects_rights
    RENAME new_level TO level;
