-- Contador singleton: se bloquea con SELECT ... FOR UPDATE hasta el commit para asignar deliveryCursor
-- en orden de commit y sin huecos por rollback (ADR-0002).
CREATE TABLE medical_alert_cursor (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_cursor BIGINT   NOT NULL DEFAULT 0
);

INSERT INTO medical_alert_cursor (id, last_cursor) VALUES (1, 0);

CREATE TABLE medical_alert (
    alert_id        UUID PRIMARY KEY,
    delivery_cursor BIGINT      NOT NULL UNIQUE,
    patient_id      UUID        NOT NULL UNIQUE REFERENCES patient (id),
    level           VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
