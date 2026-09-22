"""Read-only by default, exact-value Web cache contract transition for the API host."""
import argparse
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

CONTRACT_KEY = 'WEB_CACHE_CONTRACT_VERSION'


def require(value, code='CACHE_CONTRACT_HELD'):
    if not value:
        raise ValueError(code)


def parse_dotenv(content):
    require(isinstance(content, bytes) and b'\x00' not in content)
    values = {}
    for raw in content.decode('utf-8').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'([A-Z][A-Z0-9_]*)=(.*)', raw)
        require(match and match[1] not in values, 'CACHE_CONTRACT_ENV_REJECTED')
        values[match[1]] = match[2]
    return values


def contract_candidate(content, target):
    require(target in ('1', '2', '3'), 'CACHE_CONTRACT_TARGET_REJECTED')
    values = parse_dotenv(content)
    require(values.get('WEB_CACHE_REVALIDATION_ENABLED') == 'true')
    require(values.get('WEB_CACHE_ORIGIN') == 'https://geupddong.com')
    require(bool(values.get('WEB_CACHE_REVALIDATION_SECRET')))
    require(values.get(CONTRACT_KEY, '1') in ('1', '2', '3'))
    lines = content.decode('utf-8').splitlines()
    indices = [index for index, line in enumerate(lines) if line.startswith(CONTRACT_KEY + '=')]
    require(len(indices) <= 1, 'CACHE_CONTRACT_ENV_REJECTED')
    replacement = CONTRACT_KEY + '=' + target
    if indices:
        lines[indices[0]] = replacement
    else:
        lines.append(replacement)
    return ('\n'.join(lines) + '\n').encode('utf-8')


def read_owned(path):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 1024 * 1024)
    return path.read_bytes()


def read_project_file(path):
    require(path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and info.st_gid == os.getegid()
            and info.st_nlink == 1 and not (info.st_mode & 0o002))
    require(info.st_size <= 1024 * 1024)
    return path.read_bytes()


