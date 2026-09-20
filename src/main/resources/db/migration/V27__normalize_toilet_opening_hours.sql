-- Raw public-data text remains in toilet.open_time/open_time_detail.
-- These tables contain language-neutral search and display facts only.
CREATE TABLE toilet_opening_hours (
    toilet_id BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    opening_policy VARCHAR(24) NOT NULL,
    is_open_24h BOOLEAN NULL,
    normalization_status VARCHAR(24) NOT NULL,
    confidence DECIMAL(5,4) NULL,
    parser_version VARCHAR(20) NOT NULL,
    holiday_policy VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    source_changed BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by_user_id BIGINT NULL,
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (toilet_id),
    KEY idx_toilet_opening_hours_24h (is_open_24h, normalization_status, source_changed, toilet_id),
    KEY idx_toilet_opening_hours_review (normalization_status, toilet_id),
    CONSTRAINT fk_toilet_opening_hours_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet (toilet_id) ON DELETE CASCADE
);

CREATE TABLE toilet_opening_schedule (
    schedule_id BIGINT NOT NULL AUTO_INCREMENT,
    toilet_id BIGINT NOT NULL,
    day_of_week TINYINT UNSIGNED NOT NULL,
    slot_index TINYINT UNSIGNED NOT NULL DEFAULT 0,
    start_time TIME NULL,
    end_time TIME NULL,
    crosses_midnight BOOLEAN NOT NULL DEFAULT FALSE,
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (schedule_id),
    UNIQUE KEY uk_toilet_opening_schedule_slot (toilet_id, day_of_week, slot_index),
    KEY idx_toilet_opening_schedule_day (day_of_week, start_time, end_time, toilet_id),
    CONSTRAINT fk_toilet_opening_schedule_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet (toilet_id) ON DELETE CASCADE,
    CONSTRAINT chk_toilet_opening_schedule_day CHECK (day_of_week BETWEEN 1 AND 7)
);

-- A conservative first backfill. The Java normalizer refines these rows after deployment.
INSERT INTO toilet_opening_hours
    (toilet_id, source_hash, opening_policy, is_open_24h, normalization_status,
     confidence, parser_version, holiday_policy, manual_override, source_changed,
     created_at, updated_at)
SELECT toilet_id,
       SHA2(CONCAT(COALESCE(TRIM(open_time), ''), CHAR(31),
                   COALESCE(TRIM(open_time_detail), '')), 256),
       CASE
           WHEN TRIM(COALESCE(open_time_detail, open_time, '')) IN
                ('24시간', '24 시간', '00:00~24:00', '00:00-24:00', '00~24') THEN 'ALWAYS'
           WHEN TRIM(COALESCE(open_time, '')) = '미개방'
                AND TRIM(COALESCE(open_time_detail, '')) = '' THEN 'CLOSED'
           ELSE 'UNKNOWN'
       END,
       CASE
           WHEN TRIM(COALESCE(open_time_detail, open_time, '')) IN
                ('24시간', '24 시간', '00:00~24:00', '00:00-24:00', '00~24') THEN TRUE
           WHEN TRIM(COALESCE(open_time, '')) = '미개방'
                AND TRIM(COALESCE(open_time_detail, '')) = '' THEN FALSE
           ELSE NULL
       END,
       CASE
           WHEN TRIM(COALESCE(open_time_detail, open_time, '')) IN
                ('24시간', '24 시간', '00:00~24:00', '00:00-24:00', '00~24') THEN 'PARSED'
           WHEN TRIM(COALESCE(open_time, '')) = '미개방'
                AND TRIM(COALESCE(open_time_detail, '')) = '' THEN 'PARSED'
           ELSE 'REVIEW_REQUIRED'
       END,
       CASE
           WHEN TRIM(COALESCE(open_time_detail, open_time, '')) IN
                ('24시간', '24 시간', '00:00~24:00', '00:00-24:00', '00~24') THEN 1.0000
           WHEN TRIM(COALESCE(open_time, '')) = '미개방'
                AND TRIM(COALESCE(open_time_detail, '')) = '' THEN 0.9500
           ELSE NULL
       END,
       'bootstrap-v1',
       CASE WHEN CONCAT(COALESCE(open_time, ''), ' ', COALESCE(open_time_detail, '')) LIKE '%연중무휴%'
            THEN 'OPEN' ELSE 'UNKNOWN' END,
       FALSE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM toilet;
