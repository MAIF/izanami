ALTER TABLE users ADD COLUMN roles jsonb;

UPDATE users SET roles = '[]'::jsonb WHERE user_type='OIDC';

ALTER TABLE users ADD CONSTRAINT roles_is_array CHECK (jsonb_typeof(roles) = 'array');
