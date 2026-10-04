#!/usr/bin/env python3
"""Preserve the running API/batch configuration while switching only GROWTH_ENABLED.

Runs on the production host from a reviewed, commit-pinned script. Check is read-only; writes
require an explicit operation and deployment freeze. Never prints Compose or secrets.
"""

import argparse
import copy
import importlib.util
import ipaddress
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
GROWTH_ENV = ROOT / '.growth.env'


def require(condition, code='GROWTH_RELEASE_HELD'):
    if not condition:
        raise ValueError(code)


def values(active):
    return {'GROWTH_ENABLED': 'true' if active else 'false'}


def dotenv(active):
    return ("GROWTH_ENABLED='" + values(active)['GROWTH_ENABLED'] + "'\n").encode()


def environment(obj):
    result = {}
    for line in obj['Config']['Env']:
        key, value = line.split('=', 1)
        require(key not in result)
        result[key] = value
    return result


def inject_growth_env(content):
    text = content.decode('utf-8')
    require('.growth.env' not in text and 'GROWTH_ENABLED:' not in text,
            'GROWTH_RELEASE_ALREADY_MOUNTED')
    anchor = '      - .account-lifecycle.env\n'
    require(text.count(anchor) == 1, 'GROWTH_RELEASE_COMPOSE_SHAPE_REJECTED')
    return text.replace(anchor, anchor + '      - .growth.env\n').encode()


def strip_growth(rendered):
    result = copy.deepcopy(rendered)
    env = result['services']['api'].get('environment', {})
    actual = env.pop('GROWTH_ENABLED', None)
    return result, actual


def validate_render_change(before, after, active):
    stripped, actual = strip_growth(after)
    require(stripped == before, 'GROWTH_RELEASE_NON_GROWTH_CHANGE_REJECTED')
    require(actual == values(active)['GROWTH_ENABLED'], 'GROWTH_RELEASE_ENV_REJECTED')


def read_owned(path, private=False):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 1000 and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 if private else
            info.st_gid == 1000 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()


def atomic_replace(path, content):
    descriptor, temporary = tempfile.mkstemp(prefix='.growth-release-', dir=path.parent)
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
    target = path.with_name('.growth-release-before-' + str(time.time_ns()) + '-' + path.name.lstrip('.'))
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    return target


def normalized(obj):
    result = copy.deepcopy(obj)
    result['Mounts'] = sorted(result['Mounts'], key=lambda item: json.dumps(item, sort_keys=True))
    return result


class Host:
    def __init__(self):
        require(ROOT.resolve(strict=True) == ROOT)
        info = ROOT.stat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 1000 and info.st_gid == 1000)
        spec = importlib.util.spec_from_file_location('ledger_context',
                                                     '/home/luha/.local/bin/restore-ledger-context.py')
        self.context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.context)

    def run(self, args, timeout=30):
        return subprocess.run(args, text=True, capture_output=True, timeout=timeout,
                              check=True).stdout.strip()

    def compose(self, *args):
        return ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(COMPOSE), *args]

    def capture(self):
        return {role: normalized(json.loads(self.run(['docker', 'inspect', 'toilet-' + role]))[0])
                for role in ('api', 'batch')}

    def rendered(self):
        return json.loads(self.run(self.compose('config', '--format', 'json')))

    def healthy(self):
        api = self.capture()['api']
        env = environment(api)
        port = env.get('API_PORT', '')
        require(port.isascii() and port.isdigit() and 1 <= int(port) <= 65535)
        address = api['NetworkSettings']['Networks']['toilet-network']['IPAddress']
        require(ipaddress.ip_address(address).is_private)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open('http://' + address + ':' + port + '/api/health', timeout=4) as response:
            body = response.read(8192).decode()
        require(response.status == 200 and (body == 'API server is running (DB: toilet_db)'
                or json.loads(body) == {'status': 'UP'}), 'GROWTH_RELEASE_HEALTH_REJECTED')

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
                    raise ValueError('GROWTH_RELEASE_HEALTH_REJECTED') from None
                time.sleep(2)


