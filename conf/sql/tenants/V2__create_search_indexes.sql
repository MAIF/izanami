CREATE EXTENSION IF NOT EXISTS pg_trgm with schema ${extensions_schema};
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch with schema ${extensions_schema};


CREATE INDEX trgm_idx_feature ON features USING gist (name ${extensions_schema}.gist_trgm_ops, description ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_projects ON projects USING gist (name ${extensions_schema}.gist_trgm_ops, description ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_tags ON tags USING gist (name ${extensions_schema}.gist_trgm_ops, description ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_apikeys ON apikeys USING gist (name ${extensions_schema}.gist_trgm_ops, description ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_webhooks ON webhooks USING gist (name ${extensions_schema}.gist_trgm_ops, description ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_globalcontexts ON global_feature_contexts USING gist (name ${extensions_schema}.gist_trgm_ops);
CREATE INDEX trgm_idx_featurecontexts ON feature_contexts USING gist (name ${extensions_schema}.gist_trgm_ops);
