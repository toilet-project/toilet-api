#!/usr/bin/env bash
set -euo pipefail

remote_dir="${1:?remote work directory is required}"
expected_count="${2:-1000}"

case "$remote_dir" in
  /tmp/toilet-translation-pilot-[0-9]*-[0-9]*) ;;
  *) echo 'unexpected remote work directory' >&2; exit 2 ;;
esac
case "$expected_count" in
  ''|*[!0-9]*) echo 'expected count must be an integer' >&2; exit 2 ;;
esac
test "$expected_count" -ge 1
test "$expected_count" -le 1000
test -f "$remote_dir/input.tgz"

umask 077
bundle_dir="$remote_dir/bundle"
work_dir="$remote_dir/work"
output_dir="$remote_dir/output"
mkdir -p "$bundle_dir" "$work_dir" "$output_dir"
tar -xzf "$remote_dir/input.tgz" -C "$bundle_dir"
rm -f -- "$remote_dir/input.tgz"

google_key="$(cat "$bundle_dir/credentials/google.key")"
juso_key="$(cat "$bundle_dir/credentials/juso.key")"
test -n "$google_key"
test -n "$juso_key"
rm -rf -- "$bundle_dir/credentials"

export TRANSLATION_PILOT_COUNT="$expected_count"
bash "$bundle_dir/scripts/collect-translation-pilot-source.sh" > "$work_dir/source.jsonl"
python3 -B "$bundle_dir/scripts/translation_pilot.py" audit-source \
  "$work_dir/source.jsonl" "$work_dir/source-audit.json" \
  --expected-count "$expected_count"
python3 - "$work_dir/source-audit.json" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
characters = int(report.get("googleBillableCharacters", 0))
if characters <= 0 or characters > 15000:
    raise SystemExit(f"Google character safety cap exceeded: {characters}")
PY

GOOGLE_TRANSLATION_API_KEY="$google_key" \
JUSO_ENGLISH_API_KEY="$juso_key" \
python3 -B "$bundle_dir/scripts/translation_pilot.py" translate \
  "$work_dir/source.jsonl" "$work_dir/results.jsonl" \
  --expected-count "$expected_count" \
  --batch-size 50 \
  --request-interval 0.08

unset google_key juso_key GOOGLE_TRANSLATION_API_KEY JUSO_ENGLISH_API_KEY

python3 -B "$bundle_dir/scripts/translation_pilot.py" audit-results \
  "$work_dir/source.jsonl" "$work_dir/results.jsonl" \
  "$output_dir/translation-pilot-audit.json"

python3 - "$work_dir/results.jsonl" "$output_dir/translation-pilot-audit.json" "$expected_count" <<'PY'
import json
import sys
from pathlib import Path

results_path = Path(sys.argv[1])
audit_path = Path(sys.argv[2])
expected_count = int(sys.argv[3])
audit = json.loads(audit_path.read_text(encoding="utf-8"))
if audit.get("sourceCount") != expected_count:
    raise SystemExit(f"unexpected source count: {audit.get('sourceCount')}")
if audit.get("resultCount") != expected_count:
    raise SystemExit(f"unexpected result count: {audit.get('resultCount')}")
if not audit.get("readyForHumanReview"):
    raise SystemExit("pilot result is not ready for human review")

destination = audit_path.parent / "translation-pilot-results.jsonl"
destination.write_bytes(results_path.read_bytes())
PY

rm -f -- "$work_dir/source.jsonl" "$work_dir/source-audit.json" "$work_dir/results.jsonl"
tar -czf "$remote_dir/output.tgz" -C "$output_dir" \
  translation-pilot-results.jsonl translation-pilot-audit.json