def state(objects):
    flag = environment(objects['api']).get('GROWTH_ENABLED')
    if not GROWTH_ENV.exists():
        require(flag is None, 'GROWTH_RELEASE_UNMOUNTED_FLAG_REJECTED')
        return 'unmounted'
    require(flag in ('true', 'false'), 'GROWTH_RELEASE_RUNTIME_FLAG_REJECTED')
    return 'active' if flag == 'true' else 'disabled'


def validate_runtime(before, after, commits, active):
    for role in ('api', 'batch'):
        require(after[role]['State']['Running'] and after[role]['Config']['User'] == '1000:1000')
        require(after[role]['Config']['Image'].endswith(':' + commits[role]))
    require(after['batch'] == before['batch'], 'GROWTH_RELEASE_BATCH_CHANGED')
    require(after['api']['Image'] == before['api']['Image'], 'GROWTH_RELEASE_IMAGE_CHANGED')
    require(after['api']['Mounts'] == before['api']['Mounts'], 'GROWTH_RELEASE_MOUNT_CHANGED')
    original = environment(before['api'])
    current = environment(after['api'])
    original.pop('GROWTH_ENABLED', None)
    flag = current.pop('GROWTH_ENABLED', None)
    require(original == current and flag == values(active)['GROWTH_ENABLED'],
            'GROWTH_RELEASE_OTHER_ENV_CHANGED')


def apply_mount(host, before, commits):
    original_compose = before['compose']
    require(not GROWTH_ENV.exists())
    candidate_compose = inject_growth_env(original_compose)
    candidate_env = dotenv(False)
    validate_before(host, before)
    backup(COMPOSE, original_compose)
    try:
        atomic_replace(GROWTH_ENV, candidate_env)
        atomic_replace(COMPOSE, candidate_compose)
        validate_render_change(before['render'], host.rendered(), False)
        host.restart()
        validate_runtime(before['objects'], host.capture(), commits, False)
        validate_unchanged(before)
        require(read_owned(COMPOSE) == candidate_compose and read_owned(GROWTH_ENV, True) == candidate_env)
    except Exception:
        require(COMPOSE.read_bytes() in (original_compose, candidate_compose)
                and (not GROWTH_ENV.exists() or GROWTH_ENV.read_bytes() == candidate_env),
                'GROWTH_RELEASE_EXTERNAL_CHANGE_REQUIRES_REVIEW')
        if COMPOSE.read_bytes() == candidate_compose:
            atomic_replace(COMPOSE, original_compose)
        if GROWTH_ENV.exists() and GROWTH_ENV.read_bytes() == candidate_env:
            GROWTH_ENV.unlink()
        host.restart()
        validate_rollback(before['objects'], host.capture())
        raise RuntimeError('GROWTH_RELEASE_MOUNT_FAILED_RECHECK_REQUIRED') from None


def apply_switch(host, before, commits, active):
    original = read_owned(GROWTH_ENV, True)
    require(original == dotenv(not active), 'GROWTH_RELEASE_PHASE_REJECTED')
    replacement = dotenv(active)
    validate_before(host, before)
    backup(GROWTH_ENV, original)
    try:
        atomic_replace(GROWTH_ENV, replacement)
        validate_render_change(strip_growth(before['render'])[0], host.rendered(), active)
        host.restart()
        validate_runtime(before['objects'], host.capture(), commits, active)
        validate_unchanged(before)
        require(read_owned(COMPOSE) == before['compose'])
        require(read_owned(GROWTH_ENV, True) == replacement)
    except Exception:
        if GROWTH_ENV.exists() and GROWTH_ENV.read_bytes() == replacement:
            atomic_replace(GROWTH_ENV, original)
            host.restart()
            validate_rollback(before['objects'], host.capture())
        raise RuntimeError('GROWTH_RELEASE_SWITCH_FAILED_RECHECK_REQUIRED') from None


def validate_before(host, before):
    require(host.capture() == before['objects'], 'GROWTH_RELEASE_RUNTIME_DRIFT')
    require(host.rendered() == before['render'], 'GROWTH_RELEASE_CONFIG_DRIFT')
    require(read_owned(COMPOSE) == before['compose'], 'GROWTH_RELEASE_CONFIG_DRIFT')
    validate_unchanged(before)


