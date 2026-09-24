-- Revocation must not delete the row: service_case(author_id, house_id) still references it.
ALTER TABLE membership ADD COLUMN revoked_at timestamptz;

-- Reading access checks this index on every request, not only on login.
CREATE INDEX membership_active_idx ON membership(user_id, house_id) WHERE revoked_at IS NULL;

-- Invitations grant RESIDENT in one allowed house. The code itself is never stored.
CREATE TABLE house_invitation (
    id uuid PRIMARY KEY,
    house_id uuid NOT NULL REFERENCES house(id),
    code_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    consumed_at timestamptz,
    consumed_by uuid REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Consumption is one event: either both columns are set or neither is.
    CHECK ((consumed_at IS NULL) = (consumed_by IS NULL))
);
CREATE INDEX invitation_open_idx ON house_invitation(code_hash) WHERE consumed_at IS NULL;
