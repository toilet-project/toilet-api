-- Manual opt-in. Apply only after the canonical V26 toilet_translation table exists.
-- Korean source rows mirror toilet and are already covered by cache_toilet_update.
-- Non-Korean display changes affect both detail objects and translated marker/catalog labels.
CREATE TRIGGER cache_toilet_translation_insert AFTER INSERT ON toilet_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT NEW.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
WHERE NEW.locale <> 'ko'
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_translation_update AFTER UPDATE ON toilet_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT OLD.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
WHERE OLD.locale <> 'ko'
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_translation_delete AFTER DELETE ON toilet_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT OLD.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
WHERE OLD.locale <> 'ko'
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;
