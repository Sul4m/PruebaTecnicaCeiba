CREATE TABLE patient (
    id                UUID PRIMARY KEY,
    full_name         VARCHAR(100)  NOT NULL,
    age               SMALLINT      NOT NULL,
    chief_complaint   VARCHAR(200)  NOT NULL,
    heart_rate        SMALLINT      NOT NULL,
    systolic          SMALLINT      NOT NULL,
    diastolic         SMALLINT      NOT NULL,
    spo2              SMALLINT      NOT NULL,
    temperature       NUMERIC       NOT NULL,
    pain              SMALLINT      NOT NULL,
    gcs               SMALLINT      NOT NULL,
    level             VARCHAR(20)   NOT NULL,
    sla_minutes       INTEGER       NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    escalated         BOOLEAN       NOT NULL DEFAULT FALSE,
    registered_at     TIMESTAMPTZ   NOT NULL,
    status_changed_at TIMESTAMPTZ   NOT NULL
);

CREATE TABLE audit_event (
    id              UUID PRIMARY KEY,
    patient_id      UUID         NOT NULL REFERENCES patient (id),
    previous_status VARCHAR(20),
    new_status      VARCHAR(20)  NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_event_patient ON audit_event (patient_id);
