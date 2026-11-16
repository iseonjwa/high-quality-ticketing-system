#!/usr/bin/env bash
# 배포 서버에서 오픈 피크 경로를 미리 실행해 JIT 컴파일을 끝내 둔다(ISE-264). 측정이 아니라 준비 작업이다.
#   script/deploy/warmup.sh [기준 주소, 기본 http://127.0.0.1]
# deploy.sh가 배포 성공 뒤 부르고(실패해도 배포는 성공), 측정 모드에서는 measure-mode.sh on 뒤에 사람이 직접 부른다.
#
# 하는 일: k6 시나리오 loadtest/k6/scenarios/w1-warmup.js를 서버 안에서 돌린다. 예열 전용 공연 "[예열 전용] 배포 JIT 예열"의
# 회차에서 공연 상세 → 대기열 진입·순번 → 좌석 조회(일부) → 선점 → 결제(pgProvider MOCK) → 확정 확인 → 취소를 반복한다.
# 취소가 좌석·매수를 돌려주고 Mock PG로 환불하므로 운영 공연에는 아무것도 남지 않고, 예열 회차의 좌석도 거의 줄지 않는다.
#
# 환경 변수:
#   WARMUP_RATE(기본 25), WARMUP_SECONDS(기본 120)   초당 반복 수와 시간. 반복 하나가 위 경로를 한 번 돈다
#   WARMUP_TIMEOUT_SECONDS(기본 420)                  k6가 이 시간 안에 끝나지 않으면 멈춘다(배포가 예열에 묶이지 않게)
#   WARMUP_USER_ID_BASE(기본 900000000)               예열 사용자 ID 시작값. 운영 사용자·측정 사용자(1000000~)와 겹치지 않게 한다
#   DRY_RUN=1                                         k6를 돌리지 않고 부를 명령만 출력한다(키 값은 출력하지 않는다)
# 서명 키: 측정 모드(.deploy/measure-mode가 있음)이면 .env.measure의 측정 전용 키를, 아니면 .env의 키를 쓴다(run.sh가 읽는다).
#   deploy.sh는 측정 모드를 풀고 .env로 다시 띄우므로 WARMUP_FROM_DEPLOY=1을 줘 늘 .env 키를 쓰게 한다.
# 종료 코드: k6의 종료 코드(0 성공). 부르는 쪽이 실패를 경고로만 다룬다.
set -euo pipefail
cd "$(dirname "$0")/../.."
base="${1:-http://127.0.0.1}"
dry="${DRY_RUN:-0}"
mkdir -p .deploy

# .env는 source하지 않고 키별로 꺼낸다(seed-demo.sh와 같다)
env_value() {
  local line
  line=$(grep -m1 "^$2=" "$1" 2> /dev/null || true)
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  printf '%s' "$line"
}

if [[ -f .deploy/measure-mode && -f .env.measure && "${WARMUP_FROM_DEPLOY:-0}" != 1 ]]; then
  # run.sh는 이미 있는 환경변수를 .env보다 앞에 둔다. 측정 모드 스택은 이 키로 서명한 토큰만 받는다
  export JWT_ACCESS_SECRET ENTRY_TOKEN_SECRET
  JWT_ACCESS_SECRET="$(env_value .env.measure JWT_ACCESS_SECRET)"
  ENTRY_TOKEN_SECRET="$(env_value .env.measure ENTRY_TOKEN_SECRET)"
  keys="측정 전용 키(.env.measure)"
else
  keys="운영 키(.env)"
fi

# 예열 회차를 새로 만들 때 쓸 공연장. 조회 API가 없어 예약 DB에서 이름으로 찾는다(seed-demo.sh와 같다). 못 찾으면 k6 기본값 1
if [[ -z "${VENUE_ID:-}" && "$dry" != 1 ]]; then
  container=$(docker ps -q --filter label=com.docker.compose.service=postgres-reservation | head -1 || true)
  if [[ -n "$container" ]]; then
    db_user=$(env_value .env DB_USER); db_user="${db_user:-iseonjwa}"
    VENUE_ID=$(docker exec "$container" psql -U "$db_user" -d reservation -tAc \
      "SELECT id FROM venues WHERE name = 'KSPO DOME' ORDER BY id LIMIT 1" 2> /dev/null | tr -d '[:space:]' || true)
  fi
fi
[[ "${VENUE_ID:-}" =~ ^[0-9]+$ ]] && export VENUE_ID || unset VENUE_ID

export BASE_URL="$base" K6_DOCKER_NETWORK=host K6_RAW_CSV=0
export RATE="${WARMUP_RATE:-25}" DURATION_SECONDS="${WARMUP_SECONDS:-120}"
export USER_ID_BASE="${WARMUP_USER_ID_BASE:-900000000}"
timeout_seconds="${WARMUP_TIMEOUT_SECONDS:-420}"

echo "== JIT 예열: $base, 초당 ${RATE}번 × ${DURATION_SECONDS}초, 서명 키 $keys, 공연장 ${VENUE_ID:-기본}, 상한 ${timeout_seconds}초"
if [[ "$dry" == 1 ]]; then
  echo "  (DRY_RUN) timeout ${timeout_seconds} bash loadtest/k6/run.sh scenarios/w1-warmup.js"
  exit 0
fi

# Note: docker run은 터미널 없이 돌 때 받은 신호를 컨테이너에 넘긴다(기본 --sig-proxy). timeout이 보낸 SIGTERM으로 k6도 멈춘다
status=0
timeout --kill-after=30 "$timeout_seconds" bash loadtest/k6/run.sh scenarios/w1-warmup.js > .deploy/warmup-last.txt 2>&1 || status=$?
tail -n 40 .deploy/warmup-last.txt | grep -E 'W1 |warmup_results|http_req_failed|iterations|level=error' || true

# 예열 결과는 마지막 몇 번만 남긴다(배포마다 쌓이지 않게)
ls -1t loadtest/k6/results/w1-warmup-* 2> /dev/null | tail -n +11 | xargs -r rm -f
echo "== JIT 예열 끝(종료 코드 $status). 전체 출력: .deploy/warmup-last.txt"
exit "$status"
