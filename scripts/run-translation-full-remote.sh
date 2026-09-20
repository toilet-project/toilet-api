#!/usr/bin/env bash
set -euo pipefail

remote_dir="${1:?remote work directory is required}"
max_rows="${2:-100000}"
max_google_chars="${3:-650000}"

case "$remote_dir" in /tmp/toilet-translation-full-[0-9]*-[0-9]*) ;; *) echo 'unexpected remote work directory' >&2; exit 2;; esac
case "$max_rows" in ''|*[!0-9]*) exit 2;; esac
case "$max_google_chars" in ''|*[!0-9]*) exit 2;; esac
test "$max_rows" -ge 1 && test "$max_rows" -le 100000
test "$max_google_chars" -ge 1 && test "$max_google_chars" -le 650000
test -f "$remote_dir/input.tgz"
echo 'translation-full: remote script started' >&2

umask 077
bundle_dir="$remote_dir/bundle"
work_dir="$remote_dir/work"
output_dir="$remote_dir/output"
mkdir -p "$bundle_dir" "$work_dir" "$output_dir"
tar -xzf "$remote_dir/input.tgz" -C "$bundle_dir"
rm -f -- "$remote_dir/input.tgz"
echo 'translation-full: bundle extracted' >&2

google_key="$(cat "$bundle_dir/credentials/google.key")"
juso_key="$(cat "$bundle_dir/credentials/juso.key")"
test -n "$google_key" && test -n "$juso_key"
rm -rf -- "$bundle_dir/credentials"
echo 'translation-full: credentials loaded and source files removed' >&2

bash "$bundle_dir/scripts/collect-translation-full-source.sh" plan > "$work_dir/initial-plan.json"
echo 'translation-full: initial plan collected' >&2
python3 - "$work_dir/initial-plan.json" <<'PY' >&2
import json,sys
plan=json.load(open(sys.argv[1],encoding='utf-8'))
print(f"translation-full: plan rows={int(plan.get('targetCount') or 0)} chars={int(plan.get('googleCharacters') or 0)}")
PY
python3 - "$work_dir/initial-plan.json" "$max_rows" "$max_google_chars" <<'PY'
import json, sys
from pathlib import Path
plan=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
count=int(plan.get('targetCount') or 0); chars=int(plan.get('googleCharacters') or 0)
if count < 0 or count > int(sys.argv[2]): raise SystemExit(f'row safety cap exceeded: {count}')
if chars < 0 or chars > int(sys.argv[3]): raise SystemExit(f'Google character safety cap exceeded: {chars}')
PY

cursor=0
processed=0
: > "$work_dir/audit-parts.jsonl"
while true; do
  source_file="$work_dir/source.jsonl"
  results_file="$work_dir/results.jsonl"
  audit_file="$work_dir/audit.json"
  TRANSLATION_FULL_AFTER_ID="$cursor" TRANSLATION_FULL_BATCH_SIZE=500 \
    bash "$bundle_dir/scripts/collect-translation-full-source.sh" batch > "$source_file"
  count="$(grep -c . "$source_file" || true)"
  if test "$count" -eq 0; then break; fi
  echo "translation-full: processing after=$cursor count=$count" >&2
  next_cursor="$(python3 - "$source_file" <<'PY'
import json,sys
print(max(json.loads(line)['toiletId'] for line in open(sys.argv[1], encoding='utf-8') if line.strip()))
PY
)"
  python3 -B "$bundle_dir/scripts/translation_pilot.py" audit-source \
    "$source_file" "$work_dir/source-audit.json" --expected-count "$count"
  GOOGLE_TRANSLATION_API_KEY="$google_key" JUSO_ENGLISH_API_KEY="$juso_key" \
  TRANSLATION_PROVIDER_SOURCE='FULL_GOOGLE_NMT_JUSO' \
    python3 -B "$bundle_dir/scripts/translation_pilot.py" translate \
      "$source_file" "$results_file" --expected-count "$count" \
      --batch-size 50 --address-workers 4 --request-interval 0.4
  python3 -B "$bundle_dir/scripts/translation_pilot.py" audit-results \
    "$source_file" "$results_file" "$audit_file"
  python3 - "$audit_file" <<'PY' >> "$work_dir/audit-parts.jsonl"
import json,sys
report=json.load(open(sys.argv[1],encoding='utf-8'))
if not report.get('readyForHumanReview'): raise SystemExit('translation batch audit failed')
print(json.dumps({k:report.get(k,0) for k in ('sourceCount','resultCount','issueCount','translatedAddressCount','addressLookupErrorCount','googleCharactersSubmitted')},sort_keys=True))
PY
  bash "$bundle_dir/scripts/apply-translation-full-results.sh" \
    "$results_file" "$bundle_dir/scripts/translation_full_sql.py" > "$work_dir/apply-last.json"
  processed=$((processed + count))
  echo "translation-full: committed processed=$processed" >&2
  cursor="$next_cursor"
  rm -f -- "$source_file" "$results_file" "$audit_file" "$work_dir/source-audit.json" "$work_dir/apply-last.json"
done

unset google_key juso_key GOOGLE_TRANSLATION_API_KEY JUSO_ENGLISH_API_KEY
final_plan="$(bash "$bundle_dir/scripts/collect-translation-full-source.sh" plan)"
printf '%s\n' "$final_plan" > "$work_dir/final-plan.json"
python3 - "$work_dir/initial-plan.json" "$work_dir/final-plan.json" "$work_dir/audit-parts.jsonl" "$processed" "$output_dir/translation-full-report.json" <<'PY'
import datetime as dt,json,sys
from pathlib import Path
initial=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
final=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
parts=[json.loads(line) for line in Path(sys.argv[3]).read_text(encoding='utf-8').splitlines() if line.strip()]
processed=int(sys.argv[4])
if int(final.get('targetCount') or 0) != 0: raise SystemExit('eligible translation rows remain; rerun safely resumes them')
report={
  'initialPlan':initial,'finalPlan':final,'processedCount':processed,
  'resultCount':sum(int(p.get('resultCount') or 0) for p in parts),
  'issueCount':sum(int(p.get('issueCount') or 0) for p in parts),
  'translatedAddressCount':sum(int(p.get('translatedAddressCount') or 0) for p in parts),
  'addressLookupErrorCount':sum(int(p.get('addressLookupErrorCount') or 0) for p in parts),
  'googleCharactersSubmitted':sum(int(p.get('googleCharactersSubmitted') or 0) for p in parts),
  'estimatedGoogleNmtCostUsdBeforeFreeCredit':round(sum(int(p.get('googleCharactersSubmitted') or 0) for p in parts)*20/1_000_000,4),
  'productionWriteApplied':True,'rawSourceExported':False,
  'completedAt':dt.datetime.now(dt.timezone.utc).isoformat()
}
Path(sys.argv[5]).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
PY

rm -rf -- "$work_dir"
tar -czf "$remote_dir/output.tgz" -C "$output_dir" translation-full-report.json
test -s "$remote_dir/output.tgz"
echo "translation-full: completed processed=$processed" >&2
