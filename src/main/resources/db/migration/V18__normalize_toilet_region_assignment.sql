-- Separate canonical region names, current assignments, manual decisions and append-only evidence.
-- region_revision is maintained by the API and batch whenever a source coordinate/address changes.

ALTER TABLE toilet
    ADD COLUMN region_revision BIGINT NOT NULL DEFAULT 1;

-- Preserve a historical code that is absent from the current MOIS list. It stays readable but
-- inactive, so a new administrator confirmation still requires an active canonical code.
INSERT IGNORE INTO region_sigungu_reference
    (sigungu_code,sido_code,sido_name,sigungu_name,city_name,district_name,display_name,
     is_active,source_name,source_checked_on)
SELECT r.sigungu_code,
       COALESCE(MAX(NULLIF(r.sido_code,'')),LEFT(r.sigungu_code,2)),
       COALESCE(MAX(NULLIF(r.sido_name,'')),'미확인'),
       MAX(NULLIF(r.sigungu_name,'')),MAX(NULLIF(r.city_name,'')),MAX(NULLIF(r.district_name,'')),
       CONCAT_WS(' ',COALESCE(MAX(NULLIF(r.sido_name,'')),'미확인'),
                     COALESCE(MAX(NULLIF(r.sigungu_name,'')),r.sigungu_code)),
       0,'LEGACY_TOILET_REGION',CURRENT_DATE
FROM toilet_region r
WHERE r.sigungu_code REGEXP '^[0-9]{5}$'
GROUP BY r.sigungu_code;

-- V8 rows created before append-only history existed are copied once. The new assignment points
-- at evidence instead of carrying another result_json payload.
INSERT INTO toilet_region_assessment_history
    (toilet_id,source_hash,algorithm_version,status,reason,result_json,checked_epoch_millis,checked_at)
SELECT r.toilet_id,r.source_hash,'legacy-current-v1',r.status,r.reason,r.result_json,
       CAST(UNIX_TIMESTAMP(r.checked_at) * 1000 AS SIGNED),r.checked_at
FROM toilet_region r
WHERE NOT EXISTS (
    SELECT 1 FROM toilet_region_assessment_history h
    WHERE h.toilet_id=r.toilet_id AND h.source_hash=r.source_hash
);

CREATE TABLE toilet_region_assignment (
    toilet_id BIGINT NOT NULL,
    sigungu_code CHAR(5) NULL,
    legal_dong_code CHAR(10) NULL,
    administrative_dong_code CHAR(10) NULL,
    region_source VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    evaluated_latitude DECIMAL(10,7) NULL,
    evaluated_longitude DECIMAL(10,7) NULL,
    assessment_id BIGINT NULL,
    checked_at DATETIME NOT NULL,
    PRIMARY KEY (toilet_id),
    KEY idx_region_assignment_review (status,checked_at,toilet_id),
    KEY idx_region_assignment_sigungu (sigungu_code,status,toilet_id),
    KEY idx_region_assignment_assessment (assessment_id),
    CONSTRAINT fk_region_assignment_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet (toilet_id) ON DELETE CASCADE,
    CONSTRAINT fk_region_assignment_assessment FOREIGN KEY (assessment_id)
        REFERENCES toilet_region_assessment_history (assessment_id)
);

INSERT INTO toilet_region_assignment
    (toilet_id,sigungu_code,legal_dong_code,administrative_dong_code,region_source,status,reason,
     source_hash,source_revision,evaluated_latitude,evaluated_longitude,assessment_id,checked_at)
SELECT r.toilet_id,r.sigungu_code,r.legal_dong_code,r.administrative_dong_code,r.region_source,
       r.status,r.reason,r.source_hash,
       IF(t.latitude <=> r.source_latitude
          AND t.longitude <=> r.source_longitude
          AND BINARY t.road_address <=> BINARY r.source_road_address
          AND BINARY t.jibun_address <=> BINARY r.source_jibun_address
          AND (r.status <> 'VERIFIED'
               OR (t.latitude <=> r.evaluated_latitude AND t.longitude <=> r.evaluated_longitude)),
          t.region_revision,0),
       r.evaluated_latitude,r.evaluated_longitude,
       (SELECT h.assessment_id
        FROM toilet_region_assessment_history h
        WHERE h.toilet_id=r.toilet_id AND h.source_hash=r.source_hash
        ORDER BY h.checked_at DESC,h.assessment_id DESC LIMIT 1),
       r.checked_at
