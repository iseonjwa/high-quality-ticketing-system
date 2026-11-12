#!/usr/bin/env bash
# 배포 서버에서 측정용 새 회차(판매 중, KSPO DOME 15,000석, 1인 4매)를 만들고 회차 ID만 출력한다.
# 로컬 tools/create-schedule.sh와 같은 조건이다. 관리자 계정은 서버 .env에서 읽고 값과 토큰은 출력하지 않는다.
# 사용: create-schedule.sh <제목>   HOLD_MINUTES(기본 5)로 선점 유지 시간을 바꾼다
# Note: 서버의 seed-demo.sh open은 1인 매수·선점 유지 시간을 정하지 않아(서버 기본값) 로컬 측정과 조건이 달라 쓰지 않는다
set -euo pipefail
source "$(dirname "$0")/common.sh"
TITLE="${1:-k6 deploy}"
BASE="${BASE:-http://127.0.0.1:8080}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

post() {  # post <경로> <본문> : 응답 본문을 출력한다. 2xx가 아니면 실패
  local status
  status="$(printf '%s' "$2" | curl -sS --max-time 60 -o "$tmp/body" -w '%{http_code}' -X POST "$BASE$1" \
    -H 'Content-Type: application/json' ${TOKEN:+-H "@$tmp/auth"} --data-binary @-)"
  [[ "$status" == 2* ]] || { echo "POST $1 응답 $status: $(head -c 200 "$tmp/body")" >&2; return 1; }
  cat "$tmp/body"
}

TOKEN=""
login="$(jq -n --arg e "$(env_value ADMIN_EMAIL)" --arg p "$(env_value ADMIN_PASSWORD)" '{email: $e, password: $p}')"
# Note: 이미지를 바꾼 직후 첫 로그인은 user-service·Gateway 경로가 덜 데워져 실패할 수 있다. 측정과 무관한 준비라 몇 번 다시 한다
for attempt in 1 2 3 4 5; do
  if res="$(post /api/v1/auth/login "$login" 2>/dev/null)"; then break; fi
  [ "$attempt" = 5 ] && { echo "관리자 로그인 실패" >&2; exit 1; }
  sleep 5
done
TOKEN="$(jq -r '.data.accessToken' <<< "$res")"
printf 'Authorization: Bearer %s\n' "$TOKEN" > "$tmp/auth"

venue="${VENUE_ID:-$(psql_reservation -tAc "SELECT id FROM venues WHERE name = 'KSPO DOME' ORDER BY id LIMIT 1" | tr -d '[:space:]')}"
[[ "$venue" =~ ^[0-9]+$ ]] || { echo "공연장(KSPO DOME) id를 찾지 못했다" >&2; exit 1; }
concert="$(post /api/v1/admin/concerts "$(jq -n --arg t "$TITLE" '{title: $t, description: "k6 deploy steps"}')" | jq -r '.data.concertId')"
now="$(date -u +%s)"
body="$(jq -n --argjson v "$venue" --arg s "$(date -u -d "@$(( now + 7 * 86400 ))" +%Y-%m-%dT%H:%M:%SZ)" \
  --arg o "$(date -u -d "@$(( now - 60 ))" +%Y-%m-%dT%H:%M:%SZ)" --argjson h "${HOLD_MINUTES:-5}" \
  '{venueId: $v, startsAt: $s, openAt: $o, maxPerUser: 4, holdMinutes: $h}')"
schedule="$(post "/api/v1/admin/concerts/$concert/schedules" "$body" | jq -r '.data.scheduleId')"
post "/api/v1/admin/schedules/$schedule/seats" \
  '{"prices":[{"grade":"VIP","price":165000},{"grade":"R","price":143000},{"grade":"S","price":112000}]}' >/dev/null
echo "$schedule"
