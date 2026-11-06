#!/usr/bin/env bash
# 추첨 시연 응모자를 공개 API로 적재한다(ISE-199, docs/design/demo/2026-11-06-mvp-scenarios.md 2-2절).
#   script/deploy/seed-lottery-entrants.sh prepare [기준 주소]              가입, 로그인, 카드 등록. 시연 전에 실행한다
#   script/deploy/seed-lottery-entrants.sh enter <추첨 id> [기준 주소]      응모. 응모 기간에 실행한다
# 기준 주소 기본값은 로컬 nginx http://127.0.0.1:8000이다.
# 공유 서버 보호: loopback이 아닌 주소는 LOTTERY_SEED_ALLOW_REMOTE=yes가 있어야 요청을 보낸다.
#
# 다시 실행해도 안전하다.
#   가입: 이미 있으면 409를 건너뛴다.
#   카드: 조회 API가 없고 재등록하면 기존 키가 해지되므로, 등록을 끝낸 계정을 상태 디렉터리에 적어 다시 등록하지 않는다.
#         기록을 잃어 재등록해도 활성 키는 1개다. 가입이 201이면 DB가 초기화된 것이므로 그 계정의 기록을 믿지 않는다.
#   응모: 멱등키를 추첨 id와 이메일에서 계산해 같은 계정의 재요청은 처음 응모를 돌려받는다.
#
# 비밀번호: LOTTERY_SEED_PASSWORD가 있으면 그 값을 쓰고, 없으면 상태 디렉터리의 password 파일을 쓰고, 그것도 없으면 임의 값을 만들어 저장한다.
# 상태 디렉터리는 저장소 밖이다: ${LOTTERY_SEED_STATE_DIR:-$HOME/.iseonjwa/lottery-seed/<호스트_포트>}. 비밀번호와 토큰은 출력하지 않는다.
#
# 환경 변수: ENTRANT_COUNT(120), TWO_SEAT_COUNT(30), NO_CARD_COUNT(12). 처음 prepare에서 정한 값이 상태에 남고, 다른 값으로 다시 돌리면 2로 끝난다.
# 종료 코드: 0 성공, 1 실패(일부 계정 실패 포함, 다시 실행하면 이어서 한다), 2 사용법·입력 오류
#
# Note: jq 없이 curl과 셸 도구만 쓴다. jq가 없는 PC에서도 돌리기 위해서다. 그래서 응답에서는 따옴표가 없는 값(토큰, 상태, 코드)만 읽는다.
set -euo pipefail

usage() {
  echo "사용법: script/deploy/seed-lottery-entrants.sh prepare [기준 주소]  |  script/deploy/seed-lottery-entrants.sh enter <추첨 id> [기준 주소]" >&2
  exit 2
}
bad_input() { echo "입력 오류: $*" >&2; exit 2; }
fail() { echo "실패: $*" >&2; exit 1; }

