#!/usr/bin/env python3
"""Append missing regional Traditional Chinese translations from current Korean text.

Run only on the production database host. Source text and the resumable SQLite
ledger stay in a mode-0700 local directory; workflow artifacts contain counts.
Existing translations are never replaced. Every Google request is reserved in a
durable cost ledger before it is sent, including retries and uncertain failures.
"""
import argparse
import collections
from concurrent.futures import ThreadPoolExecutor
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request

ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
TARGETS = {"zh-tw": ("zh-TW", "GOOGLE_NMT_KO"), "zh-hk": ("zh-HK", "GOOGLE_LLM_KO")}
SOURCE_HASH = "SHA2(CONCAT(COALESCE(TRIM(t.name),''),CHAR(31),COALESCE(TRIM(t.road_address),''),CHAR(31),COALESCE(TRIM(t.jibun_address),'')),256)"
LANG = "CROSS JOIN (SELECT 'zh-tw' locale UNION ALL SELECT 'zh-hk') lang"
FACILITY_WHERE = ("FROM toilet t JOIN toilet_translation ko ON ko.toilet_id=t.toilet_id AND ko.locale='ko' "
                  + LANG + " LEFT JOIN toilet_translation dst ON dst.toilet_id=t.toilet_id AND dst.locale=lang.locale "
                  "WHERE t.visibility_status='VISIBLE' AND NULLIF(TRIM(ko.name),'') IS NOT NULL "
                  "AND ko.source_hash=" + SOURCE_HASH + " AND dst.toilet_id IS NULL")
GROUP_WHERE = ("FROM toilet_display_group g " + LANG
               + " LEFT JOIN toilet_display_group_translation dst ON dst.group_id=g.group_id AND dst.locale=lang.locale "
               "WHERE NULLIF(TRIM(g.display_name),'') IS NOT NULL AND dst.group_id IS NULL")


def sql_text(value):
    return "NULL" if value is None else "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


class Database:
    def __init__(self):
        found = subprocess.run(["docker", "inspect", "toilet-api"], capture_output=True, text=True)
        if found.returncode:
            raise RuntimeError("API container inspection failed")
        self.env = dict(item.split("=", 1) for item in json.loads(found.stdout)[0]["Config"]["Env"] if "=" in item)
        if not self.env.get("SPRING_DB_USERNAME") or not self.env.get("SPRING_DB_PASSWORD"):
            raise RuntimeError("database credentials unavailable")

    def query(self, sql):
        env = dict(os.environ, MYSQL_PWD=self.env["SPRING_DB_PASSWORD"])
        result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
                                 "--protocol=tcp", "-h127.0.0.1", "--default-character-set=utf8mb4", "--batch",
                                 "--raw", "--skip-column-names", "-u", self.env["SPRING_DB_USERNAME"], "toilet_db"],
                                input="SET NAMES utf8mb4; SET time_zone='+09:00';\n" + sql,
                                env=env, text=True, capture_output=True)
        if result.returncode:
            match = re.search(r"ERROR (\d+)", result.stderr)
            raise RuntimeError("database operation failed: " + (match.group(1) if match else "unknown"))
        return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]

    def sources(self):
        facilities = self.query("START TRANSACTION READ ONLY; SELECT JSON_OBJECT('kind','facility','id',t.toilet_id,"
                                "'locale',lang.locale,'sourceHash',ko.source_hash,'name',TRIM(ko.name),"
                                "'road',NULLIF(TRIM(ko.road_address),''),'jibun',NULLIF(TRIM(ko.jibun_address),'')) "
                                + FACILITY_WHERE + " ORDER BY t.toilet_id,lang.locale; COMMIT;")
        groups = self.query("START TRANSACTION READ ONLY; SELECT JSON_OBJECT('kind','group','id',g.group_id,"
                            "'locale',lang.locale,'sourceName',g.display_name,'name',TRIM(g.display_name)) "
                            + GROUP_WHERE + " ORDER BY g.group_id,lang.locale; COMMIT;")
        return facilities + groups


def fields(row):
    return [field for field in (("name", "road", "jibun") if row["kind"] == "facility" else ("name",))
            if isinstance(row.get(field), str) and row[field].strip()]


