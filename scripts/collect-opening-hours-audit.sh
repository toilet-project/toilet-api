#!/usr/bin/env bash
set -euo pipefail

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user"
test -n "$mysql_password"

mysql_query() {
  docker exec -e MYSQL_PWD="$mysql_password" toilet-mysql \
    mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 --batch --raw \
    -u "$mysql_user" toilet_db \
    -e "START TRANSACTION READ ONLY; $1 ROLLBACK;"
}

cat <<'MARKDOWN'
# 개방시간 운영 데이터 감사

원문 보존, 정형 데이터 적용 범위, 24시간 필터의 보수적 판정을 운영 DB 전체에서 확인한 결과입니다.

## 전체 적용 범위

```text
MARKDOWN
mysql_query "
SELECT COUNT(*) AS toilets,
       SUM(oh.toilet_id IS NOT NULL) AS normalized,
       SUM(oh.toilet_id IS NULL) AS missing_normalized,
       COUNT(DISTINCT SHA2(CONCAT(COALESCE(TRIM(t.open_time), ''), CHAR(31),
           COALESCE(TRIM(t.open_time_detail), '')), 256)) AS raw_patterns,
       SUM(TRIM(COALESCE(t.open_time, '')) = '' AND TRIM(COALESCE(t.open_time_detail, '')) = '') AS both_missing
FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id;
"
printf '%s\n' '```' '' '## 정형 판정 분포' '' '```text'
mysql_query "
SELECT 'normalization_status' AS metric, normalization_status AS item, COUNT(*) AS row_count
FROM toilet_opening_hours GROUP BY normalization_status
UNION ALL
SELECT 'opening_policy', opening_policy, COUNT(*)
FROM toilet_opening_hours GROUP BY opening_policy
UNION ALL
SELECT 'is_open_24h', COALESCE(CAST(is_open_24h AS CHAR), 'NULL'), COUNT(*)
FROM toilet_opening_hours GROUP BY is_open_24h
UNION ALL
SELECT 'holiday_policy', holiday_policy, COUNT(*)
FROM toilet_opening_hours GROUP BY holiday_policy;
"
printf '%s\n' '```' '' '## 공공데이터 운영 구분 분포' '' '```text'
mysql_query "
SELECT COALESCE(NULLIF(TRIM(open_time),''),'<EMPTY>') AS open_time, COUNT(*) AS row_count
FROM toilet GROUP BY COALESCE(NULLIF(TRIM(open_time),''),'<EMPTY>') ORDER BY row_count DESC;
"
printf '%s\n' '```' '' '## 예외 문구와 공개 필터 범위' '' '```text'
mysql_query "
SELECT SUM(CONCAT(COALESCE(t.open_time,''),' ',COALESCE(t.open_time_detail,'')) REGEXP '동절기|하절기|계절') AS seasonal_rows,
       SUM(CONCAT(COALESCE(t.open_time,''),' ',COALESCE(t.open_time_detail,'')) REGEXP '임시|휴관') AS temporary_or_closure_rows,
       SUM(CONCAT(COALESCE(t.open_time,''),' ',COALESCE(t.open_time_detail,'')) REGEXP '공휴일|연중무휴') AS holiday_rows,
       SUM(oh.manual_override=TRUE) AS manual_rows,
       SUM(oh.manual_override=TRUE AND COALESCE(oh.is_open_24h,FALSE)=FALSE AND
           REGEXP_LIKE(COALESCE(NULLIF(TRIM(t.open_time_detail),''),TRIM(t.open_time),''),
            '^(24[[:space:]]*시간([[:space:]]*개방)?|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))$', 'c')) AS manual_24h_source_overrides,
       SUM(oh.source_changed=TRUE) AS source_changed_rows,
       SUM(oh.is_open_24h=TRUE AND oh.normalization_status IN ('PARSED','CONFIRMED') AND oh.source_changed=FALSE) AS filter_eligible
