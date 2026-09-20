#!/usr/bin/env bash
set -euo pipefail

expected_count="${TRANSLATION_PILOT_COUNT:-1000}"
case "$expected_count" in
  ''|*[!0-9]*) echo 'TRANSLATION_PILOT_COUNT must be an integer' >&2; exit 2 ;;
esac
test "$expected_count" -ge 1
test "$expected_count" -le 1000

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user"
test -n "$mysql_password"

docker exec -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db <<SQL
START TRANSACTION READ ONLY;
WITH source_rows AS (
    SELECT t.toilet_id,
           TRIM(ko.name) AS name,
           NULLIF(TRIM(ko.road_address), '') AS road_address,
           NULLIF(TRIM(ko.jibun_address), '') AS jibun_address,
           ko.source_hash,
           COALESCE(NULLIF(r.sido_name, ''),
                    NULLIF(SUBSTRING_INDEX(COALESCE(NULLIF(TRIM(ko.road_address), ''),
                                                     NULLIF(TRIM(ko.jibun_address), ''), ''), ' ', 1), ''),
                    '미분류') AS region_key,
           COALESCE(NULLIF(TRIM(t.toilet_type), ''), '미분류') AS type_key
      FROM toilet t
      JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
      LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
     WHERE t.visibility_status='VISIBLE'
       AND NULLIF(TRIM(ko.name), '') IS NOT NULL
       AND ko.source_hash=SHA2(CONCAT(COALESCE(TRIM(t.name), ''), CHAR(31),
                                     COALESCE(TRIM(t.road_address), ''), CHAR(31),
                                     COALESCE(TRIM(t.jibun_address), '')), 256)
), ranked AS (
    SELECT source_rows.*,
           ROW_NUMBER() OVER (
               PARTITION BY region_key, type_key
               ORDER BY CRC32(CONCAT('translation-pilot-v1:', toilet_id)), toilet_id
           ) AS group_rank
      FROM source_rows
), selected AS (
    SELECT *
      FROM ranked
     ORDER BY group_rank, region_key, type_key,
              CRC32(CONCAT('translation-pilot-v1:', toilet_id)), toilet_id
     LIMIT ${expected_count}
)
SELECT JSON_OBJECT(
           'toiletId', toilet_id,
           'name', name,
           'roadAddress', road_address,
           'jibunAddress', jibun_address,
           'selectedAddressKind', CASE
               WHEN road_address IS NOT NULL THEN 'ROAD'
               WHEN jibun_address IS NOT NULL THEN 'JIBUN'
               ELSE 'NONE'
           END,
           'selectedAddress', COALESCE(road_address, jibun_address),
           'sourceHash', source_hash,
           'region', region_key,
           'toiletType', type_key
       )
  FROM selected
 ORDER BY group_rank, region_key, type_key,
          CRC32(CONCAT('translation-pilot-v1:', toilet_id)), toilet_id;
ROLLBACK;
SQL
