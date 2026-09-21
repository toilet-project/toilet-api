#!/usr/bin/env python3
"""Build a guarded transaction for English address recovery results."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

HEX64 = re.compile(r"[0-9a-f]{64}")
ALLOWED_STATUS = {"TRANSLATED", "NEEDS_REVIEW"}
ALLOWED_SOURCE = {"MOIS_JUSO_NORMALIZED", "KAKAO_REVERSE_MOIS_JUSO", "RECOVERY_EXHAUSTED"}


def sql_text(value) -> str:
    if value is None or not str(value).strip():
        return "NULL"
    return f"CONVERT(0x{str(value).strip().encode('utf-8').hex()} USING utf8mb4)"


def load_rows(path: Path) -> list[dict]:
    rows, seen = [], set()
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        row = json.loads(raw)
        toilet_id = int(row.get("toiletId") or 0)
        source_hash = str(row.get("expectedSourceHash") or "").lower()
        status = str(row.get("addressTranslationStatus") or "")
        source = str(row.get("addressTranslationSource") or "")
        road, jibun = row.get("roadAddress"), row.get("jibunAddress")
        if toilet_id <= 0 or toilet_id in seen:
            raise ValueError(f"line {line_number}: invalid or duplicate toiletId")
        if not HEX64.fullmatch(source_hash):
            raise ValueError(f"line {line_number}: invalid source hash")
        if status not in ALLOWED_STATUS or source not in ALLOWED_SOURCE:
            raise ValueError(f"line {line_number}: invalid address result status/source")
        if status == "TRANSLATED" and not (str(road or "").strip() or str(jibun or "").strip()):
            raise ValueError(f"line {line_number}: translated address is empty")
        if status == "NEEDS_REVIEW" and (str(road or "").strip() or str(jibun or "").strip()):
            raise ValueError(f"line {line_number}: failed recovery contains an address")
        for value in (road, jibun):
            if value is not None and len(str(value).strip()) > 500:
                raise ValueError(f"line {line_number}: address is too long")
        seen.add(toilet_id); rows.append(row)
    if not rows or len(rows) > 500:
        raise ValueError("recovery batch must contain 1 to 500 rows")
    return rows


def relation(rows: list[dict]) -> str:
    selects = []
    for row in rows:
        selects.append("SELECT " + ",".join((
            f"{int(row['toiletId'])} AS toilet_id",
            f"'{str(row['expectedSourceHash']).lower()}' AS source_hash",
            f"{sql_text(row.get('roadAddress'))} AS road_address",
            f"{sql_text(row.get('jibunAddress'))} AS jibun_address",
            f"'{row['addressTranslationStatus']}' AS address_status",
            f"'{row['addressTranslationSource']}' AS address_source",
        )))
    return "\nUNION ALL\n".join(selects)


def build_sql(rows: list[dict]) -> str:
    values = relation(rows)
    ids = ",".join(str(int(row["toiletId"])) for row in rows)
    return f"""SET NAMES utf8mb4;
START TRANSACTION;
UPDATE toilet_translation en
JOIN (
{values}
) s ON s.toilet_id=en.toilet_id
JOIN toilet_translation ko ON ko.toilet_id=en.toilet_id AND ko.locale='ko' AND ko.source_hash=s.source_hash
SET en.road_address=s.road_address,
    en.jibun_address=s.jibun_address,
    en.address_translation_status=s.address_status,
    en.address_translation_source=s.address_source,
    en.version=en.version+1,
    en.updated_at=NOW()
WHERE en.locale='en'
  AND en.source_hash=s.source_hash
  AND en.manual_override=FALSE
  AND en.address_translation_status='NO_RESULT'
  AND NULLIF(TRIM(en.road_address),'') IS NULL
  AND NULLIF(TRIM(en.jibun_address),'') IS NULL;
SET @applied_count = ROW_COUNT();
COMMIT;
SELECT JSON_OBJECT(
         'stagedCount',{len(rows)},
         'appliedCount',@applied_count,
         'translatedCount',SUM(address_translation_status='TRANSLATED'),
         'needsReviewCount',SUM(address_translation_status='NEEDS_REVIEW'),
         'currentCount',SUM(en.source_hash=ko.source_hash),
         'manualProtectedCount',SUM(en.manual_override=TRUE)
       )
  FROM toilet_translation en
  JOIN toilet_translation ko ON ko.toilet_id=en.toilet_id AND ko.locale='ko'
 WHERE en.locale='en' AND en.toilet_id IN ({ids});
"""


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("results", type=Path)
    args = parser.parse_args(); print(build_sql(load_rows(args.results)), end=""); return 0


if __name__ == "__main__":
    raise SystemExit(main())
