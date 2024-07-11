CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;


CREATE INDEX trgm_idx_feature ON features USING gist (name gist_trgm_ops, description gist_trgm_ops);
CREATE INDEX trgm_idx_projects ON projects USING gist (name gist_trgm_ops, description gist_trgm_ops);
CREATE INDEX trgm_idx_tags ON tags USING gist (name gist_trgm_ops, description gist_trgm_ops);
CREATE INDEX trgm_idx_apikeys ON apikeys USING gist (name gist_trgm_ops, description gist_trgm_ops);
CREATE INDEX trgm_idx_webhooks ON webhooks USING gist (name gist_trgm_ops, description gist_trgm_ops);
CREATE INDEX trgm_idx_globalcontexts ON global_feature_contexts USING gist (name gist_trgm_ops);
CREATE INDEX trgm_idx_featurecontexts ON feature_contexts USING gist (name gist_trgm_ops);

CREATE OR REPLACE VIEW search_entities AS
SELECT
    text 'Features' AS origin_table,
    id::text AS id,
    name,
    project,
    description,
    NULL as parent
FROM
    features
UNION ALL
SELECT
    text 'Projects' AS origin_table,
    id::text as id,
    name,
    name as project,
    description,
    NULL as parent
FROM
    projects
UNION ALL
SELECT
    text 'Tags' AS origin_table,
    id::text as id,
    name,
    NULL as project,
    description,
    NULL as parent
FROM
    tags
UNION ALL
SELECT
    text 'Apikeys' AS origin_table,
        clientid as id,
    name,
    ap.project as project,
    description,
    NULL as parent
FROM
   apikeys f
        left join apikeys_projects ap on f.name = ap.apikey
UNION ALL
SELECT
    text 'Webhooks' AS origin_table,
    id::text as id,
    name,
    (SELECT name from projects where id=(SELECT project from webhooks_projects WHERE project=id)) as project,
    description, NULL as parent
FROM
    webhooks
UNION ALL
SELECT
    text 'Global contexts' AS origin_table,
    id::text as id,
    name,
    NULL as project,
    NULL as description,
    parent
FROM
    global_feature_contexts
UNION ALL
SELECT
    text 'Projects contexts' AS origin_table,
    id::text as id,
    name,
    project,
    NULL as description,
    COALESCE(parent , global_parent) as parent
FROM
    feature_contexts;
