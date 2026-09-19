#!/usr/bin/env bash
set -euo pipefail

readonly OBSERVATION_STARTED_AT='2026-09-15 00:00:00'

/home/luha/.local/bin/maintenance-preflight api
docker container inspect toilet-api toilet-batch toilet-mysql >/dev/null

mysql_environment="$(docker container inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-mysql)"
mysql_user="$(sed -n 's/^MYSQL_USER=//p' <<<"$mysql_environment")"
mysql_password="$(sed -n 's/^MYSQL_PASSWORD=//p' <<<"$mysql_environment")"
mysql_database="$(sed -n 's/^MYSQL_DATABASE=//p' <<<"$mysql_environment")"
unset mysql_environment

: "${mysql_user:?missing MySQL application user}"
: "${mysql_password:?missing MySQL application password}"
: "${mysql_database:?missing MySQL database name}"

mysql_readonly() {
  docker exec -i -e MYSQL_PWD="$mysql_password" toilet-mysql \
    mysql --protocol=socket --batch --raw --skip-column-names \
    -u "$mysql_user" "$mysql_database"
}

observed_at="$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S %Z')"
api_state="$(docker container inspect --format '{{.State.Status}}' toilet-api)"
batch_state="$(docker container inspect --format '{{.State.Status}}' toilet-batch)"

cat <<EOF
# 행정구역 레거시 전환 관찰 보고서

- 관찰 시각: $observed_at
- 관찰 시작 기준: $OBSERVATION_STARTED_AT KST
- API 컨테이너: $api_state
- 배치 컨테이너: $batch_state
- 실행 모드: 운영 DB 읽기 전용 SELECT

## Flyway 및 테이블 현황

\`\`\`text
metric\tvalue
EOF

mysql_readonly <<'SQL'
SELECT 'flyway_v17_success', COUNT(*) FROM flyway_schema_history WHERE version='17' AND success=1
UNION ALL SELECT 'flyway_v18_success', COUNT(*) FROM flyway_schema_history WHERE version='18' AND success=1
UNION ALL SELECT 'region_reference_active', COUNT(*) FROM region_sigungu_reference WHERE is_active=1
UNION ALL SELECT 'legacy_region_rows', COUNT(*) FROM toilet_region
UNION ALL SELECT 'new_assignment_rows', COUNT(*) FROM toilet_region_assignment
UNION ALL SELECT 'legacy_override_rows', COUNT(*) FROM toilet_region_override
UNION ALL SELECT 'new_decision_rows', COUNT(*) FROM toilet_region_decision
UNION ALL SELECT 'assessment_history_rows', COUNT(*) FROM toilet_region_assessment_history;
SQL

cat <<'EOF'
```

## 02:00 정기 배치 이력

```text
started_at_kst	status	pages	received	inserted	updated	skipped	failed	total_toilets	duration_ms	error_recorded
EOF

mysql_readonly <<'SQL'
SELECT DATE_FORMAT(started_at,'%Y-%m-%d %H:%i:%s'),status,requested_pages,received_records,
       inserted_records,updated_records,skipped_records,failed_records,total_toilet_count,
       TIMESTAMPDIFF(MICROSECOND,started_at,completed_at) DIV 1000,
       IF(error_message IS NULL OR error_message='',0,1)
FROM batch_sync_history
WHERE job_name='PUBLIC_RESTROOM_SYNC'
  AND trigger_type='SCHEDULED'
  AND started_at >= '2026-09-15 00:00:00'
ORDER BY started_at,id;
SQL

cat <<'EOF'
```

