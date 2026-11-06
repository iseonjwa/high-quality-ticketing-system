#!/usr/bin/env bash
# 단계 측정 한 회. 측정 브랜치(experiment/ISE-79-k6-steps) 전용이다.
# 지금 커밋의 코드로 reservation-service를 다시 빌드해 새로 띄우고, 새 회차에 측정 하나를 건 뒤 결과를 results/에 모은다.
# 설계: docs/design/reservation/k6-seathold-bottleneck-plan.md 1-6절(측정 종류), 3절(단계)
# 사용: bash reservation-service/k6/tools/step-run.sh <단계 이름> <L|S|H>
#   L: K1 부하. 초당 100명 300초
#   S: K1 스트레스 + 회복. 초당 5명부터 두 배씩 60초, 끝나고 120초 탐침
#   H: K1-H(선점만) 스트레스 + 회복. 초당 50명부터 두 배씩 60초
#   예: bash reservation-service/k6/tools/step-run.sh base S
#   OBSERVE_JFR=1이면 JFR 녹화를 켜고 측정 뒤 results/에 꺼낸다(조건부 단계 판단 회에만. 계획 문서 3-4절)
#
# 기준 환경: 배포 메모리 + 만료 스케줄러 끔(compose.deploy-memory.yml), 지표·GC 로그(compose.b8-observe.yml, compose.step-observe.yml),
# 60초 워밍업. 그 위에 step-overlays.txt의 덮어쓰기를 얹는다.
# Note: 매 회 컨테이너를 새로 띄운다. 회복 단계(R1) 전에는 과부하 적체가 10분 가까이 남아 다음 측정을 오염시키기 때문이다.
set -euo pipefail

STEP="${1:?단계 이름이 필요합니다(base, g1, r1, p4a 등)}"
KIND="${2:?측정 종류가 필요합니다(L, S, H)}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
K6="$ROOT/reservation-service/k6"
cd "$ROOT"

# 워크트리에는 .env가 없다. ENV_FILE로 원래 저장소의 .env를 가리킨다. 값은 읽어 넘기기만 하고 출력하지 않는다
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
[ -f "$ENV_FILE" ] || { echo "ENV_FILE($ENV_FILE)이 없습니다. 원래 저장소의 .env를 가리킵니다" >&2; exit 1; }
ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"
export ENV_FILE

# 단계는 커밋과 같아야 한다. 측정 대상 코드나 단계 설정이 커밋되지 않았으면 멈춘다
DIRTY="$(git status --porcelain -- reservation-service/src config-repo "$K6"/*.yml "$K6/step-overlays.txt")"
if [ -n "$DIRTY" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "커밋되지 않은 변경이 있습니다. 단계를 커밋한 뒤 잽니다(시험 실행은 ALLOW_DIRTY=1)" >&2
  echo "$DIRTY" >&2
  exit 1
fi
COMMIT="$(git rev-parse --short HEAD)${DIRTY:+-dirty}"

FILES=(-f compose.yml -f compose.local.yml -f "$K6/compose.deploy-memory.yml" -f "$K6/compose.b8-observe.yml" -f "$K6/compose.step-observe.yml")
OVERLAYS=()
while IFS= read -r line; do
  line="${line%%#*}"; line="${line//[[:space:]]/}"
  [ -n "$line" ] && OVERLAYS+=("$line") && FILES+=(-f "$line")
done < "$K6/step-overlays.txt"
# JFR은 단계가 아니라 관찰 옵션이다. 단계 목록과 섞이지 않게 따로 얹는다
[ "${OBSERVE_JFR:-0}" = 1 ] && FILES+=(-f "$K6/compose.jfr.yml")

WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
case "$KIND" in
  L) export MODEL=open RATE="${RATE:-100}" DURATION_SECONDS="${DURATION_SECONDS:-300}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-0}"
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  S) export MODEL=ramp STAGES="${STAGES:-5,10,20,40,80,160,320}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-120}" MAX_VUS="${MAX_VUS:-4000}" ;;
  H) export MODEL=ramp SEAT_MAP=once STAGES="${STAGES:-50,100,200,400,800}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-120}" MAX_VUS="${MAX_VUS:-4000}" ;;
  *) echo "측정 종류는 L, S, H 중 하나입니다" >&2; exit 1 ;;
esac
if [ "$MODEL" = ramp ]; then
  STAGE_COUNT="$(echo "$STAGES" | tr ',' '\n' | grep -c .)"
  LOAD_SECONDS=$(( STAGE_COUNT * ${STAGE_SECONDS:-60} ))
fi

echo "[$STEP-$KIND] 커밋 $COMMIT, 덮어쓰기: ${OVERLAYS[*]:-(없음)}"
echo "[$STEP-$KIND] reservation-service 빌드 후 다시 띄움"
docker compose --env-file "$ENV_FILE" "${FILES[@]}" up -d --build --no-deps --force-recreate --wait reservation-service >/dev/null
# Gateway가 새 인스턴스를 Eureka에서 받을 때까지 공개 API로 확인한다
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/concerts)" = 200 ] && break
  sleep 2
done
sleep 10

