#!/usr/bin/env bash
# 배포 서버를 실제 서비스처럼 보이는 공연·회차로 채우고, 측정·리허설 공연을 목록에서 숨긴다(ISE-302).
#   script/deploy/seed-showcase.sh [기준 주소, 기본 http://127.0.0.1]
#   SHOWCASE_DRY_RUN=1 script/deploy/seed-showcase.sh   # 바꿀 것만 보여 주고 아무것도 바꾸지 않는다
#
# 정의는 frontend/src/data/showcase.json 한 곳에 있다. 화면은 같은 파일의 제목으로 공연 사진을 고른다.
# 다시 돌려도 안전하다. 같은 이름의 공연장, 같은 제목의 공연은 다시 만들지 않는다. 공연만 있고 회차가 0개면
# (이전 실행이 중간에 실패) 회차·좌석을 이어서 만든다.
#
# 하는 일
#   1. 공연장: 조회·생성 API가 없어 예약 DB에 직접 넣는다(venues + venue_seats). KSPO DOME은 시드 마이그레이션에 있다.
#   2. 숨김: hide.titles·hide.titlePrefixes에 맞는 공연을 지운 것으로 표시한다(deleted_at). 예매·결제 행은 그대로 두고,
#      "내 예매"는 지운 공연도 보인다(SpringDataReservationRepository.findMineRows). 되돌리기는 아래 "되돌리기"를 본다.
#      JIT 예열 공연([예열 전용])은 배포마다 같은 공연을 찾아 다시 쓰므로 지우지 않는다. 화면 목록에서만 뺀다.
#   3. 공연·회차·좌석: 관리자 API로 만든다(공개 진입점 nginx를 거친다).
#
# 되돌리기(숨긴 공연 다시 보이기): 예약 DB에서
#   UPDATE concerts SET deleted_at = NULL WHERE deleted_at >= '<실행 시각>';
#
# 읽는 .env 키: ADMIN_EMAIL, ADMIN_PASSWORD, DB_USER
# 환경 변수: COMPOSE_PROJECT_NAME(같은 서버에 스택이 둘일 때 예약 DB 컨테이너를 고른다), SHOWCASE_DRY_RUN
# 종료 코드: 0 성공, 1 실패(API 응답·DB 오류 등), 2 사용법 오류
set -euo pipefail
cd "$(dirname "$0")/../.."

