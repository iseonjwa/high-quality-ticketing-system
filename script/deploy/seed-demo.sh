#!/usr/bin/env bash
# 배포 서버에 시연용 공연·회차·좌석과 샘플 사용자를 넣는다(ISE-177). 첫 배포가 끝난 뒤 서버에서 한 번 돌린다.
#   script/deploy/seed-demo.sh [기준 주소, 기본 http://127.0.0.1]
#   script/deploy/seed-demo.sh open <OPEN_AT> [기준 주소]
# 관리자 API와 가입 API만 쓴다(공개 진입점 nginx 80을 거친다). 비밀번호·토큰은 출력하지 않는다.
#
# 기본 실행은 다시 돌려도 안전하다. 시연 공연이 있고 회차가 있으면 만들지 않고, 샘플 사용자가 있으면 가입을 건너뛴다.
# 공연만 있고 회차가 0개면(이전 실행이 중간에 실패) 회차·좌석 만들기를 이어서 한다.
# open 하위 명령은 시연 공연에 "오픈 시연" 회차를 하나 더 만든다. 회차를 고치는 API가 없어, 리허설·시연 직전에
# "몇 분 뒤 오픈" 회차를 새로 만들 때 쓴다. 공연 시작은 OPEN_AT 다음 날이다.
#
# 환경 변수:
#   OPEN_AT      기본 실행에서 "오픈 시연" 회차의 예매 오픈 시각(ISO-8601 UTC, 예 2026-11-06T05:00:00Z). 없으면 지금부터 30분 뒤.
#                형식과 "공연 시작(8일 뒤)보다 앞선다"를 API를 부르기 전에 검사하고, 틀리면 아무것도 만들지 않고 2로 끝난다
#   VENUE_ID     공연장 id. 없으면 예약 DB에서 KSPO DOME의 id를 찾는다(공연장 조회 API가 없다)
#   COMPOSE_PROJECT_NAME  예약 DB 컨테이너를 찾을 compose 프로젝트(같은 서버에 스택이 둘일 때만)
# 읽는 .env 키: ADMIN_EMAIL, ADMIN_PASSWORD, SAMPLE_USER_EMAIL, SAMPLE_USER_PASSWORD, SAMPLE_USER_NICKNAME, DB_USER
# 종료 코드: 0 성공, 1 실패(API 응답 등), 2 사용법·입력 오류
set -euo pipefail
cd "$(dirname "$0")/../.."
title="이선좌 시연 공연"
iso_format='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'

usage() { echo "사용법: script/deploy/seed-demo.sh [기준 주소]  |  script/deploy/seed-demo.sh open <OPEN_AT> [기준 주소]" >&2; exit 2; }
bad_input() { echo "입력 오류: $*" >&2; exit 2; }
fail() { echo "실패: $*" >&2; exit 1; }

# 시각 변환. GNU date(서버)와 BSD date(맥) 모두에서 돈다.
epoch_of() {
  date -u -d "$1" +%s 2> /dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s 2> /dev/null
}
iso_of() {
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2> /dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}

