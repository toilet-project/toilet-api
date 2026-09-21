#!/usr/bin/env bash
set -euo pipefail

results_file="${1:?results JSONL is required}"
sql_builder="${2:?SQL builder is required}"
test -s "$results_file" && test -f "$sql_builder"

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user" && test -n "$mysql_password"

python3 -B "$sql_builder" "$results_file" | docker exec -i -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db
