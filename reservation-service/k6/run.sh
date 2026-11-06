#!/usr/bin/env bash
# k6를 설치하지 않고 Docker 이미지로 K1을 돌린다. 사용법은 같은 폴더 README.md
# 예: SCHEDULE_ID=12 bash reservation-service/k6/run.sh
set -euo pipefail

K6_IMAGE="${K6_IMAGE:-grafana/k6:1.0.0}"
SCRIPT="${SCRIPT:-k1-seathold.js}"
K6_DIR="$(cd "$(dirname "$0")" && pwd)"
: "${SCHEDULE_ID:?SCHEDULE_ID 환경변수가 필요합니다}"

# TOKEN_MODE=sign은 local 스택 전용이다. 서명 키를 저장소 루트 .env에서 읽어 환경변수로만 넘기고 출력하지 않는다
if [ "${TOKEN_MODE:-csv}" = "sign" ]; then
  # 워크트리에는 .env가 없으므로 ENV_FILE로 원래 저장소의 .env를 가리킬 수 있다
  ENV_FILE="${ENV_FILE:-$K6_DIR/../../.env}"
  for key in JWT_ACCESS_SECRET ENTRY_TOKEN_SECRET; do
    if [ -z "${!key:-}" ]; then
      value="$(grep -E "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2-)"
      [ -n "$value" ] || { echo "$key 값이 .env에 없습니다" >&2; exit 1; }
      export "$key=$value"
    fi
  done
fi

mkdir -p "$K6_DIR/results"
STAMP="$(date +%Y%m%d-%H%M%S)"
# SEAT_MAP=once(K1-H)는 파일 이름에 holdonly를 붙여 K1 결과와 섞이지 않게 한다
MODE_TAG=""
[ "${SEAT_MAP:-each}" = "once" ] && MODE_TAG="-holdonly"
# LABEL(예: p4a-S)을 주면 파일 이름에 붙여 단계별 결과를 나눈다
NAME="${SCRIPT%.js}${MODE_TAG}${LABEL:+-$LABEL}-${MODEL:-open}-${STAMP}"

# Note: Git Bash는 /scripts 같은 인자를 Windows 경로로 바꿔 버린다. MSYS_NO_PATHCONV로 막는다
# Note: 컨테이너 안에서 localhost는 컨테이너 자신이다. 호스트의 Gateway는 host.docker.internal로 부른다
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$K6_DIR:/scripts" \
  -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" \
  -e SCHEDULE_ID \
  -e MODEL -e RATE -e DURATION_SECONDS -e CLOSED_VUS \
  -e THINK_MIN -e THINK_MAX -e MAX_RETRY -e HOT_RATIO -e HOT_SHARE \
  -e PRE_VUS -e MAX_VUS -e USERS_CSV \
  -e SEAT_MAP -e RECOVERY_SECONDS -e STAGES -e STAGE_SECONDS -e WARMUP_SECONDS -e WARMUP_RATE -e WARMUP_SCHEDULE_ID -e TOKEN_MODE -e USER_ID_BASE -e JWT_ACCESS_SECRET -e ENTRY_TOKEN_SECRET \
  "$K6_IMAGE" run \
  --summary-export "/scripts/results/${NAME}-summary.json" \
  --out "csv=/scripts/results/${NAME}-raw.csv.gz" \
  "/scripts/${SCRIPT}" | tee "$K6_DIR/results/${NAME}-stdout.txt"
