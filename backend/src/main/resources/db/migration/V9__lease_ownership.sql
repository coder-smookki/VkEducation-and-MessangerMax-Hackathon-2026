-- S-05: who holds the lease, not just until when. A worker whose lease expired mid-send must not
-- overwrite the result of the worker that took the row after it.
ALTER TABLE inbox_event ADD COLUMN leased_by uuid;
ALTER TABLE outbox_event ADD COLUMN leased_by uuid;
