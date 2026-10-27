#!/usr/bin/env python3
"""Config Server 불가 시 앱이 성공 기동하지 않는지 별도 JVM으로 검사한다."""
from pathlib import Path
import os
import subprocess
import time
import socket

root = Path(__file__).resolve().parent.parent
start = time.monotonic()
env = os.environ.copy()
env['SPRING_PROFILES_ACTIVE'] = 'local'
with socket.socket() as blocker:
    blocker.bind(('127.0.0.1', 0))
    port = blocker.getsockname()[1]
    env['CONFIG_SERVER_URL'] = f'http://127.0.0.1:{port}'
    result = subprocess.run(['java', '-Xmx128m', '-jar', str(root/'eureka/build/libs/app.jar'),
                             '--spring.cloud.config.request-connect-timeout=200',
                             '--spring.cloud.config.request-read-timeout=200',
                             '--server.port=0'], cwd=root, env=env, capture_output=True, text=True, timeout=45)
assert result.returncode != 0, '설정 서버가 없는데 성공 기동했다'
assert 'Could not locate PropertySource' in result.stdout + result.stderr or 'ConfigClientFailFastException' in result.stdout + result.stderr, '다른 원인으로 실패했다'
print(f'Config 불가 시 기동 실패 확인 ({time.monotonic()-start:.1f}초, 비밀값·전체 로그 출력 안 함)')
