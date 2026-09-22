#!/usr/bin/env python3
"""Validate translated code labels and build the SQL seed and web snapshot together."""

import argparse
import json
import os
from pathlib import Path
import re

LOCALES = ("en", "ja", "zh-CN", "zh-TW", "zh-HK")
HANGUL = re.compile("[가-힣]")
PREFIXES = {
    "ja": {
        "12": ("全南光州統合特別市", "全南光州統合特区", "全羅南道光州統合特別市"),
        "51": ("江原特別自治道", "江原道"),
        "52": ("全北特別自治道", "全羅北特別自治道"),
    },
    "zh-CN": {
        "12": ("全南光州综合特区", "全南光州综合特别市", "光州综合特区", "全罗南道光州综合特别市"),
        "51": ("江原特别自治道", "江原道"),
        "52": ("全罗北特别自治道", "全北特别自治道"),
    },
    "zh-TW": {
        "12": ("全南光州綜合特區", "全南光州綜合特別市", "全羅南道光州綜合特區", "光州綜合特區"),
        "51": ("江原特別自治道", "江原道"),
        "52": ("全羅北特別自治道", "全北特別自治道"),
    },
}
PREFIXES["zh-HK"] = PREFIXES["zh-TW"]
SUFFIX = {"ja": {"구": "区", "군": "郡", "시": "市"},
          "zh-CN": {"구": "区", "군": "郡", "시": "市"},
          "zh-TW": {"구": "區", "군": "郡", "시": "市"},
          "zh-HK": {"구": "區", "군": "郡", "시": "市"}}
