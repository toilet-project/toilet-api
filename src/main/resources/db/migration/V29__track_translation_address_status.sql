ALTER TABLE toilet_translation
    ADD COLUMN address_translation_status VARCHAR(24) NOT NULL DEFAULT 'NO_RESULT'
        AFTER translation_source,
    ADD COLUMN address_translation_source VARCHAR(40) NOT NULL DEFAULT 'UNAVAILABLE'
        AFTER address_translation_status,
    ADD KEY idx_toilet_translation_address_status
        (locale, address_translation_status, toilet_id);

UPDATE toilet_translation
   SET address_translation_status = CASE
           WHEN locale = 'ko' THEN 'SOURCE'
           WHEN NULLIF(TRIM(road_address), '') IS NOT NULL
             OR NULLIF(TRIM(jibun_address), '') IS NOT NULL THEN 'TRANSLATED'
           ELSE 'NO_RESULT'
       END,
       address_translation_source = CASE
           WHEN locale = 'ko' THEN 'SOURCE'
           WHEN manual_override = TRUE THEN 'MANUAL'
           WHEN locale = 'en' AND (NULLIF(TRIM(road_address), '') IS NOT NULL
             OR NULLIF(TRIM(jibun_address), '') IS NOT NULL) THEN 'MOIS_JUSO_ORIGINAL'
           WHEN locale = 'en' THEN 'MOIS_JUSO_NO_RESULT'
           ELSE translation_source
       END;
