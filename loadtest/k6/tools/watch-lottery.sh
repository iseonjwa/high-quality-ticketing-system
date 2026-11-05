#!/usr/bin/env bash
# L2·L3 보조: 추첨 응모 상태별 수(ENTERED·WON·WAITLISTED·LOST·PAID·FAILED)와 배정된 예매 수를 주기마다 CSV로 남긴다.
# local PostgreSQL 컨테이너 전용.
# 사용: bash loadtest/k6/tools/watch-lottery.sh <추첨 ID> [초, 기본 720] [간격, 기본 10]
set -euo pipefail

LID="${1:?추첨 ID가 필요합니다}"
SECONDS_TO_WATCH="${2:-720}"
INTERVAL="${3:-10}"
PG="${PG_CONTAINER:-iseonjwa-postgres-reservation-1}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/results/lottery-${LID}-$(date +%Y%m%d-%H%M%S).csv"
mkdir -p "$(dirname "$OUT")"

SQL="select count(*) filter (where status = 'ENTERED'), count(*) filter (where status = 'WON'),
  count(*) filter (where status = 'WAITLISTED'), count(*) filter (where status = 'LOST'),
  count(*) filter (where status = 'PAID'), count(*) filter (where status = 'FAILED'),
  count(reservation_id) from lottery_entries where lottery_id = ${LID}"
echo "epoch_ms,entered,won,waitlisted,lost,paid,failed,assigned" > "$OUT"
for _ in $(seq 1 $((SECONDS_TO_WATCH / INTERVAL))); do
  echo "$(date +%s%3N),$(docker exec "$PG" psql -U iseonjwa -d reservation -At -F, -c "$SQL")" >> "$OUT"
  sleep "$INTERVAL"
done
echo "$OUT"
