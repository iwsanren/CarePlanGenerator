-- Messages waiting to be sent to Kafka, written in the same transaction as the business change (transactional
-- outbox). The table stands alone: it is read and written with plain SQL, has no entity and no foreign key (on an
-- empty database care_plans does not exist yet when Flyway runs; Hibernate creates it later).
CREATE TABLE IF NOT EXISTS outbox_event (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    aggregate_id BIGINT       NOT NULL,                 -- the carePlanId
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,                 -- JSON, ids only: never patient data
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    sent_at      TIMESTAMP,                             -- NULL = not sent yet
    attempts     INTEGER      NOT NULL DEFAULT 0,       -- failed send attempts
    last_error   VARCHAR(200)                           -- exception class name only, never its message
);

-- Only unsent rows, in the order the sender takes them: rows that have failed move behind the others.
CREATE INDEX IF NOT EXISTS outbox_event_unsent ON outbox_event (attempts, id) WHERE sent_at IS NULL;
