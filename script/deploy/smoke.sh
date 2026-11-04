#!/usr/bin/env bash
# 배포 뒤 가벼운 스모크 테스트(ISE-174). 배포 서버 안에서 공개 진입점(nginx 80)을 거쳐 부른다.
#   script/deploy/smoke.sh [기준 주소, 기본 http://127.0.0.1]
# 확인: 화면(SPA)이 뜬다 → 공개 API(공연 목록)가 Gateway를 거쳐 200 → 인증이 필요한 API가 401(인증 필터가 동작).
# 가입·로그인처럼 데이터를 만드는 확인은 하지 않는다. 예매 한 흐름 전체(BookingFlowSystemTest)는 따로 돌린다.
#
# Note: 컨테이너가 healthy가 돼도 Gateway가 Eureka에서 새 서비스를 찾기까지 시간이 걸린다(2026-11-04 첫 배포에서
# reservation-service 기동 뒤 약 35초 동안 503). 그래서 항목마다 기대한 상태가 나올 때까지 SMOKE_WAIT_SECONDS(기본 120초)
# 동안 5초 간격으로 다시 확인한다. 그 안에 안 되면 실패다.
set -euo pipefail
base="${1:-http://127.0.0.1}"
wait_seconds="${SMOKE_WAIT_SECONDS:-120}"
failed=0

check() {
  local name="$1" expected="$2" url="$3" actual deadline
  deadline=$(( $(date +%s) + wait_seconds ))
  while :; do
    # curl은 연결에 실패해도 -w로 000을 찍고 0이 아닌 코드로 끝난다. 000을 한 번만 쓰게 한다.
    actual=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url") || true
    [[ -n "$actual" ]] || actual=000
    if [[ "$actual" == "$expected" ]]; then
      echo "  통과: $name ($actual)"
      return 0
    fi
    if (( $(date +%s) >= deadline )); then
      echo "  실패: $name (기대 $expected, 실제 $actual, ${wait_seconds}초 기다림) $url" >&2
      failed=1
      return 0
    fi
    sleep 5
  done
}

echo "== 스모크 테스트: $base (항목마다 최대 ${wait_seconds}초)"
check "화면" 200 "$base/"
check "SPA 새로고침(깊은 경로)" 200 "$base/concerts/1"
check "공연 목록(공개 API)" 200 "$base/api/v1/concerts"
check "내 정보(인증 필요)" 401 "$base/api/v1/users/me"
exit "$failed"
