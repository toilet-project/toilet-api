-- Deployment candidate only: requires migration V23 and cache contract V2.
-- Manual opt-in, NOT executed by Flyway. Validate on MySQL before enabling hiding.
-- Keep the pre-existing INSERT/DELETE/region triggers; replace only UPDATE handling.
DROP TRIGGER IF EXISTS cache_toilet_update;
CREATE TRIGGER cache_toilet_update AFTER UPDATE ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,IF(NEW.visibility_status='VISIBLE','UPSERT','PRIVATE'),
        NOT (OLD.visibility_status <=> NEW.visibility_status),0,
        UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed OR VALUES(catalog_changed),VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;
