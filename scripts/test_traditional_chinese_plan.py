import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("plan", Path(__file__).with_name("plan_traditional_chinese_translation.py"))
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


class TraditionalChinesePlanTest(unittest.TestCase):
    def test_deduplicates_exact_source_text_per_target_language(self):
        rows = [
            {"kind": "facility", "locale": "zh-tw", "name": "화장실", "road": "서울 1", "jibun": None},
            {"kind": "facility", "locale": "zh-tw", "name": "화장실", "road": "서울 1", "jibun": None},
            {"kind": "group", "locale": "zh-tw", "name": "화장실"},
            {"kind": "facility", "locale": "zh-hk", "name": "화장실", "road": None, "jibun": None},
        ]
        result = plan.summarize(rows)
        self.assertEqual(result["targets"]["zh-tw"]["facilityRows"], 2)
        self.assertEqual(result["targets"]["zh-tw"]["uniqueTexts"], 2)
        self.assertEqual(result["targets"]["zh-hk"]["missingAddressRows"], 1)
        self.assertEqual(result["billableInputCharacters"], len("화장실") * 2 + len("서울 1"))
        self.assertFalse(result["rawSourceExported"])


if __name__ == "__main__":
    unittest.main()
