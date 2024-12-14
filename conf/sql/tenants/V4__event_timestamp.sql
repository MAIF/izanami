ALTER TABLE events ADD COLUMN emitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE events ALTER COLUMN emitted_at SET DEFAULT now();

ALTER TABLE events ADD COLUMN origin izanami.EVENT_ORIGIN DEFAULT 'NORMAL';
ALTER table events
    ALTER COLUMN origin DROP DEFAULT,
ALTER COLUMN origin SET NOT NULL;

ALTER TABLE events ADD COLUMN authentication izanami.AUTHENTICATION DEFAULT 'BACKOFFICE';
ALTER table events
    ALTER COLUMN authentication DROP DEFAULT,
ALTER COLUMN authentication SET NOT NULL;

ALTER TABLE events ADD COLUMN username TEXT;
UPDATE events SET username=coalesce(event->>user,'<unknown user>');
ALTER TABLE events
    ALTER COLUMN username SET NOT NULL;