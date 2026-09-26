-- S-05: a bot reply belongs to a person, not always to a case, so case_id becomes optional.
-- One queue keeps lease, retry and backoff in a single implementation.
ALTER TABLE outbox_event ALTER COLUMN case_id DROP NOT NULL;
