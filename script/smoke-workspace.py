#!/usr/bin/env python3
"""local override로 실행한 스택의 실제 연결을 검사한다. 토큰과 비밀번호는 출력하지 않는다."""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import secrets
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

from workspace_env import load_local_env

ROOT = Path(__file__).resolve().parent.parent
ENV = load_local_env(ROOT / '.env')
COMPOSE = ['docker', 'compose', '-f', 'compose.yml', '-f', 'compose.local.yml']
BASE = 'http://127.0.0.1:8080'
COUNT = 0


def http(method, url, body=None, token=None, headers=None):
    headers = dict(headers or {})
    headers['Content-Type'] = 'application/json'
    headers.setdefault('traceparent', f'00-{uuid.uuid4().hex}-{secrets.token_hex(8)}-01')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        result = urlopen(request, timeout=8)
    except HTTPError as error:
        result = error
    content = result.read()
    try:
        value = json.loads(content)
    except (ValueError, UnicodeDecodeError):
        value = None
    return result.status, value


def check(label, method, path, expected, body=None, token=None, headers=None):
    global COUNT
    status, result = http(method, BASE + path, body, token, headers)
    assert status == expected, f'{label}: HTTP {status}, 기대 {expected}'
    assert isinstance(result, dict) and set(result) == {'status', 'code', 'message', 'data', 'errors', 'traceId'}, f'{label}: 응답 6키 불일치'
    assert result['status'] == expected, f'{label}: HTTP와 본문 상태 불일치'
    COUNT += 1
    print(f'통과 {COUNT}: {label}')
    return result


