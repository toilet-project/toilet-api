import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from cache_contract_transition import contract_candidate, parse_dotenv

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

    def test_workflow_is_manual_exact_sha_and_pinned_tunnel_only(self):
        source = (ROOT / '.github/workflows/cache-contract-transition.yml').read_text()
        for text in ('workflow_dispatch:', "github.ref == 'refs/heads/main'",
                     "vars.CACHE_CONTRACT_APPROVED_SHA == github.sha",
                     "test \"$TUNNEL_SSH_HOST\" = ssh-deploy.geupddong.com",
                     "--operation $OPERATION", "--contract-version $CONTRACT_VERSION",
                     "discover -s scripts -p test_cache_contract_transition.py"):
            self.assertIn(text, source)
        self.assertNotIn('\n  push:', source)
        self.assertNotIn('\n  schedule:', source)
        self.assertNotIn('secrets.', (ROOT / 'scripts/cache_contract_transition.py').read_text())


if __name__ == '__main__':
    unittest.main()
