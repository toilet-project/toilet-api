#!/usr/bin/env python3
"""Pinned, additive visibility-cache installation and single-flag activation.

Default is read-only. Existing images, account/review/analytics flags, source rows,
and existing triggers are never changed by cache installation. Activation only
recreates the API with one additional environment variable.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

_spec = importlib.util.spec_from_file_location('duplicate_release_base',
    Path(__file__).with_name('service_analytics_release_transition.py'))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)

FLAG = 'DUPLICATE_FACILITIES_ENABLED'
TRIGGER = 'cache_toilet_visibility_update'
OLD_TRIGGER_HASH = 'd30dee7684ee050113d226e22359fc572d81cdf23068bac1354c45412fd0ea8d'
base.ANALYTICS_CONFIG = {FLAG: 'true'}
base.ANALYTICS_KEYS = (FLAG,)


def require(value, code='DUPLICATE_RELEASE_HELD'):
    if not value:
        raise ValueError(code)


def query(objects, sql, root=False):
    api = base.environment(objects['api'])
    if root:
        result = subprocess.run(['docker', 'inspect', 'toilet-mysql'],
                                capture_output=True, text=True, check=True, timeout=10)
        password = base.environment(json.loads(result.stdout)[0])['MYSQL_ROOT_PASSWORD']
        user = 'root'
    else:
        password = api['SPRING_DB_PASSWORD']
        user = api['SPRING_DB_USERNAME']
    env = dict(os.environ, MYSQL_PWD=password)
    result = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', 'toilet-mysql',
        'mysql', '--protocol=socket' if root else '--host=127.0.0.1', '--user='+user,
        '--database=toilet_db', '--batch', '--raw', '--skip-column-names'],
        input=sql, text=True, capture_output=True, timeout=30, env=env)
    require(result.returncode == 0, 'DUPLICATE_RELEASE_SQL_HELD')
    return result.stdout.strip().splitlines()


def trigger_state(objects):
    rows = query(objects, "SELECT TRIGGER_NAME,SHA2(ACTION_STATEMENT,256),ACTION_ORDER "
        "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME")
    return {name: (digest, int(order)) for name, digest, order in (row.split('\t') for row in rows)}


def schema_ready(objects):
    require(query(objects, "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('23','24') AND success=1") == ['2'])
    require(query(objects, "SELECT COUNT(*) FROM toilet WHERE visibility_status<>'VISIBLE'") == ['0'],
            'DUPLICATE_RELEASE_EXISTING_HIDDEN_ROWS_REVIEW_REQUIRED')
    require(query(objects, "SELECT COUNT(*) FROM batch_sync_history WHERE status IN ('RUNNING','STARTED','STARTING')") == ['0'],
            'DUPLICATE_RELEASE_BATCH_RUNNING')
    require(base.environment(objects['api']).get('WEB_CACHE_REVALIDATION_ENABLED') == 'true')
    require(base.environment(objects['api']).get('WEB_CACHE_CONTRACT_VERSION') in ('2', '3'))


def expected_body(sql):
    clean = '\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--')).strip()
    prefix = ('CREATE TRIGGER '+TRIGGER+' AFTER UPDATE ON toilet\n'
              'FOR EACH ROW FOLLOWS cache_toilet_update\n')
    require(clean.startswith(prefix) and clean.count(';') == 1 and clean.endswith(';'))
    return clean[len(prefix):-1]


def validate_cache(state, digest):
    require(state.get('cache_toilet_update', (None,))[0] == OLD_TRIGGER_HASH,
            'DUPLICATE_RELEASE_EXISTING_TRIGGER_CHANGED')
    require(state.get(TRIGGER) == (digest, state['cache_toilet_update'][1]+1),
            'DUPLICATE_RELEASE_VISIBILITY_TRIGGER_NOT_READY')


def preserved_environment(before, after):
    expected = dict(before)
    expected[FLAG] = 'true'
    require(after == expected, 'DUPLICATE_RELEASE_UNRELATED_ENV_CHANGED')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--operation', choices=('check', 'install-cache', 'activate'), default='check')
    parser.add_argument('--api-commit', required=True)
    parser.add_argument('--batch-commit', required=True)
    parser.add_argument('--cache-sql', type=Path, required=True)
    parser.add_argument('--apply-approved', action='store_true')
    parser.add_argument('--deployment-freeze-confirmed', action='store_true')
    args = parser.parse_args()
    require(os.geteuid() == 1000 and sys.platform.startswith('linux'))
    require(all(re.fullmatch('[a-f0-9]{40}', item) for item in (args.api_commit,args.batch_commit)))
    require(args.apply_approved == (args.operation != 'check'))
    require(not args.apply_approved or args.deployment_freeze_confirmed)
    sql = args.cache_sql.read_text(encoding='utf-8').replace('\r\n','\n')
    body_hash = hashlib.sha256(expected_body(sql).encode()).hexdigest()
    host = base.Host()
    commits = {'api': args.api_commit, 'batch': args.batch_commit}
    with host.context.maintenance_lease.acquire():
        objects = host.capture()
        state = base.analytics_state(objects['api'])
        base.validate_runtime(objects, commits, state)
        require(state == 'inactive', 'DUPLICATE_RELEASE_ALREADY_ACTIVE')
        schema_ready(objects)
        triggers = trigger_state(objects)
        require(triggers.get('cache_toilet_update', (None,))[0] == OLD_TRIGGER_HASH,
                'DUPLICATE_RELEASE_EXISTING_TRIGGER_CHANGED')
        snapshots = {'objects': objects, 'render': host.rendered(),
            'compose': base.read_owned(base.COMPOSE),
            'base_env': base.read_owned(base.ROOT/'.env', True),
            'account_env': base.read_owned(base.ROOT/'.account-lifecycle.env', True)}
        candidate = base.inject_analytics(snapshots['compose'])
        base.validate_render_change(snapshots['render'], host.rendered(candidate), True)
        if args.operation == 'install-cache':
            require(TRIGGER not in triggers, 'DUPLICATE_RELEASE_CACHE_ALREADY_INSTALLED')
            # No DROP, no source-row writes, and no gaps in existing capture.
            query(objects, 'SET SESSION lock_wait_timeout=10;\n'+sql, root=True)
            current = trigger_state(objects)
            validate_cache(current, body_hash)
            require({k:v for k,v in current.items() if k != TRIGGER} == triggers)
            require(host.capture() == objects)
        elif args.operation == 'activate':
            validate_cache(triggers, body_hash)
            original_validate = base.validate_runtime
            def validate_preserved(current, pinned, phase):
                original_validate(current, pinned, phase)
                preserved_environment(base.environment(objects['api']), base.environment(current['api']))
            base.validate_runtime = validate_preserved
            base.apply_compose(host, snapshots, commits, candidate, 'active')
        elif TRIGGER in triggers:
            validate_cache(triggers, body_hash)
    print(json.dumps({'outcome':'DUPLICATE_RELEASE_OK', 'operation':args.operation,
        'existingTriggersPreserved':True, 'unrelatedConfigurationPreserved':True,
        'facilityRowsChanged':False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error)
        if not re.fullmatch('DUPLICATE_RELEASE_[A-Z_]+', code):
            code = 'DUPLICATE_RELEASE_HELD'
        print(code+' detailsSuppressed=true', file=sys.stderr)
        raise SystemExit(1)
