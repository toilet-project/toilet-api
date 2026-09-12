#!/usr/bin/env python3
"""Mount or switch the production review feature without changing account state."""

import argparse
import copy
import importlib.util
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path('/home/luha/toilet-api')
COMPOSE = ROOT / 'compose.yaml'
REVIEW_ENV = ROOT / '.review.env'
REVIEW_DIRECTORY = '/home/luha/geupddong-review-unlink-ledger'
ACCOUNT_DIRECTORY = '/home/luha/geupddong-erasure-ledger'
TOOL_DIRECTORY = Path('/home/luha/erasure-tools/review-unlink-candidate-ebdcba1')
REVIEW_KEYS = (
    'REVIEWS_ENABLED', 'REVIEW_UNLINK_ENABLED', 'REVIEW_UNLINK_LOCAL_VERIFIED',
    'REVIEW_UNLINK_DIRECTORY', 'REVIEW_UNLINK_STORE_ID', 'REVIEW_GUARD_CLEANUP_ENABLED',
)
ACCOUNT_ACTIVE = {
    'ACCOUNT_LIFECYCLE_MAINTENANCE': 'false',
    'ACCOUNT_RETENTION_ENABLED': 'true',
    'ACCOUNT_ERASURE_ENABLED': 'true',
    'ERASURE_LEDGER_ENABLED': 'true',
    'ERASURE_LEDGER_CATALOGUE_ENABLED': 'true',
    'ERASURE_CHECKPOINT_ENABLED': 'true',
}


def api_health_ok(body):
    # Keep the previous exact response during rollout and image rollback.
    if body == 'API server is running (DB: toilet_db)':
        return True
    try:
        return json.loads(body) == {'status': 'UP'}
    except (ValueError, TypeError):
        return False



def require(value, code='REVIEW_RELEASE_HELD'):
    if not value:
        raise ValueError(code)


def environment(obj):
    values = {}
    for item in obj['Config']['Env']:
        key, value = item.split('=', 1)
        require(key not in values)
        values[key] = value
    return values


def normalize_inspection(obj):
    require(isinstance(obj.get('Mounts'), list))
    result = copy.deepcopy(obj)
    result['Mounts'] = sorted(result['Mounts'], key=lambda item: json.dumps(item, sort_keys=True))
    return result


