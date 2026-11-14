-- Additive migration, same approach as V1: Hibernate (ddl-auto=update) creates care_plans later on an
-- empty database, and builds these three columns from the CarePlan entity. On an existing database the
-- columns are added here. IF EXISTS / IF NOT EXISTS keep the script safe in both cases, and when
-- Hibernate has already created the columns.
-- All three columns stay NULL for rows that existed before this migration, for manually uploaded care
-- plans and for plans that were reset for regeneration: NULL means "no data", never 0.
-- The types must match the CarePlan entity: LocalDateTime -> TIMESTAMP, Integer -> INTEGER.
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;
ALTER TABLE IF EXISTS care_plans ADD COLUMN IF NOT EXISTS attempt_count INTEGER;
