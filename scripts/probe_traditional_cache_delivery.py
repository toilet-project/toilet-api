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


def probe(opener=urllib.request.urlopen):
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
    query = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
                            "--protocol=tcp", "-h127.0.0.1", "--default-character-set=utf8mb4",
                            "--batch", "--raw", "--skip-column-names", "-u",
                            environment["SPRING_DB_USERNAME"], "toilet_db"],
                           input=("START TRANSACTION READ ONLY; "
                                  "SELECT JSON_OBJECT('toiletId',toilet_id,'revision',revision,"
                                  "'action',action,'catalogChanged',catalog_changed) "
                                  "FROM web_cache_invalidation WHERE toilet_id=53585 "
                                  "AND delivered_at IS NULL; COMMIT;"),
                           env=mysql_env, text=True, capture_output=True)
    if query.returncode:
        raise RuntimeError("pending cache event lookup failed")
    events = [json.loads(line) for line in query.stdout.splitlines() if line.strip()]
    if len(events) != 1 or events[0]["action"] != "UPSERT" or \
            events[0]["toiletId"] != TOILET_ID or events[0]["revision"] < 1:
        raise RuntimeError("expected pending public cache event unavailable")
    events[0]["catalogChanged"] = bool(events[0]["catalogChanged"])
    body = json.dumps({"contractVersion": 2, "events": [events[0]]}, separators=(",", ":"))
    timestamp = str(int(time.time()))
    signature = hmac.new(secret.encode(),
                         f"v1\nPOST\n{PATH}\n{timestamp}\n{body}".encode(),
                         hashlib.sha256).hexdigest()
    request = urllib.request.Request(ENDPOINT, data=body.encode(), method="POST",
                                     headers={"Content-Type": "application/json",
                                              "User-Agent": "Java-http-client/21.0.12",
                                              "x-cache-timestamp": timestamp,
                                              "x-cache-signature": signature})
    unsigned = {label: unsigned_status(agent, opener) for label, agent in (
        ("python", "Python-urllib/3.11"), ("java", "Java-http-client/21.0.12"),
        ("browser", "Mozilla/5.0"))}
    start = time.monotonic()
    error_headers = None
    try:
        with opener(request, timeout=15) as response:
            status = response.status
            payload = json.load(response) if status == 200 else None
        acknowledged = (status == 200 and payload == {"ok": True, "acceptedEvents": [
            {"toiletId": TOILET_ID, "revision": events[0]["revision"]}]})
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
    return {"schema": 1, "toiletId": TOILET_ID, "outcome": outcome, "httpStatus": status,
            "elapsedMillis": round((time.monotonic() - start) * 1000),
            "unsignedStatusByAgent": unsigned, "errorHeaders": error_headers,
            "outboxAcknowledged": False, "rawSourceExported": False}


if __name__ == "__main__":
    try:
        print(json.dumps(probe(), separators=(",", ":")))
    except Exception:
        print("cache delivery probe failed", file=__import__("sys").stderr)
        raise SystemExit(1)
