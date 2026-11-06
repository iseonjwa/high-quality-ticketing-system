#!/usr/bin/env bash
# 측정용 새 회차(판매 중, 시드 공연장 15,000석)를 만들고 회차 ID만 출력한다. 절차는 BookingFlowSystemTest와 같다.
# 관리자 계정은 저장소 루트 .env의 ADMIN_EMAIL·ADMIN_PASSWORD를 읽는다. 값과 토큰은 출력하지 않는다.
# 사용: SCHEDULE_ID=$(bash reservation-service/k6/tools/create-schedule.sh "k6 B8")
set -euo pipefail

TITLE="${1:-k6 K1}"
BASE="${BASE:-http://localhost:8080}"
ENV_FILE="$(cd "$(dirname "$0")/../../.." && pwd)/.env"
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
TOKEN="$(post /api/v1/auth/login "$(json email="$ADMIN_EMAIL" password="$ADMIN_PASSWORD")" | field accessToken)"
CONCERT="$(post /api/v1/admin/concerts "$(json title="$TITLE" description=k6)" | field concertId)"
NOW="$(date -u +%s)"
SCHEDULE_BODY="$(python -c "
import json,datetime as d
now=d.datetime.fromtimestamp($NOW,d.timezone.utc)
iso=lambda t:t.strftime('%Y-%m-%dT%H:%M:%SZ')
print(json.dumps({'venueId':1,'startsAt':iso(now+d.timedelta(days=7)),'openAt':iso(now-d.timedelta(seconds=60)),'maxPerUser':4,'holdMinutes':5}))")"
SCHEDULE="$(post "/api/v1/admin/concerts/$CONCERT/schedules" "$SCHEDULE_BODY" | field scheduleId)"
post "/api/v1/admin/schedules/$SCHEDULE/seats" \
  '{"prices":[{"grade":"VIP","price":165000},{"grade":"R","price":143000},{"grade":"S","price":112000}]}' >/dev/null
echo "$SCHEDULE"