mode="${1:-}"
case "$mode" in
  prepare)
    [[ $# -le 2 ]] || usage
    base="${2:-http://127.0.0.1:8000}"
    ;;
  enter)
    [[ $# -ge 2 && $# -le 3 ]] || usage
    lottery_id="$2"
    [[ "$lottery_id" =~ ^[1-9][0-9]*$ ]] || bad_input "추첨 id는 양의 정수여야 한다: $lottery_id"
    base="${3:-http://127.0.0.1:8000}"
    ;;
  *) usage ;;
esac
base="${base%/}"

[[ "$base" =~ ^https?://([^/:]+|\[[^]]+\])(:[0-9]+)?$ ]] || bad_input "기준 주소 형식이 아니다: $base"
host="${BASH_REMATCH[1]}"
port="${BASH_REMATCH[2]#:}"
case "$host" in
  127.0.0.1 | localhost | '[::1]') ;;
  *)
    [[ "${LOTTERY_SEED_ALLOW_REMOTE:-}" == yes ]] \
      || bad_input "loopback이 아닌 주소다: $host. 공유 서버 적재가 승인됐을 때만 LOTTERY_SEED_ALLOW_REMOTE=yes로 실행한다"
    ;;
esac

for tool in curl sed grep; do
  command -v "$tool" > /dev/null || fail "$tool이 없다"
done
if command -v sha256sum > /dev/null; then sha256() { sha256sum; }; else sha256() { shasum -a 256; }; fi

state_dir="${LOTTERY_SEED_STATE_DIR:-$HOME/.iseonjwa/lottery-seed/${host//[^A-Za-z0-9.]/_}_${port:-default}}"
plan_file="$state_dir/plan"
password_file="$state_dir/password"
carded_file="$state_dir/carded"

# 계획은 처음 prepare에서 고정한다. 나중에 바뀌면 멱등키 재사용(409)이 나거나 카드 미등록 계정이 달라진다.
load_plan() {
  local saved requested
  requested="${ENTRANT_COUNT:-120} ${TWO_SEAT_COUNT:-30} ${NO_CARD_COUNT:-12}"
  if [[ -f "$plan_file" ]]; then
    saved=$(cat "$plan_file")
    if [[ -n "${ENTRANT_COUNT:-}${TWO_SEAT_COUNT:-}${NO_CARD_COUNT:-}" && "$saved" != "$requested" ]]; then
      bad_input "계획이 이미 '$saved'(응모자 2매 미등록)로 정해져 있다: $plan_file"
    fi
  else
    [[ "$mode" == prepare ]] || fail "계획이 없다. prepare를 먼저 실행한다: $plan_file"
    saved="$requested"
  fi
  read -r entrant_count two_seat_count no_card_count <<< "$saved"
  [[ "$entrant_count" =~ ^[1-9][0-9]{0,3}$ && "$two_seat_count" =~ ^[0-9]+$ && "$no_card_count" =~ ^[0-9]+$ ]] \
    || bad_input "응모자 수와 2매·미등록 수는 정수여야 한다: $saved"
  (( two_seat_count <= entrant_count && no_card_count <= entrant_count )) \
    || bad_input "2매·미등록 수는 응모자 수를 넘을 수 없다: $saved"
}

load_password() {
  if [[ -n "${LOTTERY_SEED_PASSWORD:-}" ]]; then
    password="$LOTTERY_SEED_PASSWORD"
  elif [[ -f "$password_file" ]]; then
    password=$(cat "$password_file")
  else
    [[ "$mode" == prepare ]] || fail "비밀번호가 없다. prepare를 먼저 실행하거나 LOTTERY_SEED_PASSWORD를 정한다"
    password=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    ( umask 077; printf '%s' "$password" > "$password_file" )
  fi
  # 가입 규칙은 8~72자다. JSON 이스케이프를 피하려고 따옴표, 역슬래시, 공백을 받지 않는다.
  (( ${#password} >= 8 && ${#password} <= 72 )) || bad_input "비밀번호는 8~72자여야 한다"
  [[ "$password" =~ ^[[:graph:]]+$ && "$password" != *[\"\\]* ]] || bad_input "비밀번호에 따옴표, 역슬래시, 공백을 쓸 수 없다"
}

# 인증 헤더는 인자에 넣지 않고 임시 파일(0600)로 넘긴다. 본문도 표준 입력으로 보내 비밀번호가 프로세스 목록에 보이지 않게 한다.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"
: > "$tmp/auth"

status=000
response=""
# call <메서드> <경로> [JSON 본문]. 응답은 status와 response에 둔다. 서비스 재기동 직후의 502·503·504와 연결 실패는 다시 시도한다.
call() {
  local method="$1" path="$2" data="${3:-}" attempt
  local args=(-sS --max-time 30 -o "$tmp/body" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' -H "@$tmp/auth")
  if [[ -n "${request_key:-}" ]]; then args+=(-H "Idempotency-Key: $request_key"); fi
  if [[ -n "$data" ]]; then args+=(--data-binary @-); fi
  for attempt in 1 2 3 4; do
    : > "$tmp/body"
    status=$(printf '%s' "$data" | curl "${args[@]}" "$base$path" 2> /dev/null) || status=000
    case "$status" in
      502 | 503 | 504 | 000) (( attempt == 4 )) || sleep 5 ;;
      *) break ;;
    esac
  done
  response=$(cat "$tmp/body")
}

# 응답에서 문자열 필드를 읽는다. 없으면 빈 값이다. 숫자인 최상위 status는 따옴표가 없어 data.status만 잡힌다.
field() {
  printf '%s' "$response" | grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | sed "s/^\"$1\":\"//; s/\"\$//" || true
}

email_of() { printf 'lottery-seed-%03d@iseonjwa.local' "$1"; }

# 1~N에 count개를 고르게 나눈다. 같은 n은 언제나 같은 답이다.
is_picked() {
  local n="$1" count="$2"
  (( n * count / entrant_count != (n - 1) * count / entrant_count ))
}
quantity_of() { if is_picked "$1" "$two_seat_count"; then echo 2; else echo 1; fi; }
has_no_card() { is_picked "$1" "$no_card_count"; }

is_carded() { [[ -f "$carded_file" ]] && grep -qx "$1" "$carded_file"; }
forget_carded() {
  [[ -f "$carded_file" ]] || return 0
  grep -vx "$1" "$carded_file" > "$tmp/carded" || true
  cat "$tmp/carded" > "$carded_file"
}

# 사용자 로그인. 틀린 비밀번호를 계속 보내면 계정이 잠기므로(5회) 401·423은 전체를 멈춘다. 그 밖의 실패는 1을 돌려준다.
login() {
  local email="$1" token
  : > "$tmp/auth"
  call POST /api/v1/auth/login "{\"email\":\"$email\",\"password\":\"$password\"}"
  case "$status" in
    200) ;;
    401 | 423)
      fail "$email 로그인 응답 $status code=$(field code). 저장한 비밀번호가 가입 때와 다를 수 있어 잠기기 전에 멈춘다. 처음 쓴 비밀번호를 LOTTERY_SEED_PASSWORD로 넘긴다"
      ;;
    *)
      echo "  $email 로그인 실패: 응답 $status code=$(field code)" >&2
      return 1
      ;;
  esac
  token=$(field accessToken)
  [[ -n "$token" ]] || { echo "  $email 로그인 응답에 토큰이 없다" >&2; return 1; }
  printf 'Authorization: Bearer %s\n' "$token" > "$tmp/auth"
}

prepare_one() {
  local n="$1" email
  email=$(email_of "$n")
  : > "$tmp/auth"
  call POST /api/v1/users "{\"email\":\"$email\",\"password\":\"$password\",\"nickname\":\"$(printf 'seed-%03d' "$n")\"}"
  case "$status" in
    201)
      signed_up=$(( signed_up + 1 ))
      forget_carded "$n"
      ;;
    409)
      if is_carded "$n"; then
        skipped=$(( skipped + 1 ))
        return 0
      fi
      ;;
    *)
      echo "  $email 가입 실패: 응답 $status code=$(field code)" >&2
      return 1
      ;;
  esac

  login "$email" || return 1
  if has_no_card "$n"; then
    no_card=$(( no_card + 1 ))
    return 0
  fi
  call POST /api/v1/payments/billing-keys "{\"authKey\":\"$(printf 'auth-lottery-seed-%03d' "$n")\"}"
  if [[ "$status" != 201 ]]; then
    echo "  $email 카드 등록 실패: 응답 $status code=$(field code)" >&2
    return 1
  fi
  echo "$n" >> "$carded_file"
  carded=$(( carded + 1 ))
}

run_prepare() {
  local n
  signed_up=0 carded=0 no_card=0 skipped=0 failed=0
  echo "== 응모자 준비: $base (응모자 $entrant_count, 2매 $two_seat_count, 카드 미등록 $no_card_count)"
  for (( n = 1; n <= entrant_count; n++ )); do
    prepare_one "$n" || failed=$(( failed + 1 ))
    (( n % 20 != 0 )) || echo "  $n/$entrant_count 처리, ${SECONDS}초"
  done
  echo "== 준비 끝(${SECONDS}초): 새 가입 $signed_up, 카드 등록 $carded, 이번에 확인한 미등록 계정 $no_card, 이미 끝나 건너뜀 $skipped, 실패 $failed"
  echo "계정: $(email_of 1) ~ $(email_of "$entrant_count")"
  echo "비밀번호 파일: $password_file (LOTTERY_SEED_PASSWORD를 쓴 실행이면 그 값)"
  (( failed == 0 )) || fail "$failed개 계정이 실패했다. 다시 실행하면 이어서 한다"
}

# 추첨 id와 이메일이 같으면 언제나 같은 UUID 형식 값이다.
request_key_of() {
  local hex
  hex=$(printf 'lottery-seed:%s:%s' "$lottery_id" "$1" | sha256)
  printf '%s-%s-%s-%s-%s' "${hex:0:8}" "${hex:8:4}" "${hex:12:4}" "${hex:16:4}" "${hex:20:12}"
}

declare -A entry_counts=()
enter_one() {
  local n="$1" email code entry_status
  email=$(email_of "$n")
  login "$email" || return 1
  request_key=$(request_key_of "$email")
  call POST "/api/v1/lotteries/$lottery_id/entries" "{\"quantity\":$(quantity_of "$n")}"
  request_key=""
  code=$(field code)
  case "$status:$code" in
    201:*)
      entry_status=$(field status); entry_status="${entry_status:-UNKNOWN}"
      entry_counts[$entry_status]=$(( ${entry_counts[$entry_status]:-0} + 1 ))
      ;;
    409:LOTTERY_ALREADY_ENTERED)
      entry_counts[ALREADY_ENTERED]=$(( ${entry_counts[ALREADY_ENTERED]:-0} + 1 ))
      ;;
    409:LOTTERY_NOT_OPEN)
      fail "응모 기간이 아니다(추첨 $lottery_id). $n번 계정에서 멈췄다. 앞 계정의 응모는 남아 있으니 기간 안에 다시 실행한다"
      ;;
    *)
      echo "  $email 응모 실패: 응답 $status code=$code" >&2
      return 1
      ;;
  esac
}

