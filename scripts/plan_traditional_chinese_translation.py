#!/usr/bin/env python3
"""Count missing Taiwan/Hong Kong translations without exporting source text."""
import collections
import json
import os
from pathlib import Path
import sqlite3
import subprocess


LOCALES = ("zh-tw", "zh-hk")
LEDGER_PATH = Path("/tmp/toilet-traditional-translation-20260923/ledger.sqlite")
SOURCE_HASH = "SHA2(CONCAT(COALESCE(TRIM(t.name),''),CHAR(31),COALESCE(TRIM(t.road_address),''),CHAR(31),COALESCE(TRIM(t.jibun_address),'')),256)"
FACILITY_SQL = """START TRANSACTION READ ONLY;
SELECT JSON_OBJECT('kind','facility','locale',lang.locale,'name',TRIM(ko.name),
 'road',NULLIF(TRIM(ko.road_address),''),'jibun',NULLIF(TRIM(ko.jibun_address),''))
FROM toilet t
JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko'
CROSS JOIN (SELECT 'zh-tw' locale UNION ALL SELECT 'zh-hk') lang
LEFT JOIN toilet_translation dst ON dst.toilet_id=t.toilet_id AND dst.locale=lang.locale
WHERE t.visibility_status='VISIBLE' AND NULLIF(TRIM(ko.name),'') IS NOT NULL
 AND ko.source_hash=""" + SOURCE_HASH + """ AND dst.toilet_id IS NULL;
COMMIT;"""
GROUP_SQL = """START TRANSACTION READ ONLY;
SELECT JSON_OBJECT('kind','group','locale',lang.locale,'name',TRIM(g.display_name))
FROM toilet_display_group g
CROSS JOIN (SELECT 'zh-tw' locale UNION ALL SELECT 'zh-hk') lang
LEFT JOIN toilet_display_group_translation dst ON dst.group_id=g.group_id AND dst.locale=lang.locale
WHERE NULLIF(TRIM(g.display_name),'') IS NOT NULL AND dst.group_id IS NULL;
COMMIT;"""
CACHE_DELIVERY_SQL = """START TRANSACTION READ ONLY;
SELECT JSON_OBJECT('pending',COALESCE(SUM(delivered_at IS NULL),0),
 'oldestPendingSeconds',COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,
 MIN(CASE WHEN delivered_at IS NULL THEN first_queued_at END),UTC_TIMESTAMP(6))),0),
 'pendingWithErrors',COALESCE(SUM(delivered_at IS NULL AND attempts > 0),0),
 'dueNow',COALESCE(SUM(delivered_at IS NULL AND next_attempt_at<=UTC_TIMESTAMP(6)),0),
 'maximumAttempts',COALESCE(MAX(CASE WHEN delivered_at IS NULL THEN attempts END),0),
 'delivered',COALESCE(SUM(delivered_at IS NOT NULL),0))
FROM web_cache_invalidation;
COMMIT;"""
CACHE_ERRORS_SQL = """START TRANSACTION READ ONLY;
SELECT JSON_OBJECT('code',COALESCE(last_error_code,'NONE'),'rows',COUNT(*))
FROM web_cache_invalidation WHERE delivered_at IS NULL
GROUP BY last_error_code;
COMMIT;"""
TRANSLATION_TRIGGER_SQL = """START TRANSACTION READ ONLY;
SELECT JSON_OBJECT('installedTranslationTriggers',COUNT(*))
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN
 ('cache_toilet_translation_insert','cache_toilet_translation_update',
  'cache_toilet_translation_delete');
COMMIT;"""


def summarize(rows, include_sources=False):
    counts = collections.Counter()
    unique = {locale: set() for locale in LOCALES}
    raw_characters = collections.Counter()
    for row in rows:
        locale = row["locale"]
        kind = row["kind"]
        if locale not in unique or kind not in ("facility", "group"):
            raise ValueError("unexpected source category")
        counts[f"{locale}:{kind}"] += 1
        fields = ("name", "road", "jibun") if kind == "facility" else ("name",)
        for field in fields:
            value = row.get(field)
            if isinstance(value, str) and value.strip():
                text = value.strip()
                unique[locale].add(text)
                raw_characters[locale] += len(text)
        if kind == "facility" and not row.get("road") and not row.get("jibun"):
            counts[f"{locale}:missingAddress"] += 1
    per_locale = {locale: {
        "facilityRows": counts[f"{locale}:facility"],
        "groupRows": counts[f"{locale}:group"],
        "missingAddressRows": counts[f"{locale}:missingAddress"],
        "uniqueTexts": len(unique[locale]),
        "sourceCharactersBeforeDedup": raw_characters[locale],
        "billableInputCharacters": sum(map(len, unique[locale])),
    } for locale in LOCALES}
    report = {"schema": 1, "sourceLanguage": "ko", "targets": per_locale,
              "billableInputCharacters": sum(item["billableInputCharacters"] for item in per_locale.values()),
              "rawSourceExported": False}
    return (report, unique) if include_sources else report


