import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('review_release', Path(__file__).with_name('review_release_transition.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
STORE = 'bc125d65-18a9-4106-9bbf-998ac3bc5392'


class HealthResponseCompatibilityTest(unittest.TestCase):
    def test_actual_health_gate_rejects_http_failure(self):
        host = object.__new__(release.Host)
        host.capture = Mock(return_value={'api': {
            'Config': {'Env': ['API_PORT=8080']},
            'NetworkSettings': {'Networks': {'toilet-network': {'IPAddress': '172.22.0.2'}}},
        }})
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"status":"UP"}'
        with patch.object(release.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            host.healthy()
            response.status = 503
            with self.assertRaises(ValueError): host.healthy()
            response.status = 200
            response.read.return_value = b'{"status":"DOWN"}'
            with self.assertRaises(ValueError): host.healthy()

    def test_both_success_formats(self):
        for body in ('API server is running (DB: toilet_db)', '{"status":"UP"}', '{ "status": "UP" }'):
            with self.subTest(body=body):
                self.assertTrue(release.api_health_ok(body))

    def test_failures_and_unexpected_payloads(self):
        for body in ('API server is running (DB: other)', 'API database connection failed: synthetic',
                     '{"status":"DOWN"}', '{"status":"UP","detail":"unexpected"}', 'null', '[]', '{}', 'OK'):
            with self.subTest(body=body):
                self.assertFalse(release.api_health_ok(body))


class ReviewReleaseTransitionTest(unittest.TestCase):
    def compose(self):
        return b'''services:\n  api:\n    volumes:\n      - type: bind\n        source: /home/luha/geupddong-maintenance\n        target: /home/luha/geupddong-maintenance\n        read_only: false\n        bind:\n          create_host_path: false\n    env_file:\n      - .env\n      - .account-lifecycle.env\n'''

    def test_injection_adds_only_review_mount_and_env_file(self):
        result = release.inject_review_mount(self.compose()).decode()
        self.assertEqual(result.count(release.REVIEW_DIRECTORY), 2)
        self.assertEqual(result.count('.review.env'), 1)
        with self.assertRaisesRegex(ValueError, 'ALREADY_MOUNTED'):
            release.inject_review_mount(result.encode())

    def test_review_dotenv_is_closed_by_default_and_exact(self):
        disabled = release.review_values(STORE, False)
        self.assertEqual(set(disabled), set(release.REVIEW_KEYS))
        self.assertTrue(all(disabled[key] == 'false' for key in (
            'REVIEWS_ENABLED', 'REVIEW_UNLINK_ENABLED', 'REVIEW_UNLINK_LOCAL_VERIFIED',
            'REVIEW_GUARD_CLEANUP_ENABLED')))
        self.assertNotIn('true', release.dotenv(disabled).decode())
        for bad in ('unknown', STORE + "'", ''):
            with self.assertRaises(ValueError):
                release.review_values(bad, False)

    def test_render_validation_rejects_every_non_review_change(self):
        before = {'services': {'api': {'image': 'synthetic', 'environment': {'ACCOUNT_ERASURE_ENABLED': 'true'},
                                       'volumes': [{'type': 'bind', 'source': '/existing', 'target': '/existing'}]},
                               'redis': {'image': 'redis'}}}
        expected = release.review_values(STORE, False)
        after = copy.deepcopy(before)
        after['services']['api']['environment'].update(expected)
        after['services']['api']['volumes'].append({'type': 'bind', 'source': release.REVIEW_DIRECTORY,
                                                     'target': release.REVIEW_DIRECTORY,
                                                     'bind': {'create_host_path': False}})
        release.validate_render_change(before, after, expected)
        for changed in ('image', 'redis'):
            bad = copy.deepcopy(after)
            if changed == 'image': bad['services']['api']['image'] = 'other'
            else: bad['services']['redis']['image'] = 'other'
            with self.assertRaises(ValueError): release.validate_render_change(before, bad, expected)

    def test_workflow_is_manual_pinned_and_normal_deploy_has_exact_sha_gate(self):
        root = Path(__file__).parents[1]
        workflow = (root / '.github/workflows/review-preserving-transition.yml').read_text()
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertNotRegex(workflow, r'(?m)^  (push|pull_request|schedule):')
        for value in ("github.ref == 'refs/heads/main'", 'vars.REVIEW_RELEASE_APPROVED_SHA == github.sha',
                      'vars.REVIEW_RELEASE_API_COMMIT', 'vars.REVIEW_RELEASE_BATCH_COMMIT',
                      '--deployment-freeze-confirmed', 'StrictHostKeyChecking=yes'):
            self.assertIn(value, workflow)
        deploy = (root / '.github/workflows/deploy.yml').read_text()
        self.assertIn("vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA == github.sha", deploy)


if __name__ == '__main__':
    unittest.main()
