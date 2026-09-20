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

# The selected names and addresses never leave the database container. Only this
# aggregate JSON document is written to stdout and returned to the workflow.
docker exec -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db <<SQL
SET SESSION group_concat_max_len = 1048576;
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
    SELECT ranked.*,
           CASE
               WHEN road_address IS NOT NULL THEN 'ROAD'
               WHEN jibun_address IS NOT NULL THEN 'JIBUN'
               ELSE 'NONE'
           END AS selected_address_kind,
           COALESCE(road_address, jibun_address, '') AS selected_address
      FROM ranked
     ORDER BY group_rank, region_key, type_key,
              CRC32(CONCAT('translation-pilot-v1:', toilet_id)), toilet_id
     LIMIT ${expected_count}
), region_counts AS (
    SELECT region_key, COUNT(*) AS item_count
      FROM selected
     GROUP BY region_key
), type_counts AS (
    SELECT type_key, COUNT(*) AS item_count
      FROM selected
     GROUP BY type_key
)
SELECT JSON_OBJECT(
           'sampleCount', COUNT(*),
           'addressKinds', JSON_OBJECT(
               'ROAD', COALESCE(SUM(selected_address_kind='ROAD'), 0),
               'JIBUN', COALESCE(SUM(selected_address_kind='JIBUN'), 0),
               'NONE', COALESCE(SUM(selected_address_kind='NONE'), 0)
           ),
           'nameCharactersForGoogle', COALESCE(SUM(CHAR_LENGTH(name)), 0),
           'addressCharactersForJuso', COALESCE(SUM(CHAR_LENGTH(selected_address)), 0),
           'regionDistribution', (SELECT JSON_OBJECTAGG(region_key, item_count) FROM region_counts),
           'typeDistribution', (SELECT JSON_OBJECTAGG(type_key, item_count) FROM type_counts),
           'sourceFingerprintSha256', SHA2(GROUP_CONCAT(
               SHA2(CONCAT(CAST(toilet_id AS CHAR), CHAR(31), name, CHAR(31),
                           selected_address_kind, CHAR(31), selected_address, CHAR(31),
                           source_hash, CHAR(31), region_key, CHAR(31), type_key), 256)
               ORDER BY group_rank, region_key, type_key,
                        CRC32(CONCAT('translation-pilot-v1:', toilet_id)), toilet_id
               SEPARATOR ''
           ), 256),
           'rawSourceRetained', JSON_EXTRACT('false', '$'),
           'rawSourceTransferred', JSON_EXTRACT('false', '$'),
           'addressSelectionRule', 'ROAD_THEN_JIBUN'
       )
  FROM selected;
ROLLBACK;
SQL
