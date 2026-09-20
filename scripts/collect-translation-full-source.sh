#!/usr/bin/env bash
set -euo pipefail

mode="${1:?plan or batch is required}"
after_id="${TRANSLATION_FULL_AFTER_ID:-0}"
batch_size="${TRANSLATION_FULL_BATCH_SIZE:-500}"

case "$mode" in plan|batch) ;; *) echo 'mode must be plan or batch' >&2; exit 2;; esac
case "$after_id" in ''|*[!0-9]*) echo 'after id must be an integer' >&2; exit 2;; esac
case "$batch_size" in ''|*[!0-9]*) echo 'batch size must be an integer' >&2; exit 2;; esac
test "$batch_size" -ge 1
test "$batch_size" -le 1000
echo "translation-full-source: mode=$mode after=$after_id limit=$batch_size" >&2

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user"
test -n "$mysql_password"
echo 'translation-full-source: database credentials loaded' >&2

common_sql="
  FROM toilet t
  JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
  LEFT JOIN toilet_translation en ON en.toilet_id=t.toilet_id AND en.locale='en'
  LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
 WHERE t.visibility_status='VISIBLE'
   AND NULLIF(TRIM(ko.name), '') IS NOT NULL
   AND ko.source_hash=SHA2(CONCAT(COALESCE(TRIM(t.name), ''), CHAR(31),
                                 COALESCE(TRIM(t.road_address), ''), CHAR(31),
                                 COALESCE(TRIM(t.jibun_address), '')), 256)
   AND (en.toilet_id IS NULL OR (en.manual_override=FALSE AND en.source_hash<>ko.source_hash))"

if test "$mode" = plan; then
  sql="SELECT JSON_OBJECT(
         'targetCount', COUNT(*),
         'googleCharacters', COALESCE(SUM(CHAR_LENGTH(TRIM(ko.name))), 0),
         'addressLookupCount', COALESCE(SUM(COALESCE(NULLIF(TRIM(ko.road_address), ''), NULLIF(TRIM(ko.jibun_address), '')) IS NOT NULL), 0),
         'roadAddressCount', COALESCE(SUM(NULLIF(TRIM(ko.road_address), '') IS NOT NULL), 0),
         'jibunFallbackCount', COALESCE(SUM(NULLIF(TRIM(ko.road_address), '') IS NULL AND NULLIF(TRIM(ko.jibun_address), '') IS NOT NULL), 0)
       ) ${common_sql};"
else
  sql="SELECT JSON_OBJECT(
         'toiletId', t.toilet_id,
         'name', TRIM(ko.name),
         'roadAddress', NULLIF(TRIM(ko.road_address), ''),
         'jibunAddress', NULLIF(TRIM(ko.jibun_address), ''),
         'selectedAddressKind', CASE WHEN NULLIF(TRIM(ko.road_address), '') IS NOT NULL THEN 'ROAD' WHEN NULLIF(TRIM(ko.jibun_address), '') IS NOT NULL THEN 'JIBUN' ELSE 'NONE' END,
         'selectedAddress', COALESCE(NULLIF(TRIM(ko.road_address), ''), NULLIF(TRIM(ko.jibun_address), '')),
         'sourceHash', ko.source_hash,
         'region', COALESCE(NULLIF(r.sido_name, ''), NULLIF(SUBSTRING_INDEX(COALESCE(NULLIF(TRIM(ko.road_address), ''), NULLIF(TRIM(ko.jibun_address), ''), ''), ' ', 1), ''), '미분류'),
         'toiletType', COALESCE(NULLIF(TRIM(t.toilet_type), ''), '미분류')
       ) ${common_sql}
       AND t.toilet_id>${after_id}
       ORDER BY t.toilet_id
       LIMIT ${batch_size};"
fi

docker exec -i -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db -e "$sql"
