#!/usr/bin/env bash
set -euo pipefail

mode="${1:?plan, batch, or audit is required}"
batch_size="${TRANSLATION_ADDRESS_BATCH_SIZE:-500}"
case "$mode" in plan|batch|audit) ;; *) echo 'mode must be plan, batch, or audit' >&2; exit 2;; esac
case "$batch_size" in ''|*[!0-9]*) echo 'batch size must be an integer' >&2; exit 2;; esac
test "$batch_size" -ge 1 && test "$batch_size" -le 500

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user" && test -n "$mysql_password"

current_scope_sql="
  FROM toilet t
  JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
  JOIN toilet_translation en ON en.toilet_id=t.toilet_id AND en.locale='en'
                             AND en.source_hash=ko.source_hash
  LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
 WHERE t.visibility_status='VISIBLE'
   AND ko.source_hash=SHA2(CONCAT(COALESCE(TRIM(t.name), ''), CHAR(31),
                                 COALESCE(TRIM(t.road_address), ''), CHAR(31),
                                 COALESCE(TRIM(t.jibun_address), '')), 256)"

target_sql="${current_scope_sql}
   AND en.manual_override=FALSE
   AND en.address_translation_status='NO_RESULT'
   AND NULLIF(TRIM(en.name), '') IS NOT NULL
   AND NULLIF(TRIM(en.road_address), '') IS NULL
   AND NULLIF(TRIM(en.jibun_address), '') IS NULL
   AND COALESCE(NULLIF(TRIM(ko.road_address), ''), NULLIF(TRIM(ko.jibun_address), '')) IS NOT NULL"

if test "$mode" = plan; then
  sql="SELECT JSON_OBJECT(
         'targetCount',COUNT(*),
         'roadSourceCount',COALESCE(SUM(NULLIF(TRIM(ko.road_address), '') IS NOT NULL),0),
         'jibunSourceCount',COALESCE(SUM(NULLIF(TRIM(ko.road_address), '') IS NULL AND NULLIF(TRIM(ko.jibun_address), '') IS NOT NULL),0),
         'validCoordinateCount',COALESCE(SUM(t.latitude BETWEEN 33 AND 39.5 AND t.longitude BETWEEN 124 AND 132),0),
         'invalidCoordinateCount',COALESCE(SUM(NOT(t.latitude BETWEEN 33 AND 39.5 AND t.longitude BETWEEN 124 AND 132) OR t.latitude IS NULL OR t.longitude IS NULL),0)
       ) ${target_sql};"
elif test "$mode" = batch; then
  sql="SELECT JSON_OBJECT(
         'toiletId',t.toilet_id,
         'name',TRIM(en.name),
         'roadAddress',NULLIF(TRIM(ko.road_address), ''),
         'jibunAddress',NULLIF(TRIM(ko.jibun_address), ''),
         'latitude',t.latitude,
         'longitude',t.longitude,
         'coordinateSource',COALESCE(NULLIF(TRIM(t.coordinate_source), ''), 'UNKNOWN'),
         'sidoName',NULLIF(TRIM(r.sido_name), ''),
         'sigunguName',NULLIF(TRIM(r.sigungu_name), ''),
         'sourceHash',ko.source_hash
       ) ${target_sql}
       ORDER BY CRC32(CONCAT('translation-address-recovery-v1:',t.toilet_id)),t.toilet_id
       LIMIT ${batch_size};"
else
  sql="SELECT JSON_OBJECT(
         'visibleCurrentCount',COUNT(*),
         'officialAddressCount',COALESCE(SUM(COALESCE(NULLIF(TRIM(en.road_address), ''), NULLIF(TRIM(en.jibun_address), '')) IS NOT NULL),0),
         'targetCount',COALESCE(SUM(en.manual_override=FALSE
           AND en.address_translation_status='NO_RESULT'
           AND NULLIF(TRIM(en.name), '') IS NOT NULL
           AND NULLIF(TRIM(en.road_address), '') IS NULL
           AND NULLIF(TRIM(en.jibun_address), '') IS NULL
           AND COALESCE(NULLIF(TRIM(ko.road_address), ''), NULLIF(TRIM(ko.jibun_address), '')) IS NOT NULL),0),
         'statusCounts',JSON_OBJECT(
           'TRANSLATED',COALESCE(SUM(en.address_translation_status='TRANSLATED'),0),
           'NEEDS_REVIEW',COALESCE(SUM(en.address_translation_status='NEEDS_REVIEW'),0),
           'NO_RESULT',COALESCE(SUM(en.address_translation_status='NO_RESULT'),0)
         ),
         'sourceCounts',JSON_OBJECT(
           'MOIS_JUSO_ORIGINAL',COALESCE(SUM(en.address_translation_source='MOIS_JUSO_ORIGINAL'),0),
           'MOIS_JUSO_NORMALIZED',COALESCE(SUM(en.address_translation_source='MOIS_JUSO_NORMALIZED'),0),
           'KAKAO_REVERSE_MOIS_JUSO',COALESCE(SUM(en.address_translation_source='KAKAO_REVERSE_MOIS_JUSO'),0),
           'RECOVERY_EXHAUSTED',COALESCE(SUM(en.address_translation_source='RECOVERY_EXHAUSTED'),0),
           'MOIS_JUSO_NO_RESULT',COALESCE(SUM(en.address_translation_source='MOIS_JUSO_NO_RESULT'),0),
           'OTHER',COALESCE(SUM(en.address_translation_source IS NULL OR en.address_translation_source NOT IN
             ('MOIS_JUSO_ORIGINAL','MOIS_JUSO_NORMALIZED','KAKAO_REVERSE_MOIS_JUSO','RECOVERY_EXHAUSTED','MOIS_JUSO_NO_RESULT')),0)
         )
       ) ${current_scope_sql};"
fi

mysql_output="$(mktemp)"
mysql_error="$(mktemp)"
trap 'rm -f -- "$mysql_output" "$mysql_error"' EXIT
if ! docker exec -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db -e "$sql" \
  > "$mysql_output" 2> "$mysql_error"; then
  echo 'translation address recovery source query failed' >&2
  sed -E 's/(using password:).*/\1 [redacted]/I' "$mysql_error" >&2
  exit 1
fi
cat "$mysql_output"
