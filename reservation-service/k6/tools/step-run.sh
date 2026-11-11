#!/usr/bin/env bash
# 단계 측정 한 회. 측정 브랜치(experiment/ISE-79-k6-steps) 전용이다.
# 지금 커밋의 코드로 reservation-service를 다시 빌드해 새로 띄우고, 새 회차에 측정 하나를 건 뒤 결과를 results/에 모은다.
# 설계: docs/design/reservation/k6-seathold-bottleneck-plan.md 1-6절(측정 종류), 3절(단계)
# 사용: bash reservation-service/k6/tools/step-run.sh <단계 이름> <L|S|H>
#   L: K1 부하. 초당 100명 300초
#   S: K1 스트레스 + 회복. 초당 5명부터 두 배씩 60초, 끝나고 120초 탐침
#   H: K1-H(선점만) 스트레스 + 회복. 초당 50명부터 두 배씩 60초
#   T: K2 멀티 탭. 같은 사용자가 탭 4개로 2석씩 동시에 선점(매수 한도 S1). 초당 50명 120초
#   D: K2 연타. 같은 사용자가 같은 좌석·같은 멱등키로 3번 동시에 선점(멱등키 S2). 초당 50명 120초
#   X: K1 부하 + 만료 스케줄러 켬. 선점 유지 1분 회차에 초당 100명 300초(만료와 선점 경합 S3·S4, 동시성 테스트 C7)
#   K: K1 부하 + 잠금 장애 주입. 부하 LOCK_AFTER초(기본 30) 뒤 별도 세션이 앞쪽 LOCK_RANGE석(기본 3,000, 인기 구역) 중
#      선택 가능 좌석을 LOCK_SECONDS초(기본 30) 동안 FOR UPDATE로 쥔다. 잠긴 행을 노린 선점이 어디까지 번지는지 본다(S3)
#   Z: 잠긴 행 집중 스트레스. 측정 내내 앞쪽 LOCK_RANGE석(기본 10, 인기석)을 잠그고 선점을 그 좌석에만 초당 10명부터 두 배씩 보낸다.
#      같이 구경꾼 요청(공연 목록, user-service, payment-service)을 API마다 초당 10건 보내 번짐을 본다. 잠금은 부하 끝 30초 뒤 푼다
#   M: 매진을 피한 K1 스트레스. S와 같은 흐름이지만 단계마다 새 회차를 쓰고, 회차 하나에는 초당 PER_SCHEDULE_RATE명(기본 100)까지만 보낸다.
#      S는 160~320명 단계에서 매진되어 그 위 단계가 좌석 조회만 받았다. M은 모든 단계에서 조회와 선점이 섞인다
#   선택: EXTRA_OVERLAYS="a.yml b.yml"로 덮어쓰기를 더 얹고, SCALE=2로 reservation-service를 여러 대 띄운다
#   예: bash reservation-service/k6/tools/step-run.sh base S
#   OBSERVE_JFR=1이면 JFR 녹화를 켜고 측정 뒤 results/에 꺼낸다(조건부 단계 판단 회에만. 계획 문서 3-4절)
#
# 기준 환경: 배포 메모리 + 만료 스케줄러 끔(compose.deploy-memory.yml), 지표·GC 로그(compose.b8-observe.yml, compose.step-observe.yml),
# Gateway 인증 차단기 완화(compose.measure-gateway.yml),
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
DIRTY="$(git status --porcelain -- reservation-service/src config-repo "$K6"/*.yml "$K6/config-measure" "$K6/step-overlays.txt")"
if [ -n "$DIRTY" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "커밋되지 않은 변경이 있습니다. 단계를 커밋한 뒤 잽니다(시험 실행은 ALLOW_DIRTY=1)" >&2
  echo "$DIRTY" >&2
  exit 1
fi
COMMIT="$(git rev-parse --short HEAD)${DIRTY:+-dirty}"

FILES=(-f compose.yml -f compose.local.yml -f "$K6/compose.deploy-memory.yml" -f "$K6/compose.b8-observe.yml" -f "$K6/compose.step-observe.yml"
  -f "$K6/compose.measure-gateway.yml")
OVERLAYS=()
while IFS= read -r line; do
  line="${line%%#*}"; line="${line//[[:space:]]/}"
  [ -n "$line" ] && OVERLAYS+=("$line") && FILES+=(-f "$line")
done < "$K6/step-overlays.txt"
# JFR은 단계가 아니라 관찰 옵션이다. 단계 목록과 섞이지 않게 따로 얹는다
[ "${OBSERVE_JFR:-0}" = 1 ] && FILES+=(-f "$K6/compose.jfr.yml")
# X는 기준 환경에서 꺼 둔 만료 스케줄러를 켠다. 단계가 아니라 측정 종류의 조건이라 단계 목록과 따로 얹는다
[ "$KIND" = X ] && FILES+=(-f "$K6/compose.expiry-on.yml")
# 측정 조건으로 더 얹는 덮어쓰기(예: 만료 한 주기 상한). 단계가 아니므로 meta에 따로 남긴다
for extra in ${EXTRA_OVERLAYS:-}; do FILES+=(-f "$extra"); done
# SCALE이 2 이상이면 고정 호스트 포트를 지운다. 같은 포트를 여러 컨테이너가 쓸 수 없다
SCALE="${SCALE:-1}"
[ "$SCALE" -gt 1 ] && FILES+=(-f "$K6/compose.scale-ports.yml")

WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
# Note: 워밍업 속도는 모든 단계에서 초당 2명이다. k6 기본값(10명)은 base의 처리 한계(좌석 조회 초당 약 4건)를 넘어,
# 워밍업만으로 요청 스레드 200개와 커넥션 대기가 쌓인 채 측정이 시작됐다(2026-11-06 base S 첫 회, 5명 단계부터 전부 504)
WARMUP_RATE="${WARMUP_RATE:-2}"
case "$KIND" in
  L) export MODEL=open RATE="${RATE:-100}" DURATION_SECONDS="${DURATION_SECONDS:-300}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-0}"
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  S) export MODEL=ramp STAGES="${STAGES:-5,10,20,40,80,160,320}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-120}" MAX_VUS="${MAX_VUS:-4000}" ;;
  T) export MODEL=open SEAT_MAP=once BURST=tabs RATE="${RATE:-50}" DURATION_SECONDS="${DURATION_SECONDS:-120}" RECOVERY_SECONDS=0
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  D) export MODEL=open SEAT_MAP=once BURST=double RATE="${RATE:-50}" DURATION_SECONDS="${DURATION_SECONDS:-120}" RECOVERY_SECONDS=0
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  X) export MODEL=open RATE="${RATE:-100}" DURATION_SECONDS="${DURATION_SECONDS:-300}" RECOVERY_SECONDS=0 HOLD_MINUTES="${HOLD_MINUTES:-1}"
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  K) export MODEL=open RATE="${RATE:-100}" DURATION_SECONDS="${DURATION_SECONDS:-300}" RECOVERY_SECONDS=0
     LOAD_SECONDS="$DURATION_SECONDS" ;;
  Z) export MODEL=ramp SEAT_MAP=once STAGES="${STAGES:-10,20,40,80,160,320}" RECOVERY_SECONDS=0 MAX_VUS="${MAX_VUS:-4000}" \n       HOT_RATIO=1 HOT_SHARE="${HOT_SHARE:-0.000667}" MAX_RETRY=0 THINK_MIN=0 THINK_MAX=0.2 BYSTANDER_RATE="${BYSTANDER_RATE:-10}" \n       LOCK_AFTER="${LOCK_AFTER:--10}" LOCK_RANGE="${LOCK_RANGE:-10}" ;;
  M) export MODEL=ramp STAGES="${STAGES:-5,10,20,40,80,160,320,640,1280}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-120}" MAX_VUS="${MAX_VUS:-4000}"        PER_SCHEDULE_RATE="${PER_SCHEDULE_RATE:-100}" ;;
  H) export MODEL=ramp SEAT_MAP=once STAGES="${STAGES:-50,100,200,400,800}" RECOVERY_SECONDS="${RECOVERY_SECONDS:-120}" MAX_VUS="${MAX_VUS:-4000}" ;;
  *) echo "측정 종류는 L, S, H, M, T, D, X, K, Z 중 하나입니다" >&2; exit 1 ;;
