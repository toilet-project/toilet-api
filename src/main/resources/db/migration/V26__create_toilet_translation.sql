-- Localized display text is separated from the canonical public/admin source in toilet.
-- The Korean row mirrors that source; other locales retain the source hash they translated.
CREATE TABLE toilet_translation (
    toilet_id BIGINT NOT NULL,
    locale VARCHAR(12) NOT NULL,
    name VARCHAR(255) NULL,
    road_address VARCHAR(500) NULL,
    jibun_address VARCHAR(500) NULL,
    open_time VARCHAR(255) NULL,
    open_time_detail VARCHAR(500) NULL,
    source_hash CHAR(64) NOT NULL,
    translation_status VARCHAR(24) NOT NULL,
    translation_source VARCHAR(40) NOT NULL,
    manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    translated_at DATETIME NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (toilet_id, locale),
    KEY idx_toilet_translation_locale_status (locale, translation_status, toilet_id),
    KEY idx_toilet_translation_source_hash (toilet_id, source_hash),
    CONSTRAINT fk_toilet_translation_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet (toilet_id) ON DELETE CASCADE
);

INSERT INTO toilet_translation
    (toilet_id, locale, name, road_address, jibun_address, open_time, open_time_detail,
     source_hash, translation_status, translation_source, manual_override,
     translated_at, reviewed_at, created_at, updated_at)
SELECT toilet_id, 'ko', name, road_address, jibun_address, open_time, open_time_detail,
       SHA2(CONCAT(COALESCE(TRIM(name), ''), CHAR(31),
                   COALESCE(TRIM(road_address), ''), CHAR(31),
                   COALESCE(TRIM(jibun_address), ''), CHAR(31),
                   COALESCE(TRIM(open_time), ''), CHAR(31),
                   COALESCE(TRIM(open_time_detail), '')), 256),
       'SOURCE', 'SOURCE', FALSE, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM toilet;

-- Direct batch writes and administrator updates both pass through toilet, so a single-row
-- trigger keeps the Korean mirror current without coupling those writers to this feature.
CREATE TRIGGER toilet_translation_after_insert
AFTER INSERT ON toilet
FOR EACH ROW
INSERT INTO toilet_translation
    (toilet_id, locale, name, road_address, jibun_address, open_time, open_time_detail,
     source_hash, translation_status, translation_source, manual_override,
     translated_at, reviewed_at, created_at, updated_at)
VALUES
    (NEW.toilet_id, 'ko', NEW.name, NEW.road_address, NEW.jibun_address, NEW.open_time, NEW.open_time_detail,
     SHA2(CONCAT(COALESCE(TRIM(NEW.name), ''), CHAR(31),
                 COALESCE(TRIM(NEW.road_address), ''), CHAR(31),
                 COALESCE(TRIM(NEW.jibun_address), ''), CHAR(31),
                 COALESCE(TRIM(NEW.open_time), ''), CHAR(31),
                 COALESCE(TRIM(NEW.open_time_detail), '')), 256),
     'SOURCE', 'SOURCE', FALSE, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    version = IF(source_hash <> VALUES(source_hash), version + 1, version),
    updated_at = IF(source_hash <> VALUES(source_hash), CURRENT_TIMESTAMP, updated_at),
    name = VALUES(name), road_address = VALUES(road_address), jibun_address = VALUES(jibun_address),
    open_time = VALUES(open_time), open_time_detail = VALUES(open_time_detail),
    source_hash = VALUES(source_hash), translation_status = 'SOURCE', translation_source = 'SOURCE';

CREATE TRIGGER toilet_translation_after_update
AFTER UPDATE ON toilet
FOR EACH ROW
INSERT INTO toilet_translation
    (toilet_id, locale, name, road_address, jibun_address, open_time, open_time_detail,
     source_hash, translation_status, translation_source, manual_override,
     translated_at, reviewed_at, created_at, updated_at)
VALUES
    (NEW.toilet_id, 'ko', NEW.name, NEW.road_address, NEW.jibun_address, NEW.open_time, NEW.open_time_detail,
     SHA2(CONCAT(COALESCE(TRIM(NEW.name), ''), CHAR(31),
                 COALESCE(TRIM(NEW.road_address), ''), CHAR(31),
                 COALESCE(TRIM(NEW.jibun_address), ''), CHAR(31),
                 COALESCE(TRIM(NEW.open_time), ''), CHAR(31),
                 COALESCE(TRIM(NEW.open_time_detail), '')), 256),
     'SOURCE', 'SOURCE', FALSE, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    version = IF(source_hash <> VALUES(source_hash), version + 1, version),
    updated_at = IF(source_hash <> VALUES(source_hash), CURRENT_TIMESTAMP, updated_at),
    name = VALUES(name), road_address = VALUES(road_address), jibun_address = VALUES(jibun_address),
    open_time = VALUES(open_time), open_time_detail = VALUES(open_time_detail),
    source_hash = VALUES(source_hash), translation_status = 'SOURCE', translation_source = 'SOURCE';
