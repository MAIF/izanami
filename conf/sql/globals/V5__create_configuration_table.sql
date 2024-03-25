CREATE TABLE configuration (
    onerow_id bool PRIMARY KEY DEFAULT TRUE,
    mailer TEXT REFERENCES mailers(name) NOT NULL,
    invitation_mode TEXT NOT NULL,
    origin_email TEXT,
    izanami_id UUID NOT NULL DEFAULT gen_random_uuid(),
    anonymous_reporting BOOLEAN DEFAULT false,
    anonymous_reporting_date TIMESTAMP WITH TIME ZONE,
    CONSTRAINT onerow_uni CHECK (onerow_id)
);

INSERT INTO configuration(mailer, invitation_mode)
values ('CONSOLE', 'RESPONSE');