esac
if [ "$MODEL" = ramp ]; then
  STAGE_COUNT="$(echo "$STAGES" | tr ',' '\n' | grep -c .)"
  LOAD_SECONDS=$(( STAGE_COUNT * ${STAGE_SECONDS:-60} ))
fi

echo "[$STEP-$KIND] 커밋 $COMMIT, 덮어쓰기: ${OVERLAYS[*]:-(없음)}"
# 이 스크립트는 reservation-service만 다시 띄운다. Gateway가 측정 환경(k6measure 프로필)으로 떠 있지 않으면
# 차단기가 공유 기준(느린 호출 50ms, 실패율 50%)으로 돌아 K1-H가 Gateway에서 막힌다. 그런 회는 다른 환경이므로 시작하지 않는다
if ! docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' iseonjwa-gateway-1 2>/dev/null | grep -q '^SPRING_PROFILES_ACTIVE=.*k6measure'; then
  echo "Gateway가 측정 환경으로 떠 있지 않습니다. STEPS.md 실행 방법대로 전체 스택을 다시 띄웁니다" >&2
  exit 1
fi
# Note: 측정 경로에 없는 컨테이너는 측정 중에 멈춘다. P4b부터 k6가 GB 단위 메모리를 쓰면서 서비스 전체(약 4.5GiB)와 합쳐
# Docker VM(약 7.6GiB)이 스왑으로 넘어가 모든 컨테이너가 함께 멈췄다(STEPS.md P4b 실측 2차). 이 컨테이너들은 앞 단계에서도 거의 놀았다.
# Prometheus는 지표 수집에 쓰므로 남긴다. 다시 켜려면 STEPS.md 실행 절의 전체 스택 명령을 다시 실행한다
# CPU 상한 측정은 reservation-service만이 아니라 전체 스택이 같은 코어에 묶여 있어야 한다. Gateway로 확인하고 다르면 멈춘다
if [[ " ${EXTRA_OVERLAYS:-} " == *compose.cpuset-4.yml* ]] && [ "$(docker inspect -f '{{.HostConfig.CpusetCpus}}' iseonjwa-gateway-1)" != 0-3 ]; then
  echo "CPU 상한 측정인데 Gateway가 0-3 코어에 묶여 있지 않습니다. compose.cpuset-4.yml을 얹어 전체 스택을 다시 띄웁니다" >&2
  exit 1
