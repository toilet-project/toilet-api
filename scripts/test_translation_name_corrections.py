import copy
import json
import unittest

from scripts.translation_name_corrections import MANIFEST, build_sql, sql_text, validate


class NameCorrectionTest(unittest.TestCase):
    def setUp(self):
        self.rows = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_approved_four_targets(self):
        self.assertEqual(len(validate(self.rows)), 4)

    def test_default_is_read_only(self):
        sql = build_sql(self.rows)
        self.assertIn("START TRANSACTION READ ONLY", sql)
        self.assertNotIn("UPDATE toilet_translation", sql)
        self.assertNotIn("FOR UPDATE", sql)

    def test_locks_before_guard_and_update(self):
        sql = build_sql(self.rows, "apply")
        self.assertLess(sql.index("FOR UPDATE"), sql.index("INTO @name_fix_eligible"))
        self.assertLess(sql.index("INTO @name_fix_eligible"), sql.index("UPDATE toilet_translation"))
        self.assertIn("@name_fix_eligible=4 AND @name_fix_cache_trigger=1", sql)
        self.assertIn("tr.manual_override=1, tr.version=tr.version+1", sql)

    def test_only_name_and_protection_metadata_are_written(self):
        for mode in ("apply", "rollback"):
            sql = build_sql(self.rows, mode)
            updates = sql.split("SET tr.name=", 1)[1].split("WHERE", 1)[0]
            for field in ("road_address", "jibun_address", "source_hash", "translation_status", "reviewed_at", "translated_at"):
                self.assertNotIn(field + "=", updates)
            self.assertNotIn("INSERT INTO", sql)
            self.assertNotIn("DELETE FROM", sql)

    def test_rollback_requires_exact_result_and_keeps_version_monotonic(self):
        sql = build_sql(self.rows, "rollback")
        self.assertIn("2 AS expected_version", sql)
        self.assertIn("AND tr.manual_override=1", sql)
        self.assertIn("tr.manual_override=0, tr.version=tr.version+1", sql)

    def test_rejects_incomplete_duplicate_extra_targets(self):
        for rows in (self.rows[:3], self.rows + self.rows[:1], self.rows[:3] + self.rows[:1]):
            with self.assertRaises(ValueError):
                validate(rows)
        rows = copy.deepcopy(self.rows)
        rows[0]["locale"] = "ko"
        with self.assertRaises(ValueError):
            validate(rows)

    def test_rejects_bad_hash_and_version(self):
        for field, value in (("sourceHash", "x"), ("addressHash", "' OR 1=1"), ("expectedVersion", 0)):
            rows = copy.deepcopy(self.rows)
            rows[0][field] = value
            with self.assertRaises(ValueError):
                validate(rows)

    def test_text_is_utf8_hex_not_sql_interpolation(self):
        self.assertEqual(sql_text("坐'"), "CONVERT(0xe59d9027 USING utf8mb4)")


if __name__ == "__main__":
    unittest.main()