FROM toilet t JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id;
"
printf '%s\n' '```'

printf '%s\n' '' '## 순수 24시간 원문이지만 자동 24시간 판정에서 제외된 유형' '' '```text'
mysql_query "
SELECT COALESCE(NULLIF(TRIM(t.open_time),''),'<EMPTY>') AS open_time,
       COALESCE(NULLIF(TRIM(t.open_time_detail),''),'<EMPTY>') AS open_time_detail,
       oh.opening_policy,COALESCE(CAST(oh.is_open_24h AS CHAR),'NULL') AS is_open_24h,
       oh.normalization_status,COUNT(*) AS row_count
FROM toilet t JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
WHERE oh.manual_override=FALSE AND COALESCE(oh.is_open_24h,FALSE)=FALSE AND
      REGEXP_LIKE(COALESCE(NULLIF(TRIM(t.open_time_detail),''),TRIM(t.open_time),''),
       '^(24[[:space:]]*시간([[:space:]]*개방)?|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))$', 'c')
GROUP BY open_time,open_time_detail,oh.opening_policy,oh.is_open_24h,oh.normalization_status
ORDER BY row_count DESC LIMIT 100;
"
printf '%s\n' '```'

quality="$(mysql_query "
SELECT SUM(oh.toilet_id IS NULL) AS missing_normalized,
       SUM(oh.is_open_24h=TRUE AND oh.manual_override=FALSE AND NOT
           REGEXP_LIKE(CONCAT(COALESCE(t.open_time,''),' ',COALESCE(t.open_time_detail,'')),
            '(^|[^0-9])(24[[:space:]]*시간|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))([^0-9]|$)', 'c')) AS auto_true_without_explicit_source,
       SUM(oh.manual_override=FALSE AND COALESCE(oh.is_open_24h,FALSE)=FALSE AND
           REGEXP_LIKE(COALESCE(NULLIF(TRIM(t.open_time_detail),''),TRIM(t.open_time),''),
            '^(24[[:space:]]*시간([[:space:]]*개방)?|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))$', 'c')) AS explicit_24h_not_true_without_exception,
       SUM(oh.opening_policy='SCHEDULED' AND NOT EXISTS
           (SELECT 1 FROM toilet_opening_schedule s WHERE s.toilet_id=oh.toilet_id)) AS scheduled_without_slots,
       SUM(oh.opening_policy<>'SCHEDULED' AND EXISTS
           (SELECT 1 FROM toilet_opening_schedule s WHERE s.toilet_id=oh.toilet_id)) AS non_scheduled_with_slots,
       SUM(oh.opening_policy='SCHEDULED' AND oh.is_open_24h=TRUE) AS scheduled_marked_24h
FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id;
" | tail -n 1)"
IFS=$'\t' read -r missing_normalized auto_true_without_explicit explicit_24h_not_true scheduled_without_slots non_scheduled_with_slots scheduled_marked_24h <<< "$quality"

cat <<MARKDOWN

## 품질 게이트

| 검사 | 오류 건수 |
| --- | ---: |
| 정형 행 누락 | $missing_normalized |
| 관리자 확정이 아닌 24시간 판정에 명시적 근거 없음 | $auto_true_without_explicit |
| 관리자 확정이 아닌 순수 24시간 원문이 24시간으로 판정되지 않음 | $explicit_24h_not_true |
| 요일별 운영인데 일정 없음 | $scheduled_without_slots |
| 요일별 운영이 아닌데 일정이 남음 | $non_scheduled_with_slots |
| 요일별 운영과 24시간 판정이 동시에 설정됨 | $scheduled_marked_24h |
MARKDOWN

for issue_count in "$missing_normalized" "$auto_true_without_explicit" "$explicit_24h_not_true" \
                   "$scheduled_without_slots" "$non_scheduled_with_slots" "$scheduled_marked_24h"; do
  test "$issue_count" = '0'
done
