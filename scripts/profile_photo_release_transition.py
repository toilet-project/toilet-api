#!/usr/bin/env python3
"""Attach or switch profile-photo storage without changing active account state."""

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
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path('/home/luha/toilet-api')
COMPOSE = ROOT / 'compose.yaml'
PROFILE_ENV = Path('/home/luha/.config/geupddong/profile-photo.env')
PROFILE_ENV_TEXT = str(PROFILE_ENV)
PROFILE_BASE_KEYS = (
    'PROFILE_PHOTO_R2_ENDPOINT', 'PROFILE_PHOTO_R2_BUCKET',
    'PROFILE_PHOTO_R2_ACCESS_KEY_ID', 'PROFILE_PHOTO_R2_SECRET_ACCESS_KEY',
)
PROFILE_CDN_KEYS = ('PROFILE_PHOTO_CDN_ZONE_ID', 'PROFILE_PHOTO_CDN_TOKEN')
PROFILE_KEYS = PROFILE_BASE_KEYS + PROFILE_CDN_KEYS
PROFILE_FLAGS = ('PROFILE_PHOTO_ENABLED', 'PROFILE_PHOTO_CDN_ENABLED', 'KAKAO_LOGIN_SCOPES')
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
POLICY = {
    'data-profile-photo-policy-status': 'published',
    'data-profile-photo-policy-version': 'profile-photo-us-r2-public-v2',
    'data-profile-photo-policy-announced-at': '2026-09-13T15:15:00Z',
    'data-profile-photo-policy-effective-at': '2026-09-13T15:15:00Z',
}


def require(value, code='PROFILE_PHOTO_RELEASE_HELD'):
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


def feature_values(active, cdn_enabled=False):
    return {
        'PROFILE_PHOTO_ENABLED': 'true' if active else 'false',
        'PROFILE_PHOTO_CDN_ENABLED': 'true' if active and cdn_enabled else 'false',
        'KAKAO_LOGIN_SCOPES': ('profile_nickname,account_email,profile_image'
                               if active else 'profile_nickname,account_email'),
    }


def parse_storage(content):
    values = {}
    for line in content.decode('utf-8').splitlines():
        if not line or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'([A-Z][A-Z0-9_]*)=([^\r\n\0]+)', line)
        require(match and match[1] not in values, 'PROFILE_PHOTO_STORAGE_ENV_REJECTED')
        values[match[1]] = match[2]
    require(set(values) in (set(PROFILE_BASE_KEYS), set(PROFILE_KEYS)),
            'PROFILE_PHOTO_STORAGE_ENV_REJECTED')
    endpoint = values['PROFILE_PHOTO_R2_ENDPOINT']
    require(re.fullmatch(r'https://[a-f0-9]{32}\.us\.r2\.cloudflarestorage\.com', endpoint),
            'PROFILE_PHOTO_STORAGE_ENDPOINT_REJECTED')
    require(values['PROFILE_PHOTO_R2_BUCKET'] == 'geupddong-profile-photos-us',
            'PROFILE_PHOTO_STORAGE_BUCKET_REJECTED')
    return values


def inject_profile(content):
    text = content.decode('utf-8')
    require(PROFILE_ENV_TEXT not in text and 'PROFILE_PHOTO_ENABLED:' not in text
            and 'PROFILE_PHOTO_CDN_ENABLED:' not in text and 'KAKAO_LOGIN_SCOPES:' not in text,
            'PROFILE_PHOTO_RELEASE_ALREADY_MOUNTED')
    env_anchor = "      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'\n"
    file_anchor = '      - .account-lifecycle.env\n'
    require(text.count(env_anchor) == 1 and text.count(file_anchor) == 1,
            'PROFILE_PHOTO_RELEASE_COMPOSE_SHAPE_REJECTED')
    flags = ("      PROFILE_PHOTO_ENABLED: 'false'\n"
             "      PROFILE_PHOTO_CDN_ENABLED: 'false'\n"
             "      KAKAO_LOGIN_SCOPES: 'profile_nickname,account_email'\n")
    text = text.replace(env_anchor, env_anchor + flags)
    text = text.replace(file_anchor, file_anchor + '      - ' + PROFILE_ENV_TEXT + '\n')
    return text.encode('utf-8')


