#!/usr/bin/env python3
"""Stop only the verified regional translation process holding its lock."""
import json
import os
from pathlib import Path
import signal
import time

LOCK = Path("/tmp/toilet-traditional-translation-20260923/run.lock")
PROJECT = "project-a89d2fb9-4c1a-4a14-98c"


def owner_pids():
    identity = (LOCK.stat().st_dev, LOCK.stat().st_ino)
    owners = []
    for process in Path("/proc").iterdir():
        if not process.name.isdigit():
            continue
        try:
            argv = (process / "cmdline").read_bytes().split(b"\0")
            args = [item.decode("utf-8", "replace") for item in argv if item]
            if not ({"python3", "-", "run", "--project", PROJECT, "--max-usd", "100"} <= set(args)):
                continue
            for fd in (process / "fd").iterdir():
                found = os.stat(fd)
                if (found.st_dev, found.st_ino) == identity:
                    owners.append(int(process.name))
                    break
        except (OSError, PermissionError):
            continue
    return owners


def main():
    if not LOCK.is_file():
        print(json.dumps({"stopped": False, "reason": "lock_absent"}))
        return
    owners = owner_pids()
    if not owners:
        print(json.dumps({"stopped": False, "reason": "matching_process_absent"}))
        return
    if len(owners) != 1:
        raise RuntimeError("more than one matching translation process holds the lock")
    pid = owners[0]
    os.kill(pid, signal.SIGTERM)
    for _ in range(30):
        if pid not in owner_pids():
            print(json.dumps({"stopped": True, "signal": "SIGTERM"}))
            return
        time.sleep(0.5)
    os.kill(pid, signal.SIGKILL)
    for _ in range(20):
        if pid not in owner_pids():
            print(json.dumps({"stopped": True, "signal": "SIGKILL"}))
            return
        time.sleep(0.5)
    raise RuntimeError("verified process still holds translation lock")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("regional translation stop failed", file=__import__("sys").stderr)
        raise SystemExit(1)
