-- Read-only. Select toilet_db in your SQL client before running.
SELECT DATABASE() AS selected_database, CURRENT_USER() AS connected_account,
       @@version AS mysql_version, @@default_storage_engine AS default_engine;

SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('toilet', 'toilet_region', 'web_cache_invalidation');

-- V2 expected: exactly twelve rows, AFTER / INSERT, UPDATE, DELETE on each source table.
-- V3 additionally includes cache_toilet_visibility_update after cache_toilet_update.
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, DEFINER
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND TRIGGER_NAME IN (
    'cache_toilet_insert', 'cache_toilet_update', 'cache_toilet_delete', 'cache_toilet_visibility_update',
    'cache_toilet_region_insert', 'cache_toilet_region_update', 'cache_toilet_region_delete',
    'cache_region_assignment_insert', 'cache_region_assignment_update', 'cache_region_assignment_delete',
    'cache_region_decision_insert', 'cache_region_decision_update', 'cache_region_decision_delete'
  )
ORDER BY EVENT_OBJECT_TABLE, EVENT_MANIPULATION;

-- Run after installation. Zero is normal until a real change occurs.
-- Nonzero is also normal while the sender remains disabled.
SELECT COUNT(*) AS pending_count, MIN(first_queued_at) AS oldest_pending_utc
FROM web_cache_invalidation
WHERE delivered_at IS NULL;

-- V2: delivered rows remain as a per-toilet monotonic revision ledger.
SELECT action, catalog_changed, delivered_at IS NULL AS pending, COUNT(*) AS rows_count,
       MIN(revision) AS minimum_revision, MAX(revision) AS maximum_revision
FROM web_cache_invalidation
GROUP BY action, catalog_changed, delivered_at IS NULL;
