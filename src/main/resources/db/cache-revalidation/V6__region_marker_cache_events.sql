-- Manual opt-in after V30 and cache-revalidation V5. Install before the web's 30-day region cache.
-- A map change queues member IDs in the existing transactional outbox. The web invalidates
-- region markers for every event; catalog_changed is reserved for sitemap-relevant source edits.
CREATE TRIGGER cache_toilet_sitemap_update AFTER UPDATE ON toilet
FOR EACH ROW FOLLOWS cache_toilet_visibility_update
UPDATE web_cache_invalidation
SET catalog_changed=catalog_changed OR NOT (
    OLD.name <=> NEW.name AND OLD.latitude <=> NEW.latitude
    AND OLD.longitude <=> NEW.longitude
    AND OLD.road_address <=> NEW.road_address
    AND OLD.jibun_address <=> NEW.jibun_address)
WHERE toilet_id=NEW.toilet_id;

CREATE TRIGGER cache_group_member_insert AFTER INSERT ON toilet_display_group_member
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_member_delete AFTER DELETE ON toilet_display_group_member
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_member_update_old AFTER UPDATE ON toilet_display_group_member
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_member_update_new AFTER UPDATE ON toilet_display_group_member
FOR EACH ROW FOLLOWS cache_group_member_update_old
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_display_group_update AFTER UPDATE ON toilet_display_group
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT m.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_display_group_member m WHERE m.group_id=NEW.group_id
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

-- Foreign-key cascades do not invoke member-row triggers in MySQL.
CREATE TRIGGER cache_display_group_delete BEFORE DELETE ON toilet_display_group
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT m.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_display_group_member m WHERE m.group_id=OLD.group_id
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_translation_insert AFTER INSERT ON toilet_display_group_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT m.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_display_group_member m WHERE m.group_id=NEW.group_id
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_translation_update AFTER UPDATE ON toilet_display_group_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT m.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_display_group_member m WHERE m.group_id=NEW.group_id
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_group_translation_delete AFTER DELETE ON toilet_display_group_translation
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
SELECT m.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL
FROM toilet_display_group_member m WHERE m.group_id=OLD.group_id
ON DUPLICATE KEY UPDATE
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
revision=revision+1,event_id=VALUES(event_id),action='UPSERT',attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;