# Note: 새로 띄운 직후에는 관리자 경로가 데워지지 않아 좌석 15,000개 생성이 Gateway 시한(3초)을 넘긴다(504, 데운 뒤 약 1초).
# 측정과 무관한 준비 단계라, 버리는 회차를 하나 먼저 만들어 데운다. 실패해도 넘어간다
bash "$K6/tools/create-schedule.sh" "k6 $STEP prime" >/dev/null 2>&1 || true
# 워밍업은 측정 회차의 좌석을 쓰지 않게 다른 회차로 한다(계획 문서 1-6절)
WARMUP_SCHEDULE_ID="$(bash "$K6/tools/create-schedule.sh" "k6 $STEP warmup")"
SCHEDULE_ID="$(bash "$K6/tools/create-schedule.sh" "k6 $STEP $KIND")"
export SCHEDULE_ID WARMUP_SCHEDULE_ID WARMUP_SECONDS TOKEN_MODE=sign LABEL="$STEP-$KIND"
echo "[$STEP-$KIND] 측정 회차 $SCHEDULE_ID, 워밍업 회차 $WARMUP_SCHEDULE_ID"

mkdir -p "$K6/results"
PREFIX="$K6/results/step-$STEP-$KIND-$(date +%Y%m%d-%H%M%S)"
# 노트북 경합을 가리려고 컨테이너 CPU·메모리를 10초마다 남긴다(k6 컨테이너 포함). 잠금 대기 세션 수도 같이 남긴다
( while true; do
    echo "## $(date +%s)"
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null
    # 행 잠금을 기다리는 세션 수(S3 직면 여부, 계획 문서 3-4절). 같은 좌석을 잡으려는 선점이 앞 트랜잭션의 커밋을 기다리는 수다
    echo "lock_waits $(docker exec iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -tAc       "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" 2>/dev/null)"
    sleep 10
  done ) > "$PREFIX-stats.txt" &
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

bash "$K6/run.sh" > "$PREFIX-k6.log" 2>&1 || true
END=$(date +%s)
kill "$STATS_PID" 2>/dev/null || true

# 부하 시작은 k6 setup이 남긴 load_start다(워밍업 뒤). 못 찾으면 이 회를 버린다
START_MS="$(grep -oE 'load_start epoch_ms=[0-9]+' "$PREFIX-k6.log" | head -1 | cut -d= -f2)"
[ -n "$START_MS" ] || { echo "[$STEP-$KIND] load_start를 찾지 못했습니다. $PREFIX-k6.log를 확인합니다" >&2; exit 1; }
START=$(( START_MS / 1000 ))
LOAD_END=$(( START + LOAD_SECONDS ))

grep -oE 'probe epoch_ms=[0-9]+ status=[0-9]+ [0-9]+ms' "$PREFIX-k6.log" \
  | awk -v end="$LOAD_END" '{split($2,a,"="); printf "+%ds %s %s\n", a[2]/1000-end, $3, $4}' > "$PREFIX-probe.txt" || true
python "$K6/tools/b8_metrics.py" "$START" "$LOAD_END" "$END" "$PREFIX"
# Note: Git Bash는 /tmp를 Windows 경로로 바꾼다. MSYS_NO_PATHCONV로 막는다. 그러면 /e/... 같은 로컬 경로도 바뀌지 않아
# Windows의 docker가 쓰지 못하므로, 로컬 쪽은 cygpath로 Windows 경로를 넘긴다
LOCAL_PREFIX="$(cygpath -w "$PREFIX" 2>/dev/null || echo "$PREFIX")"
MSYS_NO_PATHCONV=1 docker cp iseonjwa-reservation-service-1:/tmp/gc.log "$LOCAL_PREFIX-gc.log" >/dev/null 2>&1 || true
# 정합성(대사). 0이 아니면 그 회의 처리량은 쓰지 않는다(계획 문서 1-4절)
docker exec -i iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -v sid="$SCHEDULE_ID" \
  < "$K6/tools/invariants.sql" > "$PREFIX-invariants.txt" 2>&1 || true
{
  echo "step=$STEP kind=$KIND commit=$COMMIT overlays=${OVERLAYS[*]:-none}"
  echo "schedule=$SCHEDULE_ID warmup_schedule=$WARMUP_SCHEDULE_ID warmup_seconds=$WARMUP_SECONDS"
  echo "start=$START load_end=$LOAD_END end=$END restarts=$(docker inspect -f '{{.RestartCount}}' iseonjwa-reservation-service-1)"
  echo "jfr=${OBSERVE_JFR:-0}"
  echo "gc=$(MSYS_NO_PATHCONV=1 docker exec iseonjwa-reservation-service-1 sh -c 'head -c 4000 /tmp/gc.log' 2>/dev/null | grep -oE 'Using [A-Za-z0-9 ]+' | head -1)"
} > "$PREFIX-meta.txt"
if [ "${OBSERVE_JFR:-0}" = 1 ]; then
  # 녹화는 JVM 종료 때 쓰인다. 기본 10초 시한이면 강제 종료로 파일이 남지 않을 수 있어 넉넉히 준다
  docker stop -t 90 iseonjwa-reservation-service-1 >/dev/null
  MSYS_NO_PATHCONV=1 docker cp iseonjwa-reservation-service-1:/tmp/rec.jfr "$LOCAL_PREFIX.jfr" >/dev/null     && echo "[$STEP-$KIND] JFR: $PREFIX.jfr (분석: jfr view hot-methods <파일>). reservation-service는 멈춘 상태다"     || echo "[$STEP-$KIND] JFR 파일을 꺼내지 못했습니다" >&2
fi
echo "[$STEP-$KIND] 끝: $PREFIX"
cat "$PREFIX-invariants.txt"
