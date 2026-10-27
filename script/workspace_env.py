"""Compose와 같은 규칙으로 개인 환경변수를 해석한다. 값을 로그에 남기지 않는다."""
import json
import os
from pathlib import Path
import subprocess

LOCAL_ENV_KEYS = ('DB_USER', 'DB_PASSWORD', 'JWT_ACCESS_SECRET', 'JWT_REFRESH_SECRET',
                  'ADMIN_EMAIL', 'ADMIN_PASSWORD', 'GRAFANA_ADMIN_PASSWORD')


def load_local_env(path, environ=None):
    path = Path(path).resolve()
    if not path.is_file():
        raise ValueError('개인 환경변수 파일이 없습니다. script/init-local-env.py를 먼저 실행하세요')
    expressions = {key: '${' + key + (':-iseonjwa}' if key == 'DB_USER' else '-}')
                   for key in LOCAL_ENV_KEYS}
    definition = {'name': 'iseonjwa-env-parser', 'services': {
        'values': {'image': 'scratch', 'environment': expressions}}}
    try:
        result = subprocess.run(['docker', 'compose', '--project-directory', str(path.parent),
                                 '--env-file', str(path), '-f', '-', 'config', '--format', 'json'],
                                input=json.dumps(definition), capture_output=True, text=True,
                                env=dict(os.environ if environ is None else environ), timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError('Docker Compose로 개인 환경변수를 해석하지 못했습니다') from None
    if result.returncode != 0:
        raise ValueError('개인 환경변수를 해석하지 못했습니다. .env 형식과 Compose 설치를 확인하세요')
    values = json.loads(result.stdout)['services']['values']['environment']
    # canonical Compose 출력은 재해석을 막으려고 $를 $$로 렌더링한다. 한 번만 되돌린다.
    return {key: '' if values.get(key) is None else str(values[key]).replace('$$', '$')
            for key in LOCAL_ENV_KEYS}
