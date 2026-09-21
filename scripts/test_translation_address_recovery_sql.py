import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "translation_address_recovery_sql", Path(__file__).with_name("translation_address_recovery_sql.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TranslationAddressRecoverySqlTest(unittest.TestCase):
    def row(self, recovered=True):
        return {
            "toiletId": 1, "expectedSourceHash": "a" * 64, "recovered": recovered,
            "roadAddress": "110 Sejong-daero, Jung-gu, Seoul" if recovered else None,
            "jibunAddress": None,
            "addressTranslationStatus": "TRANSLATED" if recovered else "NEEDS_REVIEW",
            "addressTranslationSource": "MOIS_JUSO_NORMALIZED" if recovered else "RECOVERY_EXHAUSTED",
        }

    def load(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
            return MODULE.load_rows(path)

    def test_builds_guarded_update_without_plaintext(self):
        sql = MODULE.build_sql([self.row()])
        self.assertIn("en.manual_override=FALSE", sql)
        self.assertIn("ko.source_hash=s.source_hash", sql)
        self.assertIn("address_translation_status='NO_RESULT'", sql)
        self.assertIn("SUM(en.address_translation_status='TRANSLATED')", sql)
        self.assertIn("SUM(en.address_translation_status='NEEDS_REVIEW')", sql)
        self.assertIn("@applied_count", sql)
        self.assertNotIn("110 Sejong-daero", sql)
        self.assertIn("CONVERT(0x", sql)

    def test_failed_recovery_can_be_marked_for_review(self):
        rows = self.load([self.row(False)])
        self.assertEqual("NEEDS_REVIEW", rows[0]["addressTranslationStatus"])

    def test_translated_status_requires_address(self):
        row = self.row(); row["roadAddress"] = None
        with self.assertRaises(ValueError):
            self.load([row])

    def test_remote_report_includes_current_production_aggregate(self):
        collector = Path(__file__).with_name("collect-translation-address-recovery-source.sh").read_text(encoding="utf-8")
        runner = Path(__file__).with_name("run-translation-address-recovery-remote.sh").read_text(encoding="utf-8")
        self.assertIn("plan|batch|audit", collector)
        self.assertIn("'sourceCounts',JSON_OBJECT", collector)
        self.assertIn("'productionAudit':production_audit", runner)
        self.assertIn("production audit target count does not match final plan", runner)


if __name__ == "__main__":
    unittest.main()
