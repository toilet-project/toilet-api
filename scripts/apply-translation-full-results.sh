#!/usr/bin/env bash
set -euo pipefail

results="${1:?results jsonl is required}"
sql_builder="${2:?SQL builder path is required}"
test -f "$results"
test -f "$sql_builder"

work_dir="$(dirname "$results")"
sql_file="$work_dir/apply.sql"
report_file="$work_dir/apply-report.json"
trap 'rm -f -- "$sql_file"' EXIT
python3 -B "$sql_builder" "$results" > "$sql_file"

api_environment="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-api)"
mysql_user="$(sed -n 's/^SPRING_DB_USERNAME=//p' <<<"$api_environment")"
mysql_password="$(sed -n 's/^SPRING_DB_PASSWORD=//p' <<<"$api_environment")"
unset api_environment
test -n "$mysql_user"
test -n "$mysql_password"

docker exec -i -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=tcp -h 127.0.0.1 --default-character-set=utf8mb4 \
  --batch --raw --skip-column-names -u "$mysql_user" toilet_db \
  < "$sql_file" > "$report_file"

python3 - "$report_file" <<'PY'
import json, sys
from pathlib import Path
report = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8').strip())
if int(report.get('currentAppliedCount') or 0) != int(report.get('stagedCount') or 0):
    raise SystemExit('translation batch was not fully applied; rerun will select remaining rows')
print(json.dumps(report, ensure_ascii=False, sort_keys=True))
PY
