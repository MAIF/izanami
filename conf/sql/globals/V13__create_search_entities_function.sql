CREATE OR REPLACE  FUNCTION  izanami.search_all_byusers(search_term TEXT, user_name TEXT)
    RETURNS TABLE (origin_table TEXT, id TEXT, name_search TEXT, origin_tenant TEXT) AS $$
DECLARE
tenant_record RECORD;
    search_query TEXT;
BEGIN
FOR tenant_record IN SELECT tenant FROM izanami.users_tenants_rights WHERE username= user_name LOOP
            search_query := format('
            SELECT
                origin_table,
                id,
                name AS name_search , %L::TEXT as origin_tenant
            FROM %I.search_entities
            WHERE searchable_name @@ to_tsquery(''english'', %L)',tenant_record.tenant,
                                   tenant_record.tenant ,
                                   plainto_tsquery(search_term));

RETURN QUERY EXECUTE search_query;
END LOOP;
END;
$$ LANGUAGE plpgsql;