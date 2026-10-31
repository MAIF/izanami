ALTER TABLE features ADD COLUMN allowed_values JSONB;

ALTER TABLE features ADD CONSTRAINT features_allowed_values_shape
  CHECK (allowed_values IS NULL OR (
    result_type = 'string' AND script_config IS NULL
    AND CASE WHEN jsonb_typeof(allowed_values) = 'array' THEN
      jsonb_array_length(allowed_values) > 0
      AND NOT jsonb_path_exists(allowed_values,
        'strict $[*] ? (@.type() != "object" || !exists(@.value ? (@.type() == "string" && !(@ like_regex "^[[:space:]]*$"))) || (exists(@.label) && !exists(@.label ? (@.type() == "string" || @.type() == "null"))))')
    ELSE false END
  ));

CREATE FUNCTION lock_string_values_parent() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('SELECT id FROM %I.features WHERE project = $1 AND name = $2 FOR UPDATE', TG_TABLE_SCHEMA)
    USING NEW.project, NEW.feature;
  RETURN NEW;
END
$$;

-- Lock the parent before each overload insert or update, until the transaction ends.
-- Serializes concurrent overload writes and parent updates so that changing the shared
-- choices cannot race with adding an overload value, alternative, or WASM strategy.
CREATE TRIGGER lock_string_values_parent
  BEFORE INSERT OR UPDATE ON feature_contexts_strategies
  FOR EACH ROW EXECUTE FUNCTION lock_string_values_parent();

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
        AND (value IS NULL OR NOT allowed_values @> jsonb_build_array(jsonb_build_object('value', value)))
      UNION ALL
      SELECT location || '.conditions[' || (ordinality - 1) || '].value: '
        || coalesce((condition -> 'value')::text, 'missing')
      FROM strategies
      CROSS JOIN LATERAL jsonb_array_elements(conditions::jsonb)
        WITH ORDINALITY AS alternatives(condition, ordinality)
      WHERE script_config IS NULL AND
        (jsonb_typeof(condition -> 'value') IS DISTINCT FROM 'string'
          OR NOT allowed_values @> jsonb_build_array(jsonb_build_object('value', condition -> 'value')))
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

-- Validate the parent's and all overloads' base values and conditional alternatives
-- against the parent's shared choices, and reject WASM strategies when choices exist.
-- Covers creating a constrained flag, enabling or changing its choices, and editing
-- its own strategy; in particular, a choice still used by an overload cannot be removed.
-- Runs at statement end by default; imports defer it until commit to validate the
-- final merged state rather than temporary inconsistencies between imported rows.
CREATE CONSTRAINT TRIGGER features_allowed_values_uses
  AFTER INSERT OR UPDATE ON features
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION check_string_allowed_values();

-- Apply the same shared-choice validation after each overload insert or update.
-- Covers new overloads, changes to their base values or conditional alternatives,
-- and attempts to use WASM under a constrained parent. Unrestricted parents are ignored.
-- Imports can defer this check until commit so parents and overloads can be updated
-- together, while an invalid final state causes the entire transaction to roll back.
CREATE CONSTRAINT TRIGGER overloads_allowed_values_uses
  AFTER INSERT OR UPDATE ON feature_contexts_strategies
  DEFERRABLE INITIALLY IMMEDIATE
  FOR EACH ROW EXECUTE FUNCTION check_string_allowed_values();
