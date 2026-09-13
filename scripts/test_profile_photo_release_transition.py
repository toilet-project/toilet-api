import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'profile_photo_release', Path(__file__).with_name('profile_photo_release_transition.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ProfilePhotoReleaseTransitionTest(unittest.TestCase):
    def compose(self):
        return b'''services:\n  api:\n    environment:\n      ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'\n      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n    env_file:\n      - .env\n      - .account-lifecycle.env\n      - .review.env\n'''

    def storage(self):
        return {
            'PROFILE_PHOTO_R2_ENDPOINT': 'https://' + 'a' * 32 + '.us.r2.cloudflarestorage.com',
            'PROFILE_PHOTO_R2_BUCKET': 'geupddong-profile-photos-us',
            'PROFILE_PHOTO_R2_ACCESS_KEY_ID': 'synthetic-access',
            'PROFILE_PHOTO_R2_SECRET_ACCESS_KEY': 'synthetic-secret',
        }

    def test_mount_injects_only_profile_flags_and_env_file(self):
        result = release.inject_profile(self.compose())
        text = result.decode()
        self.assertEqual(text.count(release.PROFILE_ENV_TEXT), 1)
        self.assertEqual(text.count('PROFILE_PHOTO_ENABLED:'), 1)
        self.assertEqual(text.count('KAKAO_LOGIN_SCOPES:'), 1)
        self.assertIn("PROFILE_PHOTO_ENABLED: 'false'", text)
        self.assertLess(text.index('.account-lifecycle.env'), text.index(release.PROFILE_ENV_TEXT))
        self.assertLess(text.index(release.PROFILE_ENV_TEXT), text.index('.review.env'))
        with self.assertRaisesRegex(ValueError, 'ALREADY_MOUNTED'):
            release.inject_profile(result)

    def test_switch_is_exact_and_reversible(self):
        disabled = release.inject_profile(self.compose())
        active = release.switch_profile(disabled, True)
        self.assertIn("PROFILE_PHOTO_ENABLED: 'true'", active.decode())
        self.assertIn('profile_nickname,account_email,profile_image', active.decode())
        self.assertEqual(release.switch_profile(active, False), disabled)
        with self.assertRaisesRegex(ValueError, 'PHASE_REJECTED'):
            release.switch_profile(active, True)

    def test_storage_is_exact_us_bucket_only(self):
        content = ''.join(key + '=' + value + '\n' for key, value in self.storage().items()).encode()
        self.assertEqual(release.parse_storage(content), self.storage())
        for changed in (
            content + b'EXTRA=value\n',
            content.replace(b'.us.r2.', b'.r2.'),
            content.replace(b'geupddong-profile-photos-us', b'other'),
        ):
            with self.assertRaises(ValueError):
                release.parse_storage(changed)

    def test_render_rejects_non_profile_change(self):
        before = {'services': {'api': {'image': 'api', 'environment': {'ACCOUNT_ERASURE_ENABLED': 'true'}},
                               'redis': {'image': 'redis'}}}
        after = copy.deepcopy(before)
        after['services']['api']['environment'].update(self.storage())
        after['services']['api']['environment'].update(release.feature_values(False))
        release.validate_render_change(before, after, self.storage(), False)
        changed = copy.deepcopy(after)
        changed['services']['redis']['image'] = 'other'
        with self.assertRaisesRegex(ValueError, 'NON_PROFILE_CHANGE'):
            release.validate_render_change(before, changed, self.storage(), False)

    def test_workflow_is_manual_and_pinned(self):
        root = Path(__file__).parents[1]
        workflow = (root / '.github/workflows/profile-photo-preserving-transition.yml').read_text()
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertNotRegex(workflow, r'(?m)^  (push|pull_request|schedule):')
        for value in ("github.ref == 'refs/heads/main'", 'vars.PROFILE_PHOTO_RELEASE_APPROVED_SHA == github.sha',
                      'vars.PROFILE_PHOTO_RELEASE_API_COMMIT', 'vars.PROFILE_PHOTO_RELEASE_BATCH_COMMIT',
                      '--deployment-freeze-confirmed', 'StrictHostKeyChecking=yes', '--apply-synthetic'):
            self.assertIn(value, workflow)


if __name__ == '__main__':
    unittest.main()
