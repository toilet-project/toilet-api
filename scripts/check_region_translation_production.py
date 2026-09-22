"""Read-only production gate for the canonical region translation rollout.

The expected codes are extracted from the committed V32 migration by the workflow.
No migration or data mutation is performed by this script.
"""

import argparse
import json
import os
import re
import subprocess
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect(container):
    obj = json.loads(subprocess.run(
        ["docker", "inspect", container], check=True, capture_output=True, text=True,
        timeout=15).stdout)[0]
    require(obj["State"]["Running"], "required container is not running")
    return dict(item.split("=", 1) for item in obj["Config"]["Env"] if "=" in item)


def query(sql, username, password):
    environment = dict(os.environ, MYSQL_PWD=password)
    result = subprocess.run(
        ["docker", "exec", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
         "--protocol=socket", "--batch", "--raw", "--skip-column-names",
         "-u", username, "toilet_db", "-e",
         "SET SESSION MAX_EXECUTION_TIME=10000; START TRANSACTION READ ONLY; " + sql + "; ROLLBACK"],
        check=True, capture_output=True, text=True, timeout=30, env=environment)
    return result.stdout.strip()


def integer(sql, username, password):
    result = query(sql, username, password)
    require(re.fullmatch(r"[0-9]+", result) is not None, "invalid count response")
    return int(result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("before", "after"), required=True)
    parser.add_argument("--expected-codes", required=True)
    args = parser.parse_args()
    codes = args.expected_codes.split(",")
    require(len(codes) == 287 and len(set(codes)) == 287 and
            all(re.fullmatch(r"[0-9]{5}", code) for code in codes),
            "expected migration codes invalid")

    api = inspect("toilet-api")
    inspect("toilet-batch")
    inspect("toilet-mysql")
    username = api["SPRING_DB_USERNAME"]
    password = api["SPRING_DB_PASSWORD"]
    quoted_codes = ",".join("'" + code + "'" for code in codes)
    migration = lambda version: integer(
        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='" + version + "' AND success=1",
        username, password)
    reference_count = integer(
        "SELECT COUNT(*) FROM region_sigungu_reference WHERE sigungu_code IN (" + quoted_codes + ")",
        username, password)
    table_count = integer(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
        "AND table_name='region_sigungu_translation'", username, password)
    result = {
        "mode": args.mode,
        "v17": migration("17"),
        "v18": migration("18"),
        "expectedReferenceCodes": reference_count,
        "translationTablePresent": table_count == 1,
        "v31": migration("31"),
        "v32": migration("32"),
    }
    require(result["v17"] == result["v18"] == 1 and reference_count == 287,
            "canonical reference does not match migration")
    if args.mode == "before":
        require(table_count == result["v31"] == result["v32"] == 0,
                "translation migration is not in the expected pre-release state")
    else:
        require(table_count == result["v31"] == result["v32"] == 1,
                "translation migration did not complete")
        result["translationRows"] = integer(
            "SELECT COUNT(*) FROM region_sigungu_translation", username, password)
        result["translatedCodes"] = integer(
            "SELECT COUNT(DISTINCT sigungu_code) FROM region_sigungu_translation",
            username, password)
        result["incompleteCodes"] = integer(
            "SELECT COUNT(*) FROM (SELECT sigungu_code FROM region_sigungu_translation "
            "GROUP BY sigungu_code HAVING COUNT(DISTINCT locale)<>5) missing",
            username, password)
        require(result["translationRows"] == 1435 and result["translatedCodes"] == 287
                and result["incompleteCodes"] == 0, "translation rows incomplete")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("REGION_TRANSLATION_READ_ONLY_CHECK_FAILED: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