NAME_CORRECTIONS = {
    "11380": {"ja": "恩平区"},
    "12210": {"zh-CN": "东区"},
    "12240": {"zh-CN": "西区", "zh-TW": "西區", "zh-HK": "西區"},
    "12270": {"zh-CN": "南区", "zh-TW": "南區", "zh-HK": "南區"},
    "12300": {"zh-CN": "北区", "zh-TW": "北區", "zh-HK": "北區"},
    "12730": {"zh-CN": "求礼郡", "zh-TW": "求禮郡", "zh-HK": "求禮郡"},
    "12740": {"zh-CN": "高兴郡", "zh-TW": "高興郡", "zh-HK": "高興郡"},
    "12770": {"zh-CN": "长兴郡", "zh-TW": "長興郡", "zh-HK": "長興郡"},
    "12810": {"ja": "務安郡", "zh-CN": "务安郡", "zh-TW": "務安郡", "zh-HK": "務安郡"},
    "12830": {"zh-CN": "灵光郡", "zh-TW": "靈光郡", "zh-HK": "靈光郡"},
    "12850": {"zh-CN": "莞岛郡", "zh-TW": "莞島郡", "zh-HK": "莞島郡"},
    "12860": {"zh-CN": "珍岛郡", "zh-TW": "珍島郡", "zh-HK": "珍島郡"},
    "12870": {"zh-CN": "新安郡", "zh-TW": "新安郡", "zh-HK": "新安郡"},
    "26230": {"ja": "釜山鎮区"},
    "26380": {"ja": "沙下区"},
    "27710": {"ja": "達城郡"},
    "27720": {"ja": "軍威郡"},
    "28125": {"ja": "済物浦区"},
    "36110": {"en": "Sejong", "ja": "世宗特別自治市"},
    "41461": {"ja": "処仁区"},
    "41595": {"ja": "華城市餅店区"},
    "43770": {"ja": "陰城郡"},
    "47830": {"ja": "高霊郡"},
    "48720": {"ja": "宜寧郡"},
    "52210": {"ja": "金堤市"},
}


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def display_name(row: dict, isolated: dict, locale: str, common_prefix: dict) -> str:
    corrected = NAME_CORRECTIONS.get(row["code"], {}).get(locale)
    if corrected:
        return corrected
    source = row["sourceName"]
    full = row["translations"][locale].strip()
    alone = isolated["translations"][locale].strip()
    if locale == "en":
        candidate = alone if re.search(r"-(?:gu|gun|si)$", alone) else full.split(",")[0].strip()
        if " " in source and source.split(" ")[0].endswith("시"):
            pieces = [piece.strip() for piece in full.split(",")]
            if len(pieces) > 1 and (pieces[1].endswith("-si") or pieces[1].endswith(" City")):
                return ", ".join(pieces[:2])
        if source.endswith("시") and alone.endswith(" City"):
            return alone[:-5] + "-si"
        if source.endswith("군") and alone.endswith(" County"):
            return alone[:-7] + "-gun"
        if source.endswith("시") and candidate.endswith(" City"):
            candidate = candidate[:-5] + "-si"
        elif source.endswith("군") and candidate.endswith(" County"):
            candidate = candidate[:-7] + "-gun"
        if source.endswith("구") and not candidate.endswith("-gu"):
            candidate = full.split(",")[0].strip()
        return candidate
    province = row["code"][:2]
    if province == "12" and locale.startswith("zh"):
        expected = next((end for korean, end in SUFFIX[locale].items() if source.endswith(korean)), None)
        if expected and alone.endswith(expected):
            return alone
    prefixes = (*PREFIXES.get(locale, {}).get(province, ()), common_prefix.get((province, locale), ""))
    for prefix in sorted((value for value in prefixes if len(value) >= 3), key=len, reverse=True):
        if full.startswith(prefix):
            full = full[len(prefix):].strip(" ,、，·")
            break
    # Some provider responses reverse the order: "Naju City, Jeonnam...".
    full = re.split(r"[,、，]", full, maxsplit=1)[0].strip()
    expected = next((end for korean, end in SUFFIX[locale].items() if source.endswith(korean)), None)
    if expected and not full.endswith(expected):
        if alone.endswith(expected):
            full = alone
        elif source.endswith("군") and full.endswith("县"):
            full = full[:-1] + expected
        else:
            # A romanized proper name is preferable to a misleading translation
            # such as treating "병점구" as a hospital or "제물포구" as a port.
            return display_name(row, isolated, "en", common_prefix)
    return full


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("context", type=Path)
    parser.add_argument("isolated", type=Path)
    parser.add_argument("web_geojson", type=Path)
    parser.add_argument("sql_output", type=Path)
    parser.add_argument("web_output", type=Path)
    args = parser.parse_args()
    context = json.loads(args.context.read_text(encoding="utf-8"))["districts"]
    isolated = {row["code"]: row for row in json.loads(args.isolated.read_text(encoding="utf-8"))["districts"]}
    geo_codes = {feature["properties"]["sgg"] for feature in json.loads(args.web_geojson.read_text(encoding="utf-8"))["features"]}
    codes = {row["code"] for row in context}
    if len(context) != 287 or codes != set(isolated) or not geo_codes <= codes:
        raise ValueError("Canonical district code coverage changed")
    if any(row["sourceName"] != isolated[row["code"]]["sourceName"] for row in context):
        raise ValueError("Translation source names disagree for the same code")
    common_prefix = {}
    for province in {code[:2] for code in codes}:
        for locale in LOCALES[1:]:
            names = [row["translations"][locale] for row in context if row["code"].startswith(province)]
            if len(names) > 1:
                common_prefix[(province, locale)] = os.path.commonprefix(names)
    snapshot = {}
    sql = ["-- Generated from the validated code-keyed translation snapshot. No toilet rows are copied.",
           "INSERT INTO region_sigungu_translation",
           "    (sigungu_code, locale, source_name, display_name, translation_source, reviewed)", "VALUES"]
    values = []
    for row in context:
        names = {}
        for locale in LOCALES:
            label = display_name(row, isolated[row["code"]], locale, common_prefix)
            if not label or len(label) > 160 or HANGUL.search(label):
                raise ValueError(f"Invalid {locale} label for {row['code']}")
            names[locale] = label
            source = "EDITORIAL_OVERRIDE" if locale in NAME_CORRECTIONS.get(row["code"], {}) else "GOOGLE_CLOUD"
            values.append("    (" + ", ".join(map(sql_string, (row["code"], locale, row["sourceName"], label, source))) + ", FALSE)")
        if row["code"] in geo_codes:
            snapshot[row["code"]] = names
    sql.append(",\n".join(values) + ";")
    args.sql_output.write_text("\n".join(sql) + "\n", encoding="utf-8")
    args.web_output.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"referenceCodes": len(context), "webCodes": len(snapshot), "translationRows": len(values)}))


if __name__ == "__main__":
    main()
