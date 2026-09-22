"""One-shot production V6 operator. SQL is embedded by the pinned workflow."""

import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import urllib.request


SQL = base64.b64decode(b"__V6_SQL_BASE64__")
SQL_SHA256 = "92e6e98acdfc2385472810757a20c5031509d716a6f9500220a8eb16cb36c648"
API_COMMIT = "c0c59b2773b37a48de2cbb181ac1d965b5f91b9d"
BATCH_COMMIT = "d94574ea195f8869927bd6df5d16204d1e0bd57b"
BACKUP_ROOT = Path("/home/luha/geupddong-cache-v6-backups")
MAINTENANCE_ROOT = Path("/home/luha/geupddong-maintenance")
TARGET_TRIGGERS = {
    "cache_outbox_region_insert", "cache_outbox_region_update",
    "cache_toilet_sitemap_update", "cache_toilet_scope_delete",
    "cache_group_member_insert", "cache_group_member_delete",
    "cache_group_member_update_old", "cache_group_member_update_new",
    "cache_display_group_update", "cache_display_group_delete",
    "cache_group_translation_insert", "cache_group_translation_update",
    "cache_group_translation_delete",
}
TARGET_COLUMNS = {
    "region_scope_complete", "region_west", "region_south",
    "region_east", "region_north",
}
ACTIVE = {
    "ACCOUNT_LIFECYCLE_MAINTENANCE": "false",
    "ACCOUNT_RETENTION_ENABLED": "true",
    "ACCOUNT_ERASURE_ENABLED": "true",
    "ERASURE_LEDGER_ENABLED": "true",
    "ERASURE_LEDGER_CATALOGUE_ENABLED": "true",
    "ERASURE_CHECKPOINT_ENABLED": "true",
    "ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED": "true",
}


def require(condition, label):
    if not condition:
        raise RuntimeError(label)


def run(command, *, env=None, stdin=None, stdout=subprocess.PIPE, timeout=120):
    return subprocess.run(command, env=env, input=stdin, stdout=stdout,
                          stderr=subprocess.PIPE, check=True, timeout=timeout)


def inspect(name):
    objects = json.loads(run(["docker", "inspect", name], timeout=20).stdout)
    require(len(objects) == 1, name + "_inspect_count")
    return objects[0]


def values(obj):
    result = {}
    for entry in obj["Config"]["Env"]:
        key, value = entry.split("=", 1)
        require(key not in result, "duplicate_environment_key")
        result[key] = value
    return result


def identity(obj):
    return (obj["Id"], obj["Image"], obj["State"]["StartedAt"],
            obj.get("RestartCount", 0))


