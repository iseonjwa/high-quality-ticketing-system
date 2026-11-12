#!/usr/bin/env bash
# 측정 중 10초마다 컨테이너 CPU·메모리와 예약 DB 잠금 대기 세션 수를 남긴다(로컬 step-run.sh의 stats와 같은 형식).
# 사용: sampler.sh <회차 ID> <출력 파일>   deploy-run.sh가 nohup으로 띄우고 측정 뒤 kill한다
set -uo pipefail
source "$(dirname "$0")/common.sh"
SID="$1"; OUT="$2"
mkdir -p "$(dirname "$OUT")"
while true; do
  {
    echo "## $(date +%s)"
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null
    echo "lock_waits $(psql_reservation -tAc "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" 2>/dev/null)"
    echo "expiry $(psql_reservation -tAc "SELECT count(*) FILTER (WHERE status = 'HELD' AND hold_expires_at <= now()) || ' ' || count(*) FILTER (WHERE status = 'EXPIRED') FROM reservations WHERE schedule_id = $SID" 2>/dev/null)"
    echo "load $(cut -d' ' -f1-3 /proc/loadavg)"
  } >> "$OUT"
  sleep 10
done
