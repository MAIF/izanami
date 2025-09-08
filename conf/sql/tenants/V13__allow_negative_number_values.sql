ALTER TABLE features DROP CONSTRAINT feature_result_type_value_check;
ALTER TABLE feature_contexts_strategies DROP CONSTRAINT feature_contexts_strategies_result_type_value_check;

ALTER TABLE features ADD CONSTRAINT feature_result_type_value_check CHECK (
    (result_type = 'number' AND (script_config IS NOT NULL or value ~ '^-?\d*\.?\d+$')) OR
    (result_type = 'boolean' AND VALUE IS NULL) OR
    (result_type = 'string' AND (script_config IS NOT NULL or value IS NOT NULL))
);

ALTER TABLE feature_contexts_strategies ADD CONSTRAINT feature_contexts_strategies_result_type_value_check CHECK (
    (result_type = 'number' AND value ~ '^-?\d*\.?\d+$') OR
    (result_type = 'boolean' AND (value = 'true' OR value = 'false'))OR
    (result_type = 'string' AND VALUE IS NOT NULL)
)