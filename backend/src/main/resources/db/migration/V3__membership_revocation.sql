-- Revocation must not delete the row: service_case(author_id, house_id) still references it.
ALTER TABLE membership ADD COLUMN revoked_at timestamptz;

-- Reading access checks this index on every request, not only on login.
CREATE INDEX membership_active_idx ON membership(user_id, house_id) WHERE revoked_at IS NULL;