FROM toilet_region r
JOIN toilet t ON t.toilet_id=r.toilet_id;

CREATE TABLE toilet_region_decision (
    toilet_id BIGINT NOT NULL,
    sigungu_code CHAR(5) NOT NULL,
    note VARCHAR(500) NOT NULL,
    source_revision BIGINT NOT NULL,
    confirmed_by_user_id BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (toilet_id),
    KEY idx_region_decision_sigungu (sigungu_code,toilet_id),
    CONSTRAINT fk_region_decision_toilet FOREIGN KEY (toilet_id)
        REFERENCES toilet (toilet_id) ON DELETE CASCADE,
    CONSTRAINT fk_region_decision_admin FOREIGN KEY (confirmed_by_user_id)
        REFERENCES app_user (user_id)
);

INSERT INTO toilet_region_decision
    (toilet_id,sigungu_code,note,source_revision,confirmed_by_user_id,confirmed_at)
SELECT o.toilet_id,o.sigungu_code,o.note,
       IF(t.latitude <=> o.source_latitude
          AND t.longitude <=> o.source_longitude
          AND BINARY t.road_address <=> BINARY o.source_road_address
          AND BINARY t.jibun_address <=> BINARY o.source_jibun_address,
          t.region_revision,0),
       o.confirmed_by_user_id,o.confirmed_at
FROM toilet_region_override o
JOIN toilet t ON t.toilet_id=o.toilet_id;

DROP VIEW current_toilet_region;

CREATE VIEW current_toilet_region AS
SELECT t.toilet_id,
       COALESCE(dr.sido_name,ar.sido_name) AS sido_name,
       COALESCE(dr.sido_code,ar.sido_code) AS sido_code,
       COALESCE(dr.sigungu_name,ar.sigungu_name) AS sigungu_name,
       COALESCE(d.sigungu_code,a.sigungu_code) AS sigungu_code,
       COALESCE(dr.city_name,ar.city_name) AS city_name,
       COALESCE(dr.district_name,ar.district_name) AS district_name,
       IF(d.toilet_id IS NULL,a.legal_dong_code,NULL) AS legal_dong_code,
       IF(d.toilet_id IS NULL,a.administrative_dong_code,NULL) AS administrative_dong_code,
       IF(d.toilet_id IS NULL,a.region_source,'ADMIN_CONFIRMED') AS region_source,
       IF(d.toilet_id IS NULL,a.status,'VERIFIED') AS status,
       IF(d.toilet_id IS NULL,a.reason,'ADMIN_REGION_CONFIRMED') AS reason,
       a.source_hash,
       t.latitude AS source_latitude,t.longitude AS source_longitude,
       t.road_address AS source_road_address,t.jibun_address AS source_jibun_address,
       IF(d.toilet_id IS NULL,a.evaluated_latitude,t.latitude) AS evaluated_latitude,
       IF(d.toilet_id IS NULL,a.evaluated_longitude,t.longitude) AS evaluated_longitude,
       h.result_json,
       IF(d.toilet_id IS NULL,a.checked_at,d.confirmed_at) AS checked_at
FROM toilet t
LEFT JOIN toilet_region_assignment a ON a.toilet_id=t.toilet_id
LEFT JOIN toilet_region_decision d ON d.toilet_id=t.toilet_id
    AND d.source_revision=t.region_revision
LEFT JOIN region_sigungu_reference ar ON ar.sigungu_code=a.sigungu_code
LEFT JOIN region_sigungu_reference dr ON dr.sigungu_code=d.sigungu_code
LEFT JOIN toilet_region_assessment_history h ON h.assessment_id=a.assessment_id
WHERE d.toilet_id IS NOT NULL
   OR (a.status='VERIFIED'
       AND a.source_revision=t.region_revision
       AND t.latitude <=> a.evaluated_latitude
       AND t.longitude <=> a.evaluated_longitude);
