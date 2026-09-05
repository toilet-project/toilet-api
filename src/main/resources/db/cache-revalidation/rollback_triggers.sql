-- Manual rollback: disable WEB_CACHE_REVALIDATION_ENABLED and stop the sender first.
-- Retain the outbox table for diagnosis/recovery. This script does not delete queued events.
DROP TRIGGER IF EXISTS cache_toilet_insert;
DROP TRIGGER IF EXISTS cache_toilet_update;
DROP TRIGGER IF EXISTS cache_toilet_delete;
DROP TRIGGER IF EXISTS cache_toilet_region_insert;
DROP TRIGGER IF EXISTS cache_toilet_region_update;
DROP TRIGGER IF EXISTS cache_toilet_region_delete;
