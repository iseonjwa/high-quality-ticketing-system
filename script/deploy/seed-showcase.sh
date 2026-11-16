#!/usr/bin/env bash
# 배포 서버를 실제 서비스처럼 보이는 공연·회차로 채우고, 측정·리허설 공연을 목록에서 숨긴다(ISE-302).
#   script/deploy/seed-showcase.sh [기준 주소, 기본 http://127.0.0.1]
#   SHOWCASE_DRY_RUN=1 script/deploy/seed-showcase.sh   # 바꿀 것만 보여 주고 아무것도 바꾸지 않는다
#
# 정의는 frontend/src/data/showcase.json 한 곳에 있다. 화면은 같은 파일의 제목으로 공연 사진을 고른다.
# 다시 돌려도 안전하다. 공연장은 이름, 공연은 제목, 회차는 (공연, 공연장, 시작 시각)으로 이미 있는지 보고 없는 것만 만든다.
# 좌석이 없는 회차는 좌석만 만든다. 그래서 중간에 실패해도 다시 돌리면 빠진 것만 채운다.
#
# 하는 일(이 순서로 한다)
#   1. 공연장: 조회·생성 API가 없어 예약 DB에 직접 넣는다(venues + venue_seats). KSPO DOME은 시드 마이그레이션에 있다.
#   2. 공연·회차·좌석: 관리자 API로 만든다(공개 진입점 nginx를 거친다).
#   3. 숨김: hide.titles·hide.titlePrefixes에 맞는 공연을 지운 것으로 표시한다(deleted_at). 새 공연을 다 만든 뒤에 해서,
#      앞 단계가 실패하면 기존 공연은 그대로 보인다. 예매·결제 행은 그대로 두고 "내 예매"는 지운 공연도 보인다
#      (SpringDataReservationRepository.findMineRows). 숨긴 공연 id와 되돌리는 SQL을 끝에 출력한다.
#      JIT 예열 공연([예열 전용])은 배포마다 같은 공연을 찾아 다시 쓰므로 지우지 않는다. 화면 목록에서만 뺀다.
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