run_enter() {
  local n failed=0 name
  : > "$tmp/auth"
  call GET "/api/v1/lotteries/$lottery_id"
  [[ "$status" == 200 ]] || fail "추첨 $lottery_id 조회 응답 $status code=$(field code)"
  echo "== 응모: $base 추첨 $lottery_id 상태 $(field status), 응모 $(field entryStartAt) ~ $(field entryEndAt), 추첨 $(field drawAt)"
  for (( n = 1; n <= entrant_count; n++ )); do
    enter_one "$n" || failed=$(( failed + 1 ))
    (( n % 20 != 0 )) || echo "  $n/$entrant_count 처리, ${SECONDS}초"
  done
  echo "== 응모 끝(${SECONDS}초), 실패 $failed. 상태별 건수:"
  for name in "${!entry_counts[@]}"; do echo "  $name ${entry_counts[$name]}"; done
  (( failed == 0 )) || fail "$failed건이 실패했다. 기간 안에 다시 실행하면 이어서 한다"
}

mkdir -p "$state_dir"
chmod 700 "$state_dir"
load_plan
load_password
[[ -f "$plan_file" ]] || echo "$entrant_count $two_seat_count $no_card_count" > "$plan_file"

case "$mode" in
  prepare) run_prepare ;;
  enter) run_enter ;;
esac
