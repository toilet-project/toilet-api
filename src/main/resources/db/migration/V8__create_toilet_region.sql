-- Coordinates/addresses in toilet remain the source; this table is disposable derived data.
CREATE TABLE toilet_region (
    toilet_id BIGINT NOT NULL,
    sido_name VARCHAR(50) NULL,
    sido_code CHAR(2) NULL,
    sigungu_name VARCHAR(100) NULL,
    sigungu_code CHAR(5) NULL,
    city_name VARCHAR(50) NULL,
    district_name VARCHAR(50) NULL,
    legal_dong_code CHAR(10) NULL,
    administrative_dong_code CHAR(10) NULL,
    region_source VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    source_latitude DECIMAL(10,7) NULL,
    source_longitude DECIMAL(10,7) NULL,
    source_road_address VARCHAR(255) NULL,
    source_jibun_address VARCHAR(255) NULL,
    evaluated_latitude DECIMAL(10,7) NULL,
    evaluated_longitude DECIMAL(10,7) NULL,
    result_json JSON NOT NULL,
    checked_at DATETIME NOT NULL,
    PRIMARY KEY (toilet_id),
    KEY idx_toilet_region_sido (sido_code, status, toilet_id),
    KEY idx_toilet_region_sigungu (sigungu_code, status, toilet_id),
    KEY idx_toilet_region_city (sido_code, city_name, status, toilet_id),
    CONSTRAINT fk_toilet_region_toilet FOREIGN KEY (toilet_id) REFERENCES toilet (toilet_id) ON DELETE CASCADE
);

-- Consumers must use this view (or equivalent freshness predicates), not unguarded table rows.
-- A coordinate change becomes invisible immediately, before the background worker runs.
CREATE VIEW current_toilet_region AS
SELECT r.* FROM toilet_region r JOIN toilet t ON t.toilet_id = r.toilet_id
WHERE r.status = 'VERIFIED'
  AND t.latitude <=> r.source_latitude AND t.longitude <=> r.source_longitude
  AND t.latitude <=> r.evaluated_latitude AND t.longitude <=> r.evaluated_longitude
  AND BINARY t.road_address <=> BINARY r.source_road_address
  AND BINARY t.jibun_address <=> BINARY r.source_jibun_address;
