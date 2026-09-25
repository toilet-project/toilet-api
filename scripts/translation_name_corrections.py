#!/usr/bin/env python3
"""Generate, but never execute, a four-row name correction transaction.

Default mode is a read-only preflight. No network, credentials or DB driver.
This is an explicit operational change, not an application/Flyway migration.
"""

import argparse
import json
import re
from pathlib import Path

MANIFEST = Path(__file__).parent / "data/facility-name-corrections-20260925.json"
TARGETS = {(15060, "en"), (15060, "zh-cn"), (24858, "en"), (24858, "zh-cn")}
MANUAL_SOURCE = "MANUAL_NAME_CORRECTION"


def validate(rows):
    if not isinstance(rows, list) or len(rows) != 4:
        raise ValueError("Exactly four approved name corrections are required")
    targets = set()
    for row in rows:
        key = (row["toiletId"], row["locale"])
        if key not in TARGETS or key in targets:
            raise ValueError("Unexpected or duplicate correction target")
        targets.add(key)
        if type(row["toiletId"]) is not int or type(row["expectedVersion"]) is not int or row["expectedVersion"] < 1:
            raise ValueError("Invalid ID/version")
        for field in ("sourceHash", "addressHash"):
            if not re.fullmatch(r"[0-9a-f]{64}", row[field]):
                raise ValueError("Invalid snapshot hash")
        for field in ("originalName", "oldName", "newName", "source", "addressSource"):
            value = row[field]
            limit = 40 if field in ("source", "addressSource") else 255
            if not isinstance(value, str) or not value.strip() or len(value) > limit:
                raise ValueError("Invalid text field")
        if row["oldName"] == row["newName"]:
            raise ValueError("Correction must change the name")
    return rows


def sql_text(value):
    return f"CONVERT(0x{value.encode('utf-8').hex()} USING utf8mb4)"


def relation(rows, rollback):
    parts = []
    for row in rows:
        values = {
            "toilet_id": str(row["toiletId"]), "locale": sql_text(row["locale"]),
            "original_name": sql_text(row["originalName"]),
            "expected_name": sql_text(row["newName"] if rollback else row["oldName"]),
            "next_name": sql_text(row["oldName"] if rollback else row["newName"]),
            "source_hash": sql_text(row["sourceHash"]), "address_hash": sql_text(row["addressHash"]),
            "expected_version": str(row["expectedVersion"] + int(rollback)),
            "expected_source": sql_text(MANUAL_SOURCE if rollback else row["source"]),
            "next_source": sql_text(row["source"] if rollback else MANUAL_SOURCE),
            "address_source": sql_text(row["addressSource"]),
        }
        parts.append("SELECT " + ", ".join(f"{value} AS {key}" for key, value in values.items()))
    return "\nUNION ALL\n".join(parts)


def build_sql(rows, mode="check"):
    validate(rows)
    if mode not in ("check", "apply", "rollback"):
        raise ValueError("Unknown mode")
    rollback = mode == "rollback"
    plan = relation(rows, rollback)
    joins = f"""toilet_translation tr
JOIN ({plan}) p ON p.toilet_id=tr.toilet_id AND p.locale=tr.locale
JOIN toilet t ON t.toilet_id=tr.toilet_id
JOIN toilet_translation ko ON ko.toilet_id=tr.toilet_id AND ko.locale='ko'"""
    guard = f"""BINARY tr.name=BINARY p.expected_name
 AND BINARY t.name=BINARY p.original_name
 AND tr.version=p.expected_version AND tr.manual_override={int(rollback)}
 AND tr.translation_status='MACHINE_TRANSLATED'
 AND BINARY tr.translation_source=BINARY p.expected_source
 AND tr.address_translation_status='TRANSLATED'
 AND BINARY tr.address_translation_source=BINARY p.address_source
 AND SHA2(CONCAT(COALESCE(tr.road_address,''),CHAR(31),COALESCE(tr.jibun_address,'')),256)=p.address_hash
 AND tr.source_hash=p.source_hash AND ko.source_hash=p.source_hash
 AND ko.source_hash=SHA2(CONCAT(COALESCE(TRIM(t.name),''),CHAR(31),
     COALESCE(TRIM(t.road_address),''),CHAR(31),COALESCE(TRIM(t.jibun_address),'')),256)"""
    trigger = """SELECT COUNT(*) INTO @name_fix_cache_trigger
 FROM information_schema.TRIGGERS
 WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='cache_toilet_translation_update'
 AND EVENT_OBJECT_TABLE='toilet_translation' AND ACTION_TIMING='AFTER' AND EVENT_MANIPULATION='UPDATE';"""
    result = f"SET NAMES utf8mb4;\nSTART TRANSACTION{' READ ONLY' if mode == 'check' else ''};\n"
    if mode != "check":
        # Lock all target/source rows, including ineligible ones, before counting.
        result += f"SELECT tr.toilet_id,tr.locale FROM {joins} ORDER BY tr.toilet_id,tr.locale FOR UPDATE;\n"
    result += trigger + f"\nSELECT COUNT(*) INTO @name_fix_eligible FROM {joins} WHERE {guard};\n"
    if mode != "check":
        result += f"""UPDATE {joins}
SET tr.name=p.next_name, tr.translation_source=p.next_source,
    tr.manual_override={int(not rollback)}, tr.version=tr.version+1, tr.updated_at=NOW()
WHERE @name_fix_eligible=4 AND @name_fix_cache_trigger=1 AND {guard};
SET @name_fix_changed=ROW_COUNT();
"""
    else:
        result += "SET @name_fix_changed=0;\n"
    result += f"""COMMIT;
SELECT JSON_OBJECT('mode','{mode}','eligible',@name_fix_eligible,
 'cacheTriggerPresent',@name_fix_cache_trigger=1,'changed',@name_fix_changed);
"""
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("check", "apply", "rollback"), default="check")
    args = parser.parse_args()
    rows = json.loads(MANIFEST.read_text(encoding="utf-8"))
    print(build_sql(rows, args.mode), end="")


if __name__ == "__main__":
    main()
