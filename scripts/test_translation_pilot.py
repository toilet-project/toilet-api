import collections
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("translation_pilot", Path(__file__).with_name("translation_pilot.py"))
pilot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pilot)

REVIEW_SPEC = importlib.util.spec_from_file_location(
    "prepare_translation_review_sample", Path(__file__).with_name("prepare_translation_review_sample.py")
)
review = importlib.util.module_from_spec(REVIEW_SPEC)
REVIEW_SPEC.loader.exec_module(review)


class TranslationPilotTest(unittest.TestCase):
    def source(self, **overrides):
        row = {
            "toiletId": 1,
            "name": "시청 1층 화장실",
            "roadAddress": "서울특별시 중구 세종대로 110",
            "jibunAddress": "서울특별시 중구 태평로1가 31",
            "selectedAddressKind": "ROAD",
            "selectedAddress": "서울특별시 중구 세종대로 110",
            "sourceHash": "a" * 64,
            "region": "서울특별시",
            "toiletType": "공중화장실",
        }
        row.update(overrides)
        return row

    def test_road_address_wins_and_jibun_is_only_fallback(self):
        self.assertEqual(("ROAD", "서울특별시 중구 세종대로 110"), pilot.selected_address(self.source()))
        fallback = self.source(roadAddress=None, selectedAddressKind="JIBUN", selectedAddress="서울특별시 중구 태평로1가 31")
        self.assertEqual(("JIBUN", "서울특별시 중구 태평로1가 31"), pilot.selected_address(fallback))

    def test_source_audit_counts_only_names_as_google_billable(self):
        report = pilot.validate_source([self.source()], 1)
        self.assertEqual(len("시청 1층 화장실"), report["googleBillableCharacters"])
        self.assertEqual({"ROAD": 1}, report["addressKinds"])
        self.assertEqual("ROAD_THEN_JIBUN", report["addressPriority"])

    def test_full_run_can_use_bounded_address_workers(self):
        args = pilot.parser().parse_args([
            "translate", "source.jsonl", "results.jsonl", "--address-workers", "4",
        ])
        self.assertEqual(4, args.address_workers)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            pilot.parser().parse_args(["translate", "source.jsonl", "results.jsonl", "--address-workers", "9"])

    def test_result_audit_rejects_both_address_columns(self):
        result = {
            "toiletId": 1,
            "name": "City Hall 1F Restroom",
            "roadAddress": "110 Sejong-daero, Jung-gu, Seoul",
            "jibunAddress": "31 Taepyeongno 1-ga, Jung-gu, Seoul",
            "expectedSourceHash": "a" * 64,
        }
        report = pilot.audit_results([self.source()], [result])
        self.assertIn("ROAD_PRIORITY_VIOLATION", report["issueCounts"])

    def test_result_audit_detects_number_loss(self):
        result = {
            "toiletId": 1,
            "name": "City Hall Restroom",
            "roadAddress": "110 Sejong-daero, Jung-gu, Seoul",
            "jibunAddress": None,
            "expectedSourceHash": "a" * 64,
        }
        report = pilot.audit_results([self.source()], [result])
        self.assertIn("NAME_NUMBER_LOSS", report["issueCounts"])

    def test_result_audit_summarizes_provider_usage_without_raw_source(self):
        result = {
            "toiletId": 1,
            "name": "City Hall 1F Restroom",
            "roadAddress": "110 Sejong-daero, Jung-gu, Seoul",
            "jibunAddress": None,
            "expectedSourceHash": "a" * 64,
            "addressError": None,
        }
        report = pilot.audit_results([self.source()], [result])
        self.assertEqual(1, report["translatedAddressCount"])
        self.assertEqual(0, report["addressLookupErrorCount"])
        self.assertEqual(len("시청 1층 화장실"), report["googleCharactersSubmitted"])

    def test_result_audit_counts_official_address_lookup_misses(self):
        result = {
            "toiletId": 1,
            "name": "City Hall 1F Restroom",
            "roadAddress": None,
            "jibunAddress": None,
            "expectedSourceHash": "a" * 64,
            "addressError": "no official English address result",
        }
        report = pilot.audit_results([self.source()], [result])
        self.assertEqual(0, report["translatedAddressCount"])
        self.assertEqual(1, report["addressLookupErrorCount"])
        self.assertNotIn("MISSING_ADDRESS_WITHOUT_ERROR", report["issueCounts"])
        self.assertNotIn("ROAD_PRIORITY_VIOLATION", report["issueCounts"])

    @patch.object(pilot, "get_json")
    def test_juso_english_response_uses_road_address_field(self, get_json):
        get_json.return_value = {
            "results": {
                "common": {"errorCode": "0"},
                "juso": [{
                    "korAddr": "서울특별시 중구 세종대로 110",
                    "roadAddr": "110 Sejong-daero, Jung-gu, Seoul",
                    "jibunAddr": "31 Taepyeongno 1-ga, Jung-gu, Seoul",
                }],
            }
        }
        translated = pilot.translate_address("서울특별시 중구 세종대로 110", "key", "ROAD")
        self.assertEqual("110 Sejong-daero, Jung-gu, Seoul", translated)

    @patch.object(pilot, "get_json")
    def test_juso_english_response_uses_jibun_address_field(self, get_json):
        get_json.return_value = {
            "results": {
                "common": {"errorCode": "0"},
                "juso": [{
                    "korAddr": "서울특별시 중구 세종대로 110",
                    "roadAddr": "110 Sejong-daero, Jung-gu, Seoul",
                    "jibunAddr": "31 Taepyeongno 1-ga, Jung-gu, Seoul",
                }],
            }
        }
        translated = pilot.translate_address("서울특별시 중구 태평로1가 31", "key", "JIBUN")
        self.assertEqual("31 Taepyeongno 1-ga, Jung-gu, Seoul", translated)

    @patch.object(pilot, "get_json")
    def test_juso_search_removes_rejected_sql_special_characters(self, get_json):
        get_json.return_value = {"results": {"common": {"errorCode": "0"}, "juso": [{
            "korAddr": "서울특별시 중구 세종대로 110", "roadAddr": "110 Sejong-daero, Jung-gu, Seoul",
            "jibunAddr": "31 Taepyeongno 1-ga, Jung-gu, Seoul",
        }]}}
        pilot.translate_address("서울특별시 중구 세종대로 110 [별관]=1%", "key", "ROAD")
        params = get_json.call_args.args[1]
        self.assertEqual("서울특별시 중구 세종대로 110 별관 1", params["keyword"])

    def test_audit_source_records_fingerprint_without_retention(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.jsonl"
            report = Path(directory) / "report.json"
            source.write_text(json.dumps(self.source(), ensure_ascii=False) + "\n", encoding="utf-8")
            args = type("Args", (), {"source": source, "output": report, "expected_count": 1})()
            pilot.command_audit_source(args)
            saved = json.loads(report.read_text(encoding="utf-8"))
            self.assertRegex(saved["sourceFingerprintSha256"], r"^[0-9a-f]{64}$")
            self.assertFalse(saved["rawSourceRetained"])

    def test_collectors_attach_sql_to_mysql_container_stdin(self):
        scripts = Path(__file__).parent
        for filename in ("collect-translation-pilot-source.sh", "collect-translation-pilot-report.sh"):
            content = (scripts / filename).read_text(encoding="utf-8")
            self.assertIn('docker exec -i -e MYSQL_PWD="$mysql_password"', content)

    def test_provider_workflow_uses_ssh_stream_without_sftp_subsystem(self):
        workflow = Path(__file__).parents[1] / ".github" / "workflows" / "translation-pilot-run.yml"
        content = workflow.read_text(encoding="utf-8")
        self.assertNotIn("          scp ", content)
        self.assertIn("cat > '$remote_dir/input.tgz'", content)
        self.assertIn("cat '$remote_dir/output.tgz'", content)

    def test_review_sample_uses_fixed_strata_and_unique_ids(self):
        rows = []
        toilet_id = 1
        for category, count in {
            "ROAD_SUCCESS": 602,
            "JIBUN_SUCCESS": 43,
            "ROAD_NO_RESULT": 216,
            "JIBUN_NO_RESULT": 139,
        }.items():
            kind, status = category.split("_", 1)
            for index in range(count):
                rows.append({
                    "toiletId": toilet_id,
                    "addressKind": kind,
                    "roadAddress": "English road" if kind == "ROAD" and status == "SUCCESS" else None,
                    "jibunAddress": "English jibun" if kind == "JIBUN" and status == "SUCCESS" else None,
                    "region": f"region-{index % 17}",
                })
                toilet_id += 1
        selected = review.select(rows)
        self.assertEqual(100, len(selected))
        self.assertEqual(100, len({row["toiletId"] for row in selected}))
        self.assertEqual(review.QUOTAS, dict(collections.Counter(row["reviewStratum"] for row in selected)))

    def test_review_export_artifact_contains_ciphertext_only(self):
        scripts = Path(__file__).parent
        remote = (scripts / "run-translation-review-export-remote.sh").read_text(encoding="utf-8")
        workflow = (scripts.parent / ".github" / "workflows" / "translation-pilot-review-export.yml").read_text(encoding="utf-8")
        self.assertIn("openssl enc -aes-256-cbc -pbkdf2", remote)
        self.assertIn("openssl pkeyutl -encrypt -pubin", remote)
        self.assertIn("SPRING_DB_USERNAME", remote)
        self.assertIn("SPRING_DB_PASSWORD", remote)
        self.assertIn("mysql --protocol=tcp -h 127.0.0.1", remote)
        self.assertIn("rm -f -- \"$work_dir/source-base64.tsv\"", remote)
        self.assertNotIn("source-base64.tsv translation", remote)
        self.assertIn("Upload encrypted review source only", workflow)
        self.assertIn("export_public_key_base64", workflow)
        self.assertNotIn("TRANSLATION_REVIEW_EXPORT_KEY", workflow)
        self.assertNotIn("source-base64.tsv\n", workflow)


if __name__ == "__main__":
    unittest.main()