fi
IDLE_CONTAINERS="${IDLE_CONTAINERS:-grafana loki alloy zipkin kafka-ui alertmanager frontend}"
for c in $IDLE_CONTAINERS; do docker stop "iseonjwa-$c-1" >/dev/null 2>&1 || true; done
echo "[$STEP-$KIND] 측정 무관 컨테이너 멈춤: $IDLE_CONTAINERS"
echo "[$STEP-$KIND] reservation-service 빌드 후 다시 띄움"
docker compose --env-file "$ENV_FILE" "${FILES[@]}" up -d --build --no-deps --force-recreate --wait --scale reservation-service="$SCALE" reservation-service >/dev/null
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
SCHEDULE_ID="$(HOLD_MINUTES="${HOLD_MINUTES:-5}" bash "$K6/tools/create-schedule.sh" "k6 $STEP $KIND")"
ALL_SCHEDULES="$SCHEDULE_ID"
if [ "$KIND" = M ]; then
  # 단계마다 ceil(도착률 / PER_SCHEDULE_RATE)개의 새 회차를 만든다. 첫 단계의 첫 회차는 위에서 만든 측정 회차다
  STAGE_SCHEDULES=""
  for rate in ${STAGES//,/ }; do
    for _ in $(seq 1 $(( (rate + PER_SCHEDULE_RATE - 1) / PER_SCHEDULE_RATE ))); do
      if [ -z "$STAGE_SCHEDULES" ]; then id="$SCHEDULE_ID"; else id="$(bash "$K6/tools/create-schedule.sh" "k6 $STEP M $rate")"; fi
      STAGE_SCHEDULES="${STAGE_SCHEDULES:+$STAGE_SCHEDULES,}$id"
    done
  done
  ALL_SCHEDULES="$STAGE_SCHEDULES"
  export STAGE_SCHEDULES
  echo "[$STEP-$KIND] 단계 회차: $STAGE_SCHEDULES"
fi
export SCHEDULE_ID WARMUP_SCHEDULE_ID WARMUP_SECONDS WARMUP_RATE TOKEN_MODE=sign LABEL="$STEP-$KIND"
# Note: k6는 compose 네트워크 안에서 Gateway를 직접 부른다. host.docker.internal로 부르면 830KB 좌석 응답이 호스트의
# Docker 포트 전달(com.docker.backend)을 거쳐 다시 VM으로 들어오는데, P4b에서 서버가 빨라지자 이 경로가 먼저 막혔다(STEPS.md P4b 실측 4차).
# 측정 대상은 Gateway부터이므로 부하 생성기와 Gateway 사이 경로는 측정에서 뺀다
# IDENTITY=direct면 Gateway를 거치지 않고 reservation-service를 직접 부른다(k1-seathold.js 참고)
if [ "${IDENTITY:-}" = direct ]; then DEFAULT_BASE_URL=http://reservation-service:8082; else DEFAULT_BASE_URL=http://gateway:8080; fi
export IDENTITY K6_NETWORK="${K6_NETWORK:-iseonjwa_default}" BASE_URL="${BASE_URL:-$DEFAULT_BASE_URL}"
echo "[$STEP-$KIND] 측정 회차 $SCHEDULE_ID, 워밍업 회차 $WARMUP_SCHEDULE_ID"
if [ "$KIND" = X ]; then
  # Note: 앞선 회들은 만료 스케줄러를 끈 채 쟀으므로, 기한이 지난 HELD 예매가 다른 회차에 수십만 건 남아 있다(2026-11-10 약 49만 건).
  # 스케줄러는 기한이 이른 것부터 집으므로 그대로 켜면 측정 회차보다 그 적체를 먼저 치운다. 버리는 측정 회차의 기한을 1년 뒤로 미뤄 대상에서 뺀다
  PUSHED="$(docker exec iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -tAc     "WITH u AS (UPDATE reservations SET hold_expires_at = now() + interval '365 days' WHERE status = 'HELD' AND hold_expires_at <= now() + interval '10 minutes' AND schedule_id NOT IN ($SCHEDULE_ID, $WARMUP_SCHEDULE_ID) RETURNING 1) SELECT count(*) FROM u")"
  echo "[$STEP-$KIND] 다른 회차의 만료 대상 $PUSHED건을 1년 뒤로 미룸"
fi

mkdir -p "$K6/results"
PREFIX="$K6/results/step-$STEP-$KIND-$(date +%Y%m%d-%H%M%S)"
# 노트북 경합을 가리려고 컨테이너 CPU·메모리를 10초마다 남긴다(k6 컨테이너 포함). 잠금 대기 세션 수도 같이 남긴다
( while true; do
    echo "## $(date +%s)"
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null
    # 행 잠금을 기다리는 세션 수(S3 직면 여부, 계획 문서 3-4절). 같은 좌석을 잡으려는 선점이 앞 트랜잭션의 커밋을 기다리는 수다
    echo "lock_waits $(docker exec iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -tAc       "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" 2>/dev/null)"
    # 만료 적체(X): 기한이 지났는데 아직 HELD인 예매와 지금까지 만료된 예매 수
    echo "expiry $(docker exec iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -tAc "SELECT count(*) FILTER (WHERE status = 'HELD' AND hold_expires_at <= now()) || ' ' || count(*) FILTER (WHERE status = 'EXPIRED') FROM reservations WHERE schedule_id = $SCHEDULE_ID" 2>/dev/null)"
    sleep 10
  done ) > "$PREFIX-stats.txt" &
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

# Z는 부하 내내 잠그고 부하가 끝난 뒤 30초 더 쥔다(구경꾼 요청으로 잠금이 풀린 뒤의 회복을 본다)
[ "$KIND" = Z ] && LOCK_SECONDS="${LOCK_SECONDS:-$(( LOAD_SECONDS + 30 ))}"
if [ "$KIND" = K ] || [ "$KIND" = Z ]; then
  # 잠금 장애 주입. k6 setup이 남긴 load_start를 기다렸다가 LOCK_AFTER초 뒤 잠근다. 잠근 시각과 좌석 수를 lock.txt에 남긴다
  # Note: 잠글 좌석은 좌석 조회 응답 순서(구역 → 열 → 번호)의 앞쪽이다. k6의 인기 구역이 그 순서의 앞쪽이고, 좌석 ID는 그 순서로 연속이 아니다.
  # 처음에는 ID가 작은 순서로 잠가 k6가 노린 10석 중 1석만 잠겼다(STEPS.md 잠긴 행 집중 1차)
  # Note: load_start는 setup에서 워밍업 뒤의 부하 시작 시각(미래)으로 찍힌다. 줄이 보인 때가 아니라 그 시각에서 LOCK_AFTER초 뒤에 잠근다
  ( for _ in $(seq 1 600); do grep -q "load_start epoch_ms=" "$PREFIX-k6.log" 2>/dev/null && break; sleep 1; done
    LOCK_AT=$(( $(grep -oE 'load_start epoch_ms=[0-9]+' "$PREFIX-k6.log" | head -1 | cut -d= -f2) / 1000 + ${LOCK_AFTER:-30} ))
    while [ "$(date +%s)" -lt "$LOCK_AT" ]; do sleep 1; done
    echo "lock_begin $(date +%s)"
    docker exec -i iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -tA <<SQL
BEGIN;
SELECT 'locked ' || count(*) || ' first ' || min(id) FROM (SELECT ss.id FROM schedule_seats ss JOIN venue_seats vs ON vs.id = ss.venue_seat_id
  WHERE ss.schedule_id = $SCHEDULE_ID AND ss.status = 'AVAILABLE' AND ss.deleted_at IS NULL
  ORDER BY ss.section, vs.row_no, vs.seat_no LIMIT ${LOCK_RANGE:-3000} FOR UPDATE OF ss) t;
SELECT pg_sleep(${LOCK_SECONDS:-30});
COMMIT;
SQL
    echo "lock_end $(date +%s)" ) > "$PREFIX-lock.txt" 2>&1 &
fi
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
# X·K와 여러 대: 인스턴스마다 만료 스케줄러 로그와 오류를 남긴다(Prometheus는 인스턴스 하나만 긁는다)
for c in $(docker ps --format '{{.Names}}' | grep '^iseonjwa-reservation-service-'); do
  { echo "## $c"; docker logs --since "$START" "$c" 2>&1 | grep -E '선점 만료|ERROR|lock timeout|deadlock|^[a-zA-Z.]+(Exception|Error)(: |$)' || true; } >> "$PREFIX-app.log"
done
# Note: Git Bash는 /tmp를 Windows 경로로 바꾼다. MSYS_NO_PATHCONV로 막는다. 그러면 /e/... 같은 로컬 경로도 바뀌지 않아
# Windows의 docker가 쓰지 못하므로, 로컬 쪽은 cygpath로 Windows 경로를 넘긴다
LOCAL_PREFIX="$(cygpath -w "$PREFIX" 2>/dev/null || echo "$PREFIX")"
MSYS_NO_PATHCONV=1 docker cp iseonjwa-reservation-service-1:/tmp/gc.log "$LOCAL_PREFIX-gc.log" >/dev/null 2>&1 || true
# 정합성(대사). 0이 아니면 그 회의 처리량은 쓰지 않는다(계획 문서 1-4절)
# M은 단계 회차마다 검사한다. 첫 회차(측정 회차)는 머리줄 없이 남겨 기존 집계가 그대로 읽게 한다
for sid in ${ALL_SCHEDULES//,/ }; do
  [ "$sid" = "$SCHEDULE_ID" ] || echo "## schedule $sid"
  docker exec -i iseonjwa-postgres-reservation-1 psql -U "${DB_USER:-iseonjwa}" -d reservation -v sid="$sid" \
    < "$K6/tools/invariants.sql" 2>&1 || true
done > "$PREFIX-invariants.txt"
{
  echo "step=$STEP kind=$KIND commit=$COMMIT overlays=${OVERLAYS[*]:-none} extra_overlays=${EXTRA_OVERLAYS:-none} scale=$SCALE"
  echo "schedule=$SCHEDULE_ID stage_schedules=${STAGE_SCHEDULES:-none} per_schedule_rate=${PER_SCHEDULE_RATE:-none} k6_cpuset=${K6_CPUSET:-none}"
  echo "warmup_schedule=$WARMUP_SCHEDULE_ID warmup_seconds=$WARMUP_SECONDS warmup_rate=$WARMUP_RATE"
  echo "start=$START load_end=$LOAD_END end=$END restarts=$(docker inspect -f '{{.RestartCount}}' iseonjwa-reservation-service-1)"
  echo "jfr=${OBSERVE_JFR:-0} stopped=${IDLE_CONTAINERS// /,} k6_network=$K6_NETWORK base_url=$BASE_URL identity=${IDENTITY:-gateway}"
  # k6 메모리 상한과 종료 코드. k6_exit=137이면 k6가 상한에 닿아 죽은 회라 처리량을 쓰지 않는다
  echo "$(grep -oE 'k6_memory=[^ ]+ k6_gomemlimit=[^ ]+' "$PREFIX-k6.log" | head -1) $(grep -oE 'k6_exit=[0-9]+' "$PREFIX-k6.log" | tail -1)"
  # 기기가 바뀌면 절대 수치가 달라져 단계끼리 비교할 수 없다. 어느 기기에서 잰 회인지 가리려고 남긴다
  # (호스트 CPU·메모리: Windows Git Bash와 Linux는 /proc, macOS는 sysctl. Docker VM: docker info)
  echo "host_cpu=$( (grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 || sysctl -n machdep.cpu.brand_string 2>/dev/null) | sed 's/^ *//; s/ /_/g')" \
    "host_mem_kb=$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 )))" \
    "docker=$(docker info --format '{{.ServerVersion}}_{{.OperatingSystem}}_{{.Architecture}}_cpus{{.NCPU}}_mem{{.MemTotal}}' 2>/dev/null | tr ' ' '_')"
  echo "gc=$(MSYS_NO_PATHCONV=1 docker exec iseonjwa-reservation-service-1 sh -c 'head -c 4000 /tmp/gc.log' 2>/dev/null | grep -oE 'Using [A-Za-z0-9 ]+' | head -1)"
} > "$PREFIX-meta.txt"
if [ "${OBSERVE_JFR:-0}" = 1 ]; then
  # 녹화는 JVM 종료 때 쓰인다. 기본 10초 시한이면 강제 종료로 파일이 남지 않을 수 있어 넉넉히 준다
  docker stop -t 90 iseonjwa-reservation-service-1 >/dev/null
  MSYS_NO_PATHCONV=1 docker cp iseonjwa-reservation-service-1:/tmp/rec.jfr "$LOCAL_PREFIX.jfr" >/dev/null     && echo "[$STEP-$KIND] JFR: $PREFIX.jfr (분석: jfr view hot-methods <파일>). reservation-service는 멈춘 상태다"     || echo "[$STEP-$KIND] JFR 파일을 꺼내지 못했습니다" >&2
fi
echo "[$STEP-$KIND] 끝: $PREFIX"
cat "$PREFIX-invariants.txt"
