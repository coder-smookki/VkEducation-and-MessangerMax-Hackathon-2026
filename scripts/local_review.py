"""Prepare three explicitly synthetic accounts on an isolated loopback database, using real Java auth/API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
from urllib.parse import urlparse
from uuid import uuid4

from check_data_api import Client, sign_init_data

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / 'scripts/seed/local-review.private.json'


def settings(create=False):
    path = ROOT / '.env'
    if create and not path.exists():
        values = {'DB_USER': 'koren_review', 'DB_NAME': 'koren_review',
                  'DB_PASSWORD': secrets.token_urlsafe(24), 'DB_PORT': '55432',
                  'API_PORT': '58080', 'APP_PORT': '55173',
                  'DB_URL': 'jdbc:postgresql://127.0.0.1:55432/koren_review',
                  'MAX_BOT_TOKEN': 'local-review-' + secrets.token_urlsafe(32),
                  'MAX_WEBHOOK_SECRET': '', 'MAX_WORKERS_ENABLED': 'false',
                  'SPRING_PROFILES_ACTIVE': 'release', 'REVIEW_INVITATIONS_ENABLED': 'true',
                  'ANALYSIS_ENABLED': 'true', 'COMPARE_ANALYZER': 'rules', 'KAFKA_ENABLED': 'false',
                  'TRAINING_MODE': 'true', 'VITE_TRAINING_MODE': 'true', 'CASE_NUMBER_PREFIX': 'ДЕМО-'}
        path.write_text(''.join(f'{k}={v}\n' for k, v in values.items()), encoding='utf-8')
        path.chmod(0o600)
    if not path.exists():
        raise SystemExit('Run: python3 scripts/local_review.py env')
    values = dict(line.split('=', 1) for line in path.read_text().splitlines()
                  if '=' in line and not line.lstrip().startswith('#'))
    if not values.get('MAX_BOT_TOKEN', '').startswith('local-review-'):
        raise SystemExit('Refusing a real/unknown MAX token. Use a separate local-review .env.')
    if values.get('MAX_WORKERS_ENABLED') != 'false' or values.get('REVIEW_INVITATIONS_ENABLED') != 'true':
        raise SystemExit('Local review requires disabled MAX workers and enabled review invitations.')
    if not values.get('DB_NAME', '').endswith('_review'):
        raise SystemExit('Refusing a shared database: DB_NAME must end with _review.')
    for key in ('DB_USER', 'DB_NAME'):
        if not re.fullmatch(r'[A-Za-z0-9_]+', values.get(key, '')):
            raise SystemExit('Invalid local database identifier')
    return values


def compose():
    return ['docker', 'compose', '--env-file', str(ROOT / '.env'), '-p', 'koren-local-review', '-f', str(ROOT / 'compose.yaml')]


def database(sql, values, config, native):
    if native:
        command = ['psql', '-h', '127.0.0.1', '-p', config.get('DB_PORT', '55432')]
    else:
        command = compose() + ['exec', '-T', 'db', 'psql']
    command += ['-U', config['DB_USER'], '-d', config['DB_NAME'], '-v', 'ON_ERROR_STOP=1', '-Atq']
    for key, value in values.items():
        command += ['-v', f'{key}={value}']
    done = subprocess.run(command, cwd=ROOT, input=sql, text=True, capture_output=True,
                          env={**os.environ, 'PGPASSWORD': config['DB_PASSWORD']})
    if done.returncode:
        raise SystemExit('Local PostgreSQL command failed; check that the isolated database is running.')
    return done.stdout.strip()


def prepare(config, native, api_url, app_url):
    for url in (api_url, app_url):
        parsed = urlparse(url)
        if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost') or parsed.username or parsed.password:
            raise SystemExit('Only HTTP loopback URLs are allowed; no public stand mutations.')
    seed = json.loads((ROOT / 'scripts/seed/seed.json').read_text())
    houses = {h['key']: h for h in seed['houses']}
    for key, house in houses.items():
        flag = 'true' if key == 'A' else 'false'
        database("INSERT INTO house(id,address,review_only) VALUES (:'id',:'address',:review) ON CONFLICT(id) DO NOTHING;",
                 {'id': house['id'], 'address': house['address'], 'review': flag}, config, native)
    if database("SELECT review_only FROM house WHERE id=:'id';", {'id': houses['A']['id']}, config, native) != 't':
        raise SystemExit('House A already belongs to another setup. Use a new isolated review database.')
    actors = []
    for label, max_id, house_key, role in [('author', 900000001, 'A', 'RESIDENT'),
                                         ('dispatcher', 900000002, 'A', 'DISPATCHER'),
                                         ('stranger', 900000003, 'B', 'RESIDENT')]:
        client = Client(api_url, label)
        me = client.login(config['MAX_BOT_TOKEN'], max_id)
        house = houses[house_key]
        active = [m for m in me['memberships'] if m['houseId'] == house['id']]
        if active and active[0]['role'] != role:
            raise SystemExit('Existing synthetic account has another role; refusing to change its rights silently.')
        if not active:
            code = secrets.token_urlsafe(24)
            database("INSERT INTO house_invitation(id,house_id,code_hash,expires_at,granted_role)"
                     " VALUES (:'id',:'house',:'hash',now()+interval '1 day',:'role');",
                     {'id': str(uuid4()), 'house': house['id'], 'hash': hashlib.sha256(code.encode()).hexdigest(), 'role': role},
                     config, native)
            client.refresh_csrf()
            claimed = client.call('POST', '/api/v1/memberships/claim', {'invitationCode': code})
            if claimed.status != 200:
                raise SystemExit(f'Local role claim failed for {label}: HTTP {claimed.status}')
            me = claimed.json
        if not any(m['houseId'] == house['id'] and m['role'] == role for m in me['memberships']):
            raise SystemExit('Java did not confirm the expected membership')
        actors.append({'label': label, 'maxId': max_id, 'houseId': house['id'], 'role': role,
                       'initData': sign_init_data(config['MAX_BOT_TOKEN'], max_id, label)})
        print(f'{label}: {role}, house {house_key} {house["id"]}')
    PRIVATE.write_text(json.dumps({'testOnly': True, 'appUrl': app_url, 'actors': actors}, ensure_ascii=False), encoding='utf-8')
    PRIVATE.chmod(0o600)
    return PRIVATE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['env', 'start', 'seed', 'open'])
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--api-url')
    parser.add_argument('--app-url')
    args = parser.parse_args()
    config = settings(create=args.command in ('env', 'start'))
    api_url = args.api_url or 'http://127.0.0.1:' + config.get('API_PORT', '58080')
    app_url = args.app_url or 'http://127.0.0.1:' + config.get('APP_PORT', '55173')
    if args.command == 'env':
        print('Created private local-review .env; no MAX account or working bot token is used.')
        return
    if args.command == 'start':
        child_env = {k: v for k, v in os.environ.items() if not k.startswith('COMPOSE_')}
        subprocess.run(compose() + ['up', '--build', '--wait', '--wait-timeout', '180'], cwd=ROOT, env=child_env, check=True)
        for _ in range(60):
            try:
                if Client(api_url, 'readiness').call('GET', '/api/v1/csrf').status == 200:
                    break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise SystemExit('Java API is not ready')
    private = prepare(config, args.native, api_url, app_url)
    if args.command == 'open':
        command = ['node', str(ROOT / 'frontend/scripts/review-local.mjs'), str(private)]
        if args.check:
            command.append('--check')
        try:
            subprocess.run(command, cwd=ROOT, check=True)
        finally:
            private.unlink(missing_ok=True)
    else:
        private.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
