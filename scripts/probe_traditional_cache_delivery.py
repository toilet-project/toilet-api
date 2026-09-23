#!/usr/bin/env python3
"""Probe one pending production cache event without acknowledging the outbox.

The signed request can invalidate public cache for the selected toilet. It never
changes a business row, and the original outbox event remains available to retry.
Only aggregate timing/status is printed; credentials and responses stay local.
"""
import hashlib
import hmac
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request


TOILET_ID = 53585  # Public detail with a confirmed stale zh-tw page on 2026-09-23.
ENDPOINT = "https://geupddong.com/_internal/cache/revalidate"
PATH = "/_internal/cache/revalidate"


def unsigned_status(user_agent, opener):
    request = urllib.request.Request(ENDPOINT, data=b"{}", method="POST",
                                     headers={"Content-Type": "application/json",
                                              "User-Agent": user_agent})
    try:
        with opener(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        return status
    except (urllib.error.URLError, TimeoutError):
        return None


def probe(opener=urllib.request.urlopen, batch_size=1, version_id=""):
    if batch_size not in (1, 20):
        raise ValueError("Unsupported cache probe size")
    if version_id and not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", version_id):
        raise ValueError("Invalid Worker version")
    inspected = subprocess.run(["docker", "inspect", "toilet-api"], capture_output=True, text=True)
    if inspected.returncode:
        raise RuntimeError("API container inspection failed")
    environment = dict(item.split("=", 1) for item in json.loads(inspected.stdout)[0]["Config"]["Env"]
                       if "=" in item)
    if environment.get("WEB_CACHE_ORIGIN") != "https://geupddong.com" or \
            environment.get("WEB_CACHE_REVALIDATION_ENABLED") != "true" or \
            environment.get("WEB_CACHE_CONTRACT_VERSION") != "3":
        raise RuntimeError("production cache delivery configuration mismatch")
    secret = environment.get("WEB_CACHE_REVALIDATION_SECRET", "")
    if len(secret.encode()) < 32 or not environment.get("SPRING_DB_USERNAME") or \
            not environment.get("SPRING_DB_PASSWORD"):
        raise RuntimeError("cache delivery or database credentials unavailable")
    mysql_env = dict(os.environ, MYSQL_PWD=environment["SPRING_DB_PASSWORD"])
    predicate = "WHERE toilet_id=53585 AND delivered_at IS NULL" if batch_size == 1 else \
        "WHERE delivered_at IS NULL AND action='UPSERT'"
    query = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
                            "--protocol=tcp", "-h127.0.0.1", "--default-character-set=utf8mb4",
                            "--batch", "--raw", "--skip-column-names", "-u",
                            environment["SPRING_DB_USERNAME"], "toilet_db"],
                           input=("START TRANSACTION READ ONLY; "
                                  "SELECT JSON_OBJECT('toiletId',toilet_id,'revision',revision,"
                                  "'action',action,'catalogChanged',catalog_changed) "
                                  "FROM web_cache_invalidation " + predicate +
                                  f" ORDER BY next_attempt_at,toilet_id LIMIT {batch_size}; COMMIT;"),
                           env=mysql_env, text=True, capture_output=True)
    if query.returncode:
        raise RuntimeError("pending cache event lookup failed")
    events = [json.loads(line) for line in query.stdout.splitlines() if line.strip()]
    if len(events) != batch_size or any(event["action"] != "UPSERT" or
                                       event["revision"] < 1 for event in events) or \
            len({event["toiletId"] for event in events}) != batch_size or \
            (batch_size == 1 and events[0]["toiletId"] != TOILET_ID):
        raise RuntimeError("expected pending public cache event unavailable")
    for event in events:
        event["catalogChanged"] = bool(event["catalogChanged"])
    body = json.dumps({"contractVersion": 2, "events": events}, separators=(",", ":"))
    timestamp = str(int(time.time()))
    signature = hmac.new(secret.encode(),
                         f"v1\nPOST\n{PATH}\n{timestamp}\n{body}".encode(),
                         hashlib.sha256).hexdigest()
    headers = {"Content-Type": "application/json", "User-Agent": "Java-http-client/21.0.12",
               "x-cache-timestamp": timestamp, "x-cache-signature": signature}
    if version_id:
        headers["Cloudflare-Workers-Version-Overrides"] = f'geupddong-web-production="{version_id}"'
    request = urllib.request.Request(ENDPOINT, data=body.encode(), method="POST", headers=headers)
    unsigned = {label: unsigned_status(agent, opener) for label, agent in (
        ("python", "Python-urllib/3.11"), ("java", "Java-http-client/21.0.12"),
        ("browser", "Mozilla/5.0"))} if batch_size == 1 else {}
    start = time.monotonic()
    error_headers = None
    try:
        with opener(request, timeout=8) as response:
            status = response.status
            payload = json.load(response) if status == 200 else None
        expected = {(event["toiletId"], event["revision"]) for event in events}
        received = payload.get("acceptedEvents") if isinstance(payload, dict) else None
        acknowledged = (status == 200 and isinstance(payload, dict) and payload.get("ok") is True
                        and isinstance(received, list) and len(received) == len(events)
                        and all(isinstance(event, dict) for event in received) and
                        {(event.get("toiletId"), event.get("revision")) for event in received} == expected)
        outcome = "acknowledged" if acknowledged else "invalid_ack"
    except urllib.error.HTTPError as error:
        status, outcome = error.code, "http_error"
        error_headers = {"server": error.headers.get("server"),
                         "cfMitigated": error.headers.get("cf-mitigated"),
                         "contentType": error.headers.get("content-type"),
                         "cfRayPresent": bool(error.headers.get("cf-ray"))}
        error.close()
    except (urllib.error.URLError, TimeoutError):
        status, outcome = None, "transport_error"
    return {"schema": 1, "toiletId": TOILET_ID if batch_size == 1 else None,
            "batchSize": batch_size, "workerVersion": version_id or None,
            "outcome": outcome, "httpStatus": status,
            "elapsedMillis": round((time.monotonic() - start) * 1000),
            "unsignedStatusByAgent": unsigned, "errorHeaders": error_headers,
            "outboxAcknowledged": False, "rawSourceExported": False}


if __name__ == "__main__":
    try:
        print(json.dumps(probe(batch_size=int(os.environ.get("PROBE_BATCH_SIZE", "1")),
                               version_id=os.environ.get("PROBE_WORKER_VERSION", "")), separators=(",", ":")))
    except Exception:
        print("cache delivery probe failed", file=__import__("sys").stderr)
        raise SystemExit(1)
