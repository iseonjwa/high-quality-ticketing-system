#!/usr/bin/env python3
"""준비된 Compose 인프라 위에서 JVM 앱 7개의 바인딩·탐색·HTTP·관측을 검사한다."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.request import Request, urlopen

from workspace_env import load_local_env

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('run_app', ROOT / 'script/run-app.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def request(url):
    with urlopen(Request(url, headers={'Accept': 'application/json'}), timeout=3) as response:
        return json.load(response)


def wait_ready(app, proc):
    deadline = time.monotonic() + 90
    url = f'http://127.0.0.1:{runner.MANAGEMENT_PORTS[app]}/actuator/health/readiness'
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f'{app} 조기 종료. build/jvm-security-check의 로그를 확인하세요')
        try:
            if request(url)['status'] == 'UP':
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f'{app} readiness 제한 시간 초과')


def main():
    ports = [*runner.APP_PORTS.values(), *runner.MANAGEMENT_PORTS.values()]
    for port in ports:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError(f'{port} 사용 중. 이 프로젝트의 Compose 앱 7개를 먼저 정지하세요')
    for app in runner.APP_PORTS:
        if not (ROOT / app / 'build/libs/app.jar').is_file():
            raise RuntimeError('먼저 ./gradlew bootJar를 실행하세요')
    values = load_local_env(ROOT / '.env')
    processes = {}
    streams = []
    log_dir = ROOT / 'build/jvm-security-check'
    log_dir.mkdir(parents=True, exist_ok=True)
    try:
        for app in runner.APP_PORTS:
            path = log_dir / (app + '.log')
            stream = os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w')
            streams.append(stream)
            proc = subprocess.Popen(['java', '-Xms32m', '-Xmx192m', '-jar', str(ROOT/app/'build/libs/app.jar')],
                                    cwd=ROOT, env=runner.build_app_environment(app, values), stdout=stream,
                                    stderr=subprocess.STDOUT, start_new_session=True)
            processes[app] = proc
            if app in {'config-server', 'eureka'}:
                wait_ready(app, proc)
        for app, proc in processes.items():
            wait_ready(app, proc)
            output = subprocess.check_output(['lsof', '-nP', '-a', '-p', str(proc.pid),
                                               '-iTCP', '-sTCP:LISTEN'], text=True)
            addresses = {line.split('TCP ', 1)[1].split()[0] for line in output.splitlines() if 'TCP ' in line}
            expected = {f'127.0.0.1:{runner.APP_PORTS[app]}', f'127.0.0.1:{runner.MANAGEMENT_PORTS[app]}'}
            assert addresses == expected, f'{app}의 실제 바인딩이 loopback이 아닙니다'
            print('확인:', app, '업무·관리 소켓 loopback', flush=True)
        deadline = time.monotonic() + 40
        required = {'GATEWAY', 'USER-SERVICE', 'RESERVATION-SERVICE', 'PAYMENT-SERVICE', 'MOCK-PG'}
        while True:
            apps = request('http://127.0.0.1:8761/eureka/apps')['applications'].get('application', [])
            if required <= {app['name'] for app in apps}:
                assert all(instance['ipAddr'] == '127.0.0.1' for app in apps for instance in app['instance']), 'Eureka 등록 IP 불일치'
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('Eureka 앱 등록 제한 시간 초과')
            time.sleep(1)
        print('확인: Eureka 앱 5개 loopback IP 등록', flush=True)
        # 별도 JVM에서 동일 스모크를 실행해 환경변수 파서·HTTP·Kafka·Prometheus 경로도 검사한다.
        subprocess.run(['python3', '-u', str(ROOT/'script/smoke-workspace.py')], cwd=ROOT, check=True)
    finally:
        for proc in reversed(list(processes.values())):
            if proc.poll() is None:
                proc.terminate()
        deadline = time.monotonic() + 20
        for proc in processes.values():
            try:
                proc.wait(timeout=max(0.1, deadline-time.monotonic()))
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        for stream in streams:
            stream.close()
    print('JVM 보안·라우팅·관측성 검사 통과, 검사 JVM 모두 종료', flush=True)


if __name__ == '__main__':
    main()
