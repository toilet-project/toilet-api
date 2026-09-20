#!/usr/bin/env python3
"""Prepare, translate, and audit the isolated 1,000-row English pilot."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Iterable

HANGUL = re.compile(r"[\uac00-\ud7a3]")
NUMBER = re.compile(r"\d+(?:[.-]\d+)*")
JUSO_UNSUPPORTED = re.compile(r"[%=><\[\]]+")
HEX64 = re.compile(r"[0-9a-f]{64}")
GOOGLE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
JUSO_ENDPOINT = "https://business.juso.go.kr/addrlink/addrEngApi.do"
PROVIDER_SOURCE = "PILOT_GOOGLE_NMT_JUSO"
PROVIDER_SOURCE_PATTERN = re.compile(r"[A-Z0-9_]{1,40}")


def read_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    seen: set[int] = set()
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON") from exc
            toilet_id = int(row.get("toiletId", 0))
            if toilet_id <= 0 or toilet_id in seen:
                raise ValueError(f"{path}:{line_number}: invalid or duplicate toiletId")
            seen.add(toilet_id)
            rows.append(row)
    return rows


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def selected_address(row: dict) -> tuple[str, str | None]:
    road = clean(row.get("roadAddress"))
    jibun = clean(row.get("jibunAddress"))
    expected_kind = "ROAD" if road else "JIBUN" if jibun else "NONE"
    expected_value = road or jibun
    if row.get("selectedAddressKind") != expected_kind:
        raise ValueError(f"toiletId {row.get('toiletId')}: address priority mismatch")
    if clean(row.get("selectedAddress")) != expected_value:
        raise ValueError(f"toiletId {row.get('toiletId')}: selected address mismatch")
    return expected_kind, expected_value


def clean(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def validate_source(rows: list[dict], expected_count: int) -> dict:
    if len(rows) != expected_count:
        raise ValueError(f"expected {expected_count} rows, found {len(rows)}")
    address_kinds: Counter[str] = Counter()
    regions: Counter[str] = Counter()
    types: Counter[str] = Counter()
    source_hashes: set[str] = set()
    name_chars = 0
    address_chars = 0
    for row in rows:
        name = clean(row.get("name"))
        source_hash = clean(row.get("sourceHash"))
        if not name:
            raise ValueError(f"toiletId {row.get('toiletId')}: name is required")
        if not source_hash or not HEX64.fullmatch(source_hash.lower()):
            raise ValueError(f"toiletId {row.get('toiletId')}: invalid source hash")
        source_hashes.add(source_hash.lower())
        kind, address = selected_address(row)
        address_kinds[kind] += 1
        regions[str(row.get("region") or "미분류")] += 1
        types[str(row.get("toiletType") or "미분류")] += 1
        name_chars += len(name)
        address_chars += len(address or "")
    billable_chars = name_chars
    return {
        "sampleCount": len(rows),
        "uniqueSourceHashes": len(source_hashes),
        "addressPriority": "ROAD_THEN_JIBUN",
        "addressKinds": dict(sorted(address_kinds.items())),
        "regions": dict(sorted(regions.items())),
        "toiletTypes": dict(sorted(types.items())),
        "nameCharactersForGoogle": name_chars,
        "addressCharactersForJuso": address_chars,
        "googleBillableCharacters": billable_chars,
        "estimatedGoogleNmtCostUsdBeforeFreeCredit": round(billable_chars * 20 / 1_000_000, 4),
        "sourceContainsMemberOrReviewData": False,
    }


def post_json(url: str, payload: dict, attempts: int = 3) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt + 1 == attempts:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def get_json(url: str, params: dict[str, str], attempts: int = 3) -> dict:
    request_url = url + "?" + urllib.parse.urlencode(params)
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request_url, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt + 1 == attempts:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def translate_names(names: list[str], api_key: str) -> list[str]:
    response = post_json(
        GOOGLE_ENDPOINT + "?" + urllib.parse.urlencode({"key": api_key}),
        {"q": names, "source": "ko", "target": "en", "format": "text"},
    )
    translations = response.get("data", {}).get("translations", [])
    if len(translations) != len(names):
        raise RuntimeError("Google Translation returned an unexpected result count")
    return [html.unescape(item["translatedText"]).strip() for item in translations]


def normalize_spaces(value: str) -> str:
    return " ".join(value.split())


def translate_address(address: str, api_key: str, address_kind: str) -> str:
    search_keyword = normalize_spaces(JUSO_UNSUPPORTED.sub(" ", address))
    if not search_keyword:
        raise LookupError("official English address search keyword is empty")
    response = get_json(JUSO_ENDPOINT, {
        "confmKey": api_key,
        "currentPage": "1",
        "countPerPage": "10",
        "keyword": search_keyword,
        "resultType": "json",
    })
    results = response.get("results", {})
    common = results.get("common", {})
    if str(common.get("errorCode", "0")) != "0":
        raise RuntimeError(f"Juso API error: {common.get('errorMessage', 'unknown')}")
    candidates = results.get("juso") or []
    if not candidates:
        raise LookupError("no official English address result")
    normalized = normalize_spaces(address)
    exact = next((item for item in candidates if normalize_spaces(str(item.get("korAddr", ""))) == normalized), None)
    selected = exact or candidates[0]
    result_field = "roadAddr" if address_kind == "ROAD" else "jibunAddr"
    translated = clean(selected.get(result_field))
    if not translated:
        raise LookupError("official English address is empty")
    return translated


def translate(args: argparse.Namespace) -> None:
    google_key = os.environ.get("GOOGLE_TRANSLATION_API_KEY", "").strip()
    juso_key = os.environ.get("JUSO_ENGLISH_API_KEY", "").strip()
    if not google_key or not juso_key:
        raise RuntimeError("GOOGLE_TRANSLATION_API_KEY and JUSO_ENGLISH_API_KEY are required")
    provider_source = os.environ.get("TRANSLATION_PROVIDER_SOURCE", PROVIDER_SOURCE).strip()
    if not PROVIDER_SOURCE_PATTERN.fullmatch(provider_source):
        raise RuntimeError("TRANSLATION_PROVIDER_SOURCE is invalid")
    rows = read_jsonl(args.source)
    validate_source(rows, args.expected_count)
    completed = {row["toiletId"]: row for row in read_jsonl(args.output)} if args.output.exists() else {}
    pending = [row for row in rows if row["toiletId"] not in completed]
    for offset in range(0, len(pending), args.batch_size):
        batch = pending[offset: offset + args.batch_size]
        translated_names = translate_names([str(row["name"]) for row in batch], google_key)

        def address_result(source: dict) -> tuple[str, str | None, str | None]:
            kind, address = selected_address(source)
            translated_address = None
            address_error = None
            if address:
                try:
                    translated_address = translate_address(address, juso_key, kind)
                except LookupError as exc:
                    address_error = str(exc)
                finally:
                    if args.request_interval:
                        time.sleep(args.request_interval)
            return kind, translated_address, address_error

        if args.address_workers == 1:
            address_results = [address_result(source) for source in batch]
        else:
            with ThreadPoolExecutor(max_workers=args.address_workers, thread_name_prefix="juso-address") as executor:
                address_results = list(executor.map(address_result, batch))

        for source, translated_name, address_values in zip(batch, translated_names, address_results, strict=True):
            kind, translated_address, address_error = address_values
            result = {
                "toiletId": source["toiletId"],
                "locale": "en",
                "name": translated_name,
                "roadAddress": translated_address if kind == "ROAD" else None,
                "jibunAddress": translated_address if kind == "JIBUN" else None,
                "expectedSourceHash": source["sourceHash"].lower(),
                "source": provider_source,
                "nameProvider": "GOOGLE_CLOUD_TRANSLATION_BASIC",
                "addressProvider": "MOIS_JUSO_ENGLISH" if address else None,
                "addressKind": kind,
                "addressError": address_error,
                "region": source.get("region"),
                "toiletType": source.get("toiletType"),
                "translatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            }
            completed[result["toiletId"]] = result
        write_jsonl(args.output, (completed[key] for key in sorted(completed)))


def balanced_review_sample(source_rows: list[dict], result_rows: list[dict], count: int) -> list[tuple[dict, dict]]:
    source_by_id = {row["toiletId"]: row for row in source_rows}
    pairs = [(source_by_id[row["toiletId"]], row) for row in result_rows if row["toiletId"] in source_by_id]
    return sorted(
        pairs,
        key=lambda pair: (
            hashlib.sha256(f"{pair[0].get('region')}:{pair[0].get('toiletType')}:{pair[0]['toiletId']}".encode()).hexdigest(),
            pair[0]["toiletId"],
        ),
    )[:count]


def audit_results(source_rows: list[dict], result_rows: list[dict]) -> dict:
    sources = {row["toiletId"]: row for row in source_rows}
    results = {row["toiletId"]: row for row in result_rows}
    issues: list[dict] = []
    translated_address_count = 0
    address_error_counts: Counter[str] = Counter()
    google_characters_submitted = 0
    for toilet_id, source in sources.items():
        result = results.get(toilet_id)
        if not result:
            issues.append({"toiletId": toilet_id, "code": "MISSING_RESULT"})
            continue
        if result.get("expectedSourceHash") != str(source["sourceHash"]).lower():
            issues.append({"toiletId": toilet_id, "code": "SOURCE_HASH_MISMATCH"})
        name = clean(result.get("name"))
        google_characters_submitted += len(str(source.get("name") or ""))
        if not name:
            issues.append({"toiletId": toilet_id, "code": "EMPTY_NAME"})
        elif HANGUL.search(name):
            issues.append({"toiletId": toilet_id, "code": "HANGUL_IN_NAME"})
        elif len(name) > 255:
            issues.append({"toiletId": toilet_id, "code": "NAME_TOO_LONG"})
        source_numbers = set(NUMBER.findall(str(source.get("name") or "")))
        result_numbers = set(NUMBER.findall(name or ""))
        if not source_numbers.issubset(result_numbers):
            issues.append({"toiletId": toilet_id, "code": "NAME_NUMBER_LOSS"})
        kind, source_address = selected_address(source)
        road = clean(result.get("roadAddress"))
        jibun = clean(result.get("jibunAddress"))
        address_error = clean(result.get("addressError"))
        if kind == "ROAD" and not address_error and (not road or jibun):
            issues.append({"toiletId": toilet_id, "code": "ROAD_PRIORITY_VIOLATION"})
        if kind == "JIBUN" and not address_error and (road or not jibun):
            issues.append({"toiletId": toilet_id, "code": "JIBUN_FALLBACK_VIOLATION"})
        if kind == "NONE" and (road or jibun):
            issues.append({"toiletId": toilet_id, "code": "UNEXPECTED_ADDRESS"})
        translated_address = road or jibun
        if translated_address:
            translated_address_count += 1
            if HANGUL.search(translated_address):
                issues.append({"toiletId": toilet_id, "code": "HANGUL_IN_ADDRESS"})
        if address_error:
            address_error_counts[address_error] += 1
        if source_address and not translated_address and not result.get("addressError"):
            issues.append({"toiletId": toilet_id, "code": "MISSING_ADDRESS_WITHOUT_ERROR"})
        if translated_address and len(translated_address) > 500:
            issues.append({"toiletId": toilet_id, "code": "ADDRESS_TOO_LONG"})
    issue_counts = Counter(issue["code"] for issue in issues)
    return {
        "sourceCount": len(source_rows),
        "resultCount": len(result_rows),
        "issueCount": len(issues),
        "issueCounts": dict(sorted(issue_counts.items())),
        "translatedAddressCount": translated_address_count,
        "addressLookupErrorCount": sum(address_error_counts.values()),
        "addressLookupErrorCounts": dict(sorted(address_error_counts.items())),
        "googleCharactersSubmitted": google_characters_submitted,
        "estimatedGoogleNmtCostUsdBeforeFreeCredit": round(google_characters_submitted * 20 / 1_000_000, 4),
        "issues": issues,
        "readyForHumanReview": len(result_rows) == len(source_rows) and not any(
            issue["code"] in {"MISSING_RESULT", "SOURCE_HASH_MISMATCH", "EMPTY_NAME"} for issue in issues
        ),
    }


def command_audit_source(args: argparse.Namespace) -> None:
    report = validate_source(read_jsonl(args.source), args.expected_count)
    report["sourceFingerprintSha256"] = hashlib.sha256(args.source.read_bytes()).hexdigest()
    report["rawSourceRetained"] = False
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def command_audit_results(args: argparse.Namespace) -> None:
    source_rows = read_jsonl(args.source)
    result_rows = read_jsonl(args.results)
    report = audit_results(source_rows, result_rows)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.review_csv:
        pairs = balanced_review_sample(source_rows, result_rows, args.review_count)
        with args.review_csv.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["toilet_id", "region", "type", "ko_name", "en_name", "address_kind", "ko_address", "en_address", "decision", "note"])
            for source, result in pairs:
                kind, address = selected_address(source)
                writer.writerow([source["toiletId"], source.get("region"), source.get("toiletType"), source.get("name"), result.get("name"), kind, address, result.get("roadAddress") or result.get("jibunAddress"), "", ""])


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    audit_source = commands.add_parser("audit-source")
    audit_source.add_argument("source", type=Path)
    audit_source.add_argument("output", type=Path)
    audit_source.add_argument("--expected-count", type=int, default=1000)
    audit_source.set_defaults(handler=command_audit_source)
    translate_command = commands.add_parser("translate")
    translate_command.add_argument("source", type=Path)
    translate_command.add_argument("output", type=Path)
    translate_command.add_argument("--expected-count", type=int, default=1000)
    translate_command.add_argument("--batch-size", type=int, default=50, choices=range(1, 101))
    translate_command.add_argument("--request-interval", type=float, default=0.05)
    translate_command.add_argument("--address-workers", type=int, default=1, choices=range(1, 9))
    translate_command.set_defaults(handler=translate)
    audit_result = commands.add_parser("audit-results")
    audit_result.add_argument("source", type=Path)
    audit_result.add_argument("results", type=Path)
    audit_result.add_argument("output", type=Path)
    audit_result.add_argument("--review-csv", type=Path)
    audit_result.add_argument("--review-count", type=int, default=100)
    audit_result.set_defaults(handler=command_audit_results)
    return root


def main() -> int:
    try:
        args = parser().parse_args()
        args.handler(args)
        return 0
    except Exception as exc:  # concise failure for CI and resumable local runs
        print(f"translation pilot failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
