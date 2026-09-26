-- S-05: server-side dialog state, and a lease so a crashed worker's event is retried, never lost.
CREATE TABLE bot_dialog (
    user_id uuid PRIMARY KEY REFERENCES app_user(id),
    state varchar(40) NOT NULL
        CHECK (state IN ('IDLE', 'AWAITING_CASE_TEXT', 'AWAITING_OBSERVATION')),
    case_id uuid REFERENCES service_case(id),
    item_id uuid REFERENCES case_item(id),
    content_version integer CHECK (content_version > 0),
    work_round integer CHECK (work_round >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- An observation prompt is worthless without the exact round it was asked about.
    CHECK (state <> 'AWAITING_OBSERVATION' OR (case_id IS NOT NULL AND item_id IS NOT NULL
        AND content_version IS NOT NULL AND work_round IS NOT NULL))
);

ALTER TABLE inbox_event ADD COLUMN leased_until timestamptz;
ALTER TABLE outbox_event ADD COLUMN leased_until timestamptz;
ALTER TABLE outbox_event ADD COLUMN last_error varchar(500);
