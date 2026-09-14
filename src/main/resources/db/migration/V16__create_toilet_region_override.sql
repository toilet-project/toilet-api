CREATE TABLE toilet_region_override (
    toilet_id BIGINT NOT NULL,
    sido_name VARCHAR(50) NOT NULL,
    sido_code CHAR(2) NOT NULL,
    sigungu_name VARCHAR(100) NULL,
    sigungu_code CHAR(5) NOT NULL,
    city_name VARCHAR(50) NULL,
    district_name VARCHAR(50) NULL,
    note VARCHAR(500) NOT NULL,
    source_latitude DECIMAL(10,7) NULL,
    source_longitude DECIMAL(10,7) NULL,
    source_road_address VARCHAR(255) NULL,
    source_jibun_address VARCHAR(255) NULL,
    confirmed_by_user_id BIGINT NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (toilet_id),
    KEY idx_region_override_sigungu (sigungu_code, toilet_id),
    CONSTRAINT fk_region_override_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id) ON DELETE CASCADE,
    CONSTRAINT fk_region_override_admin FOREIGN KEY (confirmed_by_user_id) REFERENCES app_user (user_id)
);

DROP VIEW current_toilet_region;

-- A manual district decision wins only while the reviewed source position and addresses remain unchanged.
CREATE VIEW current_toilet_region AS
SELECT
    t.toilet_id,
    COALESCE(o.sido_name, r.sido_name) AS sido_name,
    COALESCE(o.sido_code, r.sido_code) AS sido_code,
    COALESCE(o.sigungu_name, r.sigungu_name) AS sigungu_name,
    COALESCE(o.sigungu_code, r.sigungu_code) AS sigungu_code,
    COALESCE(o.city_name, r.city_name) AS city_name,
    COALESCE(o.district_name, r.district_name) AS district_name,
    IF(o.toilet_id IS NULL, r.legal_dong_code, NULL) AS legal_dong_code,
    IF(o.toilet_id IS NULL, r.administrative_dong_code, NULL) AS administrative_dong_code,
    IF(o.toilet_id IS NULL, r.region_source, 'ADMIN_CONFIRMED') AS region_source,
    IF(o.toilet_id IS NULL, r.status, 'VERIFIED') AS status,
    IF(o.toilet_id IS NULL, r.reason, 'ADMIN_REGION_CONFIRMED') AS reason,
    r.source_hash,
    IF(o.toilet_id IS NULL, r.source_latitude, o.source_latitude) AS source_latitude,
    IF(o.toilet_id IS NULL, r.source_longitude, o.source_longitude) AS source_longitude,
    IF(o.toilet_id IS NULL, r.source_road_address, o.source_road_address) AS source_road_address,
    IF(o.toilet_id IS NULL, r.source_jibun_address, o.source_jibun_address) AS source_jibun_address,
    IF(o.toilet_id IS NULL, r.evaluated_latitude, t.latitude) AS evaluated_latitude,
    IF(o.toilet_id IS NULL, r.evaluated_longitude, t.longitude) AS evaluated_longitude,
    r.result_json,
    IF(o.toilet_id IS NULL, r.checked_at, o.confirmed_at) AS checked_at
FROM toilet t
LEFT JOIN toilet_region r ON r.toilet_id = t.toilet_id
LEFT JOIN toilet_region_override o ON o.toilet_id = t.toilet_id
    AND t.latitude <=> o.source_latitude
    AND t.longitude <=> o.source_longitude
    AND BINARY t.road_address <=> BINARY o.source_road_address
    AND BINARY t.jibun_address <=> BINARY o.source_jibun_address
WHERE o.toilet_id IS NOT NULL
   OR (r.status = 'VERIFIED'
       AND t.latitude <=> r.source_latitude
       AND t.longitude <=> r.source_longitude
       AND t.latitude <=> r.evaluated_latitude
       AND t.longitude <=> r.evaluated_longitude
       AND BINARY t.road_address <=> BINARY r.source_road_address
       AND BINARY t.jibun_address <=> BINARY r.source_jibun_address);
