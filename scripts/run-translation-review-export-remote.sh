#!/usr/bin/env bash
set -euo pipefail

remote_dir="${1:?remote work directory is required}"

case "$remote_dir" in
  /tmp/toilet-translation-review-[0-9]*-[0-9]*) ;;
  *) echo 'unexpected remote work directory' >&2; exit 2 ;;
esac
test -f "$remote_dir/input.tgz"

umask 077
bundle_dir="$remote_dir/bundle"
work_dir="$remote_dir/work"
output_dir="$remote_dir/output"
mkdir -p "$bundle_dir" "$work_dir" "$output_dir"
tar -xzf "$remote_dir/input.tgz" -C "$bundle_dir"
rm -f -- "$remote_dir/input.tgz"

ids="$(cat "$bundle_dir/input/ids.txt")"
case "$ids" in
  ''|*[!0-9,]*) echo 'invalid review ID list' >&2; exit 2 ;;
esac
test "$(printf '%s' "$ids" | tr ',' '\n' | sort -u | wc -l)" -eq 100
export_key="$bundle_dir/credentials/review-export.key"
test -s "$export_key"

mysql_user="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-mysql | sed -n 's/^MYSQL_USER=//p')"
mysql_password="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' toilet-mysql | sed -n 's/^MYSQL_PASSWORD=//p')"
test -n "$mysql_user"
test -n "$mysql_password"

cat > "$work_dir/review.sql" <<SQL
SELECT t.toilet_id,
       REPLACE(TO_BASE64(CONVERT(ko.name USING utf8mb4)), '\\n', ''),
       REPLACE(TO_BASE64(CONVERT(ko.road_address USING utf8mb4)), '\\n', ''),
       REPLACE(TO_BASE64(CONVERT(ko.jibun_address USING utf8mb4)), '\\n', '')
  FROM toilet t
  JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
 WHERE t.toilet_id IN ($ids)
 ORDER BY FIELD(t.toilet_id,$ids);
SQL
docker exec -i -e MYSQL_PWD="$mysql_password" toilet-mysql \
  mysql --protocol=socket --batch --raw --skip-column-names -u "$mysql_user" toilet_db \
  < "$work_dir/review.sql" > "$work_dir/source-base64.tsv"
unset mysql_user mysql_password
rm -f -- "$work_dir/review.sql"
test "$(wc -l < "$work_dir/source-base64.tsv")" -eq 100

openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
  -in "$work_dir/source-base64.tsv" \
  -out "$output_dir/source-base64.tsv.enc" \
  -pass file:"$export_key"
rm -f -- "$work_dir/source-base64.tsv" "$export_key"
rm -rf -- "$bundle_dir"

cipher_sha256="$(sha256sum "$output_dir/source-base64.tsv.enc" | cut -d' ' -f1)"
cat > "$output_dir/review-export-manifest.json" <<JSON
{
  "sampleCount": 100,
  "cipher": "AES-256-CBC-PBKDF2-SHA256",
  "iterations": 200000,
  "cipherSha256": "$cipher_sha256",
  "containsPlaintextSource": false
}
JSON
tar -czf "$remote_dir/output.tgz" -C "$output_dir" \
  source-base64.tsv.enc review-export-manifest.json
