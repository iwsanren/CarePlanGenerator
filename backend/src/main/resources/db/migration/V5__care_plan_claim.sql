-- Columns for claiming a care plan before generating it (Kafka consumer). Same approach as V3: on an empty database
-- care_plans does not exist yet (Hibernate creates it later, from the CarePlan entity, with the same definitions);
-- on an existing database the columns are added here, and existing rows get the defaults.
-- claim_token:      goes up by one on every claim; a result is only written with the current number.
-- claimed_event_id: the message that made the latest claim.
-- lease_expires_at: until when that claim holds (database clock).
-- reclaim_count:    how often the current request was taken over.
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS claim_token INTEGER NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS claimed_event_id UUID;
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS reclaim_count INTEGER NOT NULL DEFAULT 0;
