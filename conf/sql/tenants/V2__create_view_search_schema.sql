CREATE OR REPLACE VIEW search_entities AS
SELECT
    text 'Features' AS origin_table,  id::text AS id, name , project, description
FROM
    features
UNION ALL
SELECT
    text 'Projects' AS origin_table, id::text as id,  name, name as project, description
FROM
    projects
UNION ALL
SELECT
    text 'Tags' AS origin_table, id::text as id, name, NULL as project, description
FROM
    tags
UNION ALL
SELECT
    text 'Apikeys' AS origin_table, clientid as id, name, (SELECT project FROM apikeys_projects WHERE apikey=name) as project, description
FROM
    apikeys
UNION ALL
SELECT
    text 'Webhooks' AS origin_table, id::text as id, name, (SELECT name from projects where id=(SELECT project from webhooks_projects WHERE project=id)) as project, description
FROM
    webhooks
UNION ALL
SELECT
    text 'Global contexts' AS origin_table, id::text as id, name, NULL as project, NULL as description
FROM
    global_feature_contexts
UNION ALL
SELECT
    text 'Projects contexts' AS origin_table, id::text as id, name, project, NULL as description
FROM
    feature_contexts;
