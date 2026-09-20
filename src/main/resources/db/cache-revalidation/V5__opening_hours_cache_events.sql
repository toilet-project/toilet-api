-- Manual opt-in after Flyway V27. Opening-hours changes can alter both detail and filtered catalogs.
CREATE TRIGGER cache_opening_hours_insert AFTER INSERT ON toilet_opening_hours
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_opening_hours_update AFTER UPDATE ON toilet_opening_hours
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_opening_hours_delete AFTER DELETE ON toilet_opening_hours
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

-- Refresh current detail and open24h-filtered catalogs once when the trigger set is installed.
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_opening_hours
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;
