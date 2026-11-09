ALTER TABLE patient ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 1;

CREATE TABLE outbox_event (
    id                UUID PRIMARY KEY,
    seq               BIGSERIAL    NOT NULL UNIQUE,
    aggregate_id      UUID         NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    event_type        VARCHAR(50)  NOT NULL,
    payload           TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,
    dead_lettered_at  TIMESTAMPTZ,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT
);

CREATE UNIQUE INDEX uq_outbox_event_aggregate_version ON outbox_event (aggregate_id, aggregate_version);

CREATE INDEX idx_outbox_event_pending ON outbox_event (seq)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
