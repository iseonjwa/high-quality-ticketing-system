#!/usr/bin/env python3
"""인프라는 Compose, 선택한 Boot 앱은 loopback JVM에서 실행한다."""
from pathlib import Path
import os
import subprocess
import sys

from workspace_env import LOCAL_ENV_KEYS, load_local_env

ROOT = Path(__file__).resolve().parent.parent
APP_PORTS = {'config-server': 8888, 'eureka': 8761, 'gateway': 8080, 'user-service': 8081,
             'reservation-service': 8082, 'payment-service': 8083, 'mock-pg': 8090}
MANAGEMENT_PORTS = {'config-server': 19888, 'eureka': 18761, 'gateway': 18080, 'user-service': 18081,
                    'reservation-service': 18082, 'payment-service': 18083, 'mock-pg': 18090}


def build_app_environment(app, values, base_env=None):
    if app not in APP_PORTS:
        raise ValueError('지원하지 않는 앱입니다')
    env = dict(os.environ if base_env is None else base_env)
    for key in LOCAL_ENV_KEYS:
        env.pop(key, None)
    env.update({'SPRING_PROFILES_ACTIVE': 'native' if app == 'config-server' else 'local',
                'CONFIG_REPO_PATH': 'file:' + str(ROOT / 'config-repo'),
                'CONFIG_SERVER_URL': 'http://127.0.0.1:8888', 'EUREKA_URL': 'http://127.0.0.1:8761/eureka/',
                'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '6379',
                'KAFKA_BOOTSTRAP_SERVERS': '127.0.0.1:19092',
                'ZIPKIN_URL': 'http://127.0.0.1:9411/api/v2/spans',
                'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(APP_PORTS[app]),
                'MANAGEMENT_SERVER_ADDRESS': '127.0.0.1',
                'MANAGEMENT_PORT': str(MANAGEMENT_PORTS[app]),
                'MANAGEMENT_SERVER_PORT': str(MANAGEMENT_PORTS[app]),
                'EUREKA_INSTANCE_IPADDRESS': '127.0.0.1', 'EUREKA_INSTANCE_HOSTNAME': 'localhost',
                'EUREKA_INSTANCE_PREFERIPADDRESS': 'true'})
    keys = set()
    if app in {'user-service', 'reservation-service', 'payment-service'}:
        keys.update({'DB_USER', 'DB_PASSWORD'})
        db, port = {'user-service': ('user', 5433), 'reservation-service': ('reservation', 5434),
                    'payment-service': ('payment', 5435)}[app]
        env['DB_URL'] = f'jdbc:postgresql://127.0.0.1:{port}/{db}'
    if app in {'user-service', 'gateway'}:
        keys.add('JWT_ACCESS_SECRET')
    if app == 'user-service':
        keys.update({'JWT_REFRESH_SECRET', 'ADMIN_EMAIL', 'ADMIN_PASSWORD'})
    for key in keys:
        if not values.get(key):
            raise ValueError(f'필수 환경변수가 비었습니다: {key}')
        env[key] = values[key]
    return env


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in APP_PORTS:
        raise SystemExit('사용: python3 script/run-app.py <' + '|'.join(APP_PORTS) + '>')
    app = sys.argv[1]
    try:
        env = build_app_environment(app, load_local_env(ROOT / '.env'))
    except ValueError as error:
        raise SystemExit(str(error)) from None
    print(app + ' loopback 실행 (개인 환경변수 값은 출력하지 않는다)', flush=True)
    try:
        return subprocess.run([str(ROOT/'gradlew'), ':'+app+':bootRun', '--no-daemon'], cwd=ROOT, env=env).returncode
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
