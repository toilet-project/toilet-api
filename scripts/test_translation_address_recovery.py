import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "translation_address_recovery", Path(__file__).with_name("translation_address_recovery.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TranslationAddressRecoveryTest(unittest.TestCase):
    def row(self, **changes):
        value = {
            "toiletId": 1, "name": "Central Library Restroom", "roadAddress": "서울특별시 중구 세종대로 110 (태평로1가)",
            "jibunAddress": None, "latitude": 37.5663, "longitude": 126.9779,
            "sidoName": "서울특별시", "sigunguName": "중구", "sourceHash": "a" * 64,
        }
        value.update(changes); return value

    def juso_success(self):
        return {"results": {"common": {"errorCode": "0"}, "juso": [{
            "korAddr": "서울특별시 중구 세종대로 110", "roadAddr": "110 Sejong-daero, Jung-gu, Seoul", "jibunAddr": ""
        }]}}

    def juso_empty(self):
        return {"results": {"common": {"errorCode": "0"}, "juso": []}}

    def kakao_success(self):
        return {"documents": [{"road_address": {
            "address_name": "서울특별시 중구 세종대로 110", "region_1depth_name": "서울특별시", "region_2depth_name": "중구"
        }, "address": {}}]}

    def test_address_variants_include_road_prefix_without_parenthetical(self):
        variants = MODULE.address_variants("서울특별시 중구 세종대로 110 (태평로1가) 2층")
        self.assertIn("서울특별시 중구 세종대로 110", variants)
        self.assertEqual(len(variants), len(set(variants)))

    def test_direct_official_recovery_uses_road_address(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE, "get_json", return_value=self.juso_success()):
            cache = MODULE.RecoveryCache(Path(directory) / "cache.json")
            result = MODULE.recover_row(self.row(), "juso", "kakao", cache, MODULE.RateLimiter(0), MODULE.RateLimiter(0))
        self.assertTrue(result["recovered"])
        self.assertEqual("MOIS_JUSO_NORMALIZED", result["addressTranslationSource"])
        self.assertEqual("110 Sejong-daero, Jung-gu, Seoul", result["roadAddress"])

    def test_reverse_geocode_then_official_recovery(self):
        def response(url, params, headers, limiter, attempts=3):
            if url == MODULE.KAKAO_ENDPOINT:
                return self.kakao_success()
            if params["keyword"] == "서울특별시 중구 세종대로 110":
                return self.juso_success()
            return self.juso_empty()
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE, "get_json", side_effect=response), \
                patch.object(MODULE, "address_variants", side_effect=lambda value: [value]):
            cache = MODULE.RecoveryCache(Path(directory) / "cache.json")
            result = MODULE.recover_row(self.row(roadAddress="잘못된 주소"), "juso", "kakao", cache, MODULE.RateLimiter(0), MODULE.RateLimiter(0))
        self.assertTrue(result["recovered"])
        self.assertEqual("KAKAO_REVERSE_MOIS_JUSO", result["addressTranslationSource"])
        self.assertEqual(1, result["kakaoRequestCount"])

    def test_missing_coordinate_is_left_for_review(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE, "get_json", return_value=self.juso_empty()), \
                patch.object(MODULE, "address_variants", side_effect=lambda value: [value]):
            cache = MODULE.RecoveryCache(Path(directory) / "cache.json")
            result = MODULE.recover_row(self.row(latitude=None, longitude=None), "juso", "kakao", cache, MODULE.RateLimiter(0), MODULE.RateLimiter(0))
        self.assertFalse(result["recovered"])
        self.assertEqual("NO_VALID_COORDINATE", result["failureReason"])
        self.assertEqual("NEEDS_REVIEW", result["addressTranslationStatus"])

    def test_reverse_region_mismatch_is_rejected(self):
        kakao = {"documents": [{"road_address": {
            "address_name": "부산광역시 중구 중앙대로 1", "region_1depth_name": "부산광역시", "region_2depth_name": "중구"
        }, "address": {}}]}
        responses = [self.juso_empty(), kakao]
        with tempfile.TemporaryDirectory() as directory, patch.object(MODULE, "get_json", side_effect=responses), \
                patch.object(MODULE, "address_variants", side_effect=lambda value: [value]):
            cache = MODULE.RecoveryCache(Path(directory) / "cache.json")
            result = MODULE.recover_row(self.row(roadAddress="잘못된 주소"), "juso", "kakao", cache, MODULE.RateLimiter(0), MODULE.RateLimiter(0))
        self.assertFalse(result["recovered"])
        self.assertEqual("REGION_MISMATCH", result["failureReason"])


if __name__ == "__main__":
    unittest.main()
