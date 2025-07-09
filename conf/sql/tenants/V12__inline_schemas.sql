CREATE OR REPLACE FUNCTION check_global_consistency() RETURNS trigger AS $check_global_consistency$
DECLARE
parent_global_status BOOLEAN;
BEGIN
    -- If a new context is global, its parent must be global as well
    IF NEW.global THEN
        IF NEW.parent IS NOT NULL THEN
            SELECT global INTO parent_global_status
            FROM "${schema}".new_contexts
            WHERE "${extensions_schema}".ltree_eq(ctx_path, NEW.parent);
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

CREATE OR REPLACE FUNCTION check_project_consistency() RETURNS trigger AS $check_project_consistency$
DECLARE
parent_project TEXT;
parent_global BOOLEAN;
BEGIN
    IF NEW.global = false AND NEW.parent IS NOT NULL THEN
        SELECT project, global INTO parent_project, parent_global
        FROM "${schema}".new_contexts
        WHERE "${extensions_schema}".ltree_eq(ctx_path, NEW.parent);

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


CREATE OR REPLACE FUNCTION enforce_protection_cascade() RETURNS trigger AS $enforce_protection_cascade$
    DECLARE
parent_protected BOOLEAN;
BEGIN
        IF NEW.protected = false AND NEW.parent IS NOT NULL THEN
            SELECT protected INTO parent_protected
            FROM "${schema}".new_contexts
            WHERE "${extensions_schema}".ltree_eq(ctx_path, NEW.parent);

            IF parent_protected = true THEN
                RAISE EXCEPTION 'Cannot remove protection: the parent % is protected.', NEW.parent;
            END IF;
        END IF;

        IF NEW.protected = true AND (OLD.protected IS DISTINCT FROM true OR TG_OP = 'INSERT') THEN
            UPDATE "${schema}".new_contexts
            SET protected = true
            WHERE ctx_path OPERATOR("${extensions_schema}".<@) NEW.ctx_path
            AND NOT "${extensions_schema}".ltree_eq(ctx_path, NEW.ctx_path);
        END IF;
RETURN NEW;
END;
$enforce_protection_cascade$ LANGUAGE plpgsql;