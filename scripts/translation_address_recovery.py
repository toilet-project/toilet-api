#!/usr/bin/env python3
"""Recover missing official English addresses without retranslating facility names."""

from __future__ import annotations

import argparse
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal, InvalidOperation
from pathlib import Path

JUSO_ENDPOINT = "https://business.juso.go.kr/addrlink/addrEngApi.do"
KAKAO_ENDPOINT = "https://dapi.kakao.com/v2/local/geo/coord2address.json"
HANGUL = re.compile(r"[\uac00-\ud7a3]")
UNSUPPORTED = re.compile(r"[%=><\[\]]+")
PARENTHETICAL = re.compile(r"\([^)]*\)|\[[^]]*\]")
ROAD_PREFIX = re.compile(r"^(.+?(?:대로|로|길)\s*\d+(?:-\d+)?)")
FLOOR_SUFFIX = re.compile(r"\s+(?:지하\s*)?\d+\s*층.*$")
HEX64 = re.compile(r"[0-9a-f]{64}")


class RateLimiter:
    def __init__(self, interval: float):
        self.interval = interval
        self.next_at = 0.0
        self.lock = threading.Lock()

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            delay = max(0.0, self.next_at - now)
            self.next_at = max(now, self.next_at) + self.interval
        if delay:
            time.sleep(delay)


class RecoveryCache:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        self.values = {"juso": {}, "kakao": {}}
        if path.exists():
            loaded = json.loads(path.read_text(encoding="utf-8"))
            for key in self.values:
                self.values[key].update(loaded.get(key) or {})

    def get(self, group: str, key: str):
        with self.lock:
            return self.values[group].get(key, _MISSING)

    def put(self, group: str, key: str, value) -> None:
        with self.lock:
            self.values[group][key] = value

    def save(self) -> None:
        with self.lock:
            self.path.write_text(json.dumps(self.values, ensure_ascii=False), encoding="utf-8")


_MISSING = object()


def clean(value) -> str | None:
    if value is None:
        return None
    result = " ".join(str(value).strip().split())
    return result or None


def read_jsonl(path: Path) -> list[dict]:
    rows, seen = [], set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        toilet_id = int(row.get("toiletId") or 0)
        if toilet_id <= 0 or toilet_id in seen:
            raise ValueError(f"{path}:{line_number}: invalid or duplicate toiletId")
        seen.add(toilet_id)
        rows.append(row)
    return rows


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def get_json(url: str, params: dict[str, str], headers: dict[str, str], limiter: RateLimiter, attempts: int = 3) -> dict:
    request = urllib.request.Request(url + "?" + urllib.parse.urlencode(params), headers=headers)
    for attempt in range(attempts):
        limiter.wait()
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code in {401, 403, 429}:
                raise RuntimeError(f"external address API rejected the request ({exc.code})") from exc
            if attempt + 1 == attempts:
                raise
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt + 1 == attempts:
                raise
        time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def normalized_region(value: str | None) -> str:
    aliases = {"서울": "서울특별시", "부산": "부산광역시", "대구": "대구광역시", "인천": "인천광역시",
               "광주": "광주광역시", "대전": "대전광역시", "울산": "울산광역시", "세종": "세종특별자치시",
               "경기": "경기도", "강원": "강원특별자치도", "충북": "충청북도", "충남": "충청남도",
               "전북": "전북특별자치도", "전남": "전라남도", "경북": "경상북도", "경남": "경상남도", "제주": "제주특별자치도"}
    text = clean(value) or ""
    return aliases.get(text, text).replace(" ", "")


def region_matches(candidate: str, sido: str | None, sigungu: str | None) -> bool:
    compact = candidate.replace(" ", "")
    expected_sido = normalized_region(sido)
    expected_sigungu = (clean(sigungu) or "").replace(" ", "")
    return (not expected_sido or expected_sido in compact) and (not expected_sigungu or expected_sigungu in compact)


def address_variants(address: str) -> list[str]:
    base = clean(UNSUPPORTED.sub(" ", address)) or ""
    candidates = [base, clean(PARENTHETICAL.sub(" ", base)), clean(base.split(",", 1)[0]), clean(FLOOR_SUFFIX.sub("", base))]
    road = ROAD_PREFIX.match(base)
    if road:
        candidates.append(clean(road.group(1)))
    unique = []
    for candidate in candidates:
        if candidate and candidate not in unique:
            unique.append(candidate)
    return unique[:5]