def switch_profile(content, active, cdn_enabled=False):
    text = content.decode('utf-8')
    before = feature_values(not active, cdn_enabled and not active)
    after = feature_values(active, cdn_enabled and active)
    source = ("      PROFILE_PHOTO_ENABLED: '" + before['PROFILE_PHOTO_ENABLED'] + "'\n"
              "      PROFILE_PHOTO_CDN_ENABLED: '" + before['PROFILE_PHOTO_CDN_ENABLED'] + "'\n"
              "      KAKAO_LOGIN_SCOPES: '" + before['KAKAO_LOGIN_SCOPES'] + "'\n")
    target = ("      PROFILE_PHOTO_ENABLED: '" + after['PROFILE_PHOTO_ENABLED'] + "'\n"
              "      PROFILE_PHOTO_CDN_ENABLED: '" + after['PROFILE_PHOTO_CDN_ENABLED'] + "'\n"
              "      KAKAO_LOGIN_SCOPES: '" + after['KAKAO_LOGIN_SCOPES'] + "'\n")
    require(text.count(source) == 1 and target not in text,
            'PROFILE_PHOTO_RELEASE_PHASE_REJECTED')
    return text.replace(source, target).encode('utf-8')


def validate_render_change(before, after, storage, active):
    expected = copy.deepcopy(before)
    expected['services']['api'].setdefault('environment', {}).update(storage)
    expected['services']['api']['environment'].update(
        feature_values(active, active and all(key in storage for key in PROFILE_CDN_KEYS)))
    require(after == expected, 'PROFILE_PHOTO_RELEASE_NON_PROFILE_CHANGE_REJECTED')


def read_owned(path, private=False):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 1000 and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 if private
            else info.st_gid == 1000 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()


def atomic_replace(path, content):
    descriptor, temporary = tempfile.mkstemp(prefix='.profile-photo-release-', dir=path.parent)
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
    target = path.with_name('.profile-photo-before-' + str(time.time_ns()) + '-' + path.name.lstrip('.'))
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    return target


class PolicyParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.markers = []
        self.text = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.hidden += 1
        values = dict(attrs)
        if tag == 'article' and 'data-profile-photo-policy-status' in values:
            require(len(values) == len(attrs), 'PROFILE_PHOTO_POLICY_MARKER_REJECTED')
            self.markers.append(values)

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden:
            self.text.append(data)


def check_policy():
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request('https://geupddong.com/policies/privacy',
                                     headers={'Cache-Control': 'no-cache',
                                              'User-Agent': 'Geupddong-Profile-Photo-Preflight/1.0'})
    with opener.open(request, timeout=15) as response:
        require(response.status == 200 and response.headers.get_content_type() == 'text/html',
                'PROFILE_PHOTO_POLICY_UNAVAILABLE')
        body = response.read(1024 * 1024 + 1)
    require(len(body) <= 1024 * 1024)
    parser = PolicyParser()
    parser.feed(body.decode('utf-8'))
    require(len(parser.markers) == 1 and all(parser.markers[0].get(key) == value
                                             for key, value in POLICY.items()),
            'PROFILE_PHOTO_POLICY_MARKER_REJECTED')
    text = ''.join(parser.text)
    require(all(value in text for value in ('미국', 'Cloudflare, Inc.', '최대 256×256 WebP',
                                            '사진을 삭제하거나 회원 탈퇴할 때까지')),
            'PROFILE_PHOTO_POLICY_CONTENT_REJECTED')
    effective = datetime.fromisoformat(POLICY['data-profile-photo-policy-effective-at'].replace('Z', '+00:00'))
    require(effective <= datetime.now(timezone.utc), 'PROFILE_PHOTO_POLICY_NOT_EFFECTIVE')