```text
metric	value
EOF

mysql_readonly <<'SQL'
SELECT 'scheduled_total',COUNT(*) FROM batch_sync_history
 WHERE job_name='PUBLIC_RESTROOM_SYNC' AND trigger_type='SCHEDULED' AND started_at >= '2026-09-15 00:00:00'
UNION ALL SELECT 'scheduled_success',COUNT(*) FROM batch_sync_history
 WHERE job_name='PUBLIC_RESTROOM_SYNC' AND trigger_type='SCHEDULED' AND status='SUCCESS' AND started_at >= '2026-09-15 00:00:00'
UNION ALL SELECT 'scheduled_failed',COUNT(*) FROM batch_sync_history
 WHERE job_name='PUBLIC_RESTROOM_SYNC' AND trigger_type='SCHEDULED' AND status<>'SUCCESS' AND started_at >= '2026-09-15 00:00:00'
UNION ALL SELECT 'duration_avg_ms',COALESCE(ROUND(AVG(TIMESTAMPDIFF(MICROSECOND,started_at,completed_at))/1000),0) FROM batch_sync_history
 WHERE job_name='PUBLIC_RESTROOM_SYNC' AND trigger_type='SCHEDULED' AND status='SUCCESS' AND started_at >= '2026-09-15 00:00:00'
UNION ALL SELECT 'duration_max_ms',COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,started_at,completed_at) DIV 1000),0) FROM batch_sync_history
 WHERE job_name='PUBLIC_RESTROOM_SYNC' AND trigger_type='SCHEDULED' AND status='SUCCESS' AND started_at >= '2026-09-15 00:00:00';
SQL

cat <<'EOF'
```

## 레거시·신규 이중 기록 일치도

```text
metric	value
EOF

mysql_readonly <<'SQL'
SELECT 'recent_legacy_region_rows',COUNT(*)
FROM toilet_region l
WHERE l.checked_at >= '2026-09-15 00:00:00'
UNION ALL
SELECT 'recent_assignment_mismatch',COUNT(*)
FROM toilet_region l
LEFT JOIN toilet_region_assignment a ON a.toilet_id=l.toilet_id
WHERE l.checked_at >= '2026-09-15 00:00:00'
  AND (a.toilet_id IS NULL OR NOT (
      l.sigungu_code <=> a.sigungu_code
      AND l.legal_dong_code <=> a.legal_dong_code
      AND l.administrative_dong_code <=> a.administrative_dong_code
      AND BINARY l.region_source <=> BINARY a.region_source
      AND BINARY l.status <=> BINARY a.status
      AND BINARY l.reason <=> BINARY a.reason
      AND BINARY l.source_hash <=> BINARY a.source_hash
      AND l.evaluated_latitude <=> a.evaluated_latitude
      AND l.evaluated_longitude <=> a.evaluated_longitude
      AND l.checked_at <=> a.checked_at))
UNION ALL
SELECT 'recent_legacy_override_rows',COUNT(*)
FROM toilet_region_override o
WHERE o.confirmed_at >= '2026-09-15 00:00:00'
UNION ALL
SELECT 'recent_decision_mismatch',COUNT(*)
FROM toilet_region_override o
LEFT JOIN toilet_region_decision d ON d.toilet_id=o.toilet_id
WHERE o.confirmed_at >= '2026-09-15 00:00:00'
  AND (d.toilet_id IS NULL OR NOT (
      BINARY o.sigungu_code <=> BINARY d.sigungu_code
      AND BINARY o.note <=> BINARY d.note
      AND o.confirmed_by_user_id <=> d.confirmed_by_user_id
      AND o.confirmed_at <=> d.confirmed_at));
SQL

cat <<'EOF'
```

## 참조 무결성·현재 revision 격리

