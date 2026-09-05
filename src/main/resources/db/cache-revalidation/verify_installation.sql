-- Read-only. Select toilet_db in your SQL client before running.
SELECT DATABASE() AS selected_database, CURRENT_USER() AS connected_account,
       @@version AS mysql_version, @@default_storage_engine AS default_engine;

SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('toilet', 'toilet_region', 'web_cache_invalidation');

-- Expected: exactly six rows, AFTER / INSERT, UPDATE, DELETE on each source table.
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, DEFINER
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND TRIGGER_NAME IN (
    'cache_toilet_insert', 'cache_toilet_update', 'cache_toilet_delete',
    'cache_toilet_region_insert', 'cache_toilet_region_update', 'cache_toilet_region_delete'
  )
ORDER BY EVENT_OBJECT_TABLE, EVENT_MANIPULATION;

-- Run after installation. Zero is normal until a real change occurs.
-- Nonzero is also normal while the sender remains disabled.
SELECT COUNT(*) AS pending_count, MIN(first_queued_at) AS oldest_pending_utc
FROM web_cache_invalidation;
