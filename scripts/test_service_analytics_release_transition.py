import copy
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).with_name('service_analytics_release_transition.py')
SPEC = importlib.util.spec_from_file_location('service_analytics_release_transition', MODULE)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

COMPOSE = b'''services:\n  api:\n    environment:\n      ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'\n      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n'''


class ServiceAnalyticsReleaseTransitionTest(unittest.TestCase):
    def test_inject_and_remove_are_exact_inverses(self):
        active = release.inject_analytics(COMPOSE)
        self.assertIn(b"SERVICE_ANALYTICS_ENABLED: 'true'", active)
        self.assertIn(b"SERVICE_ANALYTICS_DAILY_CRON: '0 30 2 * * *'", active)
        self.assertEqual(COMPOSE, release.remove_analytics(active))

    def test_injection_rejects_duplicates_and_unknown_shape(self):
        with self.assertRaises(ValueError):
            release.inject_analytics(release.inject_analytics(COMPOSE))
        with self.assertRaises(ValueError):
            release.inject_analytics(COMPOSE.replace(b'ERASURE_MAINTENANCE_DIRECTORY', b'OTHER'))

    def test_removal_rejects_partial_configuration(self):
        partial = COMPOSE + b"      SERVICE_ANALYTICS_ENABLED: 'true'\n"
        with self.assertRaises(ValueError):
            release.remove_analytics(partial)

    def test_render_validation_allows_only_analytics_values(self):
        before = {'services': {'api': {'environment': {'KEEP': 'same'}},
                               'batch': {'environment': {'KEEP': 'same'}}}}
        after = copy.deepcopy(before)
        after['services']['api']['environment'].update(release.ANALYTICS_CONFIG)
        release.validate_render_change(before, after, True)
        changed = copy.deepcopy(after)
        changed['services']['batch']['environment']['KEEP'] = 'changed'
        with self.assertRaises(ValueError):
            release.validate_render_change(before, changed, True)
        release.validate_render_change(after, before, False)

    def test_analytics_state_rejects_partial_or_wrong_values(self):
        def obj(values):
            return {'Config': {'Env': [key + '=' + value for key, value in values.items()]}}
        self.assertEqual('inactive', release.analytics_state(obj({})))
        self.assertEqual('active', release.analytics_state(obj(release.ANALYTICS_CONFIG)))
        with self.assertRaises(ValueError):
            release.analytics_state(obj({'SERVICE_ANALYTICS_ENABLED': 'true'}))
        with self.assertRaises(ValueError):
            release.analytics_state(obj({**release.ANALYTICS_CONFIG,
                                         'SERVICE_ANALYTICS_ENABLED': 'false'}))


if __name__ == '__main__':
    unittest.main()
