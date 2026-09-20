#!/usr/bin/env python3
"""Convert audited English results into a guarded, transactional MySQL upsert."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

HEX64 = re.compile(r"[0-9a-f]{64}")
SOURCE = re.compile(r"[A-Z0-9_]{1,40}")


def sql_text(value: object) -> str:
    if value is None or str(value).strip() == "":
        return "NULL"
    return f"CONVERT(0x{str(value).strip().encode('utf-8').hex()} USING utf8mb4)"


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    seen: set[int] = set()
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        row = json.loads(raw)
        toilet_id = int(row.get("toiletId", 0))
        source_hash = str(row.get("expectedSourceHash", "")).lower()
        source = str(row.get("source", ""))
        name = str(row.get("name", "")).strip()
        if toilet_id <= 0 or toilet_id in seen:
            raise ValueError(f"line {line_number}: invalid or duplicate toiletId")
        if not name or len(name) > 255:
            raise ValueError(f"line {line_number}: invalid name")
        if not HEX64.fullmatch(source_hash):
            raise ValueError(f"line {line_number}: invalid source hash")
        if not SOURCE.fullmatch(source):
            raise ValueError(f"line {line_number}: invalid source")
        for field in ("roadAddress", "jibunAddress"):
            value = row.get(field)
            if value is not None and len(str(value).strip()) > 500:
                raise ValueError(f"line {line_number}: {field} is too long")
        seen.add(toilet_id)
        rows.append(row)
    if not rows or len(rows) > 1000:
        raise ValueError("result batch must contain 1 to 1000 rows")
    return rows


def source_relation(rows: list[dict]) -> str:
    selects: list[str] = []
    for row in rows:
        values = (
            str(int(row["toiletId"])),
            sql_text(row["name"]),
            sql_text(row.get("roadAddress")),
            sql_text(row.get("jibunAddress")),
            f"'{str(row['expectedSourceHash']).lower()}'",
            f"'{row['source']}'",
        )
        selects.append(
            "SELECT " + values[0] + " AS toilet_id," + values[1] + " AS name," +
            values[2] + " AS road_address," + values[3] + " AS jibun_address," +
            values[4] + " AS source_hash," + values[5] + " AS translation_source"
        )
    return "\nUNION ALL\n".join(selects)


def build_sql(rows: list[dict]) -> str:
    relation = source_relation(rows)
    ids = ",".join(str(int(row["toiletId"])) for row in rows)
    return f"""SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO toilet_translation
    (toilet_id,locale,name,road_address,jibun_address,source_hash,
     translation_status,translation_source,manual_override,
     translated_at,reviewed_at,created_at,updated_at)
SELECT s.toilet_id,'en',s.name,s.road_address,s.jibun_address,s.source_hash,
       'MACHINE_TRANSLATED',s.translation_source,FALSE,NOW(),NULL,NOW(),NOW()
  FROM (
{relation}
       ) s
  JOIN toilet_translation ko ON ko.toilet_id=s.toilet_id
       AND ko.locale='ko' AND ko.source_hash=s.source_hash
ON DUPLICATE KEY UPDATE
    name=IF(toilet_translation.manual_override,toilet_translation.name,VALUES(name)),
    road_address=IF(toilet_translation.manual_override,toilet_translation.road_address,VALUES(road_address)),
    jibun_address=IF(toilet_translation.manual_override,toilet_translation.jibun_address,VALUES(jibun_address)),
    source_hash=IF(toilet_translation.manual_override,toilet_translation.source_hash,VALUES(source_hash)),
    translation_status=IF(toilet_translation.manual_override,toilet_translation.translation_status,VALUES(translation_status)),
    translation_source=IF(toilet_translation.manual_override,toilet_translation.translation_source,VALUES(translation_source)),
    translated_at=IF(toilet_translation.manual_override,toilet_translation.translated_at,VALUES(translated_at)),
    version=IF(toilet_translation.manual_override,toilet_translation.version,toilet_translation.version+1),
    updated_at=IF(toilet_translation.manual_override,toilet_translation.updated_at,VALUES(updated_at));
COMMIT;
SELECT JSON_OBJECT(
         'stagedCount',{len(rows)},
         'currentAppliedCount',SUM(en.source_hash=ko.source_hash AND en.translation_status='MACHINE_TRANSLATED'),
         'manualProtectedCount',SUM(en.manual_override=TRUE)
       )
  FROM toilet_translation en
  JOIN toilet_translation ko ON ko.toilet_id=en.toilet_id AND ko.locale='ko'
 WHERE en.locale='en' AND en.toilet_id IN ({ids});
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    print(build_sql(load_rows(args.results)), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