def plan(rows):
    unique = {(row["locale"], row[field].strip()) for row in rows for field in fields(row)}
    return {"rows": len(rows), "byKindAndLocale": dict(collections.Counter(
        row["kind"] + ":" + row["locale"] for row in rows)),
        "uniqueTexts": len(unique), "inputCharacters": sum(len(value) for _, value in unique)}


class Ledger:
    def __init__(self, path):
        self.path = path
        self.db = sqlite3.connect(path, timeout=60)
        self.db.execute("CREATE TABLE IF NOT EXISTS translations (locale TEXT, source TEXT, translated TEXT NOT NULL, PRIMARY KEY(locale,source))")
        self.db.execute("CREATE TABLE IF NOT EXISTS requests (id INTEGER PRIMARY KEY, locale TEXT NOT NULL, input_chars INTEGER NOT NULL, output_chars INTEGER, cost_micro_usd INTEGER NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        self.db.commit()

    def get(self, locale, source):
        row = self.db.execute("SELECT translated FROM translations WHERE locale=? AND source=?", (locale, source)).fetchone()
        return row[0] if row else None

    def save_texts(self, locale, sources, translations):
        if len(sources) != len(translations) or any(not value for value in translations):
            raise ValueError("invalid packed translation")
        with self.db:
            self.db.executemany("INSERT OR IGNORE INTO translations VALUES(?,?,?)",
                                ((locale, source, value) for source, value in zip(sources, translations)))

    def spent(self):
        return self.db.execute("SELECT COALESCE(SUM(cost_micro_usd),0) FROM requests").fetchone()[0]

    def usage(self):
        rows = self.db.execute("SELECT locale,SUM(input_chars),"
                               "SUM(CASE WHEN output_chars IS NOT NULL THEN input_chars ELSE 0 END),"
                               "SUM(COALESCE(output_chars,0)),SUM(output_chars IS NULL),SUM(cost_micro_usd) "
                               "FROM requests GROUP BY locale").fetchall()
        return {locale: {"attemptedInputCharacters": attempted, "completedInputCharacters": completed,
                         "outputCharacters": output, "uncertainRequests": uncertain,
                         "estimatedCostUsd": round(cost / 1_000_000, 4)}
                for locale, attempted, completed, output, uncertain, cost in rows}

    def reserve(self, locale, input_chars, cap_micro):
        # NMT is $20/M input chars. LLM is $10/M input and output chars.
        # Four input lengths of output are reserved until the actual result arrives.
        cost = input_chars * (20 if locale == "zh-tw" else 50)
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            if self.spent() + cost > cap_micro:
                raise RuntimeError("Google cost cap reached; no further requests sent")
            return self.db.execute("INSERT INTO requests(locale,input_chars,cost_micro_usd) VALUES(?,?,?)",
                                   (locale, input_chars, cost)).lastrowid

    def settle(self, request_id, locale, source, translated):
        output_chars = sum(map(len, translated))
        input_chars = sum(map(len, source))
        actual_cost = input_chars * (20 if locale == "zh-tw" else 10) + (0 if locale == "zh-tw" else output_chars * 10)
        with self.db:
            self.db.executemany("INSERT OR REPLACE INTO translations VALUES(?,?,?)",
                                ((locale, original, value) for original, value in zip(source, translated)))
            self.db.execute("UPDATE requests SET output_chars=?,cost_micro_usd=? WHERE id=?",
                            (output_chars, actual_cost, request_id))


def batches(texts):
    batch, count = [], 0
    for value in texts:
        if len(value) > 4000:
            raise ValueError("source text exceeds request limit")
        if batch and (len(batch) >= 100 or count + len(value) > 4000):
            yield batch
            batch, count = [], 0
        batch.append(value)
        count += len(value)
    if batch:
        yield batch


def parse_packed(text, count):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    values = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r"\s*([0-9])\s*[|:：]\s*(.+?)\s*", line)
        if not match or int(match.group(1)) != index or not match.group(2):
            return None
        values.append(match.group(2))
    return values if len(values) == count else None