# 정의 파일의 값이 SQL에 들어가므로 먼저 모양을 확인한다. 이름·제목에는 작은따옴표와 역슬래시가 없어야 하고,
# 숨길 제목에는 LIKE 와일드카드(%, _)도 없어야 한다. 구역은 대문자 한 글자, 등급은 VIP·R·S다.
# 회차의 공연장은 정의 파일의 공연장이나 KSPO DOME이어야 하고 오픈이 공연 시작보다 앞서야 한다(API를 부르기 전에 막는다)
jq -e --arg q "'" '
  ("^[^" + $q + "\\\\]+$") as $safe
  | ("^[^" + $q + "\\\\%_]+$") as $safe_like
  | ([.venues[].name] + ["KSPO DOME"]) as $venue_names
  | (.venues | all(
      (.name | test($safe))
      and (.rows | type == "number" and . >= 1 and . <= 50)
      and (.seatsPerRow | type == "number" and . >= 1 and . <= 100)
      and (.sections | keys - ["R", "S", "VIP"] | length == 0)
      and ([.sections[][]] | all(test("^[A-Z]$")))))
  and (.concerts | all(
      (.title | test($safe) and length <= 200)
      and (.prices | keys == ["R", "S", "VIP"])
      and (.schedules | length >= 1)
      and (.schedules | all(
          (.venue | IN($venue_names[]))
          and ([.openAt, .startsAt] | all(test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$")))
          and .openAt < .startsAt))))
  and ([.hide.titles[], .hide.titlePrefixes[]] | all(test($safe_like)))
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

# sql <SQL> : 결과를 구분자 없이 한 줄씩 돌려준다. SQL은 표준 입력으로 넘긴다.
# 문자열 값은 정의 파일 검사로 작은따옴표·역슬래시가 없음을 확인한 것만 넣는다
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

# 관리자 로그인을 먼저 한다. 비밀번호가 틀리거나 API가 죽어 있으면 DB를 건드리기 전에 끝난다
call POST /api/v1/auth/login 200 "$(jq -n --arg e "$admin_email" --arg p "$admin_password" '{email: $e, password: $p}')"
token=$(jq -r '.data.accessToken // empty' <<< "$response")
[[ -n "$token" ]] || fail "관리자 로그인 응답에 토큰이 없다"
printf 'Authorization: Bearer %s\n' "$token" > "$tmp/auth"
unset token

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

# 2. 공연·회차·좌석. 공연·회차·좌석을 각각 있는지 보고 없는 것만 만든다
concert_count=$(jq '.concerts | length' "$definition")
made_concerts=0
made_schedules=0
for (( i = 0; i < concert_count; i++ )); do
  concert=$(jq -c ".concerts[$i]" "$definition")
  title=$(jq -r '.title' <<< "$concert")
  schedule_total=$(jq '.schedules | length' <<< "$concert")
  concert_id=$(sql "SELECT id FROM concerts WHERE title = '$title' AND deleted_at IS NULL ORDER BY id LIMIT 1")
  if [[ -z "$concert_id" ]]; then
    if [[ -n "$dry_run" ]]; then
      echo "  공연 만들 예정: $title (회차 ${schedule_total}개)"
      continue
    fi
    call POST /api/v1/admin/concerts 201 "$(jq '{title, description}' <<< "$concert")"
    concert_id=$(jq -r '.data.concertId' <<< "$response")
    made_concerts=$(( made_concerts + 1 ))
    echo "  공연 만듦: $title (id=$concert_id)"
  fi
  prices=$(jq '{prices: (.prices | to_entries | map({grade: .key, price: .value}))}' <<< "$concert")
  max_per_user=$(jq -r '.maxPerUser // 4' <<< "$concert")
  for (( j = 0; j < schedule_total; j++ )); do
    schedule=$(jq -c --argjson m "$max_per_user" \
      ".schedules[$j] | {venue, maxPerUser: \$m, openAt: (.openAt | $kst_to_utc), startsAt: (.startsAt | $kst_to_utc)}" <<< "$concert")
    venue_name=$(jq -r '.venue' <<< "$schedule")
    starts_at=$(jq -r '.startsAt' <<< "$schedule")
    venue_id=$(sql "SELECT id FROM venues WHERE name = '$venue_name' AND deleted_at IS NULL ORDER BY id LIMIT 1")
    if [[ -z "$venue_id" ]]; then
      [[ -n "$dry_run" ]] || fail "공연장을 찾지 못했다: $venue_name"
      echo "    회차 만들 예정: $starts_at $venue_name"
      continue
    fi
    schedule_id=$(sql "SELECT id FROM schedules WHERE concert_id = $concert_id AND venue_id = $venue_id
                         AND starts_at = '$starts_at' AND deleted_at IS NULL ORDER BY id LIMIT 1")
    if [[ -z "$schedule_id" ]]; then
      if [[ -n "$dry_run" ]]; then
        echo "    회차 만들 예정: $title $starts_at $venue_name"
        continue
      fi
      call POST "/api/v1/admin/concerts/$concert_id/schedules" 201 \
        "$(jq --argjson v "$venue_id" '{venueId: $v, maxPerUser, openAt, startsAt}' <<< "$schedule")"
      schedule_id=$(jq -r '.data.scheduleId' <<< "$response")
      made_schedules=$(( made_schedules + 1 ))
    fi
    seat_count=$(sql "SELECT count(*) FROM schedule_seats WHERE schedule_id = $schedule_id AND deleted_at IS NULL")
    if (( seat_count == 0 )); then
      if [[ -n "$dry_run" ]]; then
        echo "    좌석 만들 예정: 회차 $schedule_id"
        continue
      fi
      call POST "/api/v1/admin/schedules/$schedule_id/seats" 201 "$prices"
      echo "    회차 좌석 만듦: $title $starts_at (회차 $schedule_id)"
    fi
  done
done

# 3. 측정·리허설 공연 숨기기. 새 공연을 다 만든 뒤에 한다
hide_where=$(jq -r --arg q "'" '
  ([.hide.titles[] | "title = \($q)\(.)\($q)"] + [.hide.titlePrefixes[] | "title LIKE \($q)\(.)%\($q)"]) | join(" OR ")
' "$definition")
if [[ -n "$dry_run" ]]; then
  echo "  숨길 예정:"
  sql "SELECT '    - ' || id || ' ' || title FROM concerts WHERE deleted_at IS NULL AND ($hide_where) ORDER BY id"
else
  hidden=$(sql "UPDATE concerts SET deleted_at = now() WHERE deleted_at IS NULL AND ($hide_where) RETURNING id, title")
  if [[ -n "$hidden" ]]; then
    echo "  숨김: 공연 $(wc -l <<< "$hidden" | tr -d ' ')개"
    sed 's/^/    - /; s/|/ /' <<< "$hidden"
    ids=$(cut -d'|' -f1 <<< "$hidden" | paste -sd, -)
    echo "  되돌리기(예약 DB): UPDATE concerts SET deleted_at = NULL WHERE id IN ($ids);"
  else
    echo "  숨길 공연 없음"
  fi
fi

echo "== 끝: 새 공연 ${made_concerts}개, 새 회차 ${made_schedules}개${dry_run:+ (확인만, 바꾼 것 없음)}"
