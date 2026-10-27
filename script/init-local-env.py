#!/usr/bin/env python3
"""기존 값을 덮어쓰지 않고 개인 개발용 환경변수를 만든다."""
from pathlib import Path
import os
import secrets

root = Path(__file__).resolve().parent.parent
path = root / '.env'
if path.exists():
    print('.env가 있어 기존 값을 유지한다')
else:
    values = {'DB_USER': 'iseonjwa', 'DB_PASSWORD': secrets.token_hex(24),
              'JWT_ACCESS_SECRET': secrets.token_hex(32), 'JWT_REFRESH_SECRET': secrets.token_hex(32),
              'ADMIN_EMAIL': 'admin@iseonjwa.local', 'ADMIN_PASSWORD': secrets.token_hex(16),
              'GRAFANA_ADMIN_PASSWORD': secrets.token_hex(16)}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    print('개인 개발용 .env 생성 (0600, 값은 출력하지 않음)')
