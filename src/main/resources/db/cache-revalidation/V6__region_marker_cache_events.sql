-- Manual opt-in after V30 and cache-revalidation V5. Install before the web's 30-day region cache.
-- A map change queues member IDs in the existing transactional outbox. The web invalidates
-- region markers for every event; catalog_changed is reserved for sitemap-relevant source edits.
-- Region scope stays incomplete for pre-install pending rows, so the v3 receiver can safely
-- fall back to global invalidation. A newly queued event starts a complete coordinate envelope.
ALTER TABLE web_cache_invalidation
  ADD COLUMN region_scope_complete BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN region_west DECIMAL(10,7) NULL,
  ADD COLUMN region_south DECIMAL(10,7) NULL,
  ADD COLUMN region_east DECIMAL(10,7) NULL,
  ADD COLUMN region_north DECIMAL(10,7) NULL;

CREATE TRIGGER cache_outbox_region_insert BEFORE INSERT ON web_cache_invalidation
FOR EACH ROW SET NEW.region_scope_complete=TRUE;

CREATE TRIGGER cache_outbox_region_update BEFORE UPDATE ON web_cache_invalidation
FOR EACH ROW SET
  NEW.region_scope_complete=IF(NEW.event_id <=> OLD.event_id,NEW.region_scope_complete,
    IF(OLD.delivered_at IS NOT NULL,TRUE,OLD.region_scope_complete)),
  NEW.region_west=IF(NEW.event_id <=> OLD.event_id,NEW.region_west,
    IF(OLD.delivered_at IS NOT NULL,NULL,OLD.region_west)),
  NEW.region_south=IF(NEW.event_id <=> OLD.event_id,NEW.region_south,
    IF(OLD.delivered_at IS NOT NULL,NULL,OLD.region_south)),
  NEW.region_east=IF(NEW.event_id <=> OLD.event_id,NEW.region_east,
    IF(OLD.delivered_at IS NOT NULL,NULL,OLD.region_east)),
  NEW.region_north=IF(NEW.event_id <=> OLD.event_id,NEW.region_north,
    IF(OLD.delivered_at IS NOT NULL,NULL,OLD.region_north));

CREATE TRIGGER cache_toilet_sitemap_update AFTER UPDATE ON toilet
FOR EACH ROW FOLLOWS cache_toilet_visibility_update
UPDATE web_cache_invalidation
SET catalog_changed=catalog_changed OR NOT (
    OLD.name <=> NEW.name AND OLD.latitude <=> NEW.latitude
    AND OLD.longitude <=> NEW.longitude
    AND OLD.road_address <=> NEW.road_address
    AND OLD.jibun_address <=> NEW.jibun_address),
    region_west=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      LEAST(COALESCE(region_west,OLD.longitude),OLD.longitude),region_west),
    region_south=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      LEAST(COALESCE(region_south,OLD.latitude),OLD.latitude),region_south),
    region_east=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      GREATEST(COALESCE(region_east,OLD.longitude),OLD.longitude),region_east),
    region_north=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      GREATEST(COALESCE(region_north,OLD.latitude),OLD.latitude),region_north)
WHERE toilet_id=NEW.toilet_id;

-- MySQL foreign-key cascades do not fire child-row triggers, so capture a deletion here.
CREATE TRIGGER cache_toilet_scope_delete AFTER DELETE ON toilet
FOR EACH ROW FOLLOWS cache_toilet_delete
UPDATE web_cache_invalidation
SET region_west=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      LEAST(COALESCE(region_west,OLD.longitude),OLD.longitude),region_west),
    region_south=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      LEAST(COALESCE(region_south,OLD.latitude),OLD.latitude),region_south),
    region_east=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      GREATEST(COALESCE(region_east,OLD.longitude),OLD.longitude),region_east),
    region_north=IF(OLD.latitude IS NOT NULL AND OLD.longitude IS NOT NULL,
      GREATEST(COALESCE(region_north,OLD.latitude),OLD.latitude),region_north)
WHERE toilet_id=OLD.toilet_id;

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
