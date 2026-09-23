import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
from unittest.mock import patch

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

    def test_cost_cap_is_atomic_across_parallel_connections(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ledger.sqlite"
            ledger = MODULE.Ledger(path)
            concurrent = threading.Barrier(3, timeout=5)

            def reserve_one(_index):
                connection = MODULE.Ledger(path)
                try:
                    concurrent.wait()
                    try:
                        connection.reserve("zh-hk", 1, 100)
                        return True
                    except RuntimeError as error:
                        self.assertIn("cap reached", str(error))
                        return False
                finally:
                    connection.db.close()

            with MODULE.ThreadPoolExecutor(max_workers=3) as pool:
                results = list(pool.map(reserve_one, range(3)))
            self.assertEqual(sum(results), 2)
            self.assertEqual(ledger.spent(), 100)
            ledger.db.close()

    def test_google_error_reports_only_safe_reason(self):
        body = io.BytesIO(json.dumps({"error": {"status": "PERMISSION_DENIED",
            "message": "secret endpoint and key", "errors": [{"reason": "accessNotConfigured"}]}}).encode())
        error = urllib.error.HTTPError("https://example.invalid", 403, "denied", {}, body)
        self.assertEqual(MODULE.safe_google_error(error), "PERMISSION_DENIED,accessNotConfigured")
        error.close()

    def test_per_minute_limit_waits_and_retries_without_repeating_successes(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            attempts, waits = [], []

            def opener(request, timeout):
                attempts.append(request)
                if len(attempts) == 1:
                    body = io.BytesIO(json.dumps({"error": {"errors": [
                        {"reason": "userRateLimitExceeded"}]}}).encode())
                    raise urllib.error.HTTPError("https://example.invalid", 403, "limited", {}, body)
                return io.BytesIO(json.dumps({"data": {"translations": [
                    {"translatedText": "香港公廁"}]}}).encode())

            MODULE.translate(ledger, "test-key", "test-project", "zh-hk", ["공중화장실"],
                             1_000_000, opener=opener, sleeper=waits.append)
            self.assertEqual(len(attempts), 2)
            self.assertEqual(waits, [65])
            self.assertEqual(ledger.usage()["zh-hk"]["uncertainRequests"], 1)
            ledger.db.close()

    def test_contextual_recovery_keeps_only_the_translated_span(self):
        row = {"kind": "facility", "locale": "zh-hk", "name": "한강 공중화장실"}
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            ledger.db.execute("INSERT INTO translations VALUES(?,?,?)", ("zh-hk", row["name"], "한강公廁"))
            ledger.db.execute("INSERT INTO translations VALUES(?,?,?)", ("zh-hk", MODULE.context_markup(row["name"], "name"),
                              '<div>南韓公廁名稱: <span id="translation-result">漢江公廁</span></div>'))
            ledger.db.commit()
            self.assertEqual(MODULE.candidate(ledger, row, "name"), "漢江公廁")
            ledger.db.close()

    def test_contextual_recovery_also_retries_changed_address_numbers(self):
        row = {"kind": "facility", "locale": "zh-hk", "road": "서울 12"}
        self.assertTrue(MODULE.needs_context_recovery(row, "road", "首爾 十二"))
        self.assertTrue(MODULE.needs_context_recovery(row, "road", "서울 12"))
        self.assertFalse(MODULE.needs_context_recovery(row, "road", "首爾 12"))

    def test_packed_translation_requires_all_ordered_markers(self):
        self.assertEqual(MODULE.parse_packed("0|公廁\n1|首爾市 12\n", 2), ["公廁", "首爾市 12"])
        self.assertIsNone(MODULE.parse_packed("0|公廁\n2|首爾市 12\n", 2))
        self.assertIsNone(MODULE.parse_packed("0|公廁\n", 2))

    def test_packed_hong_kong_translation_falls_back_only_for_bad_groups(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            sources = [f"시설 {number}" for number in range(20)]
            sent = []

            def fake_translate(cache, key, project, locale, batch, cap, **kwargs):
                sent.append(list(batch))
                if len(batch) == 1 and "\n" in batch[0]:
                    packed = "\n".join(f"{number}|公廁 {number}" for number in range(10))
                    cache.save_texts(locale, batch, [packed] if len(sent) == 1 else ["malformed"])
                else:
                    cache.save_texts(locale, batch, [f"公廁 {number}" for number in range(10, 20)])

            with patch.object(MODULE, "translate", side_effect=fake_translate):
                result = MODULE.translate_packed_hk(ledger, "key", "project", sources,
                                                     1_000_000, sleeper=lambda seconds: None)
            self.assertEqual(result["packedGroups"], 2)
            self.assertEqual(result["fallbackGroups"], 1)
            self.assertEqual(len(sent), 3)
            self.assertEqual(ledger.get("zh-hk", "시설 0"), "公廁 0")
            self.assertEqual(ledger.get("zh-hk", "시설 19"), "公廁 19")
            ledger.db.close()

    def test_packed_backend_failure_isolates_one_bad_text(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            sources = [f"시설 {number}" for number in range(10)]

            def fake_translate(cache, key, project, locale, batch, cap, **kwargs):
                if "시설 3" in "\n".join(batch):
                    raise RuntimeError("Google zh-hk translation failed with HTTP 500 (INTERNAL,backendError)")
                cache.save_texts(locale, batch, ["公廁" for _ in batch])

            with patch.object(MODULE, "translate", side_effect=fake_translate):
                result = MODULE.translate_packed_hk(ledger, "key", "project", sources,
                                                     1_000_000, sleeper=lambda seconds: None)
            self.assertEqual(result["fallbackGroups"], 1)
            self.assertEqual(result["skippedTexts"], 1)
            self.assertIsNone(ledger.get("zh-hk", "시설 3"))
            self.assertEqual(ledger.get("zh-hk", "시설 9"), "公廁")
            ledger.db.close()

    def test_backend_probe_continues_past_three_bad_source_texts(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            sources = [f"시설 {number}" for number in range(10)]
            waits = []

            def fake_translate(cache, key, project, locale, batch, cap, **kwargs):
                if any(f"시설 {number}" in "\n".join(batch) for number in range(3)):
                    raise RuntimeError("Google zh-hk translation failed with HTTP 500 (INTERNAL,backendError)")
                cache.save_texts(locale, batch, ["公廁" for _ in batch])

            with patch.object(MODULE, "translate", side_effect=fake_translate):
                result = MODULE.translate_packed_hk(ledger, "key", "project", sources,
                                                     1_000_000, sleeper=waits.append)
            self.assertEqual(result["skippedTexts"], 3)
            self.assertEqual(result["backendProbes"], 1)
            self.assertIn(65, waits)
            self.assertIsNone(ledger.get("zh-hk", "시설 2"))
            self.assertEqual(ledger.get("zh-hk", "시설 9"), "公廁")
            ledger.db.close()

    def test_backend_probe_stops_when_the_service_is_still_failing(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            failure = RuntimeError("Google zh-hk translation failed with HTTP 500 (INTERNAL,backendError)")
            with patch.object(MODULE, "translate", side_effect=failure):
                with self.assertRaisesRegex(RuntimeError, "backendError"):
                    MODULE.translate_packed_hk(ledger, "key", "project",
                                               [f"시설 {number}" for number in range(10)],
                                               1_000_000, sleeper=lambda seconds: None)
            ledger.db.close()

    def test_parallel_packs_share_the_cache_with_separate_connections(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger = MODULE.Ledger(Path(directory) / "ledger.sqlite")
            sources = [f"시설 {number}" for number in range(30)]
            concurrent = threading.Barrier(3, timeout=5)

            def fake_translate(cache, key, project, locale, batch, cap, **kwargs):
                concurrent.wait()
                packed = "\n".join(f"{index}|公廁 {index}" for index in range(10))
                cache.save_texts(locale, batch, [packed])

            with patch.object(MODULE, "translate", side_effect=fake_translate):
                result = MODULE.translate_packed_hk(ledger, "key", "project", sources,
                                                     1_000_000, sleeper=lambda seconds: None)
            self.assertEqual(result["packedGroups"], 3)
            self.assertEqual(result["fallbackGroups"], 0)
            self.assertTrue(all(ledger.get("zh-hk", source) for source in sources))
            ledger.db.close()

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