def validate_rollback(before, after):
    require(after['batch'] == before['batch'], 'GROWTH_RELEASE_ROLLBACK_BATCH_CHANGED')
    require(after['api']['State']['Running'] and after['api']['Image'] == before['api']['Image']
            and after['api']['Mounts'] == before['api']['Mounts']
            and environment(after['api']) == environment(before['api']),
            'GROWTH_RELEASE_ROLLBACK_UNVERIFIED')


def validate_unchanged(before):
    require(read_owned(ROOT / '.env', True) == before['base_env'])
    require(read_owned(ROOT / '.account-lifecycle.env', True) == before['account_env'])
    if before['review_env'] is not None:
        require(read_owned(ROOT / '.review.env', True) == before['review_env'])
    else:
        require(not (ROOT / '.review.env').exists())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'mount-disabled', 'activate', 'deactivate'), required=True)
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(all(re.fullmatch(r'[a-f0-9]{40}', value or '') for value in
                (args.api_commit, args.batch_commit)))
    require((args.operation == 'check') == (not args.apply_approved))
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    host = Host()
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    with host.context.maintenance_lease.acquire():
        objects = host.capture()
        for role in ('api', 'batch'):
            require(objects[role]['State']['Running'] and objects[role]['Config']['User'] == '1000:1000')
            require(objects[role]['Config']['Image'].endswith(':' + commits[role]))
        epoch = environment(objects['api']).get('ERASURE_CHECKPOINT_DATABASE_EPOCH')
        host.context.select({'toilet-' + role: obj for role, obj in objects.items()}, epoch)
        before = {'objects': objects, 'render': host.rendered(), 'compose': read_owned(COMPOSE),
                  'base_env': read_owned(ROOT / '.env', True),
                  'account_env': read_owned(ROOT / '.account-lifecycle.env', True),
                  'review_env': read_owned(ROOT / '.review.env', True)
                  if (ROOT / '.review.env').exists() else None}
        current = state(objects)
        require(before['render']['services']['api']['image'] == objects['api']['Config']['Image'])
        require(before['render']['services']['api']['environment'].get('GROWTH_ENABLED')
                == environment(objects['api']).get('GROWTH_ENABLED'), 'GROWTH_RELEASE_CONFIG_DRIFT')
        host.healthy()
        if current == 'unmounted':
            require('.growth.env' not in before['compose'].decode())
        else:
            require(before['compose'].decode().count('      - .growth.env\n') == 1)
            require(read_owned(GROWTH_ENV, True) == dotenv(current == 'active'))
        if args.operation == 'mount-disabled':
            require(current == 'unmounted')
            host.run(['docker', 'exec', 'toilet-api', 'test', '-f', '/app/growth-ops.jar'])
            apply_mount(host, before, commits)
            current = 'disabled'
        elif args.operation == 'activate':
            require(current == 'disabled')
            policy = json.loads(host.run(['docker', 'exec', 'toilet-api', 'java', '-jar',
                                         '/app/growth-ops.jar', 'policy-preview'], timeout=60))
            require(policy.get('initialized') is True and policy.get('regions') == 16
                    and type(policy.get('targetDistricts')) is int and policy['targetDistricts'] > 0,
                    'GROWTH_RELEASE_POLICY_NOT_READY')
            apply_switch(host, before, commits, True)
            current = 'active'
        elif args.operation == 'deactivate':
            require(current == 'active')
            apply_switch(host, before, commits, False)
            current = 'disabled'
    print(json.dumps({'outcome': 'GROWTH_RELEASE_' + ('CHECKED' if args.operation == 'check' else 'APPLIED'),
                      'growthState': current, 'accountConfigurationChanged': False,
                      'reviewConfigurationChanged': False, 'directDatabaseWrites': False,
                      'operation': args.operation}))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # Docker/Compose exceptions may contain environment values; never echo them.
        code = str(failure)
        print('GROWTH_RELEASE_FAILED:' + (code if re.fullmatch(r'GROWTH_RELEASE_[A-Z_]+', code)
                                          else type(failure).__name__), file=sys.stderr)
        sys.exit(1)
