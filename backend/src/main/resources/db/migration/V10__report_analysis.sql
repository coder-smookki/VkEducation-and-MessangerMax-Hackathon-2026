-- S-06: a saved report queues its analysis in the same transaction; Python is called later, outside it.
-- Only three states are stored. RUNNING is a queued row under a live lease, and STALE is a ready row
-- whose snapshot no longer matches the case: both are derived when read, so no command can forget them.
CREATE TABLE report_analysis (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL UNIQUE,
    case_id uuid NOT NULL,
    source_revision integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'READY', 'MANUAL_REQUIRED')),
    suggestion_id uuid REFERENCES model_suggestion(id),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    leased_until timestamptz,
    leased_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (report_id, case_id) REFERENCES work_report(id, case_id),
    -- A ready analysis always points at the result it was built from.
    CHECK (status <> 'READY' OR suggestion_id IS NOT NULL)
);
CREATE INDEX report_analysis_pending_idx ON report_analysis(available_at) WHERE status = 'QUEUED';
