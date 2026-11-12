#!/usr/bin/env bash
# 측정 한 회가 끝난 뒤 서버 쪽 결과를 모은다. 읽기만 한다(대사 SQL은 SELECT만 있다).
# 사용: collect.sh <출력 접두어> <회차 ID들(쉼표)> <부하 시작 epoch초>
#   <접두어>-invariants.txt  대사(정합성). 0이 아니면 그 회의 처리량은 쓰지 않는다
#   <접두어>-gc.log          reservation-service GC 로그
#   <접두어>-app.log         부하 시작 뒤 reservation-service 오류·만료 로그
#   <접두어>-server.txt      reservation-service 이미지·재시작 수·GC 종류, Gateway 재시작 수·OOM 여부, 장비 코어·메모리
set -uo pipefail
source "$(dirname "$0")/common.sh"
PREFIX="$1"; SIDS="$2"; START="$3"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$(dirname "$PREFIX")"
first="${SIDS%%,*}"
for sid in ${SIDS//,/ }; do
  [ "$sid" = "$first" ] || echo "## schedule $sid"
  psql_reservation -v sid="$sid" < "$DIR/invariants.sql" 2>&1
done > "$PREFIX-invariants.txt"
res="$(container_of reservation-service)"; gw="$(container_of gateway)"
docker cp "$res:/tmp/gc.log" "$PREFIX-gc.log" >/dev/null 2>&1 || echo "gc.log를 꺼내지 못했다" >&2
docker logs --since "$START" "$res" 2>&1 | grep -E '선점 만료|ERROR|lock timeout|deadlock|OutOfMemory|^[a-zA-Z.]+(Exception|Error)(: |$)' > "$PREFIX-app.log" || true
{
  echo "reservation_image=$(docker inspect -f '{{.Config.Image}}' "$res") commit=$(docker inspect -f '{{index .Config.Labels "k6steps.commit"}}' "$(docker inspect -f '{{.Image}}' "$res")")"
  echo "reservation_restarts=$(docker inspect -f '{{.RestartCount}}' "$res") oom=$(docker inspect -f '{{.State.OOMKilled}}' "$res")"
  echo "gateway_restarts=$(docker inspect -f '{{.RestartCount}}' "$gw") oom=$(docker inspect -f '{{.State.OOMKilled}}' "$gw")"
  echo "gc=$(docker exec "$res" sh -c 'head -c 4000 /tmp/gc.log' 2>/dev/null | grep -oE 'Using [A-Za-z0-9 ]+' | head -1)"
  echo "host_cpus=$(nproc) host_mem_kb=$(awk '/MemTotal/{print $2}' /proc/meminfo) deploy_commit=$(cat "$REPO/.deploy/current" 2>/dev/null)"
  echo "measure_mode=$(sed -n 's/^overrides=//p' "$REPO/.deploy/measure-mode" 2>/dev/null)"
} > "$PREFIX-server.txt"
