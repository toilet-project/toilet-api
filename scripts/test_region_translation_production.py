import contextlib
import io
import json
import sys
import unittest
from unittest.mock import patch

import check_region_translation_production as check


class ProductionCheckTest(unittest.TestCase):
    CODES = ",".join(f"{number:05d}" for number in range(10000, 10287))

    def run_check(self, mode, *, rows=1435):
        def fake_integer(sql, _username, _password):
            if "region_sigungu_reference" in sql:
                return 287
            if "information_schema.tables" in sql:
                return int(mode == "after")
            if "flyway_schema_history" in sql:
                return int(mode == "after" or "version='17'" in sql or "version='18'" in sql)
            if "COUNT(DISTINCT sigungu_code)" in sql:
                return 287
            if "HAVING COUNT(DISTINCT locale)<>5" in sql:
                return 0
            if "COUNT(*) FROM region_sigungu_translation" in sql:
                return rows
            raise AssertionError(sql)

        with patch.object(sys, "argv", ["check", "--mode", mode, "--expected-codes", self.CODES]), \
             patch.object(check, "inspect", return_value={
                 "SPRING_DB_USERNAME": "reader", "SPRING_DB_PASSWORD": "redacted"}), \
             patch.object(check, "integer", side_effect=fake_integer), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            check.main()
            return json.loads(output.getvalue())

    def test_before_requires_reference_codes_without_translation_table(self):
        result = self.run_check("before")
        self.assertEqual(result["expectedReferenceCodes"], 287)
        self.assertFalse(result["translationTablePresent"])

    def test_after_requires_complete_translations(self):
        result = self.run_check("after")
        self.assertEqual(result["translationRows"], 1435)
        with self.assertRaisesRegex(ValueError, "translation rows incomplete"):
            self.run_check("after", rows=1434)

    def test_duplicate_expected_code_is_rejected_before_accessing_production(self):
        with patch.object(sys, "argv", ["check", "--mode", "before", "--expected-codes", "11110,11110"]), \
             patch.object(check, "inspect") as inspect:
            with self.assertRaisesRegex(ValueError, "expected migration codes invalid"):
                check.main()
            inspect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
