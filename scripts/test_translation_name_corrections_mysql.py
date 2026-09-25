"""Integration tests use only the disposable MySQL service in GitHub Actions.

No mini-PC/production database connection or credentials are accepted.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

from scripts.translation_name_corrections import MANIFEST, build_sql, sql_text
from scripts.translation_full_sql import build_sql as machine_sql

ROOT = Path(__file__).resolve().parents[1]


def digest(*parts):
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()


@unittest.skipUnless(os.getenv("NAME_FIX_TEST_CONTAINER"), "Disposable CI MySQL service only")
class NameCorrectionMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = os.environ["NAME_FIX_TEST_CONTAINER"]
        if os.getenv("CI") != "true" or not re.fullmatch(r"[a-f0-9]{12,64}", cls.container):
            raise RuntimeError("Only an explicit CI service-container ID is allowed")

    def sql(self, sql):
        result = subprocess.run(
            ["docker", "exec", "-i", "-e", "MYSQL_PWD", self.container,
             "mysql", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
             "-uroot", "name_correction_test"],
            input="SET NAMES utf8mb4;\n" + sql, text=True, encoding="utf-8",
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30)
        return [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]

    def setUp(self):
        self.rows = copy.deepcopy(json.loads(MANIFEST.read_text(encoding="utf-8")))
        # These tables exist only in the literal name_correction_test database.
        self.sql("""DROP TABLE IF EXISTS toilet_translation;
DROP TABLE IF EXISTS web_cache_invalidation;
DROP TABLE IF EXISTS toilet;
CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY, name VARCHAR(255),
 road_address VARCHAR(500), jibun_address VARCHAR(500), latitude DOUBLE, longitude DOUBLE);
CREATE TABLE web_cache_invalidation (toilet_id BIGINT PRIMARY KEY,event_id CHAR(36),
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,action VARCHAR(10),catalog_changed BOOLEAN,
 attempts INT UNSIGNED,next_attempt_at DATETIME(6),first_queued_at DATETIME(6),
 last_queued_at DATETIME(6),delivered_at DATETIME(6),last_error_code VARCHAR(40));
""")
        for toilet_id, name in ((15060, "어울림"), (24858, "한서교 위"), (99999, "다른 시험 시설")):
            self.sql(f"INSERT INTO toilet VALUES ({toilet_id},{sql_text(name)},'source road','source lot',37.5,127.0);")
        self.sql((ROOT / "src/main/resources/db/migration/V26__create_toilet_translation.sql").read_text())
        self.sql((ROOT / "src/main/resources/db/migration/V29__track_translation_address_status.sql").read_text())
        self.sql((ROOT / "src/main/resources/db/cache-revalidation/V4__translation_cache_events.sql").read_text())
        for row in self.rows:
            row["sourceHash"] = digest(row["originalName"], "source road", "source lot")
            row["addressHash"] = digest("translated road", "translated lot")
            self.sql(f"""INSERT INTO toilet_translation (toilet_id,locale,name,road_address,jibun_address,
 source_hash,translation_status,translation_source,address_translation_status,address_translation_source,
 manual_override,version,translated_at)
 VALUES ({row['toiletId']},{sql_text(row['locale'])},{sql_text(row['oldName'])},'translated road','translated lot',
 '{row['sourceHash']}','MACHINE_TRANSLATED',{sql_text(row['source'])},'TRANSLATED',
 {sql_text(row['addressSource'])},FALSE,1,'2026-09-01 00:00:00');""")
        self.sql("""INSERT INTO toilet_translation (toilet_id,locale,name,source_hash,translation_status,translation_source)
 SELECT toilet_id,IF(toilet_id=15060,'ja','en'),'unrelated translated name',source_hash,'MACHINE_TRANSLATED','FIXTURE'
 FROM toilet_translation WHERE locale='ko' AND toilet_id IN (15060,99999);