class Host:
    def __init__(self):
        require(ROOT.resolve(strict=True) == ROOT)
        info = ROOT.stat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == 1000 and info.st_gid == 1000
                and not (info.st_mode & 0o002))
        spec = importlib.util.spec_from_file_location(
            'profile_photo_ledger_context', '/home/luha/.local/bin/restore-ledger-context.py')
        self.context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.context)

    def run(self, args, *, input=None, env=None, timeout=30):
        return subprocess.run(args, input=input, env=env, text=True, capture_output=True,
                              timeout=timeout, check=True).stdout.strip()

    def compose(self, *args, file=COMPOSE):
        return ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(file), *args]

    def capture(self):
        return {role: normalize_inspection(json.loads(self.run(['docker', 'inspect', 'toilet-' + role]))[0])
                for role in ('api', 'batch')}

    def rendered(self, content=None):
        if content is None:
            return json.loads(self.run(self.compose('config', '--format', 'json')))
        return json.loads(self.run(self.compose('config', '--format', 'json', file='-'),
                                   input=content.decode('utf-8')))

    def storage(self):
        info = PROFILE_ENV.lstat()
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600
                and info.st_uid == 1000 and info.st_gid == 1000 and info.st_nlink == 1,
                'PROFILE_PHOTO_STORAGE_FILE_REJECTED')
        return parse_storage(read_owned(PROFILE_ENV, True))

    def healthy(self):
        obj = self.capture()['api']
        env = environment(obj)
        port = env.get('API_PORT', '')
        require(port.isascii() and port.isdigit() and 1 <= int(port) <= 65535)
        address = obj['NetworkSettings']['Networks']['toilet-network']['IPAddress']
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://' + address + ':' + port + '/api/health', timeout=4) as response:
            body = response.read(8192).decode()
        require(response.status == 200 and api_health_ok(body), 'PROFILE_PHOTO_RELEASE_HEALTH_REJECTED')

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
                    raise ValueError('PROFILE_PHOTO_RELEASE_HEALTH_REJECTED') from None
                time.sleep(2)


def runtime_state(obj):
    env = environment(obj)
    base_present = {key for key in PROFILE_BASE_KEYS if key in env}
    cdn_present = {key for key in PROFILE_CDN_KEYS if key in env}
    if not base_present:
        require(env.get('PROFILE_PHOTO_ENABLED', 'false') == 'false'
                and env.get('PROFILE_PHOTO_CDN_ENABLED', 'false') == 'false'
                and 'profile_image' not in env.get('KAKAO_LOGIN_SCOPES', 'profile_nickname,account_email'))
        return 'unmounted'
    require(base_present == set(PROFILE_BASE_KEYS)
            and cdn_present in (set(), set(PROFILE_CDN_KEYS)),
            'PROFILE_PHOTO_RELEASE_PARTIAL_ENV_REJECTED')
    cdn_enabled = env.get('PROFILE_PHOTO_CDN_ENABLED', 'false') == 'true'
    require(not cdn_enabled or cdn_present == set(PROFILE_CDN_KEYS),
            'PROFILE_PHOTO_RELEASE_PARTIAL_ENV_REJECTED')
    if all(env.get(key) == value for key, value in feature_values(True, cdn_enabled).items()):
        return 'active'
    require(all(env.get(key) == value for key, value in feature_values(False, False).items()),
            'PROFILE_PHOTO_RELEASE_UNKNOWN_PHASE')
    return 'disabled'


