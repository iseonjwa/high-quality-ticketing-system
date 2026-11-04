#!/usr/bin/env bash
# 배포 서버에 시연용 공연·회차·좌석과 샘플 사용자를 넣는다(ISE-177). 첫 배포가 끝난 뒤 서버에서 한 번 돌린다.
#   script/deploy/seed-demo.sh [기준 주소, 기본 http://127.0.0.1]
# 관리자 API와 가입 API만 쓴다(공개 진입점 nginx 80을 거친다). 다시 돌려도 안전하다: 시연 공연이 이미 있으면 만들지 않고,
# 샘플 사용자가 이미 있으면 가입을 건너뛴다. 비밀번호·토큰은 출력하지 않는다.
#
# 환경 변수:
#   OPEN_AT      "오픈 시연" 회차의 예매 오픈 시각(ISO-8601 UTC, 예 2026-11-06T05:00:00Z). 없으면 지금부터 30분 뒤
#   VENUE_ID     공연장 id. 없으면 예약 DB에서 KSPO DOME의 id를 찾는다(공연장 조회 API가 없다)
#   COMPOSE_PROJECT_NAME  예약 DB 컨테이너를 찾을 compose 프로젝트(같은 서버에 스택이 둘일 때만)
# 읽는 .env 키: ADMIN_EMAIL, ADMIN_PASSWORD, SAMPLE_USER_EMAIL, SAMPLE_USER_PASSWORD, SAMPLE_USER_NICKNAME, DB_USER
set -euo pipefail
cd "$(dirname "$0")/../.."
base="${1:-http://127.0.0.1}"
title="이선좌 시연 공연"

# .env는 source하지 않고 키별로 꺼낸다(값에 공백이 있어도 셸이 해석하지 않는다).
env_value() {
  local line
  line=$(grep -m1 "^$1=" .env || true)
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  printf '%s' "$line"
}

fail() { echo "실패: $*" >&2; exit 1; }
command -v jq > /dev/null || fail "jq가 없다. script/deploy/install-docker.sh로 설치하거나 apt install jq를 실행한다"
[[ -f .env ]] || fail ".env가 없다. script/deploy/init-env.sh를 먼저 실행한다"

admin_email=$(env_value ADMIN_EMAIL)
admin_password=$(env_value ADMIN_PASSWORD)
sample_email=$(env_value SAMPLE_USER_EMAIL)
sample_password=$(env_value SAMPLE_USER_PASSWORD)
sample_nickname=$(env_value SAMPLE_USER_NICKNAME)
for name in admin_email admin_password sample_email sample_password sample_nickname; do
  [[ -n "${!name}" ]] || fail ".env에 값이 없다: $name (init-env.sh를 다시 실행하면 빠진 키를 채운다)"
done

# 인증 헤더는 인자에 넣지 않고 임시 파일(0600)로 넘긴다. 본문도 표준 입력으로 보내 비밀번호가 프로세스 목록에 보이지 않게 한다.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
: > "$tmp/auth"

