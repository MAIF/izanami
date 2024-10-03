CREATE TYPE RESULT_TYPE AS ENUM ('boolean', 'string', 'number');

ALTER TABLE features ADD COLUMN value TEXT,
                     ADD COLUMN result_type RESULT_TYPE DEFAULT 'boolean';

ALTER table features
    ALTER COLUMN result_type DROP DEFAULT,
    ALTER COLUMN result_type SET NOT NULL;


ALTER TABLE features
    ADD CONSTRAINT value_script_config CHECK (value is null or script_config is null),
    ADD CONSTRAINT feature_result_type_value_check CHECK (
        (result_type = 'number' AND value ~ '^\d*\.?\d+$') OR
        (result_type = 'boolean' AND VALUE IS NULL) OR
        (result_type = 'string' AND value IS NOT NULL)
        ),
    ADD CONSTRAINT feature_project_name_result_type_unique UNIQUE (project, name, result_type);

ALTER TABLE feature_contexts_strategies ADD COLUMN value TEXT DEFAULT 'true',
                                        ADD COLUMN result_type RESULT_TYPE DEFAULT 'boolean';

ALTER table feature_contexts_strategies ALTER COLUMN value DROP DEFAULT,
                                        ALTER COLUMN result_type DROP DEFAULT,
                                        ALTER COLUMN result_type SET NOT NULL,
                                        DROP CONSTRAINT feature_contexts_strategies_feature_project_fkey,
                                        ADD CONSTRAINT feature_contexts_strategies_feature_project_fkey
                                            FOREIGN KEY (feature, project, result_type) REFERENCES features(name, project, result_type) ON DELETE CASCADE ON UPDATE CASCADE,
                                        ADD CONSTRAINT feature_contexts_strategies_result_type_value_check CHECK (
                                            (result_type = 'number' AND value ~ '^\d*\.?\d+$') OR
                                            (result_type = 'boolean' AND (value = 'true' OR value = 'false'))OR
                                            (result_type = 'string' AND VALUE IS NOT NULL)
                                        ),
                                        ADD CONSTRAINT feature_contexts_strategies_value_script_config CHECK (value is null or script_config is null);