def fsync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def main():
    run_id = sys.argv[1]
    require(re.fullmatch(r"[0-9]+", run_id), "invalid_run_id")
    require(hashlib.sha256(SQL).hexdigest() == SQL_SHA256, "v6_sql_hash")
    spec = importlib.util.spec_from_file_location(
        "cache_v6_lease", "/home/luha/.local/bin/maintenance_lease.py")
    require(spec is not None and spec.loader is not None, "maintenance_lease_missing")
    lease = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lease)

    backup = None
    stage = "guard"
    try:
        with lease.acquire():
            objects = {role: inspect("toilet-" + role) for role in ("api", "batch")}
            objects["mysql"] = inspect("toilet-mysql")
            before_identities = {role: identity(obj) for role, obj in objects.items()}
            for role, commit in (("api", API_COMMIT), ("batch", BATCH_COMMIT)):
                obj = objects[role]
                env = values(obj)
                require(obj["State"]["Running"] is True, role + "_not_running")
                require(obj["Config"].get("User") == "1000:1000", role + "_user")
                require(obj["Config"]["Image"].endswith(":" + commit), role + "_image_changed")
                require(all(env.get(k) == v for k, v in ACTIVE.items()), role + "_account_phase")
                require(env.get("ERASURE_MAINTENANCE_LOCK_ENABLED") == "true", role + "_guard")
                require(env.get("ERASURE_MAINTENANCE_DIRECTORY") == str(MAINTENANCE_ROOT), role + "_guard_path")
                mounts = [m for m in obj["Mounts"] if m.get("Destination") == str(MAINTENANCE_ROOT)]
                require(len(mounts) == 1 and mounts[0].get("Source") == str(MAINTENANCE_ROOT)
                        and mounts[0].get("Type") == "bind" and mounts[0].get("RW") is True,
                        role + "_guard_mount")
            require(objects["mysql"]["State"]["Running"] is True, "mysql_not_running")
            api_env = values(objects["api"])
            require(api_env.get("WEB_CACHE_CONTRACT_VERSION") == "2", "cache_contract_changed")
            require(api_env.get("WEB_CACHE_REVALIDATION_ENABLED") == "true", "cache_sender_disabled")
            require(api_env.get("WEB_CACHE_ORIGIN") == "https://geupddong.com", "cache_origin_changed")
            mysql_env = values(objects["mysql"])
            user, password = api_env.get("SPRING_DB_USERNAME"), api_env.get("SPRING_DB_PASSWORD")
            root_password = mysql_env.get("MYSQL_ROOT_PASSWORD")
            require(bool(user and password and root_password), "database_identity")
            app_env = {"PATH": os.environ["PATH"], "LANG": "C.UTF-8", "MYSQL_PWD": password}
            root_env = {"PATH": os.environ["PATH"], "LANG": "C.UTF-8", "MYSQL_PWD": root_password}
            mysql = ["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
                     "--protocol=tcp", "-h", "127.0.0.1", "--batch", "--raw",
                     "--skip-column-names", "-u", user, "toilet_db"]
            root_mysql = ["docker", "exec", "-i", "-e", "MYSQL_PWD", "toilet-mysql", "mysql",
                          "--protocol=socket", "--batch", "--raw", "--skip-column-names",
                          "-u", "root", "toilet_db"]

            def query(sql):
                return run([*mysql, "-e", sql], env=app_env).stdout.decode().splitlines()

            stage = "preconditions"
            require(query("SELECT COUNT(*) FROM flyway_schema_history WHERE version='30' AND success=1;") == ["1"],
                    "v30_missing")
            tables = set(query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                               "AND TABLE_NAME IN ('toilet','toilet_display_group','toilet_display_group_member',"
                               "'toilet_display_group_translation','web_cache_invalidation');"))
            require(len(tables) == 5, "source_tables_missing")
            triggers = set(query("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                                 "WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME LIKE 'cache_%';"))
            columns = set(query("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='web_cache_invalidation' "
                                "AND COLUMN_NAME IN ('region_scope_complete','region_west','region_south',"
                                "'region_east','region_north');"))
            require(len(triggers) == 19 and not (triggers & TARGET_TRIGGERS), "cache_triggers_not_v5")
            require(not columns, "v6_columns_present")
            require(run([*root_mysql, "-e", "SELECT 1;"], env=root_env).stdout.decode().strip() == "1",
                    "database_admin_auth")

            stage = "backup"
            BACKUP_ROOT.mkdir(mode=0o700, exist_ok=True)
            root_info = BACKUP_ROOT.lstat()
            require(stat.S_ISDIR(root_info.st_mode) and root_info.st_uid == os.geteuid()
                    and stat.S_IMODE(root_info.st_mode) == 0o700, "backup_root_permissions")
            backup = BACKUP_ROOT / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-run-" + run_id)
            backup.mkdir(mode=0o700)
            schema_file = backup / "pre-v6-schema.sql"
            data_file = backup / "pre-v6-outbox-data.sql"
            sql_file = backup / "reviewed-v6.sql"
            dump_prefix = ["docker", "exec", "-e", "MYSQL_PWD", "toilet-mysql", "mysqldump",
                           "--protocol=socket", "-u", "root"]
            with schema_file.open("xb") as output:
                run([*dump_prefix, "--no-data", "--triggers", "toilet_db", "toilet",
                     "toilet_display_group", "toilet_display_group_member",
                     "toilet_display_group_translation", "web_cache_invalidation"],
                    env=root_env, stdout=output)
            with data_file.open("xb") as output:
                run([*dump_prefix, "--single-transaction", "--quick", "--no-create-info",
                     "toilet_db", "web_cache_invalidation"], env=root_env, stdout=output)
            sql_file.write_bytes(SQL)
            meta_file = backup / "metadata.json"
            meta_file.write_text(json.dumps({"apiCommit": API_COMMIT, "batchCommit": BATCH_COMMIT,
                                             "runId": run_id, "sqlSha256": SQL_SHA256}, sort_keys=True) + "\n")
            files = (schema_file, data_file, sql_file, meta_file)
            for path in files:
                os.chmod(path, 0o600)
                require(path.stat().st_size > 0, "empty_backup")
                fsync(path)
            manifest = backup / "SHA256SUMS"
            manifest.write_text("".join(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
                                        for path in files))
            os.chmod(manifest, 0o600)
            fsync(manifest)
            fsync(backup)
            fsync(BACKUP_ROOT)

            stage = "final_check"
            require({role: identity(inspect("toilet-" + role)) for role in objects} == before_identities,
                    "container_changed_before_apply")
            require(set(query("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                              "WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME LIKE 'cache_%';")) == triggers,
                    "trigger_set_changed_before_apply")

            stage = "apply"
            run(root_mysql, env=root_env, stdin=SQL, stdout=subprocess.DEVNULL, timeout=180)

            stage = "verify"
            after_triggers = set(query("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                                       "WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME LIKE 'cache_%';"))
            after_columns = set(query("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                      "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='web_cache_invalidation' "
                                      "AND COLUMN_NAME IN ('region_scope_complete','region_west','region_south',"
                                      "'region_east','region_north');"))
            require(after_triggers == triggers | TARGET_TRIGGERS, "v6_trigger_set_incomplete")
            require(after_columns == TARGET_COLUMNS, "v6_columns_incomplete")
            require({role: identity(inspect("toilet-" + role)) for role in objects} == before_identities,
                    "container_changed_after_apply")
            port = api_env.get("API_PORT", "")
            require(port.isdigit(), "api_port")
            with urllib.request.urlopen("http://127.0.0.1:" + port + "/api/health", timeout=10) as response:
                require(response.status == 200, "api_health")
            print("CACHE_V6_APPLY_PASS columns=5 new_triggers=13 total_triggers=32 services_restarted=false")
            print("backup_directory=" + str(backup))
            print("backup_manifest_sha256=" + hashlib.sha256(manifest.read_bytes()).hexdigest())
    except Exception as error:
        print("CACHE_V6_APPLY_FAILED stage=" + stage + " failure_type=" + type(error).__name__, file=sys.stderr)
        if backup is not None:
            print("backup_directory=" + str(backup), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
