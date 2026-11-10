#!/usr/bin/env bash
# 측정용 새 회차(판매 중, 시드 공연장 15,000석)를 만들고 회차 ID만 출력한다. 절차는 BookingFlowSystemTest와 같다.
# 관리자 계정은 저장소 루트 .env의 ADMIN_EMAIL·ADMIN_PASSWORD를 읽는다. 값과 토큰은 출력하지 않는다.
# 사용: SCHEDULE_ID=$(bash reservation-service/k6/tools/create-schedule.sh "k6 B8")
# HOLD_MINUTES로 선점 유지 시간(분, 기본 5)을 바꾼다. 만료를 측정 안에서 일으키는 X에서 1로 쓴다
set -euo pipefail

TITLE="${1:-k6 K1}"
BASE="${BASE:-http://localhost:8080}"
ENV_FILE="${ENV_FILE:-$(cd "$(dirname "$0")/../../.." && pwd)/.env}"
for key in ADMIN_EMAIL ADMIN_PASSWORD; do
  if [ -z "${!key:-}" ]; then
    value="$(grep -E "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2-)"
    [ -n "$value" ] || { echo "$key 값이 .env에 없습니다" >&2; exit 1; }
    export "$key=$value"
  fi
done

# JSON 본문과 응답 필드는 python으로 다룬다(jq가 없는 Git Bash 기준)
json() { python -c "import json,sys; print(json.dumps(dict(a.split('=',1) for a in sys.argv[1:])))" "$@"; }
field() { python -c "import json,sys; print(json.load(sys.stdin)['data']['$1'])"; }
post() {
  curl -sf -X POST "$BASE$1" -H 'Content-Type: application/json' ${TOKEN:+-H "Authorization: Bearer $TOKEN"} \
    --max-time 60 -d "$2"
}

TOKEN=""
# Note: 스택을 새로 띄운 직후 첫 로그인은 user-service가 데워지지 않아 Gateway 시한(5초)을 넘길 수 있다(504, 이어서 503).
# 측정과 무관한 준비 단계라 몇 번 다시 시도한다
for attempt in 1 2 3 4 5; do
  LOGIN="$(post /api/v1/auth/login "$(json email="$ADMIN_EMAIL" password="$ADMIN_PASSWORD")" || true)"
  [ -n "$LOGIN" ] && break
  echo "관리자 로그인 실패, 5초 뒤 다시 시도($attempt/5)" >&2
  sleep 5
done
TOKEN="$(printf '%s' "$LOGIN" | field accessToken)"
CONCERT="$(post /api/v1/admin/concerts "$(json title="$TITLE" description=k6)" | field concertId)"
NOW="$(date -u +%s)"
SCHEDULE_BODY="$(python -c "
import json,datetime as d
now=d.datetime.fromtimestamp($NOW,d.timezone.utc)
iso=lambda t:t.strftime('%Y-%m-%dT%H:%M:%SZ')
print(json.dumps({'venueId':1,'startsAt':iso(now+d.timedelta(days=7)),'openAt':iso(now-d.timedelta(seconds=60)),'maxPerUser':4,'holdMinutes':${HOLD_MINUTES:-5}}))")"
SCHEDULE="$(post "/api/v1/admin/concerts/$CONCERT/schedules" "$SCHEDULE_BODY" | field scheduleId)"
post "/api/v1/admin/schedules/$SCHEDULE/seats" \
  '{"prices":[{"grade":"VIP","price":165000},{"grade":"R","price":143000},{"grade":"S","price":112000}]}' >/dev/null
echo "$SCHEDULE"
