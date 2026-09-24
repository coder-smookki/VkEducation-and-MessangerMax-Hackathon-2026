-- Acceptance is a separate fact: it never changes work status, round or observation.
ALTER TABLE service_case ADD COLUMN accepted_at timestamptz;
ALTER TABLE service_case ADD COLUMN accepted_by uuid REFERENCES app_user(id);
ALTER TABLE service_case ADD CONSTRAINT case_acceptance_is_one_event
    CHECK ((accepted_at IS NULL) = (accepted_by IS NULL));

-- Human-readable number for the screens; the UUID stays the only identifier in the API.
ALTER TABLE service_case ADD COLUMN display_number bigserial;
CREATE UNIQUE INDEX case_display_number_idx ON service_case(display_number);

-- The author-entered order of items; without it the list came back sorted by random UUID.
ALTER TABLE case_item ADD COLUMN position integer;
UPDATE case_item i SET position = ranked.rn
FROM (SELECT id, row_number() OVER (PARTITION BY case_id ORDER BY id) AS rn FROM case_item) ranked
WHERE ranked.id = i.id;
ALTER TABLE case_item ALTER COLUMN position SET NOT NULL;
ALTER TABLE case_item ADD CONSTRAINT case_item_position_positive CHECK (position > 0);
-- Deferred: a full draft replacement may briefly give two items the same position before commit.
ALTER TABLE case_item ADD CONSTRAINT case_item_position_unique
    UNIQUE (case_id, position) DEFERRABLE INITIALLY DEFERRED;

-- A plan, not a report: nextAction never implies that work happened.
ALTER TABLE case_item ADD COLUMN next_action_text varchar(1000);
ALTER TABLE case_item ADD COLUMN next_action_owner_id uuid REFERENCES app_user(id);
ALTER TABLE case_item ADD COLUMN next_action_update_due_at timestamptz;
ALTER TABLE case_item ADD COLUMN next_action_updated_at timestamptz;
ALTER TABLE case_item ADD CONSTRAINT next_action_is_complete CHECK (
    (next_action_text IS NULL AND next_action_owner_id IS NULL AND next_action_updated_at IS NULL)
    OR (next_action_text IS NOT NULL AND length(trim(next_action_text)) > 0
        AND next_action_owner_id IS NOT NULL AND next_action_updated_at IS NOT NULL));
