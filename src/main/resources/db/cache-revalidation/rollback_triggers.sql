-- Manual rollback: disable WEB_CACHE_REVALIDATION_ENABLED and stop the sender first.
-- Retain the outbox/revision table for diagnosis and safe re-enable. This script deletes no rows.
DROP TRIGGER IF EXISTS cache_toilet_visibility_update;
DROP TRIGGER IF EXISTS cache_toilet_insert;
DROP TRIGGER IF EXISTS cache_toilet_update;
DROP TRIGGER IF EXISTS cache_toilet_delete;
DROP TRIGGER IF EXISTS cache_toilet_region_insert;
DROP TRIGGER IF EXISTS cache_toilet_region_update;
DROP TRIGGER IF EXISTS cache_toilet_region_delete;
DROP TRIGGER IF EXISTS cache_region_assignment_insert;
DROP TRIGGER IF EXISTS cache_region_assignment_update;
DROP TRIGGER IF EXISTS cache_region_assignment_delete;
DROP TRIGGER IF EXISTS cache_region_decision_insert;
DROP TRIGGER IF EXISTS cache_region_decision_update;
DROP TRIGGER IF EXISTS cache_region_decision_delete;
