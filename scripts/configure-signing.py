#!/usr/bin/env python3
"""Run once on the owner's computer; never prints signing material."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

REPO = 'hugfeature/hydration-break'
SECRET = 'ANDROID_SIGNING_JSON'
parser = argparse.ArgumentParser(description='Create a durable signing backup and configure GitHub Actions.')
parser.add_argument('backup_dir', type=Path, help='Private backup directory OUTSIDE the git checkout; keep it permanently')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
backup = args.backup_dir.expanduser().resolve()
if backup == root or root in backup.parents:
    parser.error('Signing backup must be outside the repository')
for tool in ('gh', 'keytool'):
    if not shutil.which(tool):
        parser.error(f'Install {tool} first (GitHub CLI and JDK 17 required)')
subprocess.run(['gh', 'auth', 'status'], check=True)
existing = json.loads(subprocess.check_output(['gh', 'secret', 'list', '-R', REPO, '--json', 'name'], text=True))
if any(item['name'] == SECRET for item in existing):
    parser.error('Signing secret already exists. Refusing to replace the application identity.')
os.umask(0o077)
backup.mkdir(parents=True, exist_ok=True, mode=0o700)
bundle = backup / 'android-signing.json'
if not bundle.exists():
    password = secrets.token_urlsafe(32)
    with tempfile.TemporaryDirectory(prefix='signing-') as temp:
        key = Path(temp) / 'app.p12'
        env = dict(os.environ, SIGNING_SETUP_PASSWORD=password)
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
                        '-storepass:env', 'SIGNING_SETUP_PASSWORD', '-keypass:env', 'SIGNING_SETUP_PASSWORD',
                        '-alias', 'hydration-break', '-keyalg', 'RSA', '-keysize', '3072',
                        '-validity', '36500', '-dname', 'CN=Hydration Break', '-noprompt'], env=env, check=True)
        data = dict(keystore=base64.b64encode(key.read_bytes()).decode(), password=password, alias='hydration-break')
    with bundle.open('x') as out:
        json.dump(data, out)
else:
    # A failed upload can be retried without generating another key.
    data = json.loads(bundle.read_text())
    if not all(data.get(k) for k in ('keystore', 'password', 'alias')):
        parser.error('Existing backup is incomplete; do not delete a previously used key')
with bundle.open('rb') as source:
    subprocess.run(['gh', 'secret', 'set', SECRET, '-R', REPO], stdin=source, check=True)
print(f'Signing configured. Keep a secure backup of {bundle}; do not commit or share it.')
print('Next: gh workflow run android.yml -R ' + REPO)