DELETE FROM web_cache_invalidation;""")

    def snapshot(self):
        return self.sql("""SELECT JSON_OBJECT('id',tr.toilet_id,'locale',tr.locale,'name',tr.name,
 'road',tr.road_address,'lot',tr.jibun_address,'hash',tr.source_hash,'status',tr.translation_status,
 'source',tr.translation_source,'addressStatus',tr.address_translation_status,'addressSource',tr.address_translation_source,
 'manual',tr.manual_override,'version',tr.version,'translated',tr.translated_at,'reviewed',tr.reviewed_at,
 'created',tr.created_at,'updated',tr.updated_at,'originalName',t.name,'originalRoad',t.road_address,
 'originalLot',t.jibun_address,'lat',t.latitude,'lng',t.longitude)
 FROM toilet_translation tr JOIN toilet t ON t.toilet_id=tr.toilet_id ORDER BY tr.toilet_id,tr.locale;""")

    def test_apply_idempotence_rollback_and_cache_events(self):
        before = self.snapshot()
        self.assertEqual(self.sql(build_sql(self.rows))[-1]["eligible"], 4)
        self.assertEqual(before, self.snapshot())
        self.assertEqual(self.sql(build_sql(self.rows, "apply"))[-1]["changed"], 4)
        after = self.snapshot()
        targets = {(row['toiletId'], row['locale']): row for row in self.rows}
        for old, new in zip(before, after):
            plan = targets.get((old["id"], old["locale"]))
            if plan:
                self.assertEqual(new["name"], plan["newName"])
                self.assertEqual((new["manual"], new["version"], new["source"]), (1, 2, "MANUAL_NAME_CORRECTION"))
                for field in old.keys() - {"name", "manual", "version", "source", "updated"}:
                    self.assertEqual(old[field], new[field], field)
            else:
                self.assertEqual(old, new)
        events = self.sql("SELECT JSON_OBJECT('id',toilet_id,'revision',revision,'catalog',catalog_changed,'pending',delivered_at IS NULL) FROM web_cache_invalidation ORDER BY toilet_id;")
        self.assertEqual(events, [dict(id=15060, revision=2, catalog=1, pending=1), dict(id=24858, revision=2, catalog=1, pending=1)])
        self.assertEqual(self.sql(build_sql(self.rows, "apply"))[-1]["changed"], 0)
        self.assertEqual(after, self.snapshot())
        self.assertEqual(self.sql(build_sql(self.rows, "rollback"))[-1]["changed"], 4)
        for old, restored in zip(before, self.snapshot()):
            for field in old.keys() - {"version", "updated"}:
                self.assertEqual(old[field], restored[field], field)
        self.assertEqual(self.sql(build_sql(self.rows, "rollback"))[-1]["changed"], 0)

    def test_one_conflicting_row_prevents_all_four_writes(self):
        mutations = [
            "UPDATE toilet_translation SET version=9 WHERE toilet_id=15060 AND locale='en'",
            "UPDATE toilet_translation SET manual_override=1 WHERE toilet_id=15060 AND locale='en'",
            "UPDATE toilet_translation SET name='edited elsewhere' WHERE toilet_id=15060 AND locale='en'",
            "UPDATE toilet_translation SET road_address='edited road' WHERE toilet_id=15060 AND locale='en'",
            "UPDATE toilet SET name='changed source' WHERE toilet_id=24858",
            "UPDATE toilet_translation SET source_hash=REPEAT('a',64) WHERE toilet_id=24858 AND locale='ko'",
            "DELETE FROM toilet_translation WHERE toilet_id=24858 AND locale='zh-cn'",
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.sql(mutation)
                before = self.snapshot()
                self.assertEqual(self.sql(build_sql(self.rows, "apply"))[-1]["changed"], 0)
                self.assertEqual(before, self.snapshot())

    def test_cache_trigger_missing_prevents_all_writes(self):
        self.sql("DROP TRIGGER cache_toilet_translation_update;")
        before = self.snapshot()
        self.assertEqual(self.sql(build_sql(self.rows, "apply"))[-1]["changed"], 0)
        self.assertEqual(before, self.snapshot())

    def test_rollback_refuses_newer_edit(self):
        self.sql(build_sql(self.rows, "apply"))
        self.sql("UPDATE toilet_translation SET name='later human edit',version=version+1 WHERE toilet_id=24858 AND locale='en';")
        before = self.snapshot()
        self.assertEqual(self.sql(build_sql(self.rows, "rollback"))[-1]["changed"], 0)
        self.assertEqual(before, self.snapshot())

    def test_existing_machine_upsert_cannot_overwrite_names(self):
        self.sql(build_sql(self.rows, "apply"))
        before = self.snapshot()
        english = [dict(toiletId=row['toiletId'], name='wrong again', roadAddress='wrong road',
                        jibunAddress='wrong lot', expectedSourceHash=row['sourceHash'], source='FULL_GOOGLE_NMT_JUSO')
                   for row in self.rows if row['locale'] == 'en']
        self.sql(machine_sql(english))
        self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
