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
SUCCESS_KEYS = {'status', 'message', 'data', 'traceId'}
FAILURE_KEYS = {'status', 'code', 'message', 'traceId'}
passed = 0


def http(method, url, body=None, token=None, headers=None):
    headers = dict(headers or {})
    headers['Content-Type'] = 'application/json'
    headers.setdefault('traceparent', f'00-{uuid.uuid4().hex}-{secrets.token_hex(8)}-01')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    request = Request(url, data=data, headers=headers, method=method)
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
    """Gateway 응답의 상태와 SA 11-2의 성공·실패 본문 형태를 함께 검사한다."""
    global passed
    status, result = http(method, BASE + path, body, token, headers)
    assert status == expected, f'{label}: HTTP {status}, 기대 {expected}'
    assert isinstance(result, dict), f'{label}: JSON 본문이 아님'
    if 200 <= expected < 300:
        expected_keys = SUCCESS_KEYS
    elif expected == 400 and 'errors' in result:
        expected_keys = FAILURE_KEYS | {'errors'}  # errors는 검증 실패일 때만 있다
    else:
        expected_keys = FAILURE_KEYS
    assert set(result) == expected_keys, f'{label}: 응답 키 불일치 {sorted(result)}'
    assert result['status'] == expected, f'{label}: HTTP와 본문 상태 불일치'
    passed += 1
    print(f'통과 {passed}: {label}')
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
    """검증 규칙을 시험하기 위해 임의로 서명한 Access 토큰을 만든다."""
    now = int(time.time())
    claims = {
        'sub': '99999999', 'jti': str(uuid.uuid4()), 'role': 'USER', 'iss': 'iseonjwa-auth',
        'aud': ['iseonjwa-api'], 'token_type': 'access', 'iat': now - 5, 'exp': now + 1800,
    }
    claims.update(overrides)

    def encode(value):
        raw = json.dumps(value, separators=(',', ':')).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b'=')

    signing_input = encode({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + encode(claims)
    signature = hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
    return (signing_input + b'.' + base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()


def check_signup_and_login(email, password):
    created = check('회원가입', 'POST', '/api/v1/users', 201,
                    {'email': email, 'password': password, 'nickname': '연결검증'})['data']
    check('중복 이메일 거절', 'POST', '/api/v1/users', 409,
          {'email': email, 'password': password, 'nickname': '중복'})
    check('잘못된 비밀번호 거절', 'POST', '/api/v1/auth/login', 401, {'email': email, 'password': 'wrong'})
    pair = check('로그인', 'POST', '/api/v1/auth/login', 200, {'email': email, 'password': password})['data']
    return created, pair


def check_identity_forwarding(created, access):
    trace_id = uuid.uuid4().hex
    spoofed = {
        'X-User-Id': '99999999', 'X-User-Role': 'ADMIN',
        'traceparent': f'00-{trace_id}-{secrets.token_hex(8)}-01',
    }
    me = check('내 정보·위조 헤더 제거', 'GET', '/api/v1/users/me', 200, token=access, headers=spoofed)['data']
    assert me['userId'] == created['userId'] and me['role'] == 'USER'

    for service, path in [('Reservation', 'reservations'), ('Payment', 'payments')]:
        identity_path = f'/api/v1/{path}/_system/identity'
        wait_until(service + ' 서비스 탐색', lambda: http('GET', BASE + identity_path, token=access)[0] == 200)
        identity = check(service + ' 라우팅·사용자 전달', 'GET', identity_path, 200, token=access,
                         headers={'X-User-Id': '99999999', 'X-User-Role': 'ADMIN'})['data']
        assert identity['userId'] == created['userId'] and identity['role'] == 'USER'
    return trace_id


def check_admin_path_protection(access):
    check('인증 누락', 'GET', '/api/v1/users/me', 401)
    check('USER 관리자 접근 차단', 'POST', '/api/v1/admin/concerts', 403, {}, access)
    variants = ['/api/v1/admin', '/api/v1/admin;x=1/review-probe',
                '/api/v1/%61dmin/review-probe', '/api/%76%31/admin/review-probe']
    for path in variants:
        check('관리자 경로 변형 차단 ' + path, 'GET', path, 403, token=access)


def check_password_byte_limit():
    for label, valid in [('ASCII', 'a' * 72), ('UTF8', '가' * 24)]:
        email = f'boundary-{uuid.uuid4()}@test.local'
        check(label + ' 72바이트 가입', 'POST', '/api/v1/users', 201,
              {'email': email, 'password': valid, 'nickname': '바이트검증'})
        check(label + ' 72바이트 로그인', 'POST', '/api/v1/auth/login', 200, {'email': email, 'password': valid})
        check(label + ' 73바이트 거절', 'POST', '/api/v1/auth/login', 400, {'email': email, 'password': valid + 'a'})


def check_token_validation(pair):
    secret = ENV['JWT_ACCESS_SECRET']
    invalid_tokens = [
        ('만료', forge(secret, exp=int(time.time()) - 120)),
        ('다른 서명', forge('invalid-signing-key-that-is-over-32-bytes')),
        ('다른 발급자', forge(secret, iss='other')),
        ('다른 대상', forge(secret, aud=['other'])),
        ('다른 토큰 종류', forge(secret, token_type='refresh')),
        ('Refresh를 Access로 혼용', pair['refreshToken']),
    ]
    for name, token in invalid_tokens:
        check(name + ' 거절', 'GET', '/api/v1/users/me', 401, token=token)


def check_refresh_rotation(pair):
    check('Access를 Refresh로 혼용', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': pair['accessToken']})
    rotated = check('Refresh 회전', 'POST', '/api/v1/auth/refresh', 200,
                    {'refreshToken': pair['refreshToken']})['data']
    check('이전 Refresh 재사용 거절', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': pair['refreshToken']})
    return rotated


def check_lockout_and_admin_reset(password, user_access):
    admin = check('운영자 시드 로그인', 'POST', '/api/v1/auth/login', 200,
                  {'email': ENV['ADMIN_EMAIL'], 'password': ENV['ADMIN_PASSWORD']})['data']
    admin_identity = check('ADMIN 역할 전달', 'GET', '/api/v1/reservations/_system/identity', 200,
                           token=admin['accessToken'])['data']
    assert admin_identity['role'] == 'ADMIN'

    email = f'locked-{uuid.uuid4()}@test.local'
    locked = check('잠금 대상 가입', 'POST', '/api/v1/users', 201,
                   {'email': email, 'password': password, 'nickname': '잠금검증'})['data']
    for attempt in range(1, 5):
        check(f'로그인 실패 {attempt}회', 'POST', '/api/v1/auth/login', 401, {'email': email, 'password': 'wrong'})
    check('로그인 5회 실패 시 잠금', 'POST', '/api/v1/auth/login', 423, {'email': email, 'password': 'wrong'})
    check('잠긴 계정은 올바른 비밀번호도 거절', 'POST', '/api/v1/auth/login', 423,
          {'email': email, 'password': password})

    reset_path = f"/api/v1/admin/users/{locked['userId']}/password-reset"
    check('USER 비밀번호 초기화 차단', 'POST', reset_path, 403, {}, user_access)
    reset = check('관리자 비밀번호 초기화', 'POST', reset_path, 200, {}, admin['accessToken'])
    temporary = reset['data']['temporaryPassword']
    check('임시 비밀번호는 변경 전 로그인 불가', 'POST', '/api/v1/auth/login', 403,
          {'email': email, 'password': temporary})
    new_password = password + '-new'
    check('비밀번호 변경', 'POST', '/api/v1/auth/password-change', 200,
          {'email': email, 'currentPassword': temporary, 'newPassword': new_password})
    check('변경한 비밀번호로 로그인', 'POST', '/api/v1/auth/login', 200, {'email': email, 'password': new_password})


def check_kafka_flow(access):
    event = check('Reservation → Kafka 발행', 'POST', '/api/v1/reservations/_system/events', 202,
                  {}, access)['data']['eventId']
    event_path = '/api/v1/payments/_system/events/' + event
    wait_until('Kafka → Payment 소비', lambda: http('GET', BASE + event_path, token=access)[0] == 200)
    check('Payment 이벤트 결과 조회', 'GET', event_path, 200, token=access)


def check_logout(access, refresh_token):
    check('로그아웃', 'POST', '/api/v1/auth/logout', 200, {}, access)
    check('로그아웃 Access 거절', 'GET', '/api/v1/users/me', 401, token=access)
    check('로그아웃 Refresh 거절', 'POST', '/api/v1/auth/refresh', 401, {'refreshToken': refresh_token})


def check_internal_paths_hidden():
    for path in ['/actuator/prometheus', '/eureka/apps', '/config-server/default', '/mock-pg/payments']:
        check('관리·내부 경로 비공개 ' + path, 'GET', path, 404)


def check_config_server():
    secrets_in_env = [value for key, value in ENV.items() if ('SECRET' in key or 'PASSWORD' in key) and value]
    for app in ['user-service', 'reservation-service', 'payment-service', 'gateway', 'mock-pg', 'eureka']:
        status, config = http('GET', f'http://127.0.0.1:8888/{app}/local')
        assert status == 200 and config['propertySources'], f'{app} Config 조회 실패'
        serialized = json.dumps(config)
        assert all(value not in serialized for value in secrets_in_env), '설정 응답의 비밀값 노출'
    print('확인: Config 공급·비밀값 비노출')


def check_eureka():
    status, registry = http('GET', 'http://127.0.0.1:8761/eureka/apps', headers={'Accept': 'application/json'})
    names = {app['name'] for app in registry['applications']['application']}
    assert {'GATEWAY', 'USER-SERVICE', 'RESERVATION-SERVICE', 'PAYMENT-SERVICE', 'MOCK-PG'} <= names
    print('확인: Eureka 앱 5개 등록')


def check_observability(trace_id):
    def seven_targets_up():
        targets = http('GET', 'http://127.0.0.1:9090/api/v1/targets')[1]['data']['activeTargets']
        return len([target for target in targets if target['health'] == 'up']) == 7

    wait_until('Prometheus 앱 7개 수집', seven_targets_up)

    credentials = base64.b64encode(('admin:' + ENV['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    dashboard_url = 'http://127.0.0.1:3000/api/dashboards/uid/iseonjwa-bootstrap'
    assert http('GET', dashboard_url, headers={'Authorization': 'Basic ' + credentials})[0] == 200
    print('확인: Grafana 대시보드 로드')

    status, clusters = http('GET', 'http://127.0.0.1:8085/api/clusters')
    assert status == 200 and any(c['name'] == 'iseonjwa' and c['status'] == 'ONLINE' for c in clusters)
    print('확인: Kafka-UI 클러스터 ONLINE')

    def same_trace():
        status, spans = http('GET', 'http://127.0.0.1:9411/api/v2/trace/' + trace_id)
        services = {span.get('localEndpoint', {}).get('serviceName') for span in spans or []}
        return status == 200 and {'gateway', 'user-service'} <= services

    wait_until('Zipkin Gateway → User 동일 trace', same_trace)


def check_redis_failure(user_id, email, password):
    token = check('Redis 장애 검증용 로그인', 'POST', '/api/v1/auth/login', 200,
                  {'email': email, 'password': password})['data']['accessToken']
    subprocess.run([*COMPOSE, 'pause', 'redis'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    try:
        check('Redis 지연 시 보호 API 503', 'GET', '/api/v1/users/me', 503, token=token)
        check('Redis 장애 중 로그인 실패', 'POST', '/api/v1/auth/login', 503, {'email': email, 'password': password})
        # Gateway를 거치지 않고 User 서비스의 로그아웃을 직접 호출한다.
        status, _ = http('POST', 'http://127.0.0.1:8081/api/v1/auth/logout', {}, token,
                         {'X-User-Id': str(user_id), 'X-User-Role': 'USER'})
        assert status == 503, 'Redis 장애 중 로그아웃을 성공으로 처리함'
        print('확인: Redis 장애 중 로그아웃 503')
    finally:
        subprocess.run([*COMPOSE, 'unpause', 'redis'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)

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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--redis-failure', action='store_true',
                        help='이 스택의 Redis만 잠깐 pause하고 반드시 복구한다')
    args = parser.parse_args()

    wait_until('Gateway → User 준비', lambda: http('POST', BASE + '/api/v1/users', {})[0] == 400)
    email = f'smoke-{uuid.uuid4()}@test.local'
    password = secrets.token_hex(12)

    created, pair = check_signup_and_login(email, password)
    trace_id = check_identity_forwarding(created, pair['accessToken'])
    check_admin_path_protection(pair['accessToken'])
    check_password_byte_limit()
    check_token_validation(pair)
    rotated = check_refresh_rotation(pair)
    check_lockout_and_admin_reset(password, rotated['accessToken'])
    check_kafka_flow(rotated['accessToken'])
    check_logout(rotated['accessToken'], rotated['refreshToken'])
    check_internal_paths_hidden()
    check_config_server()
    check_eureka()
    check_observability(trace_id)
    if args.redis_failure:
        check_redis_failure(created['userId'], email, password)
    print(f'스모크 통과: HTTP 계약 {passed}건 및 Config·Eureka·Kafka·관측성 확인')


if __name__ == '__main__':
    main()