def translate_packed_hk_one(ledger, key, project, texts, cap_micro, sleeper=time.sleep):
    """Send ten indexed source strings as one LLM item; isolate bad requests."""
    packed_groups = fallback_groups = skipped_texts = backend_probes = 0
    consecutive_backend_failures = 0

    def backend_error(error):
        return isinstance(error, RuntimeError) and str(error).startswith(
            "Google zh-hk translation failed with HTTP 500 (INTERNAL,backendError)")

    def translate_split(group):
        nonlocal skipped_texts, consecutive_backend_failures, backend_probes
        try:
            translate(ledger, key, project, "zh-hk", group, cap_micro)
            consecutive_backend_failures = 0
        except RuntimeError as error:
            if not backend_error(error):
                raise
            if len(group) > 1:
                middle = len(group) // 2
                translate_split(group[:middle])
                translate_split(group[middle:])
                return
            skipped_texts += 1
            consecutive_backend_failures += 1
            if consecutive_backend_failures >= 3:
                # Distinguish a cluster of untranslatable source texts from a
                # service outage before spending requests on the next group.
                sleeper(65)
                backend_probes += 1
                translate(ledger, key, project, "zh-hk", ["번역 서비스 점검용 짧은 문장"], cap_micro)
                consecutive_backend_failures = 0

    direct = [value for value in texts if "\n" in value or "|" in value]
    direct_set = set(direct)
    regular = [value for value in texts if value not in direct_set]
    for start in range(0, len(regular), 10):
        group = regular[start:start + 10]
        packed = "\n".join(f"{index}|{value}" for index, value in enumerate(group))
        if ledger.get("zh-hk", packed) is None:
            try:
                translate(ledger, key, project, "zh-hk", [packed], cap_micro)
            except RuntimeError as error:
                if not backend_error(error):
                    raise
        packed_output = ledger.get("zh-hk", packed)
        parsed = parse_packed(packed_output, len(group)) if packed_output else None
        if parsed is None:
            fallback_groups += 1
            translate_split(group)
        else:
            ledger.save_texts("zh-hk", group, parsed)
        packed_groups += 1
        sleeper(0.5)
    for batch in batches(direct):
        translate_split(batch)
        sleeper(31)
    return {"packedGroups": packed_groups, "fallbackGroups": fallback_groups,
            "directTexts": len(direct), "skippedTexts": skipped_texts,
            "backendProbes": backend_probes}


def translate_packed_hk(ledger, key, project, texts, cap_micro, sleeper=time.sleep):
    """Use separate SQLite connections so up to three packed requests can run at once."""
    if len(texts) < 30:
        return translate_packed_hk_one(ledger, key, project, texts, cap_micro, sleeper)

    def worker(partition):
        worker_ledger = Ledger(ledger.path)
        try:
            return translate_packed_hk_one(worker_ledger, key, project, partition, cap_micro, sleeper)
        finally:
            worker_ledger.db.close()

    with ThreadPoolExecutor(max_workers=3) as pool:
        reports = list(pool.map(worker, (texts[index::3] for index in range(3))))
    return {field: sum(report[field] for report in reports)
            for field in ("packedGroups", "fallbackGroups", "directTexts", "skippedTexts", "backendProbes")}


def safe_google_error(error):
    """Return only documented status/reason tokens, never a response message."""
    try:
        body = json.load(error)
        details = body.get("error", {})
        tokens = [details.get("status"), *(entry.get("reason") for entry in details.get("errors", [])),
                  *(entry.get("reason") for entry in details.get("details", []))]
        return ",".join(token for token in tokens if isinstance(token, str)
                        and re.fullmatch(r"[A-Za-z_]{1,60}", token)) or "unspecified"
    except (ValueError, TypeError, AttributeError):
        return "unspecified"


