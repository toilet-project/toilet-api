#!/usr/bin/env bash
set -euo pipefail

remote_dir="${1:?remote work directory is required}"
max_rows="${2:-500}"
case "$remote_dir" in /tmp/toilet-translation-address-[0-9]*-[0-9]*) ;; *) echo 'unexpected remote work directory' >&2; exit 2;; esac
case "$max_rows" in ''|*[!0-9]*) exit 2;; esac
test "$max_rows" -ge 1 && test "$max_rows" -le 100000
test -f "$remote_dir/input.tgz"

umask 077
bundle_dir="$remote_dir/bundle"
work_dir="$remote_dir/work"
output_dir="$remote_dir/output"
mkdir -p "$bundle_dir" "$work_dir" "$output_dir"
tar -xzf "$remote_dir/input.tgz" -C "$bundle_dir"
rm -f -- "$remote_dir/input.tgz"

juso_key="$(cat "$bundle_dir/credentials/juso.key")"
kakao_key="$(cat "$bundle_dir/credentials/kakao.key")"
test -n "$juso_key" && test -n "$kakao_key"
rm -rf -- "$bundle_dir/credentials"

bash "$bundle_dir/scripts/collect-translation-address-recovery-source.sh" plan > "$work_dir/initial-plan.json"
processed=0
: > "$work_dir/audit-parts.jsonl"
while test "$processed" -lt "$max_rows"; do
  remaining=$((max_rows - processed))
  batch_size=500
  if test "$remaining" -lt "$batch_size"; then batch_size="$remaining"; fi
  source_file="$work_dir/source.jsonl"
  results_file="$work_dir/results.jsonl"
  audit_file="$work_dir/audit.json"
  TRANSLATION_ADDRESS_BATCH_SIZE="$batch_size" \
    bash "$bundle_dir/scripts/collect-translation-address-recovery-source.sh" batch > "$source_file"
  count="$(grep -c . "$source_file" || true)"
  if test "$count" -eq 0; then break; fi
  JUSO_ENGLISH_API_KEY="$juso_key" KAKAO_REST_API_KEY="$kakao_key" \
    python3 -B "$bundle_dir/scripts/translation_address_recovery.py" recover \
      "$source_file" "$results_file" "$work_dir/cache.json" --expected-count "$count" --workers 4
  python3 -B "$bundle_dir/scripts/translation_address_recovery.py" audit "$results_file" "$audit_file"
  python3 - "$audit_file" <<'PY' >> "$work_dir/audit-parts.jsonl"
import json,sys
report=json.load(open(sys.argv[1],encoding='utf-8'))
print(json.dumps(report,sort_keys=True))
PY
  bash "$bundle_dir/scripts/apply-translation-address-recovery-results.sh" \
    "$results_file" "$bundle_dir/scripts/translation_address_recovery_sql.py" > "$work_dir/apply-last.json"
  python3 - "$work_dir/apply-last.json" "$count" <<'PY'
import json,sys
lines=[line for line in open(sys.argv[1],encoding='utf-8').read().splitlines() if line.strip()]
if not lines: raise SystemExit('address recovery apply produced no audit result')
report=json.loads(lines[-1]); expected=int(sys.argv[2])
if int(report.get('stagedCount') or 0) != expected or int(report.get('appliedCount') or 0) != expected:
    raise SystemExit(f'address recovery write guard rejected rows: expected={expected} report={report}')
PY
  processed=$((processed + count))
  echo "translation-address-recovery: committed processed=$processed" >&2
  rm -f -- "$source_file" "$results_file" "$audit_file" "$work_dir/apply-last.json"
done

unset juso_key kakao_key JUSO_ENGLISH_API_KEY KAKAO_REST_API_KEY
bash "$bundle_dir/scripts/collect-translation-address-recovery-source.sh" plan > "$work_dir/final-plan.json"
python3 - "$work_dir/initial-plan.json" "$work_dir/final-plan.json" "$work_dir/audit-parts.jsonl" "$processed" "$max_rows" "$output_dir/translation-address-recovery-report.json" <<'PY'
import collections,datetime as dt,json,sys
from pathlib import Path
initial=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
final=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
parts=[json.loads(line) for line in Path(sys.argv[3]).read_text(encoding='utf-8').splitlines() if line.strip()]
processed,max_rows=int(sys.argv[4]),int(sys.argv[5])
sources=collections.Counter(); reasons=collections.Counter()
for part in parts:
    sources.update(part.get('recoverySourceCounts') or {})
    reasons.update(part.get('failureReasonCounts') or {})
report={
    'initialPlan':initial,'finalPlan':final,'maxRows':max_rows,'processedCount':processed,
    'recoveredCount':sum(int(p.get('recoveredCount') or 0) for p in parts),
    'needsReviewCount':sum(int(p.get('needsReviewCount') or 0) for p in parts),
    'recoverySourceCounts':dict(sorted(sources.items())),
    'failureReasonCounts':dict(sorted(reasons.items())),
    'jusoRequestCount':sum(int(p.get('jusoRequestCount') or 0) for p in parts),
    'kakaoRequestCount':sum(int(p.get('kakaoRequestCount') or 0) for p in parts),
    'limitReached':processed >= max_rows and int(final.get('targetCount') or 0) > 0,
    'productionWriteApplied':processed > 0,'rawSourceExported':False,
    'completedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
}
if report['recoveredCount'] + report['needsReviewCount'] != processed:
    raise SystemExit('recovery audit count mismatch')
Path(sys.argv[6]).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
PY

rm -rf -- "$work_dir"
tar -czf "$remote_dir/output.tgz" -C "$output_dir" translation-address-recovery-report.json
test -s "$remote_dir/output.tgz"
