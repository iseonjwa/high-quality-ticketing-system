#!/usr/bin/env bash
# k6를 설치하지 않고 Docker 이미지로 시나리오를 돌린다. 사용법은 같은 폴더 README.md
# 예: bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
# 배포 서버를 재면 BASE_URL, TOKEN_MODE, PROM_RW_URL 등을 넘긴다(README.md "배포 서버 측정")
set -euo pipefail

K6_IMAGE="${K6_IMAGE:-grafana/k6:1.0.0}"
K6_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${1:?시나리오 경로가 필요합니다. 예: scenarios/q1-queue-enter.js}"
[ -f "$K6_DIR/$SCRIPT" ] || { echo "$K6_DIR/$SCRIPT 가 없습니다" >&2; exit 1; }

# 서명 키와 관리자 계정은 저장소 루트 .env에서 읽어 환경변수로만 넘기고 출력하지 않는다.
# 이미 환경변수로 준 값이 있으면 그것을 쓴다(배포 측정에서 .env 없이 넘길 때)
ENV_FILE="$K6_DIR/../../.env"
for key in JWT_ACCESS_SECRET ENTRY_TOKEN_SECRET ADMIN_EMAIL ADMIN_PASSWORD; do
  if [ -z "${!key:-}" ] && [ -f "$ENV_FILE" ]; then
    value="$(grep -E "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
    if [ -n "$value" ]; then export "$key=$value"; fi
  fi
done

mkdir -p "$K6_DIR/results"
NAME="$(basename "${SCRIPT%.js}")-$(date +%Y%m%d-%H%M%S)"

# k6에 넘길 변수. 새 시나리오가 변수를 더하면 이 목록에도 더한다
PASS_ENV=(BASE_URL TOKEN_MODE USER_ID_BASE JWT_ACCESS_SECRET ENTRY_TOKEN_SECRET ADMIN_EMAIL ADMIN_PASSWORD
  SCHEDULE_ID LOTTERY_ID SALE_SCHEDULE_ID USERS ENTER_SECONDS DUP_RATIO REPEAT OPEN_IN_SECONDS PRE_VUS MAX_VUS
  DURATION_SECONDS VUS FIRST_POLL_SPREAD_SECONDS STAGES STAGE_SECONDS
  CONCERTS SCHEDULES_PER_CONCERT DETAIL_RATIO ENTER_RATE PG_DELAY_MS MOCK_PG_URL CONFIRM_POLL_SECONDS CONFIRM_MAX_POLLS
  EXPIRING RATE ENTRANTS SEAT_COUNT WAITLIST_COUNT PAY_RATE PG_MODE DECLINE_RATE START_IN_SECONDS
  DRAW_AFTER_SECONDS DRAW_WINDOW_SECONDS WITH_BILLING PRE_OPEN_SECONDS AFTER_OPEN_SECONDS ENTER_SPREAD_SECONDS
  ABANDON_RATIO MAX_RETRY CONCERT_ID)
ARGS=()
for key in "${PASS_ENV[@]}"; do
  ARGS+=(-e "$key")
done

# PROM_RW_URL이 있으면 결과를 Prometheus remote write로도 보낸다(배포 측정, load-test.md 5절). 없으면 이 블록은 아무것도 바꾸지 않는다.
# 주소는 쓰기 엔드포인트 전체다. 예: PROM_RW_URL=http://<배포 서버 사설 IP>:9090/api/v1/write
# 대상 Prometheus가 --web.enable-remote-write-receiver로 떠 있어야 한다(측정 모드: loadtest/deploy/compose.measure.yml).
# 시나리오 구분 태그 testid는 결과 파일 이름의 NAME(시나리오-시각)이다. 지표 이름은 k6_*이고 Grafana에서 testid로 거른다.
RW_ARGS=()
RW_ENV=()
if [ -n "${PROM_RW_URL:-}" ]; then
  RW_ENV=(-e "K6_PROMETHEUS_RW_SERVER_URL=$PROM_RW_URL" -e "K6_PROMETHEUS_RW_TREND_STATS=${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),avg,max}")
  RW_ARGS=(--out experimental-prometheus-rw --tag "testid=${NAME}")
fi

# Note: 터미널이 아닌 곳(에이전트, tee로 남기는 stdout 파일)에서는 k6가 진행 표시줄을 1초마다 새 줄로 찍어 출력의 9할을 넘긴다(ISE-219).
# 기본은 --quiet로 끄고 마지막 요약만 남긴다. 진행 상황을 보고 싶으면 K6_PROGRESS=1을 준다
QUIET_ARGS=(--quiet)
if [ "${K6_PROGRESS:-0}" = 1 ]; then
  QUIET_ARGS=()
fi

# Note: Git Bash는 /scripts 같은 인자를 Windows 경로로 바꿔 버린다. MSYS_NO_PATHCONV로 막는다
# Note: 컨테이너 안에서 localhost는 컨테이너 자신이다. 호스트의 Gateway는 host.docker.internal로 부른다
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$K6_DIR:/scripts" \
  "${ARGS[@]}" ${RW_ENV[@]+"${RW_ENV[@]}"} \
  "$K6_IMAGE" run \
  ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"} \
  --summary-export "/scripts/results/${NAME}-summary.json" \
  --out "csv=/scripts/results/${NAME}-raw.csv.gz" \
  ${RW_ARGS[@]+"${RW_ARGS[@]}"} \
  "/scripts/${SCRIPT}" | tee "$K6_DIR/results/${NAME}-stdout.txt"