```text
metric	value
EOF

mysql_readonly <<'SQL'
SELECT 'assignment_missing_toilet',COUNT(*)
FROM toilet_region_assignment a LEFT JOIN toilet t ON t.toilet_id=a.toilet_id
WHERE t.toilet_id IS NULL
UNION ALL
SELECT 'assignment_missing_assessment',COUNT(*)
FROM toilet_region_assignment a
LEFT JOIN toilet_region_assessment_history h ON h.assessment_id=a.assessment_id
WHERE a.assessment_id IS NULL OR h.assessment_id IS NULL
UNION ALL
SELECT 'assignment_assessment_mismatch',COUNT(*)
FROM toilet_region_assignment a
JOIN toilet_region_assessment_history h ON h.assessment_id=a.assessment_id
WHERE h.toilet_id<>a.toilet_id OR BINARY h.source_hash<>BINARY a.source_hash
UNION ALL
SELECT 'assignment_unknown_sigungu',COUNT(*)
FROM toilet_region_assignment a
LEFT JOIN region_sigungu_reference r ON r.sigungu_code=a.sigungu_code
WHERE a.sigungu_code IS NOT NULL AND r.sigungu_code IS NULL
UNION ALL
SELECT 'decision_missing_toilet',COUNT(*)
FROM toilet_region_decision d LEFT JOIN toilet t ON t.toilet_id=d.toilet_id
WHERE t.toilet_id IS NULL
UNION ALL
SELECT 'decision_missing_admin',COUNT(*)
FROM toilet_region_decision d LEFT JOIN app_user u ON u.user_id=d.confirmed_by_user_id
WHERE u.user_id IS NULL
UNION ALL
SELECT 'decision_unknown_sigungu',COUNT(*)
FROM toilet_region_decision d
LEFT JOIN region_sigungu_reference r ON r.sigungu_code=d.sigungu_code
WHERE r.sigungu_code IS NULL
UNION ALL
SELECT 'stale_assignment_rows',COUNT(*)
FROM toilet_region_assignment a JOIN toilet t ON t.toilet_id=a.toilet_id
WHERE a.source_revision<>t.region_revision
UNION ALL
SELECT 'stale_decision_rows',COUNT(*)
FROM toilet_region_decision d JOIN toilet t ON t.toilet_id=d.toilet_id
WHERE d.source_revision<>t.region_revision
UNION ALL
SELECT 'current_view_revision_pollution',COUNT(*)
FROM current_toilet_region c
JOIN toilet t ON t.toilet_id=c.toilet_id
LEFT JOIN toilet_region_assignment a ON a.toilet_id=t.toilet_id
LEFT JOIN toilet_region_decision d ON d.toilet_id=t.toilet_id AND d.source_revision=t.region_revision
WHERE d.toilet_id IS NULL
  AND NOT (a.status='VERIFIED' AND a.source_revision=t.region_revision
           AND t.latitude <=> a.evaluated_latitude AND t.longitude <=> a.evaluated_longitude);
SQL

cat <<'EOF'
```

## 조회 성능 표본

```text
metric	value
EOF

mysql_readonly <<'SQL'
SET @legacy_started=SYSDATE(6);
SELECT COUNT(*) INTO @legacy_count
FROM toilet t
LEFT JOIN toilet_region_override o ON o.toilet_id=t.toilet_id
LEFT JOIN toilet_region r ON r.toilet_id=t.toilet_id
WHERE (o.toilet_id IS NOT NULL
       AND t.latitude <=> o.source_latitude AND t.longitude <=> o.source_longitude
       AND BINARY t.road_address <=> BINARY o.source_road_address
       AND BINARY t.jibun_address <=> BINARY o.source_jibun_address)
   OR (o.toilet_id IS NULL AND r.status='VERIFIED'
       AND t.latitude <=> r.source_latitude AND t.longitude <=> r.source_longitude
       AND t.latitude <=> r.evaluated_latitude AND t.longitude <=> r.evaluated_longitude
       AND BINARY t.road_address <=> BINARY r.source_road_address
       AND BINARY t.jibun_address <=> BINARY r.source_jibun_address);
SET @legacy_us=TIMESTAMPDIFF(MICROSECOND,@legacy_started,SYSDATE(6));
SET @current_started=SYSDATE(6);
SELECT COUNT(*) INTO @current_count FROM current_toilet_region;
SET @current_us=TIMESTAMPDIFF(MICROSECOND,@current_started,SYSDATE(6));
SELECT 'legacy_current_count',@legacy_count
UNION ALL SELECT 'new_current_count',@current_count
UNION ALL SELECT 'legacy_query_us',@legacy_us
UNION ALL SELECT 'new_query_us',@current_us;
SQL

cat <<'EOF'
```
EOF

unset mysql_password
