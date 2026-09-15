import pathlib
import sys
import unittest
from copy import deepcopy
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from cache_contract_transition import contract_candidate, parse_dotenv, peer_snapshot, wait_for_healthy

ROOT = pathlib.Path(__file__).resolve().parents[1]


def base(contract=''):
    suffix = f'WEB_CACHE_CONTRACT_VERSION={contract}\n' if contract else ''
    return ("API_PORT=8080\nWEB_CACHE_REVALIDATION_ENABLED=true\n"
            "WEB_CACHE_ORIGIN=https://geupddong.com\nWEB_CACHE_REVALIDATION_SECRET=synthetic\n" + suffix).encode()


class CacheContractTransitionTest(unittest.TestCase):
    def test_adds_explicit_v2_without_changing_other_values(self):
        candidate = contract_candidate(base(), '2')
        values = parse_dotenv(candidate)
        self.assertEqual(values['WEB_CACHE_CONTRACT_VERSION'], '2')
        self.assertEqual(values['API_PORT'], '8080')

    def test_replaces_v2_with_explicit_v1_for_rollback(self):
        candidate = contract_candidate(base('2'), '1')
        self.assertEqual(parse_dotenv(candidate)['WEB_CACHE_CONTRACT_VERSION'], '1')
        self.assertEqual(candidate.count(b'WEB_CACHE_CONTRACT_VERSION='), 1)

    def test_rejects_wrong_target_duplicate_or_unready_destination(self):
        with self.assertRaises(ValueError): contract_candidate(base(), '3')
        with self.assertRaises(ValueError): contract_candidate(base('1') + b'WEB_CACHE_CONTRACT_VERSION=2\n', '2')
        with self.assertRaises(ValueError): contract_candidate(base().replace(b'https://geupddong.com', b'https://preview.geupddong.com'), '2')
        with self.assertRaises(ValueError): contract_candidate(base().replace(b'WEB_CACHE_REVALIDATION_SECRET=synthetic\n', b''), '2')

    def test_peer_snapshot_ignores_health_poll_history_but_detects_real_changes(self):
        source = {
            'Id': 'container', 'Created': 'created', 'Image': 'image', 'Name': '/toilet-batch',
            'Path': 'java', 'Args': ['-jar'], 'Config': {'Env': ['A=B']},
            'HostConfig': {'NetworkMode': 'toilet-network'}, 'Mounts': [{'Source': '/safe'}],
            'RestartCount': 0,
            'State': {'Running': True, 'StartedAt': 'start', 'Health': {'Log': [{'End': 'first'}]}},
        }
        polled = deepcopy(source)
        polled['State']['Health']['Log'] = [{'End': 'later'}]
        self.assertEqual(peer_snapshot(source), peer_snapshot(polled))
        restarted = deepcopy(source)
        restarted['State']['StartedAt'] = 'different'
        self.assertNotEqual(peer_snapshot(source), peer_snapshot(restarted))
        reconfigured = deepcopy(source)
        reconfigured['Config']['Env'] = ['A=C']
        self.assertNotEqual(peer_snapshot(source), peer_snapshot(reconfigured))

    def test_wait_for_healthy_allows_bounded_startup_delay(self):
        with patch('cache_contract_transition.healthy', side_effect=[OSError('starting'), None]) as check, \
                patch('cache_contract_transition.time.monotonic', side_effect=[0, 1]), \
                patch('cache_contract_transition.time.sleep') as sleep:
            wait_for_healthy({'safe': 'object'}, timeout=60, interval=2)
        self.assertEqual(check.call_count, 2)
        sleep.assert_called_once_with(2)

    def test_wait_for_healthy_fails_after_deadline(self):
        with patch('cache_contract_transition.healthy', side_effect=OSError('still starting')), \
                patch('cache_contract_transition.time.monotonic', side_effect=[0, 60]), \
                patch('cache_contract_transition.time.sleep') as sleep:
            with self.assertRaisesRegex(ValueError, 'CACHE_CONTRACT_HEALTH_UNVERIFIED'):
                wait_for_healthy({'safe': 'object'}, timeout=60, interval=2)
        sleep.assert_not_called()

    def test_workflow_is_manual_exact_sha_and_pinned_tunnel_only(self):
        source = (ROOT / '.github/workflows/cache-contract-transition.yml').read_text()
        for text in ('workflow_dispatch:', "github.ref == 'refs/heads/main'",
                     "vars.CACHE_CONTRACT_APPROVED_SHA == github.sha",
                     'APPROVED_CONTRACT_VERSION: ${{ vars.CACHE_CONTRACT_APPROVED_VERSION }}',
                     'test "$RELEASE_APPROVED" = true',
                     "test \"$TUNNEL_SSH_HOST\" = ssh-deploy.geupddong.com",
                     "--operation $OPERATION", "--contract-version $CONTRACT_VERSION",
                     "discover -s scripts -p test_cache_contract_transition.py"):
            self.assertIn(text, source)
        self.assertNotIn('\n  push:', source)
        self.assertNotIn('\n  schedule:', source)
        condition = source.split('runs-on:', 1)[0]
        self.assertNotIn('CACHE_CONTRACT_RELEASE_APPROVED', condition)
        transition = (ROOT / 'scripts/cache_contract_transition.py').read_text()
        self.assertNotIn('secrets.', transition)
        self.assertIn('CACHE_CONTRACT_FAILURE_CONTEXT primaryStage=', transition)
        self.assertIn('rollbackStage=', transition)


if __name__ == '__main__':
    unittest.main()
