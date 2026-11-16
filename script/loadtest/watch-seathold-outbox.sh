#!/usr/bin/env bash
# ISE-296 보조: 접수형 선점(O·D 단계) 동안 구역 키별 Outbox 적체와 처리 중 요청 수를 INTERVAL초마다 CSV로 남긴다.
# 배포 서버에서 기록만 한다. 측정과 같은 시간에 SSH 창을 따로 열어 실행하고, 결과 CSV는 scp로 내려받는다.
#   bash script/loadtest/watch-seathold-outbox.sh <라벨> [초, 기본 900] [간격, 기본 15]
#   예: bash script/loadtest/watch-seathold-outbox.sh O-k1 900
# 결과: loadtest/k6/results/outbox-<라벨>-<시각>.csv
#   epoch_ms,message_key,pending,oldest_ms
#   - message_key가 구역 키({scheduleId}:{section}, ADR 0056)인 줄: 그 키의 PENDING 건수와 가장 오래된 행의 나이(ms). 많은 순 20개
#   - message_key가 _total인 줄: seat-hold.request 토픽 PENDING 전체 건수(pending)와 가장 오래된 나이
#   - message_key가 _requested인 줄: seat_hold_requests의 REQUESTED 건수(pending). 접수형에서 동기형으로 돌아가기 전에 0인지 본다
# 근거: docs/design/reservation/async-seathold-remeasure-plan.md 4-3절. 직접 발행(D)은 Outbox 행을 쓰지 않으므로 _total이 0이다.
#
# 컨테이너 이름(iseonjwa-postgres-reservation-1)과 DB 사용자(iseonjwa)는 loadtest/k6/tools/watch-held.sh와 같다.
# 다르면 PG_CONTAINER, DB_USER로 준다. 비밀번호는 쓰지 않는다(컨테이너 안 psql은 로컬 소켓으로 붙는다).
# 시각은 서버의 date +%s%3N(GNU date)이다.
set -euo pipefail

LABEL="${1:?라벨이 필요합니다. 예: O-k1}"
SECONDS_TO_WATCH="${2:-900}"
INTERVAL="${3:-15}"
PG="${PG_CONTAINER:-iseonjwa-postgres-reservation-1}"
USER_NAME="${DB_USER:-iseonjwa}"
OUT="$(cd "$(dirname "$0")/../.." && pwd)/loadtest/k6/results/outbox-${LABEL}-$(date +%Y%m%d-%H%M%S).csv"
mkdir -p "$(dirname "$OUT")"

# Note: 한 문장으로 세 종류 줄을 낸다. 표본마다 psql을 한 번만 띄워 간격을 덜 흔든다
SQL="
(select message_key, count(*), (extract(epoch from now() - min(created_at)) * 1000)::bigint
   from outbox_events where topic = 'seat-hold.request' and status = 'PENDING'
  group by message_key order by count(*) desc limit 20)
union all
select '_total', count(*), coalesce((extract(epoch from now() - min(created_at)) * 1000)::bigint, 0)
  from outbox_events where topic = 'seat-hold.request' and status = 'PENDING'
union all
select '_requested', count(*), coalesce((extract(epoch from now() - min(created_at)) * 1000)::bigint, 0)
  from seat_hold_requests where status = 'REQUESTED'"

echo "epoch_ms,message_key,pending,oldest_ms" > "$OUT"
for _ in $(seq 1 $((SECONDS_TO_WATCH / INTERVAL))); do
  now="$(date +%s%3N)"
  docker exec "$PG" psql -U "$USER_NAME" -d reservation -At -F, -c "$SQL" | sed "s/^/${now},/" >> "$OUT"
  sleep "$INTERVAL"
done
echo "$OUT"
