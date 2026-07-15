-- Transactional outbox.
--
-- Written in the same transaction as the entity change it describes, then published to
-- Kafka by the relay in OutboxRelay. This table is the reason the API has no dual write
-- between Postgres and Kafka.

CREATE TABLE outbox_events (
    id             UUID        NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64),
    payload        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0)
);

-- The relay's claim query is the only hot read on this table: unpublished rows, oldest
-- first. A partial index keeps the index the size of the backlog rather than the size of
-- the history, so it stays small and cached even after millions of published rows.
CREATE INDEX ix_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

-- Supports retention cleanup of published rows without scanning the unpublished backlog.
CREATE INDEX ix_outbox_published_at
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;