def translate(ledger, key, project, locale, source, cap_micro, opener=urllib.request.urlopen,
              sleeper=time.sleep, text_format="text"):
    target, _ = TARGETS[locale]
    model = "nmt" if locale == "zh-tw" else f"projects/{project}/locations/us-central1/models/general/translation-llm"
    payload = {"q": source, "source": "ko", "target": target, "format": text_format, "model": model}
    request = urllib.request.Request(ENDPOINT, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                     headers={"Content-Type": "application/json; charset=utf-8",
                                              "X-Goog-Api-Key": key}, method="POST")
    for attempt in range(5):
        reservation = ledger.reserve(locale, sum(map(len, source)), cap_micro)
        try:
            with opener(request, timeout=90) as response:
                data = json.load(response)
            translated = [(html.unescape(item["translatedText"]) if text_format == "text"
                           else item["translatedText"]).strip()
                          for item in data["data"]["translations"]]
            if len(translated) != len(source) or any(not value for value in translated):
                raise RuntimeError("Google returned missing/empty translations")
            ledger.settle(reservation, locale, source, translated)
            return
        except urllib.error.HTTPError as error:
            reason = safe_google_error(error)
            error.close()
            per_minute_limit = error.code == 403 and "userRateLimitExceeded" in reason
            transient = per_minute_limit or error.code in (429, 500, 502, 503, 504)
            if not transient or attempt == 4:
                raise RuntimeError(f"Google {locale} translation failed with HTTP {error.code} ({reason})") from None
            delay = 65 if per_minute_limit else min(30, 2 ** (attempt + 1))
        except (urllib.error.URLError, TimeoutError):
            if attempt == 4:
                raise RuntimeError("Google translation network failure") from None
            delay = min(30, 2 ** (attempt + 1))
        sleeper(delay)


