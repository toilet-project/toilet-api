-- The five-digit code remains the stable identity. Localized labels belong to the
-- canonical region reference, never to each toilet's derived region assignment.
CREATE TABLE region_sigungu_translation (
    sigungu_code CHAR(5) NOT NULL,
    locale VARCHAR(10) NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    translation_source VARCHAR(30) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (sigungu_code, locale),
    CONSTRAINT fk_region_sigungu_translation_reference
        FOREIGN KEY (sigungu_code) REFERENCES region_sigungu_reference (sigungu_code)
        ON DELETE CASCADE,
    CONSTRAINT chk_region_sigungu_translation_locale
        CHECK (locale IN ('en', 'ja', 'zh-CN', 'zh-TW', 'zh-HK'))
);
