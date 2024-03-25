CREATE TABLE projects (
                          id UUID DEFAULT gen_random_uuid () PRIMARY KEY,
                          name TEXT NOT NULL UNIQUE,
                          description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE wasm_script_configurations (
                                            id TEXT PRIMARY KEY,
                                            config JSONB NOT NULL
);

CREATE TABLE features (
                          id TEXT DEFAULT (gen_random_uuid ())::TEXT PRIMARY KEY,
                          name TEXT NOT NULL,
                          project TEXT NOT NULL REFERENCES projects (name) ON DELETE CASCADE ON UPDATE CASCADE,
                          enabled BOOLEAN NOT NULL,
                          conditions JSONB,
                          script_config TEXT REFERENCES wasm_script_configurations(id) ON DELETE RESTRICT ON UPDATE CASCADE,
                          metadata JSONB NOT NULL DEFAULT '{}',
                          description TEXT NOT NULL DEFAULT '',
                          CONSTRAINT unique_feature_name_for_project UNIQUE (name, project),
                          CONSTRAINT feature_type_xor CHECK ((conditions is not null and script_config is null) or (conditions is null and script_config is not null))
);

CREATE INDEX features_project ON features (project);


CREATE TABLE tags (
                      id UUID DEFAULT gen_random_uuid () PRIMARY KEY,
                      name TEXT UNIQUE,
                      description TEXT
);

CREATE TABLE features_tags (
                               tag TEXT NOT NULL REFERENCES tags (name) ON DELETE CASCADE ON UPDATE CASCADE,
                               feature TEXT NOT NULL REFERENCES features (id) ON DELETE CASCADE ON UPDATE CASCADE,
                               PRIMARY KEY (tag, feature)
);

CREATE TABLE apikeys (
                         clientid TEXT UNIQUE NOT NULL,
                         name TEXT PRIMARY KEY,
                         clientsecret TEXT NOT NULL,
                         description TEXT NOT NULL DEFAULT '',
                         enabled BOOLEAN NOT NULL DEFAULT true,
                         legacy BOOLEAN NOT NULL DEFAULT false,
                         admin BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX apikeys_clientid ON apikeys (clientid);
CREATE INDEX apikeys_clientsecret ON apikeys (clientsecret);


CREATE TABLE apikeys_projects (
                                  apikey TEXT REFERENCES apikeys (name) ON DELETE CASCADE ON UPDATE CASCADE,
                                  project TEXT REFERENCES projects(name) ON DELETE CASCADE ON UPDATE CASCADE,
                                  PRIMARY KEY (apikey, project)
);

CREATE TABLE global_feature_contexts (
                                         id TEXT PRIMARY KEY,
                                         name TEXT NOT NULL,
                                         parent TEXT REFERENCES global_feature_contexts(id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE feature_contexts (
                                  id TEXT PRIMARY KEY,
                                  name TEXT NOT NULL,
                                  parent TEXT REFERENCES feature_contexts(id) ON DELETE CASCADE ON UPDATE CASCADE,
                                  global_parent TEXT REFERENCES global_feature_contexts(id) ON DELETE CASCADE ON UPDATE CASCADE,
                                  project TEXT NOT NULL REFERENCES projects(name) ON DELETE CASCADE ON UPDATE CASCADE,
                                  CONSTRAINT feature_contexts_xor CHECK ((global_parent is not null or parent is not null) or (parent is null and global_parent is null))
);

CREATE TABLE feature_contexts_strategies (
                                             project TEXT NOT NULL REFERENCES projects(name) ON DELETE CASCADE ON UPDATE CASCADE,
                                             local_context TEXT REFERENCES feature_contexts(id) ON DELETE CASCADE ON UPDATE CASCADE,
                                             global_context TEXT REFERENCES global_feature_contexts(id) ON DELETE CASCADE ON UPDATE CASCADE,
                                             context TEXT GENERATED ALWAYS AS (case when local_context is null then global_context else local_context end) STORED,
                                             context_path TEXT GENERATED ALWAYS AS (case when local_context is null then SUBSTRING(global_context FROM POSITION('_' IN global_context)+1) else SUBSTRING(local_context FROM POSITION('_' IN local_context)+1) end) STORED,
                                             feature TEXT NOT NULL,
                                             conditions JSONB,
                                             script_config TEXT REFERENCES wasm_script_configurations(id) ON DELETE CASCADE ON UPDATE CASCADE,
                                             enabled BOOLEAN NOT NULL,
                                             PRIMARY KEY (project, context, feature),
                                             FOREIGN KEY(feature, project) REFERENCES features(name, project) ON DELETE CASCADE ON UPDATE CASCADE,
                                             CONSTRAINT feature_context_type_xor CHECK ((conditions is not null and script_config is null) or (conditions is null and script_config is not null)),
                                             CONSTRAINT feature_context_type_xor_context CHECK ((local_context is not null and global_context is null) or (local_context is null and global_context is not null))
);

-- This table is used to store both global subcontext AND local context inheriting from global subcontext
-- in order to avoid having both a global and local subcontext with the same name for the same global context
CREATE TABLE feature_context_name_unicity_check_table (
    parent TEXT,
    context TEXT,
    PRIMARY KEY (parent, context)
);

CREATE TABLE users_projects_rights (
                                       username TEXT NOT NULL REFERENCES izanami.users(username) ON DELETE CASCADE ON UPDATE CASCADE,
                                       project TEXT NOT NULL REFERENCES projects(name) ON DELETE CASCADE ON UPDATE CASCADE,
                                       level izanami.RIGHT_LEVEL NOT NULL DEFAULT 'READ',
                                       PRIMARY KEY (username, project)
);

CREATE TABLE users_keys_rights (
                                   username TEXT NOT NULL REFERENCES izanami.users(username) ON DELETE CASCADE ON UPDATE CASCADE,
                                   apikey TEXT NOT NULL REFERENCES apikeys(name) ON DELETE CASCADE ON UPDATE CASCADE,
                                   level izanami.RIGHT_LEVEL NOT NULL DEFAULT 'READ',
                                   PRIMARY KEY (username, apikey)
);
