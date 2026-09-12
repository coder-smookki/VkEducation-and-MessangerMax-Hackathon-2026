-- Initial MVP data boundary. Only Java receives these database credentials.
-- UUIDs are generated in Java; no extension is required. No real/demo users are seeded.
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    max_user_id bigint UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE house (
    id uuid PRIMARY KEY,
    address varchar(500) NOT NULL CHECK (length(trim(address)) > 0)
);
CREATE TABLE membership (
    user_id uuid NOT NULL REFERENCES app_user(id),
    house_id uuid NOT NULL REFERENCES house(id),
    role varchar(20) NOT NULL CHECK (role IN ('RESIDENT', 'DISPATCHER', 'EXECUTOR')),
    PRIMARY KEY (user_id, house_id)
);
CREATE TABLE service_case (
    id uuid PRIMARY KEY,
    house_id uuid NOT NULL REFERENCES house(id),
    author_id uuid NOT NULL,
    source_revision integer NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    submission_status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (submission_status IN ('DRAFT', 'CONFIRMED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, author_id),
    FOREIGN KEY (author_id, house_id) REFERENCES membership(user_id, house_id)
);
CREATE INDEX case_house_created_idx ON service_case(house_id, created_at DESC);
CREATE INDEX case_author_idx ON service_case(author_id, created_at DESC);
CREATE TABLE case_revision (
    case_id uuid NOT NULL REFERENCES service_case(id),
    revision integer NOT NULL CHECK (revision > 0),
    source_text varchar(5000) NOT NULL CHECK (length(trim(source_text)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (case_id, revision)
);
ALTER TABLE service_case ADD CONSTRAINT case_current_revision_fk
    FOREIGN KEY (id, source_revision) REFERENCES case_revision(case_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE case_item (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES service_case(id),
    content_version integer NOT NULL DEFAULT 1 CHECK (content_version > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    work_round integer NOT NULL DEFAULT 0 CHECK (work_round >= 0),
    work_status varchar(20) NOT NULL DEFAULT 'NOT_ASSIGNED'
        CHECK (work_status IN ('NOT_ASSIGNED', 'PLANNED', 'REPORTED_DONE')),
    observation varchar(20) NOT NULL DEFAULT 'NOT_RECEIVED'
        CHECK (observation IN ('NOT_RECEIVED', 'RESTORED', 'PERSISTS', 'CANNOT_CHECK')),
    administrative_status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (administrative_status IN ('ACTIVE', 'ARCHIVED', 'WITHDRAWN')),
    administrative_reason varchar(1000),
    UNIQUE (id, case_id),
    CHECK (work_status = 'NOT_ASSIGNED' OR work_round > 0),
    CHECK (administrative_status = 'ACTIVE' OR
           (administrative_reason IS NOT NULL AND length(trim(administrative_reason)) > 0))
);
CREATE INDEX case_item_case_idx ON case_item(case_id);
CREATE TABLE item_revision (
    item_id uuid NOT NULL REFERENCES case_item(id),
    content_version integer NOT NULL CHECK (content_version > 0),
    category varchar(20) NOT NULL CHECK (category IN ('LIGHTING', 'ENTRANCE_DOOR', 'CLEANING', 'OTHER')),
    location varchar(300) NOT NULL CHECK (length(trim(location)) > 0),
    symptom varchar(1000) NOT NULL CHECK (length(trim(symptom)) > 0),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, content_version)
);
ALTER TABLE case_item ADD CONSTRAINT item_current_revision_fk
    FOREIGN KEY (id, content_version) REFERENCES item_revision(item_id, content_version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE work_report (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES service_case(id),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    report_text varchar(5000) NOT NULL CHECK (length(trim(report_text)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, case_id)
);
CREATE TABLE report_item_link (
    report_id uuid NOT NULL,
    item_id uuid NOT NULL,
    case_id uuid NOT NULL,
    content_version integer NOT NULL,
    work_round integer NOT NULL CHECK (work_round > 0),
    work_status varchar(20) NOT NULL CHECK (work_status IN ('PLANNED', 'REPORTED_DONE')),
    confirmed_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (report_id, item_id),
    UNIQUE (item_id, content_version, work_round),
    FOREIGN KEY (report_id, case_id) REFERENCES work_report(id, case_id),
    FOREIGN KEY (item_id, case_id) REFERENCES case_item(id, case_id),
    FOREIGN KEY (item_id, content_version) REFERENCES item_revision(item_id, content_version)
);
CREATE TABLE resident_observation (
    id uuid PRIMARY KEY,
    item_id uuid NOT NULL,
    case_id uuid NOT NULL,
    author_id uuid NOT NULL,
    content_version integer NOT NULL,
    work_round integer NOT NULL CHECK (work_round >= 0),
    value varchar(20) NOT NULL CHECK (value IN ('RESTORED', 'PERSISTS', 'CANNOT_CHECK')),
    source varchar(20) NOT NULL CHECK (source IN ('AFTER_REPORT', 'INDEPENDENT')),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (item_id, case_id) REFERENCES case_item(id, case_id),
    FOREIGN KEY (case_id, author_id) REFERENCES service_case(id, author_id),
    FOREIGN KEY (item_id, content_version) REFERENCES item_revision(item_id, content_version)
);
CREATE INDEX observation_item_idx ON resident_observation(item_id, created_at);
CREATE TABLE model_suggestion (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL,
    report_id uuid NOT NULL,
    source_revision integer NOT NULL,
    model_version varchar(200) NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (report_id, case_id) REFERENCES work_report(id, case_id),
    FOREIGN KEY (case_id, source_revision) REFERENCES case_revision(case_id, revision)
);
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES service_case(id),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_case_idx ON audit_event(case_id, created_at);
CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES service_case(id),
    recipient_id uuid NOT NULL REFERENCES app_user(id),
    event_type varchar(100) NOT NULL,
    deduplication_key varchar(250) NOT NULL UNIQUE,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_pending_idx ON outbox_event(available_at) WHERE sent_at IS NULL;
CREATE TABLE processed_command (
    actor_id uuid NOT NULL REFERENCES app_user(id),
    operation varchar(100) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    response_status integer NOT NULL CHECK (response_status BETWEEN 200 AND 499),
    response_body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, operation, idempotency_key)
);
