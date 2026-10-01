import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).with_name('service_analytics_release_transition.py')
SPEC = importlib.util.spec_from_file_location('service_analytics_release_transition', MODULE)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

COMPOSE = b'''services:\n  api:\n    environment:\n      ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'\n      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n'''


class ServiceAnalyticsReleaseTransitionTest(unittest.TestCase):
    def test_engagement_changes_only_its_flag_and_can_roll_back(self):
        original = release.inject_analytics(COMPOSE)
        before = {'services': {'api': {'environment': {
            **release.ANALYTICS_CONFIG, 'WEB_CACHE_REVALIDATION_ENABLED': 'true',
            'REVIEWS_ENABLED': 'true'}}, 'batch': {'image': 'unchanged'}}}
        with patch.object(release, 'ANALYTICS_CONFIG', release.ENGAGEMENT_CONFIG), \
                patch.object(release, 'ANALYTICS_KEYS', tuple(release.ENGAGEMENT_CONFIG)):
            active = release.inject_analytics(original)
            self.assertEqual(original, release.remove_analytics(active))
            after = copy.deepcopy(before)
            after['services']['api']['environment'].update(release.ENGAGEMENT_CONFIG)
            release.validate_render_change(before, after, True)
            release.validate_render_change(after, before, False)
            after['services']['api']['environment']['REVIEWS_ENABLED'] = 'false'
            with self.assertRaises(ValueError):
                release.validate_render_change(before, after, True)

    def test_engagement_requires_migration_tables_and_secret_without_logging_it(self):
        host = object.__new__(release.Host)
        objects = {'api': {'Config': {'Env': ['JWT_SECRET=' + 'x' * 32,
            'SPRING_DB_USERNAME=test', 'SPRING_DB_PASSWORD=synthetic-password']}}}
        with patch.object(host, 'run', return_value='1\n5') as run:
            host.require_engagement_dependencies(objects)
            self.assertNotIn('synthetic-password', str(run.call_args.args))
        for result in ('0\n5', '1\n4', '1\n5\nextra'):
            with patch.object(host, 'run', return_value=result):
                with self.assertRaises(ValueError):
                    host.require_engagement_dependencies(objects)
        objects['api']['Config']['Env'][0] = 'JWT_SECRET=short'
        with self.assertRaises(ValueError):
            host.require_engagement_dependencies(objects)

    def test_bot_transition_preserves_existing_analytics_and_unrelated_settings(self):
        original = release.inject_analytics(COMPOSE)
        before = {'services': {'api': {'environment': {
            **release.ANALYTICS_CONFIG, 'ACCOUNT_LIFECYCLE_MAINTENANCE': 'false',
            'WEB_CACHE_REVALIDATION_ENABLED': 'true'}}, 'batch': {'image': 'unchanged'}}}
        with patch.object(release, 'ANALYTICS_CONFIG', release.BOT_CONFIG), \
                patch.object(release, 'ANALYTICS_KEYS', tuple(release.BOT_CONFIG)):
            active = release.inject_analytics(original)
            self.assertEqual(original, release.remove_analytics(active))
            self.assertIn(b"SERVICE_ANALYTICS_ENABLED: 'true'", active)
            self.assertIn(b"SERVICE_ANALYTICS_RETAIN_BOT_EVENTS: 'true'", active)
            after = copy.deepcopy(before)
            after['services']['api']['environment'].update(release.BOT_CONFIG)
            release.validate_render_change(before, after, True)
            release.validate_render_change(after, before, False)
            after['services']['api']['environment']['WEB_CACHE_REVALIDATION_ENABLED'] = 'false'
            with self.assertRaises(ValueError):
                release.validate_render_change(before, after, True)

    def test_bot_dependencies_require_matching_admin_and_migrated_schema(self):
        import json
        host = object.__new__(release.Host)
        objects = {'api': {'Config': {'Env': ['SERVICE_ANALYTICS_ENABLED=true',
            'SPRING_DB_USERNAME=test', 'SPRING_DB_PASSWORD=not-a-real-secret']}}}
        commit = 'a' * 40
        admin = json.dumps([{'State': {'Running': True}, 'Config': {'Image': 'test/admin:' + commit}}])
        with patch.object(host, 'run', side_effect=[admin, '1']):
            host.require_bot_dependencies(objects, commit)
        with patch.object(host, 'run', side_effect=[admin, '0']):
            with self.assertRaises(ValueError):
                host.require_bot_dependencies(objects, commit)
        with patch.object(host, 'run', return_value=admin):
            with self.assertRaises(ValueError):
                host.require_bot_dependencies(objects, 'b' * 40)

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
