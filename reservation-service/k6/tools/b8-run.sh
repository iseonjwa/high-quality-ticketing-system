#!/usr/bin/env bash
# B8(과부하 뒤 회복) 측정 한 회: reservation-service를 덮어쓰기 파일로 새로 띄우고, 새 회차에 K1(초당 RATE명, DURATION_SECONDS초)을 건 뒤
# RECOVERY_SECONDS초 동안 10초마다 좌석 조회 탐침을 보낸다. 끝나면 Prometheus 지표 CSV와 요약을 results/에 남긴다.
# 설계: docs/design/reservation/k6-overload-recovery.md 6절
# 사용: bash reservation-service/k6/tools/b8-run.sh <이름> [추가 덮어쓰기 파일...]
#   예: bash reservation-service/k6/tools/b8-run.sh r0
#       bash reservation-service/k6/tools/b8-run.sh r1 reservation-service/k6/compose.b8-r1.yml
set -euo pipefail

LABEL="${1:?이름이 필요합니다(r0, r1, r123 등)}"
shift
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
K6="$ROOT/reservation-service/k6"
cd "$ROOT"

# P0'' 환경(배포 메모리 + G1) + 지표 켜기 + 대응별 파일. 실행 중인 다른 덮어쓰기(mock-pg 등)는 reservation-service만 다시 띄우므로 건드리지 않는다
FILES=(-f compose.yml -f compose.local.yml -f "$K6/compose.deploy-memory.yml" -f "$K6/compose.g1.yml" -f "$K6/compose.b8-observe.yml")
for extra in "$@"; do FILES+=(-f "$extra"); done

echo "[$LABEL] reservation-service 다시 띄움"
docker compose "${FILES[@]}" up -d --no-deps --force-recreate --wait reservation-service >/dev/null
# Gateway가 새 인스턴스를 Eureka에서 받을 때까지 공개 API로 확인한다
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/concerts)" = 200 ] && break
  sleep 2
done
sleep 10

SCHEDULE_ID="$(bash "$K6/tools/create-schedule.sh" "k6 B8 $LABEL")"
echo "[$LABEL] 회차 $SCHEDULE_ID"
DURATION_SECONDS="${DURATION_SECONDS:-300}"
RECOVERY_SECONDS="${RECOVERY_SECONDS:-300}"
SCHEDULE_ID="$SCHEDULE_ID" TOKEN_MODE=sign RATE="${RATE:-100}" DURATION_SECONDS="$DURATION_SECONDS" \
  RECOVERY_SECONDS="$RECOVERY_SECONDS" bash "$K6/run.sh" > "$K6/results/b8-$LABEL-k6.log" 2>&1 || true
END=$(date +%s)
# 부하 시작은 k6 setup이 남긴 load_start다. 못 찾으면 이 회를 버린다
START_MS="$(grep -oE 'load_start epoch_ms=[0-9]+' "$K6/results/b8-$LABEL-k6.log" | head -1 | cut -d= -f2)"
[ -n "$START_MS" ] || { echo "[$LABEL] load_start를 찾지 못했습니다. k6 로그를 확인합니다" >&2; exit 1; }
START=$(( START_MS / 1000 ))
LOAD_END=$(( START + DURATION_SECONDS ))

PREFIX="$K6/results/b8-$LABEL-$(date +%Y%m%d-%H%M%S)"
cp "$K6/results/b8-$LABEL-k6.log" "$PREFIX-k6.log"
# 탐침 한 건마다 부하가 끝난 뒤 몇 초째인지, 상태, 지연을 남긴다
grep -oE 'probe epoch_ms=[0-9]+ status=[0-9]+ [0-9]+ms' "$PREFIX-k6.log" \
  | awk -v end="$LOAD_END" '{split($2,a,"="); printf "+%ds %s %s\n", a[2]/1000-end, $3, $4}' > "$PREFIX-probe.txt" || true
echo "restarts=$(docker inspect -f '{{.RestartCount}}' iseonjwa-reservation-service-1) schedule=$SCHEDULE_ID start=$START load_end=$LOAD_END end=$END" > "$PREFIX-meta.txt"
python "$K6/tools/b8_metrics.py" "$START" "$LOAD_END" "$END" "$PREFIX"
echo "[$LABEL] 끝: $PREFIX"
