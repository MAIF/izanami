CREATE TABLE invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid (),
    email TEXT UNIQUE NOT NULL,
    creation TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    admin BOOLEAN NOT NULL DEFAULT false,
    rights JSONB NOT NULL DEFAULT '{}'::jsonb,
    inviter TEXT REFERENCES users (username) ON DELETE CASCADE ON UPDATE CASCADE
);