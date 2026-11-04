#!/usr/bin/env bash
# 배포 뒤 가벼운 스모크 테스트(ISE-174). 배포 서버 안에서 공개 진입점(nginx 80)을 거쳐 부른다.
#   script/deploy/smoke.sh [기준 주소, 기본 http://127.0.0.1]
# 확인: 화면(SPA)이 뜬다 → 공개 API(공연 목록)가 Gateway를 거쳐 200 → 인증이 필요한 API가 401(인증 필터가 동작).
# 가입·로그인처럼 데이터를 만드는 확인은 하지 않는다. 예매 한 흐름 전체(BookingFlowSystemTest)는 Jenkins에서 고를 때만 돈다.
set -euo pipefail
base="${1:-http://127.0.0.1}"
failed=0

check() {
  local name="$1" expected="$2" url="$3" actual
  actual=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" || echo "000")
  if [[ "$actual" == "$expected" ]]; then
    echo "  통과: $name ($actual)"
  else
    echo "  실패: $name (기대 $expected, 실제 $actual) $url" >&2
    failed=1
  fi
}

echo "== 스모크 테스트: $base"
check "화면" 200 "$base/"
check "SPA 새로고침(깊은 경로)" 200 "$base/concerts/1"
check "공연 목록(공개 API)" 200 "$base/api/v1/concerts"
check "내 정보(인증 필요)" 401 "$base/api/v1/users/me"
exit "$failed"