def choose_juso_candidate(response: dict, sido: str | None, sigungu: str | None) -> dict | None:
    results = response.get("results") or {}
    common = results.get("common") or {}
    code, message = str(common.get("errorCode", "0")), str(common.get("errorMessage", ""))
    if code != "0":
        fatal = ("key", "quota", "limit", "exceed", "service", "system", "temporar", "서버", "승인키", "일일")
        if code.upper() == "E0001" or any(term in message.lower() for term in fatal):
            raise RuntimeError(f"Juso API error: {message or code}")
        return None
    candidates = results.get("juso") or []
    return next((item for item in candidates if region_matches(str(item.get("korAddr") or ""), sido, sigungu)), None)


def lookup_juso(address: str, sido: str | None, sigungu: str | None, key: str,
                cache: RecoveryCache, limiter: RateLimiter) -> tuple[dict | None, int]:
    cache_key = "|".join((address, sido or "", sigungu or ""))
    cached = cache.get("juso", cache_key)
    if cached is not _MISSING:
        return cached, 0
    requests = 0
    for keyword in address_variants(address):
        response = get_json(JUSO_ENDPOINT, {
            "confmKey": key, "currentPage": "1", "countPerPage": "10",
            "keyword": keyword, "resultType": "json",
        }, {}, limiter)
        requests += 1
        selected = choose_juso_candidate(response, sido, sigungu)
        if selected:
            road, jibun = clean(selected.get("roadAddr")), clean(selected.get("jibunAddr"))
            value = {"roadAddress": road, "jibunAddress": None if road else jibun}
            if (road or jibun) and not HANGUL.search(road or jibun or ""):
                cache.put("juso", cache_key, value)
                return value, requests
    cache.put("juso", cache_key, None)
    return None, requests


def valid_coordinate(row: dict) -> bool:
    try:
        latitude, longitude = Decimal(str(row.get("latitude"))), Decimal(str(row.get("longitude")))
        return Decimal("33") <= latitude <= Decimal("39.5") and Decimal("124") <= longitude <= Decimal("132")
    except (InvalidOperation, TypeError):
        return False


def reverse_kakao(row: dict, key: str, cache: RecoveryCache, limiter: RateLimiter) -> tuple[dict | None, int, str | None]:
    if not valid_coordinate(row):
        return None, 0, "NO_VALID_COORDINATE"
    coordinate_key = f"{row['longitude']}|{row['latitude']}"
    cached = cache.get("kakao", coordinate_key)
    if cached is not _MISSING:
        document = cached
        requests = 0
    else:
        response = get_json(KAKAO_ENDPOINT, {
            "x": str(row["longitude"]), "y": str(row["latitude"]), "input_coord": "WGS84",
        }, {"Authorization": "KakaoAK " + key}, limiter)
        requests = 1
        documents = response.get("documents") or []
        document = documents[0] if documents else None
        cache.put("kakao", coordinate_key, document)
    if not document:
        return None, requests, "KAKAO_NO_ADDRESS"
    road = document.get("road_address") or {}
    jibun = document.get("address") or {}
    selected = road if clean(road.get("address_name")) else jibun
    korean = clean(selected.get("address_name"))
    candidate_region = " ".join(filter(None, [selected.get("region_1depth_name"), selected.get("region_2depth_name")]))
    if not korean:
        return None, requests, "KAKAO_NO_ADDRESS"
    if not region_matches(candidate_region or korean, row.get("sidoName"), row.get("sigunguName")):
        return None, requests, "REGION_MISMATCH"
    return {"koreanAddress": korean}, requests, None


