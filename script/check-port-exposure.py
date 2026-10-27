#!/usr/bin/env python3
"""Compose가 호스트에 여는 포트를 검사한다.

서비스는 Gateway가 넣은 X-User-* 헤더를 서명 없이 믿는다(SA 7-2). 그래서 Gateway 외의 서비스 포트가
외부에서 닿으면 누구나 사용자를 위조할 수 있다. 이 검사는 그 전제(서비스 포트 비공개)가 깨지지 않게 한다.
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOOPBACK = '127.0.0.1'
# 기본 Compose에서 호스트에 열 수 있는 서비스: Gateway와 관리자 UI뿐이다.
PUBLISHABLE = {'gateway', 'grafana', 'kafka-ui', 'prometheus', 'zipkin'}


def violations(config, base_only):
    """Compose 설정(dict)에서 규칙을 어긴 포트를 문자열 목록으로 돌려준다."""
    found = []
    for name, service in config['services'].items():
        for port in service.get('ports') or []:
            where = f"{name}:{port['published']}"
            if port.get('host_ip') != LOOPBACK:
                found.append(f"{where}: 호스트 주소가 {port.get('host_ip')}이다. {LOOPBACK}만 허용한다")
            if base_only and name not in PUBLISHABLE:
                found.append(f"{where}: 기본 Compose에서 호스트에 열 수 없는 서비스다")
    return found


def load(files):
    command = ['docker', 'compose', '--env-file', '/dev/null']
    for file in files:
        command += ['-f', file]
    command += ['config', '--no-interpolate', '--format', 'json']
    output = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True).stdout
    return json.loads(output)


def main():
    found = []
    found += violations(load(['compose.yml']), base_only=True)
    found += violations(load(['compose.yml', 'compose.local.yml']), base_only=False)
    for message in found:
        print('포트 공개 위반:', message)
    if found:
        sys.exit(1)
    print('포트 공개 검사 통과: 기본 Compose는 Gateway·관리자 UI만 loopback에 열고, local override도 모두 loopback이다')


if __name__ == '__main__':
    main()
