ALTER TABLE izanami.global_events ADD COLUMN emitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE izanami.global_events ALTER COLUMN emitted_at SET DEFAULT now();

CREATE TYPE EVENT_ORIGIN AS ENUM ('IMPORT', 'NORMAL');
CREATE TYPE AUTHENTICATION AS ENUM('TOKEN', 'BACKOFFICE');

ALTER TABLE izanami.global_events ADD COLUMN origin EVENT_ORIGIN DEFAULT 'NORMAL';
ALTER table izanami.global_events
    ALTER COLUMN origin DROP DEFAULT,
    ALTER COLUMN origin SET NOT NULL;

ALTER TABLE izanami.global_events ADD COLUMN authentication AUTHENTICATION DEFAULT 'BACKOFFICE';
ALTER table izanami.global_events
    ALTER COLUMN authentication DROP DEFAULT,
    ALTER COLUMN authentication SET NOT NULL;

ALTER TABLE izanami.global_events ADD COLUMN username TEXT;
UPDATE izanami.global_events SET username=coalesce(event->>user,'<unknown user>');
ALTER TABLE izanami.global_events
    ALTER COLUMN username SET NOT NULL;