def recover_row(row: dict, juso_key: str, kakao_key: str, cache: RecoveryCache,
                juso_limiter: RateLimiter, kakao_limiter: RateLimiter) -> dict:
    source_address = clean(row.get("roadAddress")) or clean(row.get("jibunAddress"))
    if not source_address:
        raise ValueError(f"toiletId {row.get('toiletId')}: source address is required")
    result = {
        "toiletId": int(row["toiletId"]), "expectedSourceHash": str(row["sourceHash"]).lower(),
        "recovered": False, "roadAddress": None, "jibunAddress": None,
        "addressTranslationStatus": "NEEDS_REVIEW", "addressTranslationSource": "RECOVERY_EXHAUSTED",
        "failureReason": None, "jusoRequestCount": 0, "kakaoRequestCount": 0,
    }
    official, count = lookup_juso(source_address, row.get("sidoName"), row.get("sigunguName"), juso_key, cache, juso_limiter)
    result["jusoRequestCount"] += count
    if official:
        result.update(official)
        result.update(recovered=True, addressTranslationStatus="TRANSLATED", addressTranslationSource="MOIS_JUSO_NORMALIZED")
        return result
    reverse, count, failure = reverse_kakao(row, kakao_key, cache, kakao_limiter)
    result["kakaoRequestCount"] += count
    if not reverse:
        result["failureReason"] = failure
        return result
    official, count = lookup_juso(reverse["koreanAddress"], row.get("sidoName"), row.get("sigunguName"), juso_key, cache, juso_limiter)
    result["jusoRequestCount"] += count
    if official:
        result.update(official)
        result.update(recovered=True, addressTranslationStatus="TRANSLATED", addressTranslationSource="KAKAO_REVERSE_MOIS_JUSO")
    else:
        result["failureReason"] = "JUSO_NO_RESULT_AFTER_REVERSE"
    return result


def command_recover(args: argparse.Namespace) -> None:
    juso_key = os.environ.get("JUSO_ENGLISH_API_KEY", "").strip()
    kakao_key = os.environ.get("KAKAO_REST_API_KEY", "").strip()
    if not juso_key or not kakao_key:
        raise RuntimeError("JUSO_ENGLISH_API_KEY and KAKAO_REST_API_KEY are required")
    rows = read_jsonl(args.source)
    if len(rows) != args.expected_count:
        raise ValueError(f"expected {args.expected_count} rows, found {len(rows)}")
    for row in rows:
        if not clean(row.get("name")) or not HEX64.fullmatch(str(row.get("sourceHash") or "").lower()):
            raise ValueError(f"toiletId {row.get('toiletId')}: invalid current translation source")
    cache = RecoveryCache(args.cache)
    juso_limiter, kakao_limiter = RateLimiter(0.11), RateLimiter(0.06)
    with ThreadPoolExecutor(max_workers=args.workers, thread_name_prefix="address-recovery") as executor:
        results = list(executor.map(
            lambda row: recover_row(row, juso_key, kakao_key, cache, juso_limiter, kakao_limiter), rows
        ))
    cache.save()
    write_jsonl(args.output, results)


def command_audit(args: argparse.Namespace) -> None:
    rows = read_jsonl(args.results)
    recovered = [row for row in rows if row.get("recovered")]
    failures = [row for row in rows if not row.get("recovered")]
    sources = Counter(str(row.get("addressTranslationSource") or "UNKNOWN") for row in rows)
    reasons = Counter(str(row.get("failureReason") or "NONE") for row in failures)
    report = {
        "resultCount": len(rows), "recoveredCount": len(recovered), "needsReviewCount": len(failures),
        "recoverySourceCounts": dict(sorted(sources.items())), "failureReasonCounts": dict(sorted(reasons.items())),
        "jusoRequestCount": sum(int(row.get("jusoRequestCount") or 0) for row in rows),
        "kakaoRequestCount": sum(int(row.get("kakaoRequestCount") or 0) for row in rows),
        "criticalIssueCount": sum(1 for row in recovered if not clean(row.get("roadAddress")) and not clean(row.get("jibunAddress"))),
        "rawSourceExported": False,
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if report["criticalIssueCount"]:
        raise RuntimeError("recovered rows contain an empty address")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    recover = commands.add_parser("recover")
    recover.add_argument("source", type=Path); recover.add_argument("output", type=Path)
    recover.add_argument("cache", type=Path); recover.add_argument("--expected-count", type=int, required=True)
    recover.add_argument("--workers", type=int, default=4, choices=range(1, 9)); recover.set_defaults(handler=command_recover)
    audit = commands.add_parser("audit")
    audit.add_argument("results", type=Path); audit.add_argument("output", type=Path); audit.set_defaults(handler=command_audit)
    return root


def main() -> int:
    try:
        args = parser().parse_args(); args.handler(args); return 0
    except Exception as exc:
        print(f"translation address recovery failed: {exc}", file=os.sys.stderr); return 1


if __name__ == "__main__":
    raise SystemExit(main())