status=0
response=""
# call <메서드> <경로> <기대 상태 코드(공백으로 여러 개)> [JSON 본문]
# 응답 본문은 response, 상태 코드는 status에 둔다. 기대와 다르면 본문의 code·message를 보여 주고 끝낸다.
call() {
  local method="$1" path="$2" expected="$3" data="${4:-}"
  local args=(-sS --max-time 30 -o "$tmp/body" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' -H "@$tmp/auth")
  if [[ -n "$data" ]]; then args+=(--data-binary @-); fi
  if ! status=$(printf '%s' "$data" | curl "${args[@]}" "$base$path" 2> "$tmp/err"); then
    fail "$method $path 요청을 보내지 못했다: $(head -c 200 "$tmp/err")"
  fi
  response=$(cat "$tmp/body")
  if [[ " $expected " != *" $status "* ]]; then
    local code message
    code=$(jq -r '.code // empty' <<< "$response" 2> /dev/null || true)
    message=$(jq -r '.message // empty' <<< "$response" 2> /dev/null || true)
    fail "$method $path 응답 $status (기대 $expected) code=${code:-없음} message=${message:-없음}"
  fi
}

# 지금부터 N초 뒤의 UTC 시각. GNU date(서버)와 BSD date(맥) 모두에서 돈다.
iso_after() {
  local at=$(( $(date +%s) + $1 ))
  date -u -d "@$at" +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -r "$at" +%Y-%m-%dT%H:%M:%SZ
}

echo "== 시연 데이터 시드: $base"

# 1. 관리자 로그인
call POST /api/v1/auth/login 200 "$(jq -n --arg e "$admin_email" --arg p "$admin_password" '{email: $e, password: $p}')"
token=$(jq -r '.data.accessToken // empty' <<< "$response")
[[ -n "$token" ]] || fail "관리자 로그인 응답에 토큰이 없다"
printf 'Authorization: Bearer %s\n' "$token" > "$tmp/auth"
unset token
echo "  관리자 로그인"

# 2. 시연 공연이 이미 있는지 본다. 목록은 페이지로 나뉘므로 마지막 페이지까지 찾는다.
concert_id=""
page=0
while :; do
  call GET "/api/v1/concerts?page=$page&size=100" 200
  concert_id=$(jq -r --arg t "$title" '[.data.content[] | select(.title == $t)][0].concertId // empty' <<< "$response")
  [[ -z "$concert_id" ]] || break
  total=$(jq -r '.data.totalElements' <<< "$response")
  page=$(( page + 1 ))
  (( page * 100 < total )) || break
done

if [[ -n "$concert_id" ]]; then
  echo "  시연 공연이 이미 있다(공연 $concert_id). 공연·회차·좌석 만들기를 건너뛴다"
else
  # 공연장 id: 조회 API가 없어 예약 DB에서 이름으로 찾는다(시드 마이그레이션이 만든 KSPO DOME). 숫자를 박아 두지 않는다.
  venue_id="${VENUE_ID:-}"
  if [[ -z "$venue_id" ]]; then
    filter=(--filter label=com.docker.compose.service=postgres-reservation)
    [[ -z "${COMPOSE_PROJECT_NAME:-}" ]] || filter+=(--filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")
    containers=()
    while IFS= read -r id; do containers+=("$id"); done < <(docker ps -q "${filter[@]}")
    (( ${#containers[@]} == 1 )) || fail "예약 DB 컨테이너를 하나로 정하지 못했다(${#containers[@]}개). VENUE_ID=<id>를 넘기거나 COMPOSE_PROJECT_NAME을 정한다"
    db_user=$(env_value DB_USER); db_user="${db_user:-iseonjwa}"
    venue_id=$(docker exec "${containers[0]}" psql -U "$db_user" -d reservation -tAc "SELECT id FROM venues WHERE name = 'KSPO DOME' ORDER BY id LIMIT 1" | tr -d '[:space:]')
  fi
  [[ "$venue_id" =~ ^[0-9]+$ ]] || fail "공연장(KSPO DOME) id를 찾지 못했다. VENUE_ID=<id>로 넘긴다"
  echo "  공연장 KSPO DOME id=$venue_id"

  call POST /api/v1/admin/concerts 201 "$(jq -n --arg t "$title" '{title: $t, description: "리허설과 발표용 시연 공연이다"}')"
  concert_id=$(jq -r '.data.concertId' <<< "$response")
  echo "  공연 만듦: $concert_id"

  # 회차 1 "지금 예매": 오픈 시각이 이미 지났다(관리자 API는 지난 오픈 시각을 막지 않는다. 오픈이 공연 시작보다 앞서기만 하면 된다).
  # 회차 2 "오픈 시연": OPEN_AT, 없으면 30분 뒤. 공연은 각각 7일, 8일 뒤다.
  open_now=$(iso_after -3600)
  open_demo="${OPEN_AT:-$(iso_after 1800)}"
  prices='{"prices": [{"grade": "VIP", "price": 165000}, {"grade": "R", "price": 143000}, {"grade": "S", "price": 112000}]}'
  for spec in "지금 예매|$open_now|$(iso_after $((7 * 86400)))" "오픈 시연|$open_demo|$(iso_after $((8 * 86400)))"; do
    IFS='|' read -r label open_at starts_at <<< "$spec"
    call POST "/api/v1/admin/concerts/$concert_id/schedules" 201 \
      "$(jq -n --argjson v "$venue_id" --arg s "$starts_at" --arg o "$open_at" '{venueId: $v, startsAt: $s, openAt: $o}')"
    schedule_id=$(jq -r '.data.scheduleId' <<< "$response")
    call POST "/api/v1/admin/schedules/$schedule_id/seats" 201 "$prices"
    echo "  회차 만듦($label): $schedule_id, 오픈 $open_at, 좌석 $(jq -r '.data.createdSeats // empty' <<< "$response")석"
  done
fi

# 3. 샘플 사용자. 이미 있으면(409) 건너뛴다.
call POST /api/v1/users "201 409" \
  "$(jq -n --arg e "$sample_email" --arg p "$sample_password" --arg n "$sample_nickname" '{email: $e, password: $p, nickname: $n}')"
if [[ "$status" == 201 ]]; then echo "  샘플 사용자 가입: $sample_email"; else echo "  샘플 사용자가 이미 있다: $sample_email"; fi

# 요약
call GET "/api/v1/concerts/$concert_id" 200
echo "== 시드 완료"
echo "공연 id: $concert_id"
jq -r '.data.schedules[] | "회차 id: \(.scheduleId)  오픈 \(.openAt)  공연 \(.startsAt)  상태 \(.saleStatus)"' <<< "$response"
echo "샘플 사용자: $sample_email (비밀번호는 서버에서 'grep SAMPLE_USER_PASSWORD .env'로 본다)"
