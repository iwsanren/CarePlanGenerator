-- Additive migration; Hibernate creates orders later on an empty database.
ALTER TABLE IF EXISTS orders ADD COLUMN IF NOT EXISTS created_by_subject VARCHAR(255) DEFAULT 'legacy';
ALTER TABLE IF EXISTS orders ADD COLUMN IF NOT EXISTS updated_by_subject VARCHAR(255) DEFAULT 'legacy';
CREATE TABLE IF NOT EXISTS audit_events (
 id BIGSERIAL PRIMARY KEY,
 actor_subject VARCHAR(255) NOT NULL,
 action VARCHAR(255) NOT NULL,
 target_id BIGINT,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS audit_events_actor_time ON audit_events(actor_subject, occurred_at);
