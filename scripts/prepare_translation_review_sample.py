#!/usr/bin/env python3
import argparse
import collections
import hashlib
import json
from pathlib import Path


QUOTAS = {
    "ROAD_SUCCESS": 55,
    "JIBUN_SUCCESS": 10,
    "ROAD_NO_RESULT": 25,
    "JIBUN_NO_RESULT": 10,
}


def stratum(row: dict) -> str:
    kind = str(row.get("addressKind") or "")
    status = "SUCCESS" if row.get("roadAddress") or row.get("jibunAddress") else "NO_RESULT"
    return f"{kind}_{status}"


def rank(toilet_id: int) -> str:
    return hashlib.sha256(f"review-100:{toilet_id}".encode()).hexdigest()


def select(rows: list[dict]) -> list[dict]:
    if len(rows) != 1000:
        raise ValueError(f"expected 1000 pilot rows, got {len(rows)}")
    selected: list[dict] = []
    for category, quota in QUOTAS.items():
        groups: dict[str, list[dict]] = collections.defaultdict(list)
        for row in rows:
            if stratum(row) == category:
                groups[str(row.get("region") or "UNKNOWN")].append(row)
        for group in groups.values():
            group.sort(key=lambda row: rank(int(row["toiletId"])))
        regions = sorted(groups, key=lambda name: (-len(groups[name]), name))
        if sum(map(len, groups.values())) < quota:
            raise ValueError(f"not enough rows for {category}")
        offset = 0
        category_rows: list[dict] = []
        while len(category_rows) < quota:
            region = regions[offset % len(regions)]
            offset += 1
            if groups[region]:
                row = dict(groups[region].pop(0))
                row["reviewStratum"] = category
                category_rows.append(row)
        selected.extend(category_rows)
    if len(selected) != 100 or len({row["toiletId"] for row in selected}) != 100:
        raise ValueError("review sample must contain 100 unique rows")
    return selected


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.results.read_text(encoding="utf-8").splitlines() if line.strip()]
    selected = select(rows)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "ids.txt").write_text(",".join(str(row["toiletId"]) for row in selected), encoding="ascii")
    counts = collections.Counter(row["reviewStratum"] for row in selected)
    manifest = {
        "sampleCount": len(selected),
        "uniqueToiletIds": len({row["toiletId"] for row in selected}),
        "strata": dict(sorted(counts.items())),
        "regionCount": len({row.get("region") for row in selected}),
        "selectionRule": "review-100 sha256 rank with region round-robin",
    }
    (args.output_dir / "review-sample-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
