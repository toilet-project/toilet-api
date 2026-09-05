-- Manual, opt-in installation. NOT in Flyway's automatic db/migration directory.
-- UTC timestamps are explicit operational queue timestamps, not application display time.
CREATE TABLE web_cache_invalidation (
  toilet_id BIGINT NOT NULL PRIMARY KEY,
  event_id CHAR(36) NOT NULL,
  attempts INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  first_queued_at DATETIME(6) NOT NULL,
  last_queued_at DATETIME(6) NOT NULL,
  last_error_code VARCHAR(40) NULL,
  KEY idx_web_cache_due (next_attempt_at, toilet_id)
);
-- No FK: deleted toilets must remain deliverable until their cached pages become 404.

CREATE TRIGGER cache_toilet_insert AFTER INSERT ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (NEW.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

CREATE TRIGGER cache_toilet_update AFTER UPDATE ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (NEW.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

CREATE TRIGGER cache_toilet_delete AFTER DELETE ON toilet
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (OLD.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_insert AFTER INSERT ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (NEW.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_update AFTER UPDATE ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (NEW.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

CREATE TRIGGER cache_toilet_region_delete AFTER DELETE ON toilet_region
FOR EACH ROW
INSERT INTO web_cache_invalidation
(toilet_id,event_id,next_attempt_at,first_queued_at,last_queued_at)
VALUES (OLD.toilet_id,UUID(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE event_id=UUID(), attempts=0, next_attempt_at=UTC_TIMESTAMP(6),
last_queued_at=UTC_TIMESTAMP(6), last_error_code=NULL;

