CREATE TABLE configuration (
    onerow_id bool PRIMARY KEY DEFAULT TRUE,
    mailer TEXT REFERENCES mailers(name) NOT NULL,
    invitation_mode TEXT NOT NULL,
    origin_email TEXT,
    CONSTRAINT onerow_uni CHECK (onerow_id)
);

INSERT INTO configuration(mailer, invitation_mode)
values ('CONSOLE', 'RESPONSE');