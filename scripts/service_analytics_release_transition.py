#!/usr/bin/env python3
"""Enable first-party analytics without changing the active account lifecycle state."""

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
ANALYTICS_CONFIG = {
    'SERVICE_ANALYTICS_ENABLED': 'true',
    'SERVICE_ANALYTICS_DAILY_CRON': '0 30 2 * * *',
}
ANALYTICS_KEYS = tuple(ANALYTICS_CONFIG)
ACCOUNT_ACTIVE = {
    'ACCOUNT_LIFECYCLE_MAINTENANCE': 'false',
    'ACCOUNT_RETENTION_ENABLED': 'true',
    'ACCOUNT_ERASURE_ENABLED': 'true',
    'ERASURE_LEDGER_ENABLED': 'true',
    'ERASURE_LEDGER_CATALOGUE_ENABLED': 'true',
    'ERASURE_CHECKPOINT_ENABLED': 'true',
    'ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED': 'true',
    'ERASURE_RETIREMENT_WRITE_ENABLED': 'false',
    'ERASURE_HISTORY_COMPATIBILITY_VERIFIED': 'false',
}


def require(value, code='SERVICE_ANALYTICS_RELEASE_HELD'):
    if not value:
        raise ValueError(code)


def api_health_ok(body):
    if body == 'API server is running (DB: toilet_db)':
        return True
    try:
        return json.loads(body) == {'status': 'UP'}
    except (TypeError, ValueError):
        return False


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


def inject_analytics(content):
    text = content.decode('utf-8')
    require(not any(key + ':' in text for key in ANALYTICS_KEYS),
            'SERVICE_ANALYTICS_RELEASE_ALREADY_CONFIGURED')
    anchor = "      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n"
    require(text.count(anchor) == 1, 'SERVICE_ANALYTICS_RELEASE_COMPOSE_SHAPE_REJECTED')
    block = ''.join("      " + key + ": '" + value + "'\n"
                    for key, value in ANALYTICS_CONFIG.items())
    return text.replace(anchor, anchor + block).encode('utf-8')


def remove_analytics(content):
    text = content.decode('utf-8')
    block = ''.join("      " + key + ": '" + value + "'\n"
                    for key, value in ANALYTICS_CONFIG.items())
    require(text.count(block) == 1, 'SERVICE_ANALYTICS_RELEASE_PHASE_REJECTED')
    return text.replace(block, '').encode('utf-8')


def validate_render_change(before, after, active):
    expected = copy.deepcopy(before)
    env = expected['services']['api'].setdefault('environment', {})
    if active:
        env.update(ANALYTICS_CONFIG)
    else:
        for key in ANALYTICS_KEYS:
            require(key in env, 'SERVICE_ANALYTICS_RELEASE_PHASE_REJECTED')
            del env[key]
    require(after == expected, 'SERVICE_ANALYTICS_RELEASE_NON_ANALYTICS_CHANGE_REJECTED')


def read_owned(path, private=False):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 1000 and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 if private
            else info.st_gid == 1000 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()


def atomic_replace(path, content):
    descriptor, temporary = tempfile.mkstemp(prefix='.service-analytics-release-', dir=path.parent)
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


def backup(path, content):
    target = path.with_name('.service-analytics-before-' + str(time.time_ns()) + '-compose.yaml')
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    return target


class Host:
    def __init__(self):
        require(ROOT.resolve(strict=True) == ROOT)
        info = ROOT.stat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 1000 and info.st_gid == 1000
                and not (info.st_mode & 0o002))
        spec = importlib.util.spec_from_file_location(
            'service_analytics_ledger_context', '/home/luha/.local/bin/restore-ledger-context.py')
        self.context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.context)

    def run(self, args, *, input=None, timeout=30):
        return subprocess.run(args, input=input, text=True, capture_output=True,
                              timeout=timeout, check=True).stdout.strip()

    def compose(self, *args, file=COMPOSE):
        return ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(file), *args]

    def capture(self):
        return {role: normalize_inspection(json.loads(
            self.run(['docker', 'inspect', 'toilet-' + role]))[0]) for role in ('api', 'batch')}

    def rendered(self, content=None):
        if content is None:
            return json.loads(self.run(self.compose('config', '--format', 'json')))
        return json.loads(self.run(self.compose('config', '--format', 'json', file='-'),
                                   input=content.decode('utf-8')))

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
                'SERVICE_ANALYTICS_RELEASE_HEALTH_REJECTED')

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
                    raise ValueError('SERVICE_ANALYTICS_RELEASE_HEALTH_REJECTED') from None
                time.sleep(2)