def review_values(store_id, active):
    require(re.fullmatch(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}', store_id or ''))
    enabled = 'true' if active else 'false'
    return {
        'REVIEWS_ENABLED': enabled,
        'REVIEW_UNLINK_ENABLED': enabled,
        'REVIEW_UNLINK_LOCAL_VERIFIED': enabled,
        'REVIEW_UNLINK_DIRECTORY': REVIEW_DIRECTORY,
        'REVIEW_UNLINK_STORE_ID': store_id,
        'REVIEW_GUARD_CLEANUP_ENABLED': enabled,
    }


def dotenv(values):
    require(set(values) == set(REVIEW_KEYS))
    require(all(isinstance(value, str) and not re.search(r"[\r\n\0']", value) for value in values.values()))
    return ''.join(key + "='" + values[key] + "'\n" for key in REVIEW_KEYS).encode()


def inject_review_mount(content):
    text = content.decode('utf-8')
    require(REVIEW_DIRECTORY not in text and '.review.env' not in text, 'REVIEW_RELEASE_ALREADY_MOUNTED')
    volume_anchor = """      - type: bind
        source: /home/luha/geupddong-maintenance
        target: /home/luha/geupddong-maintenance
        read_only: false
        bind:
          create_host_path: false
"""
    volume = """      - type: bind
        source: /home/luha/geupddong-review-unlink-ledger
        target: /home/luha/geupddong-review-unlink-ledger
        read_only: false
        bind:
          create_host_path: false
"""
    env_anchor = """    env_file:
      - .env
      - .account-lifecycle.env
"""
    require(text.count(volume_anchor) == 1 and text.count(env_anchor) == 1,
            'REVIEW_RELEASE_COMPOSE_SHAPE_REJECTED')
    text = text.replace(volume_anchor, volume_anchor + volume)
    text = text.replace(env_anchor, env_anchor + '      - .review.env\n')
    return text.encode('utf-8')


def strip_review_render(rendered):
    result = copy.deepcopy(rendered)
    api = result['services']['api']
    env = api.get('environment', {})
    values = {key: env.pop(key, None) for key in REVIEW_KEYS}
    volumes = api.get('volumes', [])
    matches = [item for item in volumes if item.get('source') == REVIEW_DIRECTORY]
    require(len(matches) == 1, 'REVIEW_RELEASE_MOUNT_REJECTED')
    mount = matches[0]
    require(mount.get('type') == 'bind' and mount.get('target') == REVIEW_DIRECTORY)
    require(mount.get('read_only', False) is False)
    bind = mount.get('bind', {})
    require(bind.get('create_host_path', False) is False)
    api['volumes'] = [item for item in volumes if item is not mount]
    return result, values


def validate_render_change(before, after, expected):
    stripped, actual = strip_review_render(after)
    require(stripped == before, 'REVIEW_RELEASE_NON_REVIEW_CHANGE_REJECTED')
    require(actual == expected, 'REVIEW_RELEASE_ENV_REJECTED')


def read_owned(path, private=False):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 1000 and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 if private else info.st_gid == 1000 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()


def atomic_replace(path, content):
    descriptor, temporary = tempfile.mkstemp(prefix='.review-release-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Host:
    def __init__(self):
        require(ROOT.resolve(strict=True) == ROOT)
        root_info = ROOT.stat()
        require(stat.S_ISDIR(root_info.st_mode) and root_info.st_uid == 1000 and root_info.st_gid == 1000)
        spec = importlib.util.spec_from_file_location('ledger_context', '/home/luha/.local/bin/restore-ledger-context.py')
        self.context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.context)

    def run(self, args, *, input=None, env=None, timeout=30):
        return subprocess.run(args, input=input, env=env, text=True, capture_output=True,
                              timeout=timeout, check=True).stdout.strip()

    def compose(self, *args):
        return ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(COMPOSE), *args]

    def capture(self):
        return {role: normalize_inspection(json.loads(self.run(['docker', 'inspect', 'toilet-' + role]))[0])
                for role in ('api', 'batch')}

    def rendered(self):
        return json.loads(self.run(self.compose('config', '--format', 'json')))

    def healthy(self):
        obj = self.capture()['api']
        env = environment(obj)
        port = env.get('API_PORT', '')
        require(port.isascii() and port.isdigit() and 1 <= int(port) <= 65535)
        address = obj['NetworkSettings']['Networks']['toilet-network']['IPAddress']
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://' + address + ':' + port + '/api/health', timeout=4) as response:
            body = response.read(8192).decode()
        require(response.status == 200 and api_health_ok(body),
                'REVIEW_RELEASE_HEALTH_REJECTED')

    def restart(self):
        self.run(self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                              '--wait', '--wait-timeout', '90', 'api'), timeout=110)
        deadline = time.monotonic() + 60
        while True:
            try:
                self.healthy()
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise ValueError('REVIEW_RELEASE_HEALTH_REJECTED') from None
                time.sleep(2)

    def preflight(self, expected_records):
        require(0 <= expected_records <= 100000)
        runtime = environment(self.capture()['api'])
        keys = ('ERASURE_CHECKPOINT_DATABASE_EPOCH', 'ERASURE_LEDGER_PROVIDER',
                'ERASURE_LEDGER_LOCAL_DIRECTORY', 'ERASURE_LEDGER_ACTIVE_KEY_ID',
                'ERASURE_LEDGER_KEYS_JSON', 'ERASURE_CHECKPOINT_GITHUB_TOKEN',
                'REVIEW_UNLINK_DIRECTORY', 'REVIEW_UNLINK_STORE_ID')
        require(all(runtime.get(key) for key in keys), 'REVIEW_RELEASE_PREFLIGHT_CONFIG_MISSING')
        env = {key: runtime[key] for key in keys}
        env.update(PATH=os.environ.get('PATH', ''), REVIEW_UNLINK_PREFLIGHT_READONLY='approved',
                   REVIEW_UNLINK_EXPECTED_OBJECTS=str(expected_records))
        output = self.run(['java', '-cp', str(TOOL_DIRECTORY / 'lib' / '*'),
                           'com.example.toiletbatch.account.ReviewUnlinkLedgerPreflightCli', '--read-only'],
                          env=env, timeout=60)
        require(output == 'REVIEW_AUTHOR_UNLINK_PREFLIGHT_PASS records=' + str(expected_records) +
                ' checkpointMatched=true localStoreVerified=true activationAllowed=false',
                'REVIEW_RELEASE_PREFLIGHT_REJECTED')


def validate_runtime(objects, commits, store_id, expected_state):
    for role in ('api', 'batch'):
        obj = objects[role]
        require(obj['State']['Running'] and obj['Config']['User'] == '1000:1000')
        require(obj['Config']['Image'].endswith(':' + commits[role]))
        env = environment(obj)
        require(all(env.get(key) == value for key, value in ACCOUNT_ACTIVE.items()),
                'REVIEW_RELEASE_ACCOUNT_STATE_REJECTED')
    api = objects['api']
    api_env = environment(api)
    review_mounts = [item for item in api['Mounts'] if item.get('Source') == REVIEW_DIRECTORY]
    if expected_state == 'unmounted':
        require(not review_mounts and not any(key in api_env for key in REVIEW_KEYS))
        return
    require(len(review_mounts) == 1 and review_mounts[0].get('Destination') == REVIEW_DIRECTORY
            and review_mounts[0].get('RW') is True)
    require({key: api_env.get(key) for key in REVIEW_KEYS} == review_values(store_id, expected_state == 'active'))


