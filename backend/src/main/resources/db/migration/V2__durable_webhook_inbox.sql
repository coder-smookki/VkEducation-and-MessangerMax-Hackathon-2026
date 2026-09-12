-- K5/S5: acknowledge a valid webhook only after this record is committed.
-- event_key must be derived from the verified MAX update type and stable identifiers.
CREATE TABLE inbox_event (
    id uuid PRIMARY KEY,
    event_key varchar(250) NOT NULL UNIQUE,
    payload_hash char(64) NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inbox_pending_idx ON inbox_event(available_at) WHERE processed_at IS NULL;