def query(sql, credentials):
    env = dict(os.environ, MYSQL_PWD=credentials["SPRING_DB_PASSWORD"])
    command = ["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
               "--protocol=tcp", "-h127.0.0.1", "--default-character-set=utf8mb4",
               "--batch", "--raw", "--skip-column-names", "-u", credentials["SPRING_DB_USERNAME"], "toilet_db"]
    result = subprocess.run(command, input="SET NAMES utf8mb4;\n" + sql, env=env,
                            text=True, capture_output=True, check=False)
    if result.returncode:
        import re
        match = re.search(r"ERROR ([0-9]{3,5})", result.stderr)
        raise RuntimeError("read-only MySQL query failed: " + (match.group(1) if match else "unknown"))
    return (json.loads(line) for line in result.stdout.splitlines() if line.strip())


def ledger_progress(path=LEDGER_PATH, source_texts=None):
    if not path.is_file():
        return None
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=5)
    try:
        requests = connection.execute("SELECT locale,COUNT(*),SUM(input_chars),"
            "SUM(CASE WHEN output_chars IS NOT NULL THEN input_chars ELSE 0 END),"
            "SUM(COALESCE(output_chars,0)),SUM(output_chars IS NULL),SUM(cost_micro_usd) "
            "FROM requests GROUP BY locale").fetchall()
        cached_entries = dict(connection.execute("SELECT locale,COUNT(*) FROM translations GROUP BY locale"))
        translated_sources = {}
        if source_texts is not None:
            for locale, sources in source_texts.items():
                values = list(sources)
                completed = 0
                for offset in range(0, len(values), 400):
                    group = values[offset:offset + 400]
                    placeholders = ",".join("?" for _ in group)
                    completed += connection.execute(
                        "SELECT COUNT(*) FROM translations WHERE locale=? AND source IN (" + placeholders + ")",
                        [locale, *group]).fetchone()[0]
                translated_sources[locale] = completed
        return {locale: {"translatedUniqueTexts": translated_sources.get(locale, cached_entries.get(locale, 0)),
                         "cachedEntries": cached_entries.get(locale, 0), "requestCount": count,
                         "attemptedInputCharacters": attempted, "completedInputCharacters": completed,
                         "outputCharacters": output, "uncertainRequests": uncertain,
                         "estimatedCostUsd": round(cost / 1_000_000, 4)}
                for locale, count, attempted, completed, output, uncertain, cost in requests}
    finally:
        connection.close()


def main():
    inspected = subprocess.run(["docker", "inspect", "toilet-api"], capture_output=True, text=True, check=False)
    if inspected.returncode:
        raise RuntimeError("API container inspection failed")
    values = json.loads(inspected.stdout)[0]["Config"]["Env"]
    credentials = dict(value.split("=", 1) for value in values if "=" in value)
    if not credentials.get("SPRING_DB_USERNAME") or not credentials.get("SPRING_DB_PASSWORD"):
        raise RuntimeError("database credentials unavailable")
    report, sources = summarize([*query(FACILITY_SQL, credentials), *query(GROUP_SQL, credentials)],
                                include_sources=True)
    report["translationProgress"] = ledger_progress(source_texts=sources)
    delivery = next(query(CACHE_DELIVERY_SQL, credentials))
    delivery.update(next(query(TRANSLATION_TRIGGER_SQL, credentials)))
    delivery["pendingByLastError"] = list(query(CACHE_ERRORS_SQL, credentials))
    report["cacheDelivery"] = delivery
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error) if isinstance(error, (ValueError, RuntimeError)) else "translation planning failed",
              file=__import__("sys").stderr)
        raise SystemExit(1)
