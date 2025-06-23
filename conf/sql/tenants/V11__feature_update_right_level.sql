ALTER TABLE users_projects_rights
    ADD COLUMN new_level izanami.PROJECT_RIGHT_LEVEL NOT NULL DEFAULT 'READ';

UPDATE users_projects_rights
    SET new_level=(level::text)::izanami.PROJECT_RIGHT_LEVEL;


ALTER TABLE users_projects_rights
    DROP COLUMN level;

ALTER TABLE users_projects_rights
    RENAME new_level TO level;

ALTER TABLE webhooks_call_status
    ADD COLUMN count NUMERIC NOT NULL DEFAULT 0,
    ADD COLUMN next TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT next_must_be_valued_for_failed_calls CHECK (
    (count = 0) OR (count > 0 AND next IS NOT NULL)
);
