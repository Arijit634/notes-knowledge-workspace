#!/usr/bin/env python3
"""Fresh-host secret entry. Never reads developer environments or prints values."""
import base64
import getpass
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import urlsplit

KEYS = (
    'identity.delivery.key-base64', 'identity.mfa.key-base64', 'identity.mfa.handle-key-base64',
    'identity.session-handle.key-base64', 'identity.rate.key-base64', 'notes.cursor.active.key-base64',
    'publishing.preview.key-base64', 'knowledge.operations.handle.key-base64', 'knowledge.operations.envelope.key-base64',
)


def configuration(origin, namespace, region, bucket, client_id, client_secret, access_key, storage_secret):
    url = urlsplit(origin)
    if (url.scheme != 'https' or not re.fullmatch(r'[a-z0-9][a-z0-9.-]+\.[a-z]{2,}', url.netloc)
            or url.path or url.query or url.fragment or url.username or url.port
            or not re.fullmatch(r'[a-z0-9]{1,63}', namespace)
            or not re.fullmatch(r'[a-z]{2}-[a-z0-9]+-\d+', region)
            or not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', bucket)
            or not re.fullmatch(r'[A-Za-z0-9-]{1,256}\.apps\.googleusercontent\.com', client_id)
            or any(not v or '\n' in v or '\r' in v for v in (client_secret, access_key, storage_secret))):
        raise ValueError('invalid_preview_configuration')
    key = lambda: base64.b64encode(secrets.token_bytes(32)).decode('ascii')
    owner, runtime, redis = key(), key(), key()
    app = {name: key() for name in KEYS}
    app.update({
        'nkw.preview.origin': origin, 'nkw.preview.region': region,
        'nkw.preview.namespace': namespace, 'nkw.preview.bucket': bucket,
        'nkw.preview.s3.access-key-id': access_key, 'nkw.preview.s3.secret-access-key': storage_secret,
        'identity.oidc.google.client-id': client_id, 'identity.oidc.google.client-secret': client_secret,
        'spring.datasource.password': runtime, 'spring.data.redis.password': redis,
    })
    return app, owner, runtime, redis


def main():
    if os.name != 'posix' or os.geteuid() != 0 or len(sys.argv) != 1:
        raise ValueError('fresh_linux_host_root_required')
    root = Path('/opt/nkw-preview')
    if not (root / '.isolated-preview').is_file() or (root / 'secrets').exists():
        raise ValueError('refusing_existing_or_unrecognized_state')
    origin = input('Owned HTTPS origin (no trailing slash): ').strip()
    namespace = input('OCI namespace: ').strip()
    region = input('Verified home region: ').strip()
    bucket = input('Private preview bucket: ').strip()
    client_id = input('NEW hosted Google client ID (never localhost client): ').strip()
    app, owner, runtime, redis = configuration(origin, namespace, region, bucket, client_id,
        getpass.getpass('NEW hosted Google client secret: '),
        getpass.getpass('Dedicated preview storage access key: '),
        getpass.getpass('Dedicated preview storage secret: '))
    os.umask(0o077)
    directory = root / 'secrets'
    directory.mkdir(mode=0o700)
    (directory / 'app').mkdir(mode=0o700)
    os.chown(directory / 'app', 65533, 65533)
    for name, value in app.items():
        file = directory / 'app' / name
        file.write_text(value, encoding='utf-8')
        os.chown(file, 65533, 65533)
    file = directory / 'db-owner'
    file.write_text(owner, encoding='ascii')
    os.chown(file, 999, 999)
    file = directory / 'redis.conf'
    file.write_text(f'bind 0.0.0.0\nprotected-mode yes\nrequirepass "{redis}"\nmaxmemory 64mb\nmaxmemory-policy noeviction\nappendonly yes\nsave ""\n', encoding='ascii')
    os.chown(file, 999, 999)
    file = directory / 'migration.properties'
    file.write_text(f'url=jdbc:postgresql://postgres:5432/nkw_preview\nuser=nkw_preview_migrator\npassword={owner}\nruntimePassword={runtime}\n', encoding='ascii')
    os.chown(file, 65533, 65533)
    print('preview_secrets_created; preserve an encrypted, separately controlled recovery escrow before deployment')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('preview_secret_preparation_failed; values withheld; inspect state before retrying', file=sys.stderr)
        sys.exit(2)
