import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("translation_full_sql", Path(__file__).with_name("translation_full_sql.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TranslationFullSqlTest(unittest.TestCase):
    def row(self, toilet_id=1):
        return {"toiletId": toilet_id, "name": "Central Library Restroom", "roadAddress": "1 Main-ro",
                "jibunAddress": None, "expectedSourceHash": "a" * 64,
                "source": "FULL_GOOGLE_NMT_JUSO"}

    def test_builds_guarded_upsert_without_plaintext(self):
        sql = MODULE.build_sql([self.row()])
        self.assertIn("manual_override", sql)
        self.assertIn("ko.source_hash=s.source_hash", sql)
        self.assertIn("MACHINE_TRANSLATED", sql)
        self.assertNotIn("Central Library Restroom", sql)
        self.assertIn("CONVERT(0x", sql)

    def test_load_rejects_duplicate_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            path.write_text("\n".join(json.dumps(self.row()) for _ in range(2)), encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.load_rows(path)

    def test_load_rejects_invalid_source(self):
        row = self.row(); row["source"] = "unsafe-source"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            path.write_text(json.dumps(row), encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.load_rows(path)


if __name__ == "__main__":
    unittest.main()