def wait_until(label, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                print('확인:', label)
                return
        except (OSError, ValueError, KeyError, TypeError):
            pass
        time.sleep(1)
    raise AssertionError(label + ': 제한 시간 초과')


def forge(secret, **overrides):
    claims = {'sub': '99999999', 'jti': str(uuid.uuid4()), 'role': 'USER', 'iss': 'iseonjwa-auth',
              'aud': ['iseonjwa-api'], 'token_type': 'access', 'iat': int(time.time()) - 5, 'exp': int(time.time()) + 1800}
    claims.update(overrides)
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    raw = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode(claims)
    return (raw + b'.' + base64.urlsafe_b64encode(hmac.new(secret.encode(), raw, hashlib.sha256).digest()).rstrip(b'=')).decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--redis-failure', action='store_true', help='이 스택의 Redis만 잠깐 pause하고 반드시 복구한다')
    args = parser.parse_args()
    wait_until('Gateway → User 준비', lambda: http('POST', BASE + '/api/v1/users', {})[0] == 400)
    email = f'smoke-{uuid.uuid4()}@test.local'
    password = secrets.token_hex(12)
    created = check('회원가입', 'POST', '/api/v1/users', 201, {'email': email, 'password': password, 'nickname': '연결검증'})['data']
    check('중복 이메일 거절', 'POST', '/api/v1/users', 409, {'email': email, 'password': password, 'nickname': '중복'})
    check('잘못된 비밀번호 거절', 'POST', '/api/v1/auth/login', 401, {'email': email, 'password': 'wrong'})
    pair = check('로그인', 'POST', '/api/v1/auth/login', 200, {'email': email, 'password': password})['data']
    access = pair['accessToken']
    trace_id = uuid.uuid4().hex
    me = check('내 정보·위조 헤더 제거', 'GET', '/api/v1/users/me', 200, token=access,
               headers={'X-User-Id': '99999999', 'X-User-Role': 'ADMIN', 'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01'})['data']
    assert me['userId'] == created['userId'] and me['role'] == 'USER'
    for service, path in [('Reservation', 'reservations'), ('Payment', 'payments')]:
        wait_until(service + ' 서비스 탐색',
                   lambda: http('GET', BASE + f'/api/v1/{path}/_system/identity', token=access)[0] == 200)
        identity = check(service + ' 라우팅·사용자 전달', 'GET', f'/api/v1/{path}/_system/identity', 200,
                         token=access, headers={'X-User-Id': '99999999', 'X-User-Role': 'ADMIN'})['data']
        assert identity['userId'] == created['userId'] and identity['role'] == 'USER'
    check('인증 누락', 'GET', '/api/v1/users/me', 401)
    check('USER 관리자 접근 차단', 'POST', '/api/v1/admin/concerts', 403, {}, access)
    for path in ['/api/v1/admin', '/api/v1/admin;x=1/review-probe',
                 '/api/v1/%61dmin/review-probe', '/api/%76%31/admin/review-probe']:
        check('관리자 경로 변형 차단 ' + path, 'GET', path, 403, token=access)
    for label, valid in [('ASCII', 'a' * 72), ('UTF8', '가' * 24)]:
        boundary_email = f'boundary-{uuid.uuid4()}@test.local'
        check(label + ' 72바이트 가입', 'POST', '/api/v1/users', 201,
              {'email': boundary_email, 'password': valid, 'nickname': '바이트검증'})
        check(label + ' 72바이트 로그인', 'POST', '/api/v1/auth/login', 200,
              {'email': boundary_email, 'password': valid})
        check(label + ' 73바이트 거절', 'POST', '/api/v1/auth/login', 400,
              {'email': boundary_email, 'password': valid + 'a'})
    for name, token in [('만료', forge(ENV['JWT_ACCESS_SECRET'], exp=int(time.time())-120)),
                        ('다른 서명', forge('invalid-signing-key-that-is-over-32-bytes')),
                        ('다른 발급자', forge(ENV['JWT_ACCESS_SECRET'], iss='other')),
                        ('다른 대상', forge(ENV['JWT_ACCESS_SECRET'], aud=['other'])),
                        ('다른 토큰 종류', forge(ENV['JWT_ACCESS_SECRET'], token_type='refresh')),
                        ('Refresh를 Access로 혼용', pair['refreshToken'])]:
        check(name + ' 거절', 'GET', '/api/v1/users/me', 401, token=token)
    check('Access를 Refresh로 혼용', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': access})
    rotated = check('Refresh 회전', 'POST', '/api/v1/auth/refresh', 200, {'refreshToken': pair['refreshToken']})['data']
    check('이전 Refresh 재사용 거절', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': pair['refreshToken']})
    access = rotated['accessToken']
    admin = check('운영자 시드 로그인', 'POST', '/api/v1/auth/login', 200,
                  {'email': ENV['ADMIN_EMAIL'], 'password': ENV['ADMIN_PASSWORD']})['data']
    admin_identity = check('ADMIN 역할 전달', 'GET', '/api/v1/reservations/_system/identity', 200, token=admin['accessToken'])['data']
    assert admin_identity['role'] == 'ADMIN'
    event = check('Reservation → Kafka 발행', 'POST', '/api/v1/reservations/_system/events', 202, {}, access)['data']['eventId']
    wait_until('Kafka → Payment 소비', lambda: http('GET', BASE + '/api/v1/payments/_system/events/' + event, token=access)[0] == 200)
    check('Payment 이벤트 결과 조회', 'GET', '/api/v1/payments/_system/events/' + event, 200, token=access)
    check('로그아웃', 'POST', '/api/v1/auth/logout', 200, {}, access)
    check('로그아웃 Access 거절', 'GET', '/api/v1/users/me', 401, token=access)
    check('로그아웃 Refresh 거절', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': rotated['refreshToken']})
    for path in ['/actuator/prometheus', '/eureka/apps', '/config-server/default', '/mock-pg/payments']:
        check('관리·내부 경로 비공개 ' + path, 'GET', path, 404)
    for app in ['user-service', 'reservation-service', 'payment-service', 'gateway', 'mock-pg', 'eureka']:
        status, config = http('GET', f'http://127.0.0.1:8888/{app}/local')
        assert status == 200 and config['propertySources'], f'{app} Config 조회 실패'
        serialized = json.dumps(config)
        assert all(value not in serialized for key,value in ENV.items() if ('SECRET' in key or 'PASSWORD' in key) and value), '설정 응답의 비밀값 노출'
    print('확인: Config 공급·비밀값 비노출')
    status, registry = http('GET', 'http://127.0.0.1:8761/eureka/apps', headers={'Accept': 'application/json'})
    names = {app['name'] for app in registry['applications']['application']}
    assert {'GATEWAY','USER-SERVICE','RESERVATION-SERVICE','PAYMENT-SERVICE','MOCK-PG'} <= names
    print('확인: Eureka 앱 5개 등록')
    wait_until('Prometheus 앱 7개 수집', lambda: len([target for target in http('GET','http://127.0.0.1:9090/api/v1/targets')[1]['data']['activeTargets'] if target['health']=='up']) == 7)
    basic = base64.b64encode(('admin:' + ENV['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    assert http('GET', 'http://127.0.0.1:3000/api/dashboards/uid/iseonjwa-bootstrap', headers={'Authorization': 'Basic '+basic})[0] == 200
    print('확인: Grafana 대시보드 로드')
    status, clusters = http('GET', 'http://127.0.0.1:8085/api/clusters')
    assert status == 200 and any(cluster['name'] == 'iseonjwa' and cluster['status'] == 'ONLINE' for cluster in clusters)
    print('확인: Kafka-UI 클러스터 ONLINE')
    def traced():
        status, spans = http('GET', 'http://127.0.0.1:9411/api/v2/trace/' + trace_id)
        names = {span.get('localEndpoint',{}).get('serviceName') for span in spans or []}
        return status == 200 and {'gateway','user-service'} <= names
    wait_until('Zipkin Gateway → User 동일 trace', traced)
    if args.redis_failure:
        token = check('Redis 장애 검증용 로그인', 'POST', '/api/v1/auth/login', 200, {'email': email, 'password': password})['data']['accessToken']
        subprocess.run([*COMPOSE,'pause','redis'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        try:
            check('Redis 지연 시 보호 API 503', 'GET', '/api/v1/users/me', 503, token=token)
            check('Redis 장애 중 로그인 실패', 'POST', '/api/v1/auth/login', 503, {'email': email, 'password': password})
            status, _ = http('POST', 'http://127.0.0.1:8081/api/v1/auth/logout', {}, token,
                             {'X-User-Id': str(created['userId']), 'X-User-Role': 'USER'})
            assert status == 503, 'Redis 장애 중 로그아웃을 성공으로 처리함'
            print('확인: Redis 장애 중 로그아웃 503')
        finally:
            subprocess.run([*COMPOSE,'unpause','redis'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        recovered = {}
        def login_recovered():
            status, response = http('POST', BASE + '/api/v1/auth/login', {'email': email, 'password': password})
            if status != 200:
                return False
            recovered['access'] = response['data']['accessToken']
            return True
        wait_until('Redis 복구 후 새 로그인', login_recovered, timeout=35)
        wait_until('Redis 복구 후 새 Access 인증',
                   lambda: http('GET', BASE + '/api/v1/users/me', token=recovered['access'])[0] == 200, timeout=35)
    print(f'스모크 통과: HTTP 계약 {COUNT}건 및 Config·Eureka·Kafka·관측성 확인')

if __name__ == '__main__':
    main()