mode=seed
if [[ "${1:-}" == "open" ]]; then
  mode=open
  [[ $# -ge 2 && $# -le 3 ]] || usage
  open_at_input="$2"
  base="${3:-http://127.0.0.1}"
else
  [[ $# -le 1 ]] || usage
  open_at_input="${OPEN_AT:-}"
  base="${1:-http://127.0.0.1}"
fi

# API를 부르기 전에 시각을 검사한다. 틀리면 아무것도 만들지 않는다.
now=$(date +%s)
day=86400
open_demo=""
if [[ -n "$open_at_input" ]]; then
  [[ "$open_at_input" =~ $iso_format ]] || bad_input "OPEN_AT은 ISO-8601 UTC(예 2026-11-06T05:00:00Z) 형식이어야 한다: $open_at_input"
  open_epoch=$(epoch_of "$open_at_input") || bad_input "OPEN_AT이 올바른 시각이 아니다: $open_at_input"
  [[ "$(iso_of "$open_epoch")" == "$open_at_input" ]] || bad_input "OPEN_AT이 올바른 시각이 아니다: $open_at_input"
  open_demo="$open_at_input"
else
  [[ "$mode" == seed ]] || usage
  open_epoch=$(( now + 1800 ))
  open_demo=$(iso_of "$open_epoch")
fi
if [[ "$mode" == seed ]]; then
  starts_now_epoch=$(( now + 7 * day ))
  starts_demo_epoch=$(( now + 8 * day ))
  (( open_epoch < starts_demo_epoch )) || bad_input "OPEN_AT($open_demo)은 오픈 시연 회차의 공연 시작($(iso_of "$starts_demo_epoch"), 8일 뒤)보다 앞서야 한다"
else
  starts_demo_epoch=$(( open_epoch + day ))
fi

# .env는 source하지 않고 키별로 꺼낸다(값에 공백이 있어도 셸이 해석하지 않는다).
env_value() {
  local line
  line=$(grep -m1 "^$1=" .env || true)
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  printf '%s' "$line"
}

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

# 시연 공연의 id를 concert_id에 둔다(없으면 빈 값). 목록은 페이지로 나뉘므로 마지막 페이지까지 찾는다.
find_concert() {
  concert_id=""
  local page=0 total
  while :; do
    call GET "/api/v1/concerts?page=$page&size=100" 200
    concert_id=$(jq -r --arg t "$title" '[.data.content[] | select(.title == $t)][0].concertId // empty' <<< "$response")
    [[ -z "$concert_id" ]] || break
    total=$(jq -r '.data.totalElements' <<< "$response")
    page=$(( page + 1 ))
    (( page * 100 < total )) || break
  done
}

# 공연장 id를 venue_id에 둔다. 조회 API가 없어 예약 DB에서 이름으로 찾는다(시드 마이그레이션이 만든 KSPO DOME). 숫자를 박아 두지 않는다.
resolve_venue() {
  venue_id="${VENUE_ID:-}"
  if [[ -z "$venue_id" ]]; then
    local filter=(--filter label=com.docker.compose.service=postgres-reservation) containers=() id db_user
    [[ -z "${COMPOSE_PROJECT_NAME:-}" ]] || filter+=(--filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")
    while IFS= read -r id; do containers+=("$id"); done < <(docker ps -q "${filter[@]}")
    (( ${#containers[@]} == 1 )) || fail "예약 DB 컨테이너를 하나로 정하지 못했다(${#containers[@]}개). VENUE_ID=<id>를 넘기거나 COMPOSE_PROJECT_NAME을 정한다"
    db_user=$(env_value DB_USER); db_user="${db_user:-iseonjwa}"
    venue_id=$(docker exec "${containers[0]}" psql -U "$db_user" -d reservation -tAc "SELECT id FROM venues WHERE name = 'KSPO DOME' ORDER BY id LIMIT 1" | tr -d '[:space:]')
  fi
  [[ "$venue_id" =~ ^[0-9]+$ ]] || fail "공연장(KSPO DOME) id를 찾지 못했다. VENUE_ID=<id>로 넘긴다"
  echo "  공연장 KSPO DOME id=$venue_id"
}

prices='{"prices": [{"grade": "VIP", "price": 165000}, {"grade": "R", "price": 143000}, {"grade": "S", "price": 112000}]}'
# create_schedule <이름> <오픈 시각> <공연 시작> : 회차와 그 좌석을 만든다. 새 회차 id는 schedule_id에 둔다.
create_schedule() {
  call POST "/api/v1/admin/concerts/$concert_id/schedules" 201 \
    "$(jq -n --argjson v "$venue_id" --arg s "$3" --arg o "$2" '{venueId: $v, startsAt: $s, openAt: $o}')"
  schedule_id=$(jq -r '.data.scheduleId' <<< "$response")
  call POST "/api/v1/admin/schedules/$schedule_id/seats" 201 "$prices"
  echo "  회차 만듦($1): $schedule_id, 오픈 $2, 좌석 $(jq -r '.data.createdSeats // empty' <<< "$response")석"
}

echo "== 시연 데이터 시드($mode): $base"

# 관리자 로그인
call POST /api/v1/auth/login 200 "$(jq -n --arg e "$admin_email" --arg p "$admin_password" '{email: $e, password: $p}')"
token=$(jq -r '.data.accessToken // empty' <<< "$response")
[[ -n "$token" ]] || fail "관리자 로그인 응답에 토큰이 없다"
printf 'Authorization: Bearer %s\n' "$token" > "$tmp/auth"
unset token
echo "  관리자 로그인"

find_concert

if [[ "$mode" == open ]]; then
  [[ -n "$concert_id" ]] || fail "시연 공연이 없다. 먼저 script/deploy/seed-demo.sh를 실행한다"
  resolve_venue
  create_schedule "오픈 시연" "$open_demo" "$(iso_of "$starts_demo_epoch")"
  echo "== 오픈 시연 회차 추가 완료"
  echo "공연 id: $concert_id"
  echo "새 회차 id: $schedule_id  오픈 $open_demo"
  exit 0
fi

if [[ -n "$concert_id" ]]; then
  call GET "/api/v1/concerts/$concert_id" 200
  schedule_count=$(jq -r '.data.schedules | length' <<< "$response")
  if (( schedule_count > 0 )); then
    echo "  시연 공연이 이미 있다(공연 $concert_id, 회차 ${schedule_count}개). 공연·회차·좌석 만들기를 건너뛴다"
  else
    echo "  시연 공연은 있으나 회차가 없다(공연 $concert_id). 이전 실행이 중간에 실패한 것으로 보고 회차·좌석 만들기를 이어서 한다"
  fi
else
  schedule_count=0
fi

if (( schedule_count == 0 )); then
  resolve_venue
  if [[ -z "$concert_id" ]]; then
    call POST /api/v1/admin/concerts 201 "$(jq -n --arg t "$title" '{title: $t, description: "리허설과 발표용 시연 공연이다"}')"
    concert_id=$(jq -r '.data.concertId' <<< "$response")
    echo "  공연 만듦: $concert_id"
  fi
  # 회차 1 "지금 예매": 오픈 시각이 이미 지났다(관리자 API는 지난 오픈 시각을 막지 않는다. 오픈이 공연 시작보다 앞서기만 하면 된다).
  # 회차 2 "오픈 시연": OPEN_AT, 없으면 30분 뒤. 공연은 각각 7일, 8일 뒤다.
  create_schedule "지금 예매" "$(iso_of $(( now - 3600 )))" "$(iso_of "$starts_now_epoch")"
  create_schedule "오픈 시연" "$open_demo" "$(iso_of "$starts_demo_epoch")"
fi

# 샘플 사용자. 이미 있으면(409) 건너뛴다.
call POST /api/v1/users "201 409" \
  "$(jq -n --arg e "$sample_email" --arg p "$sample_password" --arg n "$sample_nickname" '{email: $e, password: $p, nickname: $n}')"
if [[ "$status" == 201 ]]; then echo "  샘플 사용자 가입: $sample_email"; else echo "  샘플 사용자가 이미 있다: $sample_email"; fi

# 요약
call GET "/api/v1/concerts/$concert_id" 200
echo "== 시드 완료"
echo "공연 id: $concert_id"
jq -r '.data.schedules[] | "회차 id: \(.scheduleId)  오픈 \(.openAt)  공연 \(.startsAt)  상태 \(.saleStatus)"' <<< "$response"
echo "샘플 사용자: $sample_email (비밀번호는 서버에서 'grep SAMPLE_USER_PASSWORD .env'로 본다)"
