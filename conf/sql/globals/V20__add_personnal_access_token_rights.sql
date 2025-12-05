CREATE OR REPLACE FUNCTION enum_to_text(x anyenum)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
SELECT x::text;
$$;



ALTER TYPE TOKEN_RIGHT ADD VALUE 'DELETE FEATURE';
ALTER TYPE TOKEN_RIGHT ADD VALUE 'DELETE PROJECT';
ALTER TYPE TOKEN_RIGHT ADD VALUE 'DELETE KEY';

CREATE TYPE GLOBAL_TOKEN_RIGHT AS ENUM ('CREATE TENANT');


ALTER TABLE personnal_access_token_rights DROP CONSTRAINT personnal_access_token_rights_pkey;
ALTER TABLE personnal_access_token_rights ADD COLUMN global_value GLOBAL_TOKEN_RIGHT;
ALTER TABLE personnal_access_token_rights ALTER value DROP NOT NULL,
                                          ALTER tenant DROP NOT NULL;

ALTER TABLE personnal_access_token_rights ADD COLUMN value_for_pkey TEXT GENERATED ALWAYS AS (CASE WHEN global_value IS NULL THEN tenant || '.' || enum_to_text(value) ELSE  enum_to_text(global_value) END) STORED;
ALTER TABLE personnal_access_token_rights ADD CONSTRAINT right_level_xor CHECK (
    (value <> NULL AND tenant <> NULL AND global_value IS NULL) OR
    (global_value <> NULL AND tenant IS NULL AND value IS NULL)
);
ALTER TABLE personnal_access_token_rights ADD CONSTRAINT personnal_access_token_rights_pkey PRIMARY KEY (token, value_for_pkey);
