-- W7 stage-1 image rollback only. Run with API and batch writes paused and a verified backup.
-- The normalized tables remain authoritative; do not run after the legacy tables are dropped.
-- A failed guard or DML statement requires ROLLBACK on the same connection.
CREATE TEMPORARY TABLE region_rollback_guard (
    ok TINYINT NOT NULL CHECK (ok = 1)
);

INSERT INTO region_rollback_guard (ok)
SELECT IF(COUNT(*) = 0, 1, 0)
FROM toilet_region_assignment a
JOIN toilet t ON t.toilet_id = a.toilet_id AND a.source_revision = t.region_revision
LEFT JOIN toilet_region_assessment_history h ON h.assessment_id = a.assessment_id
LEFT JOIN region_sigungu_reference r ON r.sigungu_code = a.sigungu_code
WHERE h.assessment_id IS NULL OR h.result_json IS NULL OR h.source_hash <> a.source_hash
   OR (a.sigungu_code IS NOT NULL AND r.sigungu_code IS NULL);

INSERT INTO region_rollback_guard (ok)
SELECT IF(COUNT(*) = 0, 1, 0)
FROM toilet_region_decision d
JOIN toilet t ON t.toilet_id = d.toilet_id AND d.source_revision = t.region_revision
LEFT JOIN region_sigungu_reference r ON r.sigungu_code = d.sigungu_code
WHERE r.sigungu_code IS NULL;

START TRANSACTION;

INSERT INTO toilet_region
    (toilet_id,sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name,
     legal_dong_code,administrative_dong_code,region_source,status,reason,source_hash,
     source_latitude,source_longitude,source_road_address,source_jibun_address,
     evaluated_latitude,evaluated_longitude,result_json,checked_at)
SELECT a.toilet_id,r.sido_name,r.sido_code,r.sigungu_name,a.sigungu_code,r.city_name,r.district_name,
       a.legal_dong_code,a.administrative_dong_code,a.region_source,a.status,a.reason,a.source_hash,
       t.latitude,t.longitude,t.road_address,t.jibun_address,
       a.evaluated_latitude,a.evaluated_longitude,h.result_json,a.checked_at
FROM toilet_region_assignment a
JOIN toilet t ON t.toilet_id = a.toilet_id AND a.source_revision = t.region_revision
JOIN toilet_region_assessment_history h ON h.assessment_id = a.assessment_id
LEFT JOIN region_sigungu_reference r ON r.sigungu_code = a.sigungu_code
WHERE h.result_json IS NOT NULL AND h.source_hash = a.source_hash
ON DUPLICATE KEY UPDATE
    sido_name=VALUES(sido_name),sido_code=VALUES(sido_code),sigungu_name=VALUES(sigungu_name),
    sigungu_code=VALUES(sigungu_code),city_name=VALUES(city_name),district_name=VALUES(district_name),
    legal_dong_code=VALUES(legal_dong_code),administrative_dong_code=VALUES(administrative_dong_code),
    region_source=VALUES(region_source),status=VALUES(status),reason=VALUES(reason),
    source_hash=VALUES(source_hash),source_latitude=VALUES(source_latitude),source_longitude=VALUES(source_longitude),
    source_road_address=VALUES(source_road_address),source_jibun_address=VALUES(source_jibun_address),
    evaluated_latitude=VALUES(evaluated_latitude),evaluated_longitude=VALUES(evaluated_longitude),
    result_json=VALUES(result_json),checked_at=VALUES(checked_at);

INSERT INTO toilet_region_override
    (toilet_id,sido_name,sido_code,sigungu_name,sigungu_code,city_name,district_name,note,
     source_latitude,source_longitude,source_road_address,source_jibun_address,
     confirmed_by_user_id,confirmed_at)
SELECT d.toilet_id,r.sido_name,r.sido_code,r.sigungu_name,d.sigungu_code,r.city_name,r.district_name,d.note,
       t.latitude,t.longitude,t.road_address,t.jibun_address,d.confirmed_by_user_id,d.confirmed_at
FROM toilet_region_decision d
JOIN toilet t ON t.toilet_id = d.toilet_id AND d.source_revision = t.region_revision
JOIN region_sigungu_reference r ON r.sigungu_code = d.sigungu_code
WHERE r.sigungu_code IS NOT NULL
ON DUPLICATE KEY UPDATE
    sido_name=VALUES(sido_name),sido_code=VALUES(sido_code),sigungu_name=VALUES(sigungu_name),
    sigungu_code=VALUES(sigungu_code),city_name=VALUES(city_name),district_name=VALUES(district_name),
    note=VALUES(note),source_latitude=VALUES(source_latitude),source_longitude=VALUES(source_longitude),
    source_road_address=VALUES(source_road_address),source_jibun_address=VALUES(source_jibun_address),
    confirmed_by_user_id=VALUES(confirmed_by_user_id),confirmed_at=VALUES(confirmed_at);

-- A nonzero count fails the CHECK guard before COMMIT; stop and ROLLBACK on this connection.
SELECT COUNT(*) AS assignment_mismatch
FROM toilet_region_assignment a
JOIN toilet t ON t.toilet_id = a.toilet_id AND a.source_revision = t.region_revision
LEFT JOIN toilet_region l ON l.toilet_id = a.toilet_id
WHERE l.toilet_id IS NULL OR NOT (l.source_hash <=> a.source_hash)
   OR NOT (l.status <=> a.status) OR NOT (l.sigungu_code <=> a.sigungu_code);

INSERT INTO region_rollback_guard (ok)
SELECT IF(COUNT(*) = 0, 1, 0)
FROM toilet_region_assignment a
JOIN toilet t ON t.toilet_id = a.toilet_id AND a.source_revision = t.region_revision
LEFT JOIN toilet_region l ON l.toilet_id = a.toilet_id
WHERE l.toilet_id IS NULL OR NOT (l.source_hash <=> a.source_hash)
   OR NOT (l.status <=> a.status) OR NOT (l.sigungu_code <=> a.sigungu_code);

SELECT COUNT(*) AS decision_mismatch
FROM toilet_region_decision d
JOIN toilet t ON t.toilet_id = d.toilet_id AND d.source_revision = t.region_revision
LEFT JOIN toilet_region_override l ON l.toilet_id = d.toilet_id
WHERE l.toilet_id IS NULL OR NOT (l.sigungu_code <=> d.sigungu_code)
   OR NOT (l.note <=> d.note);

INSERT INTO region_rollback_guard (ok)
SELECT IF(COUNT(*) = 0, 1, 0)
FROM toilet_region_decision d
JOIN toilet t ON t.toilet_id = d.toilet_id AND d.source_revision = t.region_revision
LEFT JOIN toilet_region_override l ON l.toilet_id = d.toilet_id
WHERE l.toilet_id IS NULL OR NOT (l.sigungu_code <=> d.sigungu_code)
   OR NOT (l.note <=> d.note);

COMMIT;
DROP TEMPORARY TABLE region_rollback_guard;