def backup(path, content):
    target = path.with_name('.review-release-before-' + str(time.time_ns()) + '-' + path.name.lstrip('.'))
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    return target


def apply_mount(host, snapshots, commits, store_id):
    original_compose = read_owned(COMPOSE)
    require(not REVIEW_ENV.exists())
    candidate_compose = inject_review_mount(original_compose)
    candidate_env = dotenv(review_values(store_id, False))
    backup(COMPOSE, original_compose)
    try:
        atomic_replace(REVIEW_ENV, candidate_env)
        atomic_replace(COMPOSE, candidate_compose)
        validate_render_change(snapshots['render'], host.rendered(), review_values(store_id, False))
        host.restart()
        current = host.capture()
        validate_runtime(current, commits, store_id, 'disabled')
        require(current['batch'] == snapshots['objects']['batch'])
        require(read_owned(ROOT / '.env', True) == snapshots['base_env'])
        require(read_owned(ROOT / '.account-lifecycle.env', True) == snapshots['account_env'])
    except Exception:
        compose_changed = COMPOSE.exists() and COMPOSE.read_bytes() == candidate_compose
        env_created = REVIEW_ENV.exists() and REVIEW_ENV.read_bytes() == candidate_env
        compose_known = COMPOSE.exists() and COMPOSE.read_bytes() in (original_compose, candidate_compose)
        require(compose_known and (not REVIEW_ENV.exists() or env_created),
                'REVIEW_RELEASE_EXTERNAL_CHANGE_REQUIRES_REVIEW')
        if compose_changed:
            atomic_replace(COMPOSE, original_compose)
        if env_created:
            REVIEW_ENV.unlink()
        if compose_changed:
            host.restart()
        raise RuntimeError('REVIEW_RELEASE_MOUNT_FAILED_CONFIG_RESTORED_RECHECK_REQUIRED') from None


def apply_switch(host, snapshots, commits, store_id, active, expected_records):
    original = read_owned(REVIEW_ENV, True)
    expected_before = dotenv(review_values(store_id, not active))
    require(original == expected_before, 'REVIEW_RELEASE_PHASE_REJECTED')
    if active:
        host.preflight(expected_records)
    replacement = dotenv(review_values(store_id, active))
    backup(REVIEW_ENV, original)
    try:
        atomic_replace(REVIEW_ENV, replacement)
        host.restart()
        current = host.capture()
        validate_runtime(current, commits, store_id, 'active' if active else 'disabled')
        require(current['batch'] == snapshots['objects']['batch'])
        require(read_owned(COMPOSE) == snapshots['compose'])
        require(read_owned(ROOT / '.env', True) == snapshots['base_env'])
        require(read_owned(ROOT / '.account-lifecycle.env', True) == snapshots['account_env'])
    except Exception:
        if REVIEW_ENV.exists() and REVIEW_ENV.read_bytes() == replacement:
            atomic_replace(REVIEW_ENV, original)
            host.restart()
        raise RuntimeError('REVIEW_RELEASE_SWITCH_FAILED_CONFIG_RESTORED_RECHECK_REQUIRED') from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'mount-disabled', 'activate', 'deactivate'), required=True)
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--store-id', required=True)
    parser.add_argument('--expected-records', type=int, default=0)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(all(re.fullmatch(r'[a-f0-9]{40}', value or '') for value in (args.api_commit, args.batch_commit)))
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    require((args.operation == 'check') == (not args.apply_approved))
    host = Host()
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    with host.context.maintenance_lease.acquire():
        objects = host.capture()
        state = 'unmounted' if not REVIEW_ENV.exists() else ('active' if environment(objects['api']).get('REVIEWS_ENABLED') == 'true' else 'disabled')
        validate_runtime(objects, commits, args.store_id, state)
        snapshots = {
            'objects': objects, 'render': host.rendered(), 'compose': read_owned(COMPOSE),
            'base_env': read_owned(ROOT / '.env', True),
            'account_env': read_owned(ROOT / '.account-lifecycle.env', True),
        }
        if args.operation == 'mount-disabled':
            require(state == 'unmounted')
            apply_mount(host, snapshots, commits, args.store_id)
            state = 'disabled'
        elif args.operation == 'activate':
            require(state == 'disabled')
            apply_switch(host, snapshots, commits, args.store_id, True, args.expected_records)
            state = 'active'
        elif args.operation == 'deactivate':
            require(state == 'active')
            apply_switch(host, snapshots, commits, args.store_id, False, args.expected_records)
            state = 'disabled'
    print(json.dumps({'outcome': 'REVIEW_RELEASE_' + ('CHECKED' if args.operation == 'check' else 'APPLIED'),
                      'reviewState': state, 'accountConfigurationChanged': False,
                      'directDatabaseWrites': False, 'operation': args.operation}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if str(error).startswith('REVIEW_RELEASE_') and re.fullmatch(r'[A-Z_]+', str(error)) else 'REVIEW_RELEASE_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
