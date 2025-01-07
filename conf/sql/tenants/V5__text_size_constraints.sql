UPDATE projects SET
    description = left(description, 500),
    name = left(name, 200);
ALTER TABLE projects ADD CONSTRAINT projectsnamesize CHECK (
    char_length(name) <= 200 AND
    char_length(description) <= 500
);

UPDATE wasm_script_configurations SET id = left(id, 200);
ALTER TABLE wasm_script_configurations ADD CONSTRAINT wasm_script_configurationsnamesize CHECK (
    char_length(id) <= 200
);

UPDATE features SET
    description = left(description, 500),
    id = left(id, 500),
    name = left(name, 200);
ALTER TABLE features ADD CONSTRAINT featuresnamesize CHECK (
    char_length(name) <= 200 AND
    char_length(description) <= 500 AND
    char_length(id) < 500 AND
    char_length(value) <= 1048576
);

UPDATE tags SET
    name = left(name, 200),
    description = left(description, 500);
ALTER TABLE tags ADD CONSTRAINT tagsnamesize CHECK (
    char_length(name) <= 200 AND
    char_length(description) <= 500
);

UPDATE apikeys SET
    name = left(name, 200),
    description = left(description, 500);
ALTER TABLE apikeys ADD CONSTRAINT apikeysnamesize CHECK (
    char_length(name) <= 200 AND
    char_length(description) <= 500
);

ALTER TABLE global_feature_contexts ADD CONSTRAINT global_feature_contextsnamesize CHECK (
    char_length(name) <= 200
);

ALTER TABLE feature_contexts ADD CONSTRAINT feature_contextsnamesize CHECK (
    char_length(name) <= 200
);

ALTER TABLE feature_contexts_strategies ADD CONSTRAINT feature_contexts_strategiesnamesize CHECK (
    char_length(value) <= 1048576
);


UPDATE webhooks SET
    description = left(description, 500),
    name = left(name, 200);
ALTER TABLE webhooks ADD CONSTRAINT webhooksnamesize CHECK (
    char_length(name) <= 200 AND
    char_length(url) <= 2048 AND
    char_length(headers::TEXT) <= 40480 AND
    char_length(body_template) <= 1048576 AND
    char_length(description) <= 500
);