def validate_runtime(objects, commits, state, storage):
    for role in ('api', 'batch'):
        obj = objects[role]
        require(obj['State']['Running'] and obj['Config']['User'] == '1000:1000')
        require(obj['Config']['Image'].endswith(':' + commits[role]))
        env = environment(obj)
        require(all(env.get(key) == value for key, value in ACCOUNT_ACTIVE.items()),
                'PROFILE_PHOTO_RELEASE_ACCOUNT_STATE_REJECTED')
    api = objects['api']
    require(runtime_state(api) == state)
    if state != 'unmounted':
        env = environment(api)
        require(all(env.get(key) == value for key, value in storage.items()),
                'PROFILE_PHOTO_RELEASE_STORAGE_REJECTED')
    require(not any(key in environment(objects['batch']) for key in PROFILE_KEYS + PROFILE_FLAGS),
            'PROFILE_PHOTO_RELEASE_BATCH_CHANGED')


def apply_compose(host, snapshots, commits, storage, replacement, target_state):
    original = snapshots['compose']
    backup(COMPOSE, original)
    try:
        atomic_replace(COMPOSE, replacement)
        validate_render_change(snapshots['render'], host.rendered(), storage, target_state == 'active')
        host.restart()
        current = host.capture()
        validate_runtime(current, commits, target_state, storage)
        require(current['batch'] == snapshots['objects']['batch'])
        require(read_owned(ROOT / '.env', True) == snapshots['base_env'])
        require(read_owned(ROOT / '.account-lifecycle.env', True) == snapshots['account_env'])
    except Exception:
        require(COMPOSE.exists() and COMPOSE.read_bytes() in (original, replacement),
                'PROFILE_PHOTO_RELEASE_EXTERNAL_CHANGE_REQUIRES_REVIEW')
        if COMPOSE.read_bytes() == replacement:
            atomic_replace(COMPOSE, original)
            host.restart()
        raise RuntimeError('PROFILE_PHOTO_RELEASE_FAILED_CONFIG_RESTORED_RECHECK_REQUIRED') from None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'mount-disabled', 'activate', 'deactivate'), required=True)
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(all(re.fullmatch(r'[a-f0-9]{40}', value or '') for value in (args.api_commit, args.batch_commit)))
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    require((args.operation == 'check') == (not args.apply_approved))
    host = Host()
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    storage = host.storage()
    with host.context.maintenance_lease.acquire():
        objects = host.capture()
        state = runtime_state(objects['api'])
        validate_runtime(objects, commits, state, storage)
        snapshots = {
            'objects': objects, 'render': host.rendered(), 'compose': read_owned(COMPOSE),
            'base_env': read_owned(ROOT / '.env', True),
            'account_env': read_owned(ROOT / '.account-lifecycle.env', True),
        }
        if args.operation == 'check' and state == 'unmounted':
            candidate = inject_profile(snapshots['compose'])
            validate_render_change(snapshots['render'], host.rendered(candidate), storage, False)
        elif args.operation == 'mount-disabled':
            require(state == 'unmounted')
            replacement = inject_profile(snapshots['compose'])
            validate_render_change(snapshots['render'], host.rendered(replacement), storage, False)
            apply_compose(host, snapshots, commits, storage, replacement, 'disabled')
            state = 'disabled'
        elif args.operation in ('activate', 'deactivate'):
            active = args.operation == 'activate'
            require(state == ('disabled' if active else 'active'), 'PROFILE_PHOTO_RELEASE_PHASE_REJECTED')
            if active:
                check_policy()
            cdn_enabled = all(key in storage for key in PROFILE_CDN_KEYS)
            replacement = switch_profile(snapshots['compose'], active, cdn_enabled)
            validate_render_change(snapshots['render'], host.rendered(replacement), storage, active)
            apply_compose(host, snapshots, commits, storage, replacement, 'active' if active else 'disabled')
            state = 'active' if active else 'disabled'
    print(json.dumps({'outcome': 'PROFILE_PHOTO_RELEASE_' +
                      ('CHECKED' if args.operation == 'check' else 'APPLIED'),
                      'profilePhotoState': state, 'accountStatePreserved': True,
                      'directDatabaseWrites': False, 'operation': args.operation}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if str(error).startswith('PROFILE_PHOTO_') \
            and re.fullmatch(r'[A-Z_]+', str(error)) else 'PROFILE_PHOTO_RELEASE_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
