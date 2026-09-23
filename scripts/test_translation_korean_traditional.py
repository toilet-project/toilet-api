import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error

SPEC = importlib.util.spec_from_file_location("traditional", Path(__file__).with_name("translation_korean_traditional.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TraditionalTranslationTest(unittest.TestCase):
    def test_plan_deduplicates_within_each_locale(self):
        rows = [
            {"kind": "facility", "locale": "zh-tw", "name": "공중화장실", "road": "서울 1", "jibun": None},
            {"kind": "facility", "locale": "zh-tw", "name": "공중화장실", "road": "서울 2", "jibun": None},
            {"kind": "facility", "locale": "zh-hk", "name": "공중화장실", "road": "서울 1", "jibun": None},
        ]
        result = MODULE.plan(rows)
        self.assertEqual(result["uniqueTexts"], 5)
        self.assertEqual(result["inputCharacters"], 2 * len("공중화장실") + 2 * len("서울 1") + len("서울 2"))

    def test_retry_and_llm_output_are_counted_in_cost_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            calls = []

            def opener(request, timeout):
                calls.append(json.loads(request.data))
                if len(calls) == 1:
                    raise TimeoutError()
                return io.BytesIO(json.dumps({"data": {"translations": [
                    {"translatedText": "香港公廁"}]}}).encode())

            MODULE.translate(ledger, "test-key", "test-project", "zh-hk", ["공중화장실"],
                             1_000_000, opener=opener, sleeper=lambda seconds: None)
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[1]["target"], "zh-HK")
            self.assertIn("translation-llm", calls[1]["model"])
            self.assertEqual(ledger.spent(), len("공중화장실") * 50 + (len("공중화장실") + len("香港公廁")) * 10)
            self.assertEqual(ledger.get("zh-hk", "공중화장실"), "香港公廁")
            self.assertEqual(ledger.usage()["zh-hk"]["uncertainRequests"], 1)
            ledger.db.close()

    def test_cap_blocks_request_before_network_call(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            with self.assertRaisesRegex(RuntimeError, "cap reached"):
                MODULE.translate(ledger, "test-key", "test-project", "zh-tw", ["공중화장실"], 1,
                                 opener=lambda *_args, **_kwargs: self.fail("network called"))
            ledger.db.close()

    def test_google_error_reports_only_safe_reason(self):
        body = io.BytesIO(json.dumps({"error": {"status": "PERMISSION_DENIED",
            "message": "secret endpoint and key", "errors": [{"reason": "accessNotConfigured"}]}}).encode())
        error = urllib.error.HTTPError("https://example.invalid", 403, "denied", {}, body)
        self.assertEqual(MODULE.safe_google_error(error), "PERMISSION_DENIED,accessNotConfigured")
        error.close()

    def test_insert_guards_source_and_existing_target(self):
        row = {"kind": "facility", "id": 42, "locale": "zh-hk", "sourceHash": "a" * 64,
               "name": "공중화장실 12", "road": "서울 12", "jibun": None}
        sql = MODULE.insert_sql(row, {"name": "公廁 12", "road": "首爾 12"})
        self.assertIn("dst.toilet_id IS NULL", sql)
        self.assertIn("ko.source_hash=", sql)
        self.assertIn(MODULE.sql_text("GOOGLE_LLM_KO"), sql)
        self.assertEqual(MODULE.validate(row, {"name": "公廁 13", "road": "首爾 12"}), ["name:numbers"])
        self.assertEqual(MODULE.validate(row, {"name": "第十二公廁", "road": "首爾 12"}), [])


if __name__ == "__main__":
    unittest.main()
