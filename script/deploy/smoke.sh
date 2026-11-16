#!/usr/bin/env bash
# 배포 뒤 가벼운 스모크 테스트(ISE-174). 배포 서버 안에서 공개 진입점(nginx 80)을 거쳐 부른다.
#   script/deploy/smoke.sh [기준 주소, 기본 http://127.0.0.1]
# 확인: 화면(SPA)이 뜬다 → 공개 API(공연 목록)가 Gateway를 거쳐 200 → 인증이 필요한 API가 401(인증 필터가 동작).
# 배포 서버는 바깥 요청을 https(443)로 받으므로(ISE-278) 같은 서버의 https 화면과 Grafana(/grafana/)도 본다.
# 기준 주소 http://127.0.0.1은 서버 안 스크립트용 http 블록이라 바깥 사용자가 지나는 443을 거치지 않기 때문이다.
# 인증서는 공인 IP용이라 127.0.0.1로는 이름 확인을 하지 않는다(-k). https 확인을 끄려면 SMOKE_HTTPS_BASE=""(로컬 등).
# 가입·로그인처럼 데이터를 만드는 확인은 하지 않는다. 예매 한 흐름 전체(BookingFlowSystemTest)는 따로 돌린다.
#
# Note: 컨테이너가 healthy가 돼도 Gateway가 Eureka에서 새 서비스를 찾기까지 시간이 걸린다(2026-11-04 첫 배포에서
# reservation-service 기동 뒤 약 35초 동안 503). 그래서 항목마다 기대한 상태가 나올 때까지 SMOKE_WAIT_SECONDS(기본 120초)
# 동안 5초 간격으로 다시 확인한다. 그 안에 안 되면 실패다.
set -euo pipefail
base="${1:-http://127.0.0.1}"
https_base="${SMOKE_HTTPS_BASE-https://127.0.0.1}"
wait_seconds="${SMOKE_WAIT_SECONDS:-120}"
failed=0

check() {
  local name="$1" expected="$2" url="$3" actual deadline
  deadline=$(( $(date +%s) + wait_seconds ))
  while :; do
    # curl은 연결에 실패해도 -w로 000을 찍고 0이 아닌 코드로 끝난다. 000을 한 번만 쓰게 한다.
    actual=$(curl -sk -o /dev/null -w '%{http_code}' --max-time 10 "$url") || true
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
if [[ -n "$https_base" ]]; then
  # PUBLIC_URL을 잘못 적으면(끝 슬래시 등) Grafana 하위 경로가 어긋나 /grafana/api/health가 200이 아니다
  check "https 화면" 200 "$https_base/"
  check "https 공개 API" 200 "$https_base/api/v1/concerts"
  check "Grafana(/grafana/)" 200 "$https_base/grafana/api/health"
fi
exit "$failed"
