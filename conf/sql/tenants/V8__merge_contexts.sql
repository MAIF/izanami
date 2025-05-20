CREATE EXTENSION IF NOT EXISTS ltree with schema ${extensions_schema};
SET SEARCH_PATH='${schema}', '${extensions_schema}';

CREATE TABLE new_contexts
(
    ctx_path ${extensions_schema}.ltree PRIMARY KEY GENERATED ALWAYS AS (${extensions_schema}.text2ltree(
            CASE WHEN parent IS NULL THEN name ELSE ${extensions_schema}.ltree2text(parent) || '.' || name END
                                                    )) STORED,
    parent ${extensions_schema}.ltree REFERENCES new_contexts(ctx_path) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED ,
    global   boolean NOT NULL,
    name     TEXT NOT NULL,
    project  TEXT REFERENCES projects(name),
    protected boolean NOT NULL,
    CONSTRAINT local_context_must_have_project CHECK (
        (global = false AND project IS NOT NULL)
            OR global = true
    ),
    CONSTRAINT feature_contextsnamesize CHECK (char_length(name) <= 200)
);

CREATE OR REPLACE FUNCTION check_global_consistency() RETURNS trigger AS $check_global_consistency$
DECLARE
parent_global_status BOOLEAN;
BEGIN
    -- If a new context is global, its parent must be global as well
    IF NEW.global THEN
        IF NEW.parent IS NOT NULL THEN
            SELECT global INTO parent_global_status
            FROM new_contexts
            WHERE ${extensions_schema}.ltree_eq(ctx_path, NEW.parent);

            IF parent_global_status IS DISTINCT FROM TRUE THEN
                RAISE EXCEPTION 'Parent % of new global context % is not global', NEW.parent, NEW.name;
            END IF;
        END IF;
    END IF;

    -- A global context can't become non-global
    IF TG_OP = 'UPDATE' THEN
        IF OLD.global = true AND NEW.global = false THEN
            RAISE EXCEPTION 'Cannot make a global context non-global: %', OLD.ctx_path;
        END IF;
    END IF;

RETURN NEW;
END;
$check_global_consistency$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_global_consistency
BEFORE INSERT OR UPDATE ON new_contexts
FOR EACH ROW
EXECUTE FUNCTION check_global_consistency();


CREATE OR REPLACE FUNCTION check_project_consistency() RETURNS trigger AS $check_project_consistency$
DECLARE
parent_project TEXT;
parent_global BOOLEAN;
BEGIN
    IF NEW.global = false AND NEW.parent IS NOT NULL THEN
        SELECT project, global INTO parent_project, parent_global
        FROM new_contexts
        WHERE ${extensions_schema}.ltree_eq(ctx_path, NEW.parent);

        IF parent_project IS DISTINCT FROM NEW.project AND parent_global IS DISTINCT FROM true THEN
                    RAISE EXCEPTION 'The project of context % (%) is different from its parent project % (%)', NEW.name, NEW.project, NEW.parent, parent_project;
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.project IS DISTINCT FROM NEW.project THEN
            RAISE EXCEPTION 'Project change forbidden for context %: % -> %', NEW.ctx_path, OLD.project, NEW.project;
        END IF;
    END IF;

RETURN NEW;
END;
$check_project_consistency$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER check_project_consistency_trigger
AFTER INSERT OR UPDATE ON new_contexts
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW
EXECUTE FUNCTION check_project_consistency();


CREATE OR REPLACE FUNCTION enforce_protection_cascade() RETURNS trigger AS $enforce_protection_cascade$
    DECLARE
    parent_protected BOOLEAN;
    BEGIN
        IF NEW.protected = false AND NEW.parent IS NOT NULL THEN
            SELECT protected INTO parent_protected
            FROM new_contexts
            WHERE ${extensions_schema}.ltree_eq(ctx_path, NEW.parent);

            IF parent_protected = true THEN
                RAISE EXCEPTION 'Cannot remove protection: the parent % is protected.', NEW.parent;
            END IF;
        END IF;

        IF NEW.protected = true AND (OLD.protected IS DISTINCT FROM true OR TG_OP = 'INSERT') THEN
            UPDATE new_contexts
            SET protected = true
            WHERE ctx_path <@ NEW.ctx_path
              AND ctx_path != NEW.ctx_path; -- Exclut lui-même
        END IF;

    RETURN NEW;
END;
$enforce_protection_cascade$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER check_protection_cascade_trigger
AFTER INSERT OR UPDATE ON new_contexts
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW
EXECUTE FUNCTION enforce_protection_cascade();

ALTER TABLE feature_contexts_strategies
DROP COLUMN context,
    ADD context ${extensions_schema}.ltree,
    ADD constraint strategy_context FOREIGN KEY (context) REFERENCES new_contexts(ctx_path) ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY IMMEDIATE;



-- Migrating global contexts first
INSERT INTO new_contexts(parent, global, name, project, protected)
SELECT ${extensions_schema}.text2ltree(replace(substring(c.parent from position('_' in c.parent)+1), '_', '.')), true, c.name, null, COALESCE(c.protected, false)
FROM global_feature_contexts c
ORDER BY length(c.id) asc;

-- Migrating local contexts
INSERT INTO new_contexts(parent, global, name, project, protected)
SELECT ${extensions_schema}.text2ltree(replace(substring(coalesce(c.parent, c.global_parent) from position('_' in coalesce(c.parent, c.global_parent))+1), '_', '.')), false, c.name, c.project, COALESCE(c.protected, false)
FROM feature_contexts c
ORDER BY length(c.id) asc;

-- TODO create backups of strategies before touching table

-- Migrate foreign keys for overrides
UPDATE feature_contexts_strategies fcs SET context=${extensions_schema}.text2ltree(replace(substring(COALESCE(fcs.local_context, fcs.global_context) from position('_' in COALESCE(fcs.local_context, fcs.global_context))+1), '_', '.'));

ALTER TABLE feature_contexts_strategies
    ALTER COLUMN context SET NOT NULL,
    ADD PRIMARY KEY (project, context, feature),
    DROP CONSTRAINT feature_context_type_xor_context,
    DROP COLUMN context_path,
    DROP COLUMN local_context,
    DROP COLUMN global_context;