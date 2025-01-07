UPDATE tenants SET
    description = left(description, 500);
ALTER TABLE tenants ADD CONSTRAINT tenantnamesize CHECK (
    char_length(name) <= 63 AND
    char_length(description) <= 200
);

ALTER TABLE users ADD CONSTRAINT usertextsize CHECK (
    char_length(username) <= 320 AND
    char_length(email) <= 320
);

ALTER TABLE configuration ADD CONSTRAINT configurationtextsize CHECK (
    char_length(origin_email) <= 320
);

ALTER TABLE invitations ADD CONSTRAINT invitationstextsize CHECK (
    char_length(email) <= 320
);

UPDATE personnal_access_tokens SET name = left(name, 200);
ALTER TABLE personnal_access_tokens ADD CONSTRAINT personnal_access_tokenstextsize CHECK (
    char_length(name) <= 200
);