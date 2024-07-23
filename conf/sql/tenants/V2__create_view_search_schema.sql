CREATE EXTENSION IF NOT EXISTS pg_trgm with schema izanami;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch with schema izanami;


CREATE INDEX trgm_idx_feature ON features USING gist (name izanami.gist_trgm_ops, description izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_projects ON projects USING gist (name izanami.gist_trgm_ops, description izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_tags ON tags USING gist (name izanami.gist_trgm_ops, description izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_apikeys ON apikeys USING gist (name izanami.gist_trgm_ops, description izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_webhooks ON webhooks USING gist (name izanami.gist_trgm_ops, description izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_globalcontexts ON global_feature_contexts USING gist (name izanami.gist_trgm_ops);
CREATE INDEX trgm_idx_featurecontexts ON feature_contexts USING gist (name izanami.gist_trgm_ops);

CREATE OR REPLACE VIEW search_entities AS
SELECT
    text 'Features' AS origin_table,
    id::text AS id,
    name,
    project,
    description,
    NULL AS parent
FROM
    features
UNION ALL
SELECT
    text 'Projects' AS origin_table,
    id::text as id,
    name,
    name AS project,
    description,
    NULL AS parent
FROM
    projects
UNION ALL
SELECT
    text 'Tags' AS origin_table,
    id::text AS id,
    name,
    NULL AS project,
    description,
    NULL AS parent
FROM
    tags
UNION ALL
SELECT
    text 'Apikeys' AS origin_table,
    clientid AS id,
    name,
    NULL AS project,
    description,
    NULL AS parent
FROM
   apikeys
UNION ALL
SELECT
    text 'Webhooks' AS origin_table,
    id::text AS id,
    name,
    NULL AS parent,
    description, NULL as parent
FROM
    webhooks
UNION ALL
SELECT
    text 'Contexts' AS origin_table,
    id::text AS id,
    name,
    NULL AS project,
    text 'Global' AS description,
    parent
FROM
    global_feature_contexts
UNION ALL
SELECT
    text 'Contexts' AS origin_table,
    id::text AS id,
    name,
    project,
    text 'Projects' AS description,
    COALESCE(parent , global_parent) AS parent
FROM
    feature_contexts
UNION ALL
SELECT
   text 'Wasm Scripts' AS origin_table,
   id::text AS id,
   id::text AS name,
   NULL AS project,
   NULL AS description,
   NULL AS parent
FROM wasm_script_configurations;

