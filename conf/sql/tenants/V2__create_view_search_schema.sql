
ALTER TABLE projects ADD COLUMN ts tsvector GENERATED ALWAYS AS (
	SETWEIGHT(to_tsvector('simple', name), 'A') || ' ' ||  SETWEIGHT(to_tsvector('simple', description), 'B') :: tsvector
) STORED;
CREATE INDEX ts_idx_project ON projects USING GIN (ts);

ALTER TABLE features ADD COLUMN ts tsvector GENERATED ALWAYS AS (
    SETWEIGHT(to_tsvector('simple', name), 'A') || ' ' ||  SETWEIGHT(to_tsvector('simple', description), 'B') :: tsvector
) STORED;
CREATE INDEX ts_idx_features ON features USING GIN (ts);

ALTER TABLE tags ADD COLUMN ts tsvector GENERATED ALWAYS AS (
    SETWEIGHT(to_tsvector('simple', name), 'A') || ' ' ||  SETWEIGHT(to_tsvector('simple', description), 'B') :: tsvector
) STORED;
CREATE INDEX ts_idx_tags ON tags USING GIN (ts);

ALTER TABLE apikeys ADD COLUMN ts tsvector GENERATED ALWAYS AS (
    SETWEIGHT(to_tsvector('simple', name), 'A') || ' ' ||  SETWEIGHT(to_tsvector('simple', description), 'B') :: tsvector
) STORED;

CREATE INDEX ts_idx_apiKeys ON apikeys USING GIN (ts);

CREATE OR REPLACE VIEW search_entities AS
SELECT
    text 'features' AS origin_table,  id::text AS id, name, ts AS searchable_name , project, description
FROM
    features
UNION ALL
SELECT
    text 'projects' AS origin_table, id::text as id,  name, ts AS searchable_name, name as project, description
FROM
    projects
UNION ALL
SELECT
    text 'tags' AS origin_table, id::text as id, name, ts AS searchable_name, NULL as project, description
FROM
    tags
UNION ALL
SELECT
    text 'apikeys' AS origin_table, clientid as id, name, ts AS searchable_name, (SELECT project FROM apikeys_projects WHERE apikey=name) as project, description
FROM
    apikeys;