def atomic_replace(path, content):
    descriptor, temporary = tempfile.mkstemp(prefix='.cache-contract-', dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run(args, timeout=30):
    return subprocess.run(args, capture_output=True, text=True, check=True, timeout=timeout).stdout.strip()


def environment(obj):
    values = {}
    for item in obj['Config']['Env']:
        key, value = item.split('=', 1)
        require(key not in values)
        values[key] = value
    return values


def peer_snapshot(obj):
    require(isinstance(obj.get('Mounts'), list) and all(isinstance(item, dict) for item in obj['Mounts']))
    require(obj.get('State', {}).get('Running') is True)
    return {
        'Id': obj.get('Id'),
        'Created': obj.get('Created'),
        'Image': obj.get('Image'),
        'Name': obj.get('Name'),
        'Path': obj.get('Path'),
        'Args': obj.get('Args'),
        'Config': obj.get('Config'),
        'HostConfig': obj.get('HostConfig'),
        'Mounts': sorted(obj['Mounts'], key=lambda item: json.dumps(item, sort_keys=True)),
        'RestartCount': obj.get('RestartCount'),
        'StartedAt': obj['State'].get('StartedAt'),
    }


def inspect_api(expected_commit):
    require(re.fullmatch(r'[a-f0-9]{40}', expected_commit or '') is not None)
    obj = json.loads(run(['docker', 'inspect', 'toilet-api']))[0]
    require(obj['State']['Running'] and obj['Config']['User'] == '1000:1000')
    require(obj['Config']['Image'].endswith(':' + expected_commit), 'CACHE_CONTRACT_IMAGE_REJECTED')
    values = environment(obj)
    require(values.get('WEB_CACHE_REVALIDATION_ENABLED') == 'true')
    require(values.get('WEB_CACHE_ORIGIN') == 'https://geupddong.com')
    require(bool(values.get('WEB_CACHE_REVALIDATION_SECRET')))
    require(values.get(CONTRACT_KEY, '1') in ('1', '2', '3'))
    return obj, values


def inspect_batch():
    return peer_snapshot(json.loads(run(['docker', 'inspect', 'toilet-batch']))[0])


def healthy(obj):
    values = environment(obj)
    port = values.get('API_PORT', '')
    require(port.isascii() and port.isdigit() and 1 <= int(port) <= 65535)
    address = obj['NetworkSettings']['Networks']['toilet-network']['IPAddress']
    require(ipaddress.ip_address(address).is_private)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open('http://' + address + ':' + port + '/api/health', timeout=4) as response:
        require(response.status == 200)
        body = response.read(8193)
    require(len(body) <= 8192)
    body = body.decode()
    try:
        valid = json.loads(body) == {'status': 'UP'}
    except (ValueError, TypeError):
        valid = body == 'API server is running (DB: toilet_db)'
    require(valid, 'CACHE_CONTRACT_HEALTH_UNVERIFIED')


def wait_for_healthy(obj, timeout=60, interval=2):
    deadline = time.monotonic() + timeout
    while True:
        try:
            healthy(obj)
            return
        except Exception:
            if time.monotonic() >= deadline:
                raise ValueError('CACHE_CONTRACT_HEALTH_UNVERIFIED') from None
            time.sleep(interval)


def restart(root):
    run(['docker', 'compose', '--project-directory', str(root), '-f', str(root / 'compose.yaml'),
         'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate',
         '--wait', '--wait-timeout', '90', 'api'], 110)


def apply(args):
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(args.operation == 'check' or (args.deployment_freeze_confirmed and args.schema_verified
                                          and (args.contract_version == '1' or args.web_ready_confirmed)))
    root = Path('/home/luha/toilet-api')
    require(root.resolve(strict=True) == root)
    root_info = root.stat()
    require(stat.S_ISDIR(root_info.st_mode) and root_info.st_uid == os.geteuid()
            and root_info.st_gid == os.getegid() and not (root_info.st_mode & 0o002))
    env_path, compose_path = root / '.env', root / 'compose.yaml'
    account_path = root / '.account-lifecycle.env'
    profile_path = Path('/home/luha/.config/geupddong/profile-photo.env')
    context_path = '/home/luha/.local/bin/restore-ledger-context.py'
    spec = importlib.util.spec_from_file_location('cache_contract_context', context_path)
    context = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(context)
    with context.maintenance_lease.acquire():
        original_obj, original_runtime = inspect_api(args.expected_api_commit)
        original_batch = inspect_batch()
        healthy(original_obj)
        original = read_owned(env_path)
        replacement = contract_candidate(original, args.contract_version)
        unchanged = {path: read_owned(path) for path in (account_path, profile_path)}
        compose = read_project_file(compose_path)
        current = original_runtime.get(CONTRACT_KEY, '1')
        require(parse_dotenv(original).get(CONTRACT_KEY, '1') == current,
                'CACHE_CONTRACT_RUNTIME_REJECTED')
        if args.operation == 'apply' and replacement != original:
            backup = env_path.with_name('.cache-contract-before-' + str(time.time_ns()) + '.env')
            descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(original)
                stream.flush()
                os.fsync(stream.fileno())
            primary_stage = 'candidate-write'
            try:
                atomic_replace(env_path, replacement)
                primary_stage = 'api-restart'
                restart(root)
                primary_stage = 'api-inspect'
                updated_obj, updated_runtime = inspect_api(args.expected_api_commit)
                expected_runtime = dict(original_runtime)
                expected_runtime[CONTRACT_KEY] = args.contract_version
                primary_stage = 'runtime-compare'
                require(updated_runtime == expected_runtime, 'CACHE_CONTRACT_RUNTIME_REJECTED')
                primary_stage = 'peer-compare'
                require(inspect_batch() == original_batch, 'CACHE_CONTRACT_PEER_CHANGED')
                primary_stage = 'compose-compare'
                require(read_project_file(compose_path) == compose)
                primary_stage = 'aux-config-compare'
                require(all(read_owned(path) == content for path, content in unchanged.items()))
                primary_stage = 'api-health'
                wait_for_healthy(updated_obj)
                primary_stage = 'backup-remove'
                backup.unlink()
            except Exception:
                rollback_stage = 'candidate-verify'
                try:
                    require(read_owned(env_path) == replacement, 'CACHE_CONTRACT_EXTERNAL_CHANGE_REQUIRES_REVIEW')
                    rollback_stage = 'config-restore'
                    atomic_replace(env_path, original)
                    rollback_stage = 'api-restart'
                    restart(root)
                    rollback_stage = 'api-inspect'
                    restored_obj, restored_runtime = inspect_api(args.expected_api_commit)
                    rollback_stage = 'runtime-compare'
                    require(restored_runtime == original_runtime)
                    rollback_stage = 'peer-compare'
                    require(inspect_batch() == original_batch, 'CACHE_CONTRACT_PEER_CHANGED')
                    rollback_stage = 'compose-compare'
                    require(read_project_file(compose_path) == compose)
                    rollback_stage = 'aux-config-compare'
                    require(all(read_owned(path) == content for path, content in unchanged.items()))
                    rollback_stage = 'api-health'
                    wait_for_healthy(restored_obj)
                    rollback_stage = 'backup-remove'
                    if backup.exists():
                        backup.unlink()
                except Exception:
                    print('CACHE_CONTRACT_FAILURE_CONTEXT primaryStage=' + primary_stage
                          + ' rollbackStage=' + rollback_stage, file=sys.stderr)
                    raise RuntimeError('CACHE_CONTRACT_ROLLBACK_UNVERIFIED') from None
                print('CACHE_CONTRACT_FAILURE_CONTEXT primaryStage=' + primary_stage
                      + ' rollbackStage=complete', file=sys.stderr)
                raise RuntimeError('CACHE_CONTRACT_FAILED_CONFIG_RESTORED') from None
        elif args.operation == 'apply':
            require(current == args.contract_version, 'CACHE_CONTRACT_RUNTIME_REJECTED')
        print(json.dumps({'outcome': 'CACHE_CONTRACT_APPLIED' if args.operation == 'apply' else 'CACHE_CONTRACT_PREFLIGHT_PASS',
                          'previousContract': current, 'targetContract': args.contract_version,
                          'applied': args.operation == 'apply' and replacement != original,
                          'apiCommit': args.expected_api_commit, 'accountConfigurationChanged': False,
                          'directDatabaseWrites': False}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'apply'), required=True)
    parser.add_argument('--contract-version', choices=('1', '2', '3'), required=True)
    parser.add_argument('--expected-api-commit', required=True)
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    parser.add_argument('--schema-verified', action='store_true')
    parser.add_argument('--web-ready-confirmed', action='store_true')
    apply(parser.parse_args())


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error)
        if not (code.startswith('CACHE_CONTRACT_') and re.fullmatch(r'[A-Z_]+', code)):
            code = 'CACHE_CONTRACT_HELD'
        print(code + ' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
