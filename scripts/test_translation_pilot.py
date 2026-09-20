import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("translation_pilot", Path(__file__).with_name("translation_pilot.py"))
pilot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pilot)


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


if __name__ == "__main__":
    unittest.main()
