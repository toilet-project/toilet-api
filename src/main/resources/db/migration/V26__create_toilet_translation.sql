-- Localized display text is separated from the canonical public/admin source in toilet.
-- The Korean row mirrors that source; other locales retain the source hash they translated.
CREATE TABLE toilet_translation (
    toilet_id BIGINT NOT NULL,
    locale VARCHAR(12) NOT NULL,
    name VARCHAR(255) NULL,
    road_address VARCHAR(500) NULL,
    jibun_address VARCHAR(500) NULL,
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
    (toilet_id, locale, name, road_address, jibun_address,
     source_hash, translation_status, translation_source, manual_override,
     translated_at, reviewed_at, created_at, updated_at)
SELECT toilet_id, 'ko', name, road_address, jibun_address,
       SHA2(CONCAT(COALESCE(TRIM(name), ''), CHAR(31),
                   COALESCE(TRIM(road_address), ''), CHAR(31),
                   COALESCE(TRIM(jibun_address), '')), 256),
       'SOURCE', 'SOURCE', FALSE, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM toilet;