def analytics_state(obj):
    env = environment(obj)
    present = {key for key in ANALYTICS_KEYS if key in env}
    if not present:
        return 'inactive'
    require(present == set(ANALYTICS_KEYS), 'SERVICE_ANALYTICS_RELEASE_PARTIAL_ENV_REJECTED')
    require(all(env.get(key) == value for key, value in ANALYTICS_CONFIG.items()),
            'SERVICE_ANALYTICS_RELEASE_UNKNOWN_PHASE')
    return 'active'


def validate_runtime(objects, commits, state):
    for role in ('api', 'batch'):
        obj = objects[role]
        require(obj['State']['Running'] and obj['Config']['User'] == '1000:1000')
        require(obj['Config']['Image'].endswith(':' + commits[role]))
        env = environment(obj)
        require(all(env.get(key) == value for key, value in ACCOUNT_ACTIVE.items()),
                'SERVICE_ANALYTICS_RELEASE_ACCOUNT_STATE_REJECTED')
    require(analytics_state(objects['api']) == state)
    require(not any(key in environment(objects['batch']) for key in ANALYTICS_KEYS),
            'SERVICE_ANALYTICS_RELEASE_BATCH_CHANGED')


def apply_compose(host, snapshots, commits, replacement, target_state):
    original = snapshots['compose']
    backup(COMPOSE, original)
    try:
        atomic_replace(COMPOSE, replacement)
        validate_render_change(snapshots['render'], host.rendered(), target_state == 'active')
        host.restart()
        current = host.capture()
        validate_runtime(current, commits, target_state)
        require(current['batch'] == snapshots['objects']['batch'])
        require(read_owned(ROOT / '.env', True) == snapshots['base_env'])
        require(read_owned(ROOT / '.account-lifecycle.env', True) == snapshots['account_env'])
    except Exception:
        require(COMPOSE.exists() and COMPOSE.read_bytes() in (original, replacement),
                'SERVICE_ANALYTICS_RELEASE_EXTERNAL_CHANGE_REQUIRES_REVIEW')
        if COMPOSE.read_bytes() == replacement:
            atomic_replace(COMPOSE, original)
            host.restart()
        raise RuntimeError('SERVICE_ANALYTICS_RELEASE_FAILED_CONFIG_RESTORED_RECHECK_REQUIRED') from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'activate', 'deactivate'), required=True)
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(all(re.fullmatch(r'[a-f0-9]{40}', value or '')
                for value in (args.api_commit, args.batch_commit)))
    read_only = args.operation == 'check'
    require(read_only == (not args.apply_approved))
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    host = Host()
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    with host.context.maintenance_lease.acquire():
        objects = host.capture()
        state = analytics_state(objects['api'])
        validate_runtime(objects, commits, state)
        snapshots = {
            'objects': objects,
            'render': host.rendered(),
            'compose': read_owned(COMPOSE),
            'base_env': read_owned(ROOT / '.env', True),
            'account_env': read_owned(ROOT / '.account-lifecycle.env', True),
        }
        if args.operation == 'check':
            candidate = inject_analytics(snapshots['compose']) if state == 'inactive' \
                else remove_analytics(snapshots['compose'])
            validate_render_change(snapshots['render'], host.rendered(candidate), state == 'inactive')
        else:
            active = args.operation == 'activate'
            require(state == ('inactive' if active else 'active'),
                    'SERVICE_ANALYTICS_RELEASE_PHASE_REJECTED')
            replacement = inject_analytics(snapshots['compose']) if active \
                else remove_analytics(snapshots['compose'])
            validate_render_change(snapshots['render'], host.rendered(replacement), active)
            apply_compose(host, snapshots, commits, replacement, 'active' if active else 'inactive')
            state = 'active' if active else 'inactive'
    print(json.dumps({
        'outcome': 'SERVICE_ANALYTICS_RELEASE_' + ('CHECKED' if read_only else 'APPLIED'),
        'serviceAnalyticsState': state,
        'accountStatePreserved': True,
        'directDatabaseWrites': False,
        'operation': args.operation,
    }))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if str(error).startswith('SERVICE_ANALYTICS_') \
            and re.fullmatch(r'[A-Z_]+', str(error)) else 'SERVICE_ANALYTICS_RELEASE_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
