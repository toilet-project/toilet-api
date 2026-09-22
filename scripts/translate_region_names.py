#!/usr/bin/env python3
"""Create a bounded, reviewable translation artifact for canonical district codes."""

import argparse
import html
import json
import os
from pathlib import Path
import re
import time
import urllib.parse
import urllib.request

LOCALES = ("en", "ja", "zh-CN", "zh-TW", "zh-HK")
SOURCE_ROW = re.compile(
    r"^\s*\('([0-9]{5})','[0-9]{2}','([^']*)',(NULL|'[^']*'),",
    re.MULTILINE,
)
ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
MAX_BILLABLE_CHARACTERS = 20000


def source_rows(sql: str) -> list[dict[str, str]]:
    rows = []
    for match in SOURCE_ROW.finditer(sql):
        code, province, district = match.groups()
        name = province if district == "NULL" else district[1:-1]
        rows.append({"code": code, "sourceName": name})
    if len(rows) < 250 or len({row["code"] for row in rows}) != len(rows):
        raise ValueError("The canonical migration has missing or duplicate district codes")
    return rows


def translate(names: list[str], locale: str, key: str) -> list[str]:
    body = json.dumps({"q": names, "source": "ko", "target": locale, "format": "text"}, ensure_ascii=False).encode("utf-8")
    url = ENDPOINT + "?" + urllib.parse.urlencode({"key": key})
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)["data"]["translations"]
            if len(result) != len(names):
                raise ValueError("Translation result count changed")
            values = [html.unescape(item["translatedText"]).strip() for item in result]
            if any(not value or len(value) > 160 for value in values):
                raise ValueError("Translation was blank or too long")
            return values
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(2**attempt)
    raise AssertionError("unreachable")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_sql", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--translate", action="store_true")
    args = parser.parse_args()
    rows = source_rows(args.source_sql.read_text(encoding="utf-8"))
    distinct = list(dict.fromkeys(row["sourceName"] for row in rows))
    billable = sum(len(name) for name in distinct) * len(LOCALES)
    if billable > MAX_BILLABLE_CHARACTERS:
        raise ValueError(f"Translation budget exceeded: {billable} characters")
    print(json.dumps({"districts": len(rows), "distinctNames": len(distinct), "maxBillableCharacters": billable}))
    if args.translate:
        key = os.environ.get("GOOGLE_TRANSLATION_API_KEY", "").strip()
        if not key:
            raise ValueError("GOOGLE_TRANSLATION_API_KEY is required")
        values: dict[str, dict[str, str]] = {name: {} for name in distinct}
        for locale in LOCALES:
            for offset in range(0, len(distinct), 50):
                batch = distinct[offset : offset + 50]
                translated = translate(batch, locale, key)
                for name, display in zip(batch, translated):
                    values[name][locale] = display
                time.sleep(0.1)
        for row in rows:
            row["translations"] = values[row["sourceName"]]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"source": "region_sigungu_reference", "districts": rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
