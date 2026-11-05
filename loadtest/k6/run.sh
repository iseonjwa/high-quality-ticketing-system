#!/usr/bin/env bash
# k6를 설치하지 않고 Docker 이미지로 시나리오를 돌린다. 사용법은 같은 폴더 README.md
# 예: bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
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
  SCHEDULE_ID USERS ENTER_SECONDS DUP_RATIO REPEAT OPEN_IN_SECONDS PRE_VUS MAX_VUS)
ARGS=()
for key in "${PASS_ENV[@]}"; do
  ARGS+=(-e "$key")
done

# Note: Git Bash는 /scripts 같은 인자를 Windows 경로로 바꿔 버린다. MSYS_NO_PATHCONV로 막는다
# Note: 컨테이너 안에서 localhost는 컨테이너 자신이다. 호스트의 Gateway는 host.docker.internal로 부른다
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$K6_DIR:/scripts" \
  "${ARGS[@]}" \
  "$K6_IMAGE" run \
  --summary-export "/scripts/results/${NAME}-summary.json" \
  --out "csv=/scripts/results/${NAME}-raw.csv.gz" \
  "/scripts/${SCRIPT}" | tee "$K6_DIR/results/${NAME}-stdout.txt"
