#!/usr/bin/env bash
# X1 보조: 회차의 예매 상태별 건수(HELD·EXPIRED 등)를 INTERVAL초마다 CSV로 남긴다. local PostgreSQL 컨테이너 전용.
# 사용: bash loadtest/k6/tools/watch-held.sh <회차 ID> [초, 기본 420] [간격, 기본 10]
# 결과: loadtest/k6/results/held-<회차>-<시각>.csv (epoch_ms, held, expired, 만료 시각이 지났는데 HELD인 수)
set -euo pipefail

SID="${1:?회차 ID가 필요합니다}"
SECONDS_TO_WATCH="${2:-420}"
INTERVAL="${3:-10}"
PG="${PG_CONTAINER:-iseonjwa-postgres-reservation-1}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/results/held-${SID}-$(date +%Y%m%d-%H%M%S).csv"
mkdir -p "$(dirname "$OUT")"

SQL="select count(*) filter (where status = 'HELD'), count(*) filter (where status = 'EXPIRED'),
  count(*) filter (where status = 'HELD' and hold_expires_at < now()) from reservations where schedule_id = ${SID}"
echo "epoch_ms,held,expired,overdue_held" > "$OUT"
for _ in $(seq 1 $((SECONDS_TO_WATCH / INTERVAL))); do
  echo "$(date +%s%3N),$(docker exec "$PG" psql -U iseonjwa -d reservation -At -F, -c "$SQL")" >> "$OUT"
  sleep "$INTERVAL"
done
echo "$OUT"
