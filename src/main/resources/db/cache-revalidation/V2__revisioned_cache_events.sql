-- Manual opt-in upgrade. Apply before WEB_CACHE_CONTRACT_VERSION=2.
-- Delivered rows are retained as the monotonic revision ledger; they contain no personal data.
ALTER TABLE web_cache_invalidation
  ADD COLUMN revision BIGINT UNSIGNED NOT NULL DEFAULT 1 AFTER event_id,
  ADD COLUMN action VARCHAR(10) NOT NULL DEFAULT 'UPSERT' AFTER revision,
  ADD COLUMN catalog_changed BOOLEAN NOT NULL DEFAULT FALSE AFTER action,
  ADD COLUMN delivered_at DATETIME(6) NULL AFTER last_queued_at,
  ADD CONSTRAINT chk_web_cache_action CHECK (action IN ('UPSERT','DELETE','PRIVATE')),
  ADD KEY idx_web_cache_delivery (delivered_at, next_attempt_at, toilet_id);

-- The exact cause of a pre-upgrade pending event is unavailable. Conservatively refresh the catalog once.
UPDATE web_cache_invalidation SET catalog_changed=TRUE WHERE delivered_at IS NULL;

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

CREATE TRIGGER cache_toilet_insert AFTER INSERT ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed OR VALUES(catalog_changed),VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_update AFTER UPDATE ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed OR VALUES(catalog_changed),VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_delete AFTER DELETE ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'DELETE',TRUE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=TRUE,revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_insert AFTER INSERT ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_update AFTER UPDATE ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_delete AFTER DELETE ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE
first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),
revision=revision+1,event_id=VALUES(event_id),action=VALUES(action),attempts=0,
next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_assignment_insert AFTER INSERT ON toilet_region_assignment
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_assignment_update AFTER UPDATE ON toilet_region_assignment
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_assignment_delete AFTER DELETE ON toilet_region_assignment
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_decision_insert AFTER INSERT ON toilet_region_decision
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_decision_update AFTER UPDATE ON toilet_region_decision
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (NEW.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;

CREATE TRIGGER cache_region_decision_delete AFTER DELETE ON toilet_region_decision
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,revision,action,catalog_changed,attempts,next_attempt_at,first_queued_at,last_queued_at,delivered_at,last_error_code)
VALUES (OLD.toilet_id,UUID(),1,'UPSERT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
ON DUPLICATE KEY UPDATE first_queued_at=IF(delivered_at IS NULL,first_queued_at,VALUES(first_queued_at)),
catalog_changed=IF(delivered_at IS NULL,catalog_changed,VALUES(catalog_changed)),revision=revision+1,event_id=VALUES(event_id),
action=VALUES(action),attempts=0,next_attempt_at=VALUES(next_attempt_at),last_queued_at=VALUES(last_queued_at),delivered_at=NULL,last_error_code=NULL;
