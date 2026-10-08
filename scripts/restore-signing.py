#!/usr/bin/env python3
"""Restore the CI keystore without logging secret values; fail closed."""
import base64
import json
import os
from pathlib import Path
import sys

try:
    data = json.loads(os.environ['ANDROID_SIGNING_JSON'])
    if not all(isinstance(data.get(k), str) and data[k] for k in ('keystore', 'password', 'alias')):
        raise ValueError()
    if any('\n' in data[k] or '\r' in data[k] for k in ('password', 'alias')):
        raise ValueError()
    raw = base64.b64decode(data['keystore'], validate=True)
    os.umask(0o077)
    key = Path(os.environ['RUNNER_TEMP']) / 'hydration-signing.p12'
    key.write_bytes(raw)
    key.chmod(0o600)
    # Mask each decoded credential before writing it to the runner's environment file.
    for value in (data['password'], data['alias']):
        print('::add-mask::' + value.replace('%', '%25'))
    with open(os.environ['GITHUB_ENV'], 'a') as out:
        out.write(f"ANDROID_KEYSTORE_PATH={key}\nANDROID_KEYSTORE_PASSWORD={data['password']}\nANDROID_KEY_ALIAS={data['alias']}\n")
except (KeyError, ValueError, TypeError):
    sys.exit('Signing secret missing or invalid. Run scripts/configure-signing.py on the owner computer; no random-signed APK will be published.')
