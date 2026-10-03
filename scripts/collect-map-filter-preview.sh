#!/usr/bin/env bash
# Read-only export: public IDs, coordinates, and positive filter facts only.
# No users, likes, reports, contact details, hidden facilities or credentials leave the host.
set -euo pipefail
api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user"
test -n "$mysql_password"
docker exec -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db -e "
START TRANSACTION READ ONLY;
SELECT /*+ MAX_EXECUTION_TIME(15000) */ JSON_ARRAY(t.toilet_id,t.latitude,t.longitude,
  CASE WHEN oh.is_open_24h=TRUE AND oh.normalization_status IN ('PARSED','CONFIRMED')
    AND oh.source_changed=FALSE THEN 1 ELSE 0 END
  + CASE WHEN ASCII(t.has_cctv)=89 AND OCTET_LENGTH(t.has_cctv)=1 THEN 2 ELSE 0 END
  + CASE WHEN ASCII(t.has_diaper_table)=89 AND OCTET_LENGTH(t.has_diaper_table)=1 THEN 4 ELSE 0 END
  + CASE WHEN ASCII(t.has_emergency_bell)=89 AND OCTET_LENGTH(t.has_emergency_bell)=1 THEN 8 ELSE 0 END
  + CASE WHEN COALESCE(t.male_disabled_toilet_count,0)>0
    OR COALESCE(t.female_disabled_toilet_count,0)>0 THEN 16 ELSE 0 END
  + CASE WHEN COALESCE(t.male_disabled_toilet_count,0)>0 THEN 32 ELSE 0 END
  + CASE WHEN COALESCE(t.female_disabled_toilet_count,0)>0 THEN 64 ELSE 0 END)
FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
WHERE t.visibility_status='VISIBLE' AND t.latitude BETWEEN 32 AND 40
  AND t.longitude BETWEEN 124 AND 132
ORDER BY t.toilet_id;
ROLLBACK;
"
unset mysql_password mysql_user
