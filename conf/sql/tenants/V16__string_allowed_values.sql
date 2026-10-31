ALTER TABLE features ADD COLUMN allowed_values JSONB;

CREATE FUNCTION valid_string_allowed_values(choices JSONB) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN choices IS NULL THEN true
    WHEN jsonb_typeof(choices) <> 'array' THEN false
    WHEN jsonb_array_length(choices) = 0 THEN false
    ELSE
      NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(choices) AS choice
        WHERE jsonb_typeof(choice) <> 'string'
          OR (choice #>> '{}') ~ '^[[:space:]]*$'
      )
      AND (SELECT count(*) = count(DISTINCT choice)
           FROM jsonb_array_elements(choices) AS choice)
  END
$$;

ALTER TABLE features ADD CONSTRAINT features_allowed_values_shape
  CHECK (valid_string_allowed_values(allowed_values)
    AND (allowed_values IS NULL OR (result_type = 'string' AND script_config IS NULL)));

-- Serialize overload writes with changes to their parent's shared list.
CREATE FUNCTION lock_string_values_parent() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('SELECT id FROM %I.features WHERE project = $1 AND name = $2 FOR UPDATE', TG_TABLE_SCHEMA)
    USING NEW.project, NEW.feature;
  RETURN NEW;
END
$$;

CREATE TRIGGER lock_string_values_parent
  BEFORE INSERT OR UPDATE ON feature_contexts_strategies
  FOR EACH ROW EXECUTE FUNCTION lock_string_values_parent();

-- Imports defer this check until both parents and overloads have been merged.
CREATE FUNCTION check_string_allowed_values() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
  parent_project TEXT;
  parent_name TEXT;
  invalid_uses TEXT;
BEGIN
  IF TG_TABLE_NAME = 'features' THEN
    parent_project := NEW.project;
    parent_name := NEW.name;
  ELSE
    parent_project := NEW.project;
    parent_name := NEW.feature;
  END IF;

  EXECUTE format($query$
    WITH parent AS (
      SELECT * FROM %1$I.features
      WHERE project = $1 AND name = $2 AND allowed_values IS NOT NULL
    ), strategies AS (
      SELECT 'parent' AS location, f.value, f.conditions, f.script_config, f.allowed_values
      FROM parent f
      UNION ALL
      SELECT 'overload ' || s.context::text, s.value, s.conditions, s.script_config, f.allowed_values
      FROM parent f JOIN %1$I.feature_contexts_strategies s
        ON s.project = f.project AND s.feature = f.name
    ), invalid AS (
      SELECT location || ': WASM is incompatible with allowedValues' AS problem
      FROM strategies WHERE script_config IS NOT NULL
      UNION ALL
      SELECT location || '.value: ' || coalesce(to_json(value)::text, 'null')
      FROM strategies WHERE script_config IS NULL
        AND (value IS NULL OR NOT allowed_values @> jsonb_build_array(value))
      UNION ALL
      SELECT location || '.conditions[' || (ordinality - 1) || '].value: '
        || coalesce((condition -> 'value')::text, 'missing')
      FROM strategies
      CROSS JOIN LATERAL jsonb_array_elements(conditions::jsonb)
        WITH ORDINALITY AS alternatives(condition, ordinality)
      WHERE script_config IS NULL AND
        (jsonb_typeof(condition -> 'value') IS DISTINCT FROM 'string'
          OR NOT allowed_values @> jsonb_build_array(condition -> 'value'))
    )
    SELECT string_agg(problem, '; ' ORDER BY problem) FROM invalid
  $query$, TG_TABLE_SCHEMA) INTO invalid_uses USING parent_project, parent_name;

  IF invalid_uses IS NOT NULL THEN
    RAISE EXCEPTION 'Invalid allowedValues for %.%: %', parent_project, parent_name, invalid_uses
      USING ERRCODE = '23514', CONSTRAINT = 'features_allowed_values_uses';
  END IF;
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER features_allowed_values_uses
  AFTER INSERT OR UPDATE ON features
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION check_string_allowed_values();

CREATE CONSTRAINT TRIGGER overloads_allowed_values_uses
  AFTER INSERT OR UPDATE ON feature_contexts_strategies
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION check_string_allowed_values();