definition=frontend/src/data/showcase.json
base="${1:-http://127.0.0.1}"
dry_run="${SHOWCASE_DRY_RUN:-}"
[[ $# -le 1 ]] || { echo "사용법: script/deploy/seed-showcase.sh [기준 주소]" >&2; exit 2; }

fail() { echo "실패: $*" >&2; exit 1; }

command -v jq > /dev/null || fail "jq가 없다. script/deploy/install-docker.sh로 설치하거나 apt install jq를 실행한다"
[[ -f .env ]] || fail ".env가 없다. script/deploy/init-env.sh를 먼저 실행한다"
[[ -f "$definition" ]] || fail "정의 파일이 없다: $definition"

# 정의 파일의 값이 SQL에 들어가므로 먼저 모양을 확인한다. 구역은 대문자 한 글자, 등급은 VIP·R·S, 이름에는 작은따옴표가 없어야 한다
jq -e --arg q "'" '
  (.venues | all(
      (.name | test("^[^" + $q + "]+$"))
      and (.rows | type == "number" and . >= 1 and . <= 50)
      and (.seatsPerRow | type == "number" and . >= 1 and . <= 100)
      and (.sections | keys - ["R", "S", "VIP"] | length == 0)
      and ([.sections[][]] | all(test("^[A-Z]$")))))
  and (.concerts | all(
      (.title | length >= 1 and length <= 200)
      and (.prices | keys == ["R", "S", "VIP"])
      and (.schedules | length >= 1)
      and ([.schedules[] | .openAt, .startsAt] | all(test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$")))))
  and ([.hide.titles[], .hide.titlePrefixes[]] | all(test("^[^" + $q + "%_]+$")))
' "$definition" > /dev/null || fail "정의 파일 모양이 맞지 않다: $definition"

# .env는 source하지 않고 키별로 꺼낸다(값에 공백이 있어도 셸이 해석하지 않는다)
env_value() {
  local line
  line=$(grep -m1 "^$1=" .env || true)
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  printf '%s' "$line"
}

admin_email=$(env_value ADMIN_EMAIL)
admin_password=$(env_value ADMIN_PASSWORD)
db_user=$(env_value DB_USER); db_user="${db_user:-iseonjwa}"
[[ -n "$admin_email" && -n "$admin_password" ]] || fail ".env에 ADMIN_EMAIL·ADMIN_PASSWORD가 없다"

# 예약 DB 컨테이너. 공연장 조회·생성 API가 없어 DB를 직접 쓴다
filter=(--filter label=com.docker.compose.service=postgres-reservation)
[[ -z "${COMPOSE_PROJECT_NAME:-}" ]] || filter+=(--filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME")
containers=()
while IFS= read -r id; do containers+=("$id"); done < <(docker ps -q "${filter[@]}")
(( ${#containers[@]} == 1 )) || fail "예약 DB 컨테이너를 하나로 정하지 못했다(${#containers[@]}개). COMPOSE_PROJECT_NAME을 정한다"
db_container="${containers[0]}"

# sql <SQL> : 결과를 구분자 없이 한 줄씩 돌려준다. SQL은 표준 입력으로 넘긴다
sql() {
  printf '%s\n' "$1" | docker exec -i "$db_container" psql -X -q -v ON_ERROR_STOP=1 -U "$db_user" -d reservation -tA
}

# 인증 헤더는 인자에 넣지 않고 임시 파일(0600)로 넘긴다. 본문도 표준 입력으로 보내 비밀번호가 프로세스 목록에 보이지 않게 한다
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
: > "$tmp/auth"

status=0
response=""
# call <메서드> <경로> <기대 상태 코드(공백으로 여러 개)> [JSON 본문] : 응답 본문은 response, 상태 코드는 status에 둔다
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

# 한국 시간 "2026-12-05T18:00"을 UTC ISO-8601("2026-12-05T09:00:00Z")로 바꾼다
kst_to_utc='(. + ":00Z" | fromdate) - 32400 | todate'

echo "== 배포 공연 채우기: $base${dry_run:+ (확인만)}"

# 1. 공연장
venue_count=$(jq '.venues | length' "$definition")
for (( i = 0; i < venue_count; i++ )); do
  venue=$(jq -c ".venues[$i]" "$definition")
  name=$(jq -r '.name' <<< "$venue")
  existing=$(sql "SELECT id FROM venues WHERE name = '$name' AND deleted_at IS NULL ORDER BY id LIMIT 1")
  if [[ -n "$existing" ]]; then
    echo "  공연장 있음: $name (id=$existing)"
    continue
  fi
  # 구역마다 등급을 붙인 VALUES 목록. 정의 파일 검사로 구역·등급이 안전한 글자뿐임을 확인했다
  sections=$(jq -r --arg q "'" '[.sections | to_entries[] | .key as $grade | .value[] | "(\($q)\(.)\($q), \($q)\($grade)\($q))"] | join(", ")' <<< "$venue")
  rows=$(jq -r '.rows' <<< "$venue")
  per_row=$(jq -r '.seatsPerRow' <<< "$venue")
  seats=$(jq -r '[.sections[][]] | length * '"$rows * $per_row" <<< "$venue")
  if [[ -n "$dry_run" ]]; then
    echo "  공연장 만들 예정: $name (${seats}석)"
    continue
  fi
  sql "WITH venue AS (
         INSERT INTO venues (name, created_at, updated_at) VALUES ('$name', now(), now()) RETURNING id
       )
       INSERT INTO venue_seats (venue_id, section, row_no, seat_no, grade, created_at, updated_at)
       SELECT venue.id, s.section, row_no, seat_no, s.grade, now(), now()
         FROM venue, (VALUES $sections) AS s(section, grade),
              generate_series(1, $rows) AS row_no, generate_series(1, $per_row) AS seat_no" > /dev/null
  echo "  공연장 만듦: $name (${seats}석)"
done

# 2. 측정·리허설 공연 숨기기
hide_where=$(jq -r --arg q "'" '
  ([.hide.titles[] | "title = \($q)\(.)\($q)"] + [.hide.titlePrefixes[] | "title LIKE \($q)\(.)%\($q)"]) | join(" OR ")
' "$definition")
to_hide=$(sql "SELECT count(*) FROM concerts WHERE deleted_at IS NULL AND ($hide_where)")
if [[ -n "$dry_run" ]]; then
  echo "  숨길 예정: 공연 ${to_hide}개"
  sql "SELECT '    - ' || id || ' ' || title FROM concerts WHERE deleted_at IS NULL AND ($hide_where) ORDER BY id"
else
  sql "UPDATE concerts SET deleted_at = now() WHERE deleted_at IS NULL AND ($hide_where)" > /dev/null
  echo "  숨김: 공연 ${to_hide}개(되돌리기는 이 파일 머리말)"
fi

# 3. 공연·회차·좌석
call POST /api/v1/auth/login 200 "$(jq -n --arg e "$admin_email" --arg p "$admin_password" '{email: $e, password: $p}')"
token=$(jq -r '.data.accessToken // empty' <<< "$response")
[[ -n "$token" ]] || fail "관리자 로그인 응답에 토큰이 없다"
printf 'Authorization: Bearer %s\n' "$token" > "$tmp/auth"
unset token

concert_count=$(jq '.concerts | length' "$definition")
created=0
for (( i = 0; i < concert_count; i++ )); do
  concert=$(jq -c ".concerts[$i]" "$definition")
  title=$(jq -r '.title' <<< "$concert")
  # 제목은 정의 파일 검사를 거쳤지만 작은따옴표를 SQL 문자열 규칙대로 두 번 써서 넘긴다
  concert_id=$(sql "SELECT id FROM concerts WHERE title = '${title//\'/\'\'}' AND deleted_at IS NULL ORDER BY id LIMIT 1")
  schedule_total=$(jq '.schedules | length' <<< "$concert")
  if [[ -n "$concert_id" ]]; then
    existing_schedules=$(sql "SELECT count(*) FROM schedules WHERE concert_id = $concert_id AND deleted_at IS NULL")
    if (( existing_schedules > 0 )); then
      echo "  공연 있음: $title (id=$concert_id, 회차 ${existing_schedules}개)"
      continue
    fi
  fi
  if [[ -n "$dry_run" ]]; then
    echo "  공연 만들 예정: $title (회차 ${schedule_total}개)"
    continue
  fi
  if [[ -z "$concert_id" ]]; then
    call POST /api/v1/admin/concerts 201 "$(jq '{title, description}' <<< "$concert")"
    concert_id=$(jq -r '.data.concertId' <<< "$response")
  fi
  prices=$(jq '{prices: (.prices | to_entries | map({grade: .key, price: .value}))}' <<< "$concert")
  max_per_user=$(jq -r '.maxPerUser // 4' <<< "$concert")
  for (( j = 0; j < schedule_total; j++ )); do
    schedule=$(jq -c ".schedules[$j]" <<< "$concert")
    venue_name=$(jq -r '.venue' <<< "$schedule")
    venue_id=$(sql "SELECT id FROM venues WHERE name = '${venue_name//\'/\'\'}' AND deleted_at IS NULL ORDER BY id LIMIT 1")
    [[ "$venue_id" =~ ^[0-9]+$ ]] || fail "공연장을 찾지 못했다: $venue_name"
    body=$(jq --argjson v "$venue_id" --argjson m "$max_per_user" \
      "{venueId: \$v, maxPerUser: \$m, openAt: (.openAt | $kst_to_utc), startsAt: (.startsAt | $kst_to_utc)}" <<< "$schedule")
    call POST "/api/v1/admin/concerts/$concert_id/schedules" 201 "$body"
    schedule_id=$(jq -r '.data.scheduleId' <<< "$response")
    call POST "/api/v1/admin/schedules/$schedule_id/seats" 201 "$prices"
  done
  created=$(( created + 1 ))
  echo "  공연 만듦: $title (id=$concert_id, 회차 ${schedule_total}개)"
done

echo "== 끝: 새 공연 ${created}개${dry_run:+ (확인만, 바꾼 것 없음)}"