def cjk_number(token):
    digits = dict(zip("零〇一二三四五六七八九", (0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
    if all(char in digits for char in token):
        return str(int("".join(str(digits[char]) for char in token)))
    units = {"十": 10, "百": 100, "千": 1000}
    total, current = 0, 0
    for char in token:
        if char in digits:
            current = digits[char]
        elif char in units:
            total += (current or 1) * units[char]
            current = 0
    return str(total + current)


def context_markup(source, field):
    label = "대한민국 공중화장실의 이름" if field == "name" else "대한민국 공중화장실의 주소"
    return '<div>' + label + ': <span id="translation-result">' + html.escape(source) + '</span></div>'


class TranslationSpan(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if self.depth:
            self.depth += 1
        elif dict(attrs).get("id") == "translation-result":
            self.depth = 1

    def handle_endtag(self, tag):
        if self.depth:
            self.depth -= 1

    def handle_data(self, data):
        if self.depth:
            self.parts.append(data)


def candidate(ledger, row, field):
    source = row[field].strip()
    plain = ledger.get(row["locale"], source)
    if not validate({"kind": row["kind"], field: source}, {field: plain}):
        return plain
    translated_markup = ledger.get(row["locale"], context_markup(source, field))
    if translated_markup:
        parser = TranslationSpan()
        parser.feed(translated_markup)
        contextual = " ".join("".join(parser.parts).split())
        if not validate({"kind": row["kind"], field: source}, {field: contextual}):
            return contextual
    return plain


def needs_context_recovery(row, field, translated):
    source = row[field].strip()
    problems = validate({"kind": row["kind"], field: source}, {field: translated})
    return any(problem.endswith((":hangul", ":numbers")) for problem in problems)


def validate(row, values):
    issues = []
    for field in fields(row):
        value = values.get(field)
        if not isinstance(value, str) or not value.strip():
            issues.append(field + ":empty")
            continue
        if len(value) > (255 if field == "name" else 500):
            issues.append(field + ":length")
        if re.search(r"[가-힣ㄱ-ㅎㅏ-ㅣ]", value):
            issues.append(field + ":hangul")
        if any(unicodedata.category(char) == "Cc" for char in value) or re.search(r"<[^>]+>", value):
            issues.append(field + ":unsafe")
        source_numbers = collections.Counter(re.findall(r"[0-9]+", unicodedata.normalize("NFKC", row[field])))
        target_numbers = collections.Counter(re.findall(r"[0-9]+", unicodedata.normalize("NFKC", value)))
        written_numbers = collections.Counter(cjk_number(number)
            for number in re.findall(r"[零〇一二三四五六七八九十百千]+", value)) if field == "name" else collections.Counter()
        if (source_numbers - target_numbers - written_numbers) or (target_numbers - source_numbers):
            issues.append(field + ":numbers")
    return issues


def insert_sql(row, values):
    locale = row["locale"]
    if locale not in TARGETS or row["id"] <= 0 or validate(row, values):
        raise ValueError("invalid translation row")
    source = sql_text(TARGETS[locale][1])
    loc = sql_text(locale)
    name = sql_text(values["name"])
    if row["kind"] == "group":
        return ("INSERT INTO toilet_display_group_translation (group_id,locale,source_name,display_name,"
                "translation_source,manual_override,translated_at) SELECT g.group_id," + loc + ",g.display_name,"
                + name + "," + source + ",FALSE,NOW() FROM toilet_display_group g LEFT JOIN "
                "toilet_display_group_translation dst ON dst.group_id=g.group_id AND dst.locale=" + loc
                + " WHERE g.group_id=" + str(row["id"]) + " AND dst.group_id IS NULL AND BINARY g.display_name=BINARY "
                + sql_text(row["sourceName"]) + ";")
    if row["kind"] != "facility" or not re.fullmatch(r"[a-f0-9]{64}", row["sourceHash"]):
        raise ValueError("invalid source hash")
    address_status = "TRANSLATED" if values.get("road") or values.get("jibun") else "NO_RESULT"
    return ("INSERT INTO toilet_translation (toilet_id,locale,name,road_address,jibun_address,source_hash,"
            "translation_status,translation_source,address_translation_status,address_translation_source,"
            "manual_override,translated_at,reviewed_at,created_at,updated_at) SELECT t.toilet_id,"
            + ",".join((loc, name, sql_text(values.get("road")), sql_text(values.get("jibun")), "ko.source_hash",
                        "'MACHINE_TRANSLATED'", source, sql_text(address_status),
                        source if address_status == "TRANSLATED" else "'UNAVAILABLE'", "FALSE", "NOW()",
                        "NULL", "NOW()", "NOW()")) + " " + FACILITY_WHERE + " AND t.toilet_id="
            + str(row["id"]) + " AND lang.locale=" + loc + " AND ko.source_hash="
            + sql_text(row["sourceHash"]) + ";")


def atomic_json(path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def run(args):
    os.umask(0o077)
    if args.stage == "run" and args.max_usd != 100:
        raise ValueError("the production rollout is capped at USD 100 before credits")
    if args.stage != "run" and not 1 <= args.max_usd <= 100:
        raise ValueError("pilot cost cap must be between USD 1 and USD 100")
    db = Database()
    rows = db.sources()
    if args.sample_rows:
        if not 1 <= args.sample_rows <= 1000:
            raise ValueError("pilot size must be 1 to 1000")
        rows = rows[::max(1, len(rows) // args.sample_rows)][:args.sample_rows]
    report = {"stage": args.stage, "plan": plan(rows), "costCapUsd": args.max_usd}
    if args.stage == "plan":
        print(json.dumps(report, ensure_ascii=False))
        return
    work = Path(args.work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)
    os.chmod(work, 0o700)
    import fcntl
    with (work / "run.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        ledger = Ledger(work / "ledger.sqlite")
        key = db.env.get("GOOGLE_TRANSLATION_API_KEY", "")
        if not key or not re.fullmatch(r"[a-z0-9-]+", args.project):
            raise RuntimeError("Google Translation configuration unavailable")
        if args.stage == "pack-pilot":
            candidates = []
            seen = set()
            for row in rows:
                if row["locale"] != "zh-hk":
                    continue
                for field in fields(row):
                    source = row[field].strip()
                    if source not in seen and "\n" not in source and "|" not in source:
                        candidates.append((field, source))
                        seen.add(source)
            selected = candidates[::max(1, len(candidates) // 30)][:30]
            if len(selected) != 30:
                raise RuntimeError("not enough packing pilot texts")
            outcomes = []
            for offset in range(0, 30, 10):
                group = selected[offset:offset + 10]
                packed = "\n".join(f"{index}|{source}" for index, (_, source) in enumerate(group))
                if ledger.get("zh-hk", packed) is None:
                    translate(ledger, key, args.project, "zh-hk", [packed], args.max_usd * 1_000_000)
                output = ledger.get("zh-hk", packed)
                parsed = parse_packed(output, len(group))
                outcomes.append({"parsed": parsed is not None,
                                 "validTexts": sum(not validate({"kind": "facility", field: source},
                                                               {field: value})
                                                   for (field, source), value in zip(group, parsed or [])),
                                 "lineCount": len(output.splitlines())})
            report.update(packPilot={"groups": outcomes},
                          estimatedCostUsd=round(ledger.spent() / 1_000_000, 4),
                          usageByLocale=ledger.usage())
            print(json.dumps(report, ensure_ascii=False))
            return
        unique = {(row["locale"], row[field].strip()) for row in rows for field in fields(row)}
        pending = {(locale, value) for locale, value in unique if ledger.get(locale, value) is None}
        if ledger.spent() + sum(len(value) * (20 if locale == "zh-tw" else 50)
                                for locale, value in pending) > args.max_usd * 1_000_000:
            # This is a pessimistic fourfold output reservation. Actual LLM output is
            # settled after each request, so only stop if the NMT+LLM input lower bound exceeds cap.
            lower_bound = ledger.spent() + sum(len(value) * (20 if locale == "zh-tw" else 10)
                                               for locale, value in pending)
            if lower_bound > args.max_usd * 1_000_000:
                raise RuntimeError("untranslated input already exceeds cost cap")
        pack_usage = None
        for locale in TARGETS:
            locale_texts = sorted(value for target, value in pending if target == locale)
            if locale == "zh-hk":
                pack_usage = translate_packed_hk(ledger, key, args.project, locale_texts,
                                                 args.max_usd * 1_000_000)
            else:
                for batch in batches(locale_texts):
                    translate(ledger, key, args.project, locale, batch, args.max_usd * 1_000_000)
        recovery = {locale: set() for locale in TARGETS}
        for row in rows:
            for field in fields(row):
                source = row[field].strip()
                if needs_context_recovery(row, field, ledger.get(row["locale"], source)):
                    markup = context_markup(source, field)
                    if ledger.get(row["locale"], markup) is None:
                        recovery[row["locale"]].add(markup)
        for locale, texts in recovery.items():
            step = 5 if locale == "zh-hk" else 100
            ordered = sorted(texts)
            for offset in range(0, len(ordered), step):
                batch = ordered[offset:offset + step]
                translate(ledger, key, args.project, locale, batch, args.max_usd * 1_000_000,
                          text_format="html")
                if locale == "zh-hk":
                    time.sleep(2)
        ready, issues = [], collections.Counter()
        for row in rows:
            values = {field: candidate(ledger, row, field) for field in fields(row)}
            errors = validate(row, values)
            if errors:
                issues.update(errors)
            else:
                ready.append((row, values))
        report.update(readyRows=len(ready), issueRows=len(rows) - len(ready), issueTypes=dict(issues),
                      estimatedCostUsd=round(ledger.spent() / 1_000_000, 4), usageByLocale=ledger.usage(),
                      translatedUniqueTexts=len(unique), recoveryTexts=sum(map(len, recovery.values())),
                      hongKongPacking=pack_usage)
        if args.stage == "run":
            applied = 0
            for offset in range(0, len(ready), 100):
                statements = ["START TRANSACTION; SET @applied=0;"]
                for row, values in ready[offset:offset + 100]:
                    statements.extend((insert_sql(row, values), "SET @applied=@applied+ROW_COUNT();"))
                statements.append("COMMIT; SELECT JSON_OBJECT('applied',@applied);")
                applied += db.query("\n".join(statements))[0]["applied"]
            report.update(appliedRows=applied, remaining=plan(db.sources()))
        atomic_json(work / "report.json", report)
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("plan", "pilot", "pack-pilot", "run"))
    parser.add_argument("--project", required=True)
    parser.add_argument("--work-dir", default="/tmp/toilet-traditional-translation-20260923")
    parser.add_argument("--max-usd", type=int, required=True)
    parser.add_argument("--sample-rows", type=int)
    try:
        run(parser.parse_args())
    except Exception as error:
        # Do not emit source text, SQL, Google responses, credentials, or stack traces.
        print(str(error) if isinstance(error, (ValueError, RuntimeError)) else "translation job failed: "
              + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
