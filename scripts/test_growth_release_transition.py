import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('growth_release', Path(__file__).with_name('growth_release_transition.py'))
growth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(growth)


def runtime(flag=None):
    env = ['EXISTING_SETTING=preserved']
    if flag is not None:
        env.append('GROWTH_ENABLED=' + flag)
    return {role: {'State': {'Running': True}, 'Image': role + '-image', 'Mounts': [],
                   'Config': {'Image': role + ':' + 'a' * 40, 'User': '1000:1000', 'Env': env[:]}}
            for role in ('api', 'batch')}


class GrowthReleaseTest(unittest.TestCase):
    def test_mount_rejects_duplicate_or_unknown_shape(self):
        source = b'services:\n  api:\n    env_file:\n      - .account-lifecycle.env\n'
        mounted = growth.inject_growth_env(source)
        self.assertEqual(mounted.count(b'.growth.env'), 1)
        self.assertIn(source.splitlines()[-1], mounted)
        for invalid in (mounted, b'services: {}', source + b'GROWTH_ENABLED: true\n'):
            with self.assertRaises(ValueError):
                growth.inject_growth_env(invalid)

    def test_render_allows_only_growth_flag(self):
        before = {'services': {'api': {'image': 'same', 'environment': {'EXISTING_SETTING': 'preserved'}}}}
        after = copy.deepcopy(before)
        after['services']['api']['environment']['GROWTH_ENABLED'] = 'true'
        growth.validate_render_change(before, after, True)
        self.assertNotIn('GROWTH_ENABLED', before['services']['api']['environment'])
        for path, value in (('image', 'unexpected'), ('user', 'root')):
            invalid = copy.deepcopy(after)
            invalid['services']['api'][path] = value
            with self.assertRaises(ValueError):
                growth.validate_render_change(before, invalid, True)
        after['services']['api']['environment']['EXISTING_SETTING'] = 'changed'
        with self.assertRaises(ValueError):
            growth.validate_render_change(before, after, True)

    def test_runtime_rejects_other_env_image_mount_or_batch_change(self):
        before = runtime()
        after = copy.deepcopy(before)
        after['api']['Config']['Env'].append('GROWTH_ENABLED=true')
        commits = {'api': 'a' * 40, 'batch': 'a' * 40}
        growth.validate_runtime(before, after, commits, True)
        mutations = (
            lambda item: item['api']['Config']['Env'].append('ADDED=forbidden'),
            lambda item: item['api'].update(Image='other'),
            lambda item: item['api']['Mounts'].append({'Source': 'other'}),
            lambda item: item['batch'].update(Image='other'),
        )
        for change in mutations:
            invalid = copy.deepcopy(after)
            change(invalid)
            with self.assertRaises(ValueError):
                growth.validate_runtime(before, invalid, commits, True)

    def test_rollback_requires_exact_original_environment(self):
        original = runtime('false')
        growth.validate_rollback(original, copy.deepcopy(original))
        changed = copy.deepcopy(original)
        changed['api']['Config']['Env'][-1] = 'GROWTH_ENABLED=true'
        with self.assertRaises(ValueError):
            growth.validate_rollback(original, changed)

    def test_switch_health_failure_restores_original_setting(self):
        original = runtime('false')
        before = {'objects': original, 'render': {'services': {'api': {'environment': {'GROWTH_ENABLED': 'false'}}}}, 'compose': b'compose'}
        host = Mock()
        host.rendered.return_value = {'services': {'api': {'environment': {'GROWTH_ENABLED': 'true'}}}}
        host.restart.side_effect = [RuntimeError('health failed'), None]
        host.capture.return_value = copy.deepcopy(original)
        with patch.object(growth, 'read_owned', return_value=growth.dotenv(False)), \
             patch.object(growth, 'validate_before'), patch.object(growth, 'backup'), \
             patch.object(growth, 'atomic_replace') as replace, \
             patch.object(growth, 'GROWTH_ENV') as path:
            path.exists.return_value = True
            path.read_bytes.return_value = growth.dotenv(True)
            with self.assertRaisesRegex(RuntimeError, 'RECHECK_REQUIRED'):
                growth.apply_switch(host, before, {'api': 'a' * 40, 'batch': 'a' * 40}, True)
            self.assertEqual(host.restart.call_count, 2)
            self.assertEqual([call.args[1] for call in replace.call_args_list], [growth.dotenv(True), growth.dotenv(False)])


if __name__ == '__main__':
    unittest.main()
