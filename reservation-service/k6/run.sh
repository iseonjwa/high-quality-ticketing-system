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
# BURST(K2)는 holdonly 대신 burst-<모드>를 붙인다
[ -n "${BURST:-}" ] && MODE_TAG="-burst-$BURST"
# LABEL(예: p4a-S)을 주면 파일 이름에 붙여 단계별 결과를 나눈다
NAME="${SCRIPT%.js}${MODE_TAG}${LABEL:+-$LABEL}-${MODEL:-open}-${STAMP}"

# Note: k6 메모리를 묶어 Docker VM을 지킨다. P4b 1차에서 서버가 빨라지자 830KB 좌석 응답을 쥔 VU가 늘어 k6가 3GB를 넘겼고,
# VM(약 8GB)이 바닥나 서비스 컨테이너까지 함께 멈췄다(STEPS.md P4b 실측 1차). GOMEMLIMIT는 상한 근처에서 Go GC를 앞당겨 넘침을 늦추고,
# 그래도 넘치면 컨테이너만 OOM으로 죽는다(종료 코드 137). 그 회는 서버가 아니라 부하 생성기의 한계로 기록한다.
# P4a까지는 k6가 0.9GB 아래였으므로 이 상한은 앞 단계의 조건을 바꾸지 않는다
# Note: 2026-11-10에 2.5GiB(GOMEMLIMIT 2GiB)에서 6GiB로 올렸다. 새 기기(VM 16GiB)에서 2.5GiB는 K1 S 640명 이상, K1-H 400명 이상에서
# k6를 GC로 멈추게 해 요청을 몰아 보냈고, 그것이 Gateway 차단기를 열었다(STEPS.md "Gateway 경유 재확인"). GOMEMLIMIT는 정수 단위만 받는다
K6_MEMORY="${K6_MEMORY:-6g}"
K6_GOMEMLIMIT="${K6_GOMEMLIMIT:-5632MiB}"
echo "k6_memory=$K6_MEMORY k6_gomemlimit=$K6_GOMEMLIMIT"

# Note: Git Bash는 /scripts 같은 인자를 Windows 경로로 바꿔 버린다. MSYS_NO_PATHCONV로 막는다
# Note: 컨테이너 안에서 localhost는 컨테이너 자신이다. 호스트의 Gateway는 host.docker.internal로 부른다
# Note: --memory-swap을 --memory와 같게 둬야 스왑으로 넘치지 않고 상한에서 멈춘다
# K6_NETWORK를 주면 k6를 그 Docker 네트워크에 붙인다. 이때 BASE_URL은 http://gateway:8080처럼 서비스 이름으로 준다
RUN_ARGS=()
[ -n "${K6_NETWORK:-}" ] && RUN_ARGS=(--network "$K6_NETWORK")
# K6_CPUSET을 주면 k6를 그 코어에만 둔다. 서버 컨테이너를 다른 코어에 묶는 CPU 상한 측정(compose.cpuset-4.yml)에서
# k6가 서버 몫의 CPU를 빼앗지 않게 한다. 배포 측정에서는 k6가 다른 EC2에서 돈다
[ -n "${K6_CPUSET:-}" ] && RUN_ARGS+=(--cpuset-cpus "$K6_CPUSET")
echo "k6_cpuset=${K6_CPUSET:-none}"
echo "k6_network=${K6_NETWORK:-host} base_url=${BASE_URL:-http://host.docker.internal:8080}"
set +e
MSYS_NO_PATHCONV=1 docker run --rm \
  --name "k6-${LABEL:-run}-${STAMP}" "${RUN_ARGS[@]}" \
  --memory "$K6_MEMORY" --memory-swap "$K6_MEMORY" -e GOMEMLIMIT="$K6_GOMEMLIMIT" \
  -v "$K6_DIR:/scripts" \
  -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" \
  -e SCHEDULE_ID \
  -e MODEL -e RATE -e DURATION_SECONDS -e CLOSED_VUS \
  -e THINK_MIN -e THINK_MAX -e MAX_RETRY -e HOT_RATIO -e HOT_SHARE \
  -e PRE_VUS -e MAX_VUS -e USERS_CSV \
  -e SEAT_MAP -e BURST -e BURST_TABS -e BURST_SEATS -e BURST_REPEAT -e MAX_PER_USER -e BYSTANDER_RATE -e BYSTANDER_TAIL_SECONDS -e RECOVERY_SECONDS -e STAGES -e STAGE_SECONDS -e STAGE_SCHEDULES -e PER_SCHEDULE_RATE -e WARMUP_SECONDS -e WARMUP_RATE -e WARMUP_SCHEDULE_ID -e TOKEN_MODE -e IDENTITY -e USER_ID_BASE -e JWT_ACCESS_SECRET -e ENTRY_TOKEN_SECRET \
  "$K6_IMAGE" run \
  --summary-export "/scripts/results/${NAME}-summary.json" \
  --out "csv=/scripts/results/${NAME}-raw.csv.gz" \
  "/scripts/${SCRIPT}" | tee "$K6_DIR/results/${NAME}-stdout.txt"
K6_EXIT="${PIPESTATUS[0]}"
set -e
echo "k6_exit=$K6_EXIT"
[ "$K6_EXIT" = 137 ] && echo "k6 컨테이너가 메모리 상한($K6_MEMORY)에 닿아 종료됐습니다. 이 회는 부하 생성기 한계로 기록합니다" >&2
exit "$K6_EXIT"
