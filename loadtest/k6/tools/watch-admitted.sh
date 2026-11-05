#!/usr/bin/env bash
# Q3 보조: 회차의 입장 수(admitted)와 순번·대기 수를 1초마다 CSV로 남긴다. local Redis 컨테이너 전용.
# 사용: bash loadtest/k6/tools/watch-admitted.sh <회차 ID> [초, 기본 700]
# 결과: loadtest/k6/results/admitted-<회차>-<시각>.csv (redis_epoch_ms, admitted, seq, waiting)
# Note: docker exec 한 번에 약 0.6초가 걸려, 반복을 컨테이너 안에서 돌리고 시각은 Redis TIME으로 찍는다
set -euo pipefail

SID="${1:?회차 ID가 필요합니다}"
SECONDS_TO_WATCH="${2:-700}"
REDIS="${REDIS_CONTAINER:-iseonjwa-redis-1}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/results/admitted-${SID}-$(date +%Y%m%d-%H%M%S).csv"
mkdir -p "$(dirname "$OUT")"

SCRIPT="local t = redis.call('TIME')
return {tostring(t[1] * 1000 + math.floor(t[2] / 1000)), redis.call('GET', KEYS[1]) or '0',
  redis.call('GET', KEYS[2]) or '0', tostring(redis.call('ZCARD', KEYS[3]))}"

echo "redis_epoch_ms,admitted,seq,waiting" > "$OUT"
# 키에 해시 태그({회차})가 있어 따옴표로 감싼다. 한 번의 EVAL이 한 줄(쉼표로 이은 4개 값)이 된다
MSYS_NO_PATHCONV=1 docker exec "$REDIS" sh -c "
  i=0
  while [ \$i -lt $SECONDS_TO_WATCH ]; do
    redis-cli --raw EVAL \"\$0\" 3 'queue:{$SID}:admitted' 'queue:{$SID}:seq' 'queue:{$SID}:waiting' | paste -sd, -
    i=\$((i + 1))
    sleep 1
  done" "$SCRIPT" >> "$OUT"
echo "$OUT"
