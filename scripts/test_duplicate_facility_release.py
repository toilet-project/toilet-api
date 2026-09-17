import copy
from pathlib import Path
import unittest
import duplicate_facility_release as release


class DuplicateReleaseTest(unittest.TestCase):
    def test_single_flag_only(self):
        before={'services':{'api':{'environment':{'SERVICE_ANALYTICS_ENABLED':'true','REVIEWS_ENABLED':'true'}},'batch':{}}}
        after=copy.deepcopy(before)
        after['services']['api']['environment'][release.FLAG]='true'
        release.base.validate_render_change(before,after,True)
        after['services']['api']['environment']['REVIEWS_ENABLED']='false'
        with self.assertRaises(ValueError):
            release.base.validate_render_change(before,after,True)

    def test_environment_preservation(self):
        release.preserved_environment({'KEEP':'original'}, {'KEEP':'original',release.FLAG:'true'})
        with self.assertRaises(ValueError):
            release.preserved_environment({'KEEP':'original'}, {'KEEP':'changed',release.FLAG:'true'})

    def test_cache_is_additive(self):
        path=Path(__file__).resolve().parents[1]/'src/main/resources/db/cache-revalidation/V3__visibility_cache_events.sql'
        sql=path.read_text(encoding='utf-8')
        self.assertTrue(release.expected_body(sql).startswith('UPDATE web_cache_invalidation'))
        with self.assertRaises(ValueError):
            release.expected_body('DROP TRIGGER cache_toilet_update;\n'+sql)

    def test_cache_order_and_existing_hash_are_required(self):
        state={'cache_toilet_update':(release.OLD_TRIGGER_HASH,1),release.TRIGGER:('new',2)}
        release.validate_cache(state,'new')
        state[release.TRIGGER]=('new',1)
        with self.assertRaises(ValueError):release.validate_cache(state,'new')

    def test_compose_injection_preserves_existing_text(self):
        content=b"services:\n  api:\n    environment:\n      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n      REVIEWS_ENABLED: 'true'\n"
        active=release.base.inject_analytics(content)
        self.assertEqual(content, release.base.remove_analytics(active))
        with self.assertRaises(ValueError):release.base.inject_analytics(active)


if __name__=='__main__':unittest.main()
