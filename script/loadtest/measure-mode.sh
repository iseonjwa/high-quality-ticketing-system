#!/usr/bin/env bash
# 배포 서버를 부하 측정 모드로 바꾸고 되돌린다(ISE-191). 측정 날 그날 측정자가 SSH로 접속해 실행한다(ISE-218).
#   MEASURER=<영문 이름> script/loadtest/measure-mode.sh on [추가 덮어쓰기 파일...]   예: on loadtest/k6/compose.queue-hold.yml
#   MEASURER=<영문 이름> script/loadtest/measure-mode.sh off
#   script/loadtest/measure-mode.sh status
#   on·off는 MEASURER가 있어야 돈다. 누가 켜고 껐는지 남기는 것이 알림의 목적이라 이름 없이 실행하면 사용법을 출력하고 멈춘다(ISE-224).
# 설계: docs/design/infra/load-test.md 5절, ADR 0046 "변경(2026-11-08)".
#
# 하는 일
# - on: 측정 전용 서명 키(JWT_ACCESS_SECRET, ENTRY_TOKEN_SECRET)를 새로 만들어 .env.measure에 두고(권한 600),
#   .env 다음에 얹어 스택을 다시 띄운다. 덮어쓰기는 loadtest/deploy/compose.measure.yml(추적 샘플링 0.01, Prometheus remote write·9090)
#   과 인자로 준 파일이다. 측정 전용 키를 발생기(k6)에 주면 운영 키를 발생기에 두지 않고도 k6가 토큰을 서명한다.
# - off: 같은 커밋(.deploy/current)을 .env만으로 다시 띄워 운영 키·샘플링 1.0·닫힌 9090으로 돌아가고 스모크를 돌린다.
#   그 뒤 .env.measure와 .deploy/measure-mode를 지운다.
#   이미 꺼져 있으면(.env.measure·.deploy/measure-mode 모두 없음) 아무것도 하지 않는다. 강제로 다시 띄우려면 FORCE=1(ISE-228).
# - 이미 켜져 있을 때 다시 on: 처음 켠 측정자면 "설정 변경"(파랑), 다른 측정자면 "겹침 주의"(주황)로 알린다(ISE-228).
# - status: 측정 모드인지, 언제부터인지, 누가 켰는지, 어떤 덮어쓰기 파일을 쓰는지 보여 준다.
#
# 주의
# - 측정 모드 동안 기존 로그인이 끊긴다. Access 토큰 서명 키가 바뀌기 때문이다(Refresh 키는 그대로라 다시 로그인하면 된다).
# - 측정 중에 main이 머지돼 Jenkins가 deploy.sh를 돌리면 측정 모드가 풀린다(deploy.sh는 .env와 compose.deploy.yml만 쓴다).
#   측정 동안 main 머지를 멈추거나 Jenkins 잡을 잠시 끈다.
# - 이 스크립트는 "서버에서 직접 docker compose up 하지 않는다" 규칙의 예외다. 그날 측정자만 쓴다(docs/guides/deploy-server-access.md).
#   켜기 전에 status로 다른 사람이 켜 두지 않았는지 보고, 끄고 나서 status로 꺼졌는지 본다(ADR 0046 변경 2026-11-09).
# - 키 값은 출력하지 않는다. 키 이름과 다음 할 일만 출력한다.
# - DRY_RUN=1이면 docker compose와 스모크를 실행하지 않고 부를 명령만 출력한다(스크립트 확인용). 슬랙 알림도 보내지 않고 보낼 내용만 출력한다.
# - on·off가 끝나면 슬랙으로 알린다(ISE-223). 측정 전용 채널(SLACK_WEBHOOK_LOADTEST)이 있으면 그리로, 비어 있으면
#   성능-모니터링 채널(SLACK_WEBHOOK_MONITORING)로 보낸다(ISE-224). 모양은 배포 알림(script/deploy/slack-payload.sh)과 같다.
#   웹훅 주소는 출력하지 않고 키 이름만 출력한다. 알림을 보내지 못해도 측정 모드 전환은 그대로 끝난다.
#
# Note: --env-file을 여러 번 주면 뒤 파일의 값이 앞 파일을 덮고 나머지 키는 합쳐진다. Docker 문서의 CLI 설명에는 이 동작이
# 적혀 있지 않아 Compose v5.6.0에서 직접 확인했다(같은 키를 가진 두 파일: 뒤 값 우선, 순서를 바꾸면 결과도 바뀜). 서버 Compose는 5.7이다.
# --env-file을 하나라도 주면 기본 .env 자동 읽기가 꺼지므로 .env도 같이 적는다.
set -euo pipefail
cd "$(dirname "$0")/../.."

state=.deploy
measure_env=.env.measure
mode_file="$state/measure-mode"
dry="${DRY_RUN:-0}"

usage() {
  echo "사용법: MEASURER=<영문 이름> script/loadtest/measure-mode.sh on [추가 덮어쓰기 파일...]" >&2
  echo "        MEASURER=<영문 이름> script/loadtest/measure-mode.sh off" >&2
  echo "        script/loadtest/measure-mode.sh status" >&2
  echo "  on·off는 슬랙 알림에 측정자를 남기려고 MEASURER가 필요하다. 예: MEASURER=kangmin script/loadtest/measure-mode.sh on" >&2
  exit 2
}
fail() { echo "오류: $*" >&2; exit 1; }
require_measurer() { [[ -n "${MEASURER:-}" ]] || usage; }

repo=https://github.com/iseonjwa/high-quality-ticketing-system
footer="이선좌 측정 모드 · 배포 서버"
# Note: TZ=Asia/Seoul은 시간대 자료(tzdata)가 없는 환경에서 UTC로 떨어진다. POSIX 표기 KST-9는 자료 없이도 UTC+9다
kst_now() { TZ=KST-9 date '+%Y-%m-%d %H:%M KST'; }
# .deploy/measure-mode의 started(UTC)를 KST로 바꾼다. 읽지 못하면 원래 값을 그대로 쓴다
to_kst() { TZ=KST-9 date -d "$1" '+%Y-%m-%d %H:%M KST' 2> /dev/null || echo "$1"; }
# 슬랙 mrkdwn에서 링크·멘션으로 읽히는 문자를 바꾼다(사람이 넣은 MEASURER 값에 쓴다)
# Note: bash 5.2부터 치환 문자열의 &는 찾은 문자열을 뜻한다(patsub_replacement). 따옴표 친 변수로 넘겨야 글자 그대로 들어간다
slack_escape() {
  local amp='&amp;' lt='&lt;' gt='&gt;' s
  s="${1//&/"$amp"}"; s="${s//</"$lt"}"
  printf '%s' "${s//>/"$gt"}"
}
# JSON 문자열 리터럴. jq가 없을 때만 쓴다. 역슬래시·따옴표·줄바꿈·CR·탭을 이스케이프한다(값에 다른 제어 문자는 들어오지 않는다)
json_str() {
  local s="${1//\\/\\\\}"
  s="${s//\"/\\\"}"; s="${s//$'\n'/\\n}"; s="${s//$'\r'/\\r}"; s="${s//$'\t'/\\t}"
  printf '"%s"' "$s"
}

# 슬랙 메시지 JSON을 한 줄로 낸다. 인자: 색, 제목, 본문, 그 뒤로 필드 이름과 값을 번갈아.
# 모양은 script/deploy/slack-payload.sh와 같다: 왼쪽 색 띠, 굵은 제목(커밋 링크), 두 열 필드, 꼬리말.
build_payload() {
  local color="$1" title="$2" text="$3" link fallback ts
  shift 3
  link="$repo/commit/$IMAGE_TAG"
  fallback="$title · 측정자 $(slack_escape "$MEASURER")"
  ts="$(date +%s)"
  if command -v jq > /dev/null; then
    jq -nc --arg color "$color" --arg title "$title" --arg link "$link" --arg text "$text" \
      --arg fallback "$fallback" --arg footer "$footer" --argjson ts "$ts" '
      {attachments: [{
        color: $color, fallback: $fallback, title: $title, title_link: $link, text: $text,
        mrkdwn_in: ["text", "fields"],
        fields: [range(0; $ARGS.positional | length; 2) as $i
                 | {title: $ARGS.positional[$i], value: $ARGS.positional[$i + 1], short: true}],
        footer: $footer, ts: $ts
      }]}' --args "$@"
    return
  fi
  local fields="" sep=""
  while (( $# >= 2 )); do
    fields+="${sep}{\"title\":$(json_str "$1"),\"value\":$(json_str "$2"),\"short\":true}"
    sep=","
    shift 2
  done
  printf '{"attachments":[{"color":%s,"fallback":%s,"title":%s,"title_link":%s,"text":%s,"mrkdwn_in":["text","fields"],"fields":[%s],"footer":%s,"ts":%s}]}\n' \
    "$(json_str "$color")" "$(json_str "$fallback")" "$(json_str "$title")" "$(json_str "$link")" \
    "$(json_str "$text")" "$fields" "$(json_str "$footer")" "$ts"
}

# 웹훅 주소 하나를 읽는다. 환경 변수가 있으면 그것을, 없으면 .env에서 그 키만 읽는다
webhook_from() {
  local key="$1" url="${!1:-}"
  if [[ -z "$url" && -f .env ]]; then
    url="$(sed -n "s/^${key}=//p" .env | tail -n 1)"
    url="${url%\"}"; url="${url#\"}"
  fi
  printf '%s' "$url"
}

# 슬랙 알림(ISE-223). 겹쳐 켜거나 끄는 것을 잊는 위험(ADR 0046 변경 2026-11-09)을 줄이려고, 켜고 끈 기록을 사람 손을 거치지 않고 남긴다.
# 측정 전용 채널(SLACK_WEBHOOK_LOADTEST)을 먼저 쓰고, 비어 있으면 성능-모니터링 채널(SLACK_WEBHOOK_MONITORING)로 보낸다(ISE-224).
# 주소는 출력하지 않고 키 이름만 출력한다.
# Note: 알림 실패로 측정 모드 전환을 되돌리지 않는다. 전환이 이미 끝난 뒤라 되돌리면 상태만 더 어긋난다
notify() {
  local payload="$1" key="" candidate url=""
  for candidate in SLACK_WEBHOOK_LOADTEST SLACK_WEBHOOK_MONITORING; do
    url="$(webhook_from "$candidate")"
    if [[ -n "$url" ]]; then key="$candidate"; break; fi
  done
  if [[ -z "$payload" ]]; then
    echo "경고: 슬랙 메시지를 만들지 못해 알림을 보내지 않았다. 팀 채널에 직접 알린다" >&2
    return 0
  fi
  if [[ "$dry" == 1 ]]; then
    echo "DRY_RUN: 슬랙 알림(보내지 않음, 보낼 곳: ${key:-없음})"
    echo "$payload"
    return 0
  fi
  if [[ -z "$url" ]]; then
    echo "경고: SLACK_WEBHOOK_LOADTEST·SLACK_WEBHOOK_MONITORING이 모두 비어 슬랙 알림을 보내지 않았다. 팀 채널에 직접 알린다" >&2
    return 0
  fi
  # Note: 본문을 인자(-d "$payload")로 넘기면 Windows Git Bash에서 한글이 ?로 깨졌다. 표준 입력으로 바이트 그대로 넘긴다
  if printf '%s' "$payload" | curl -fsS --max-time 5 -H 'Content-Type: application/json; charset=utf-8' --data-binary @- "$url" > /dev/null 2>&1; then
    echo "슬랙 알림을 보냈다(${key})"
  else
    echo "경고: 슬랙 알림을 보내지 못했다(${key}). 측정 모드 전환은 끝났으니 팀 채널에 직접 알린다" >&2
  fi
}

# 현재 배포된 커밋. deploy.sh가 성공할 때마다 남긴다
current_tag() {
  [[ -f "$state/current" ]] || fail "$state/current가 없다. 먼저 deploy.sh로 배포한 서버에서 실행한다"
  local tag
  tag="$(cat "$state/current")"
  [[ "$tag" =~ ^[0-9a-f]{40}$ ]] || fail "$state/current가 40자 커밋 SHA가 아니다"
  echo "$tag"
}

run_compose() {
  if [[ "$dry" == 1 ]]; then
    printf 'DRY_RUN: IMAGE_TAG=<%s> docker compose' "${IMAGE_TAG:0:7}"
    printf ' %q' "$@"
    printf '\n'
  else
    docker compose "$@"
  fi
}

# .env.measure를 만든다. 이미 있으면 그대로 둔다(측정 중 다시 실행해도 발생기에 옮긴 키와 어긋나지 않게).
# 값은 출력하지 않는다. 형식은 script/deploy/init-env.sh와 같다(openssl rand -hex 32).
ensure_measure_env() {
  if [[ -f "${measure_env}" ]]; then
    chmod 600 "${measure_env}"
    echo "${measure_env}가 이미 있어 그대로 쓴다"
    return
  fi
  local old_umask
  old_umask="$(umask)"
  umask 077
  {
    echo "JWT_ACCESS_SECRET=$(openssl rand -hex 32)"
    echo "ENTRY_TOKEN_SECRET=$(openssl rand -hex 32)"
  } > "${measure_env}"
  umask "$old_umask"
  chmod 600 "${measure_env}"
  echo "${measure_env}를 만들었다(권한 600, 키: JWT_ACCESS_SECRET, ENTRY_TOKEN_SECRET)"
}

cmd_on() {
  [[ -f .env ]] || fail ".env가 없다. script/deploy/init-env.sh를 먼저 돌린다"
  local extra=() file
  for file in "$@"; do
    [[ -f "$file" ]] || fail "덮어쓰기 파일이 없다: $file"
    extra+=(-f "$file")
  done
  IMAGE_TAG="$(current_tag)"
  export IMAGE_TAG
  mkdir -p "$state"
  ensure_measure_env

  local started was_on=0 owner="$MEASURER" previous_overrides=""
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ -f "$mode_file" ]]; then
    started="$(sed -n 's/^started=//p' "$mode_file")"
    previous_overrides="$(sed -n 's/^overrides=//p' "$mode_file")"
    # 처음 켠 측정자를 그대로 남긴다. ISE-228 전에 만든 기록 파일에는 measurer가 없어 비어 있을 수 있다
    owner="$(sed -n 's/^measurer=//p' "$mode_file")"
    was_on=1
  fi
  run_compose --env-file .env --env-file "${measure_env}" \
    -f compose.yml -f compose.deploy.yml -f loadtest/deploy/compose.measure.yml ${extra[@]+"${extra[@]}"} \
    up -d --remove-orphans --wait --wait-timeout 420
  {
    echo "started=$started"
    echo "overrides=loadtest/deploy/compose.measure.yml${*:+ $*}"
    echo "commit=$IMAGE_TAG"
    echo "measurer=${owner:-$MEASURER}"
  } > "$mode_file"

  echo "== 측정 모드를 켰다(커밋 ${IMAGE_TAG:0:7})"
  local color='#3B3BD4' title="[측정 모드 켬] 측정을 시작했습니다" payload
  local text="측정이 끝나면 \`measure-mode.sh off\`로 꺼 주세요. 측정 중에는 main 머지를 멈춰 주세요."
  local fields=(측정자 "$(slack_escape "$MEASURER")" 시각 "$(kst_now)" 커밋 "<$repo/commit/$IMAGE_TAG|${IMAGE_TAG:0:7}>"
                "덮어쓰기 파일" "loadtest/deploy/compose.measure.yml${*:+ $*}")
  # 이미 켜져 있던 상태에서 다시 켠 경우를 둘로 나눈다(ISE-228).
  # 처음 켠 측정자가 다시 켠 것은 설정 변경(예: Q2 → Q3로 덮어쓰기 파일을 바꿈)이고, 다른 측정자면 겹침일 수 있다
  if [[ "$was_on" == 1 && "$owner" == "$MEASURER" ]]; then
    title="[측정 모드 설정 변경] 켜져 있던 측정 모드에 다시 적용했습니다"
    text="같은 측정자가 덮어쓰기 파일을 바꿔 다시 적용했습니다. ${text}"
    fields+=("이전 덮어쓰기 파일" "${previous_overrides:-알 수 없음}" "기존 시작 시각" "$(to_kst "$started")")
  elif [[ "$was_on" == 1 ]]; then
    color='#F59F00'
    title="[측정 모드 켬·겹침 주의] 이미 켜져 있던 측정 모드에 다시 적용했습니다"
    text="다른 분이 측정 중이 아닌지 확인 부탁드립니다. ${text}"
    fields+=("먼저 켠 측정자" "$(slack_escape "${owner:-알 수 없음}")" "기존 시작 시각" "$(to_kst "$started")")
  fi
  payload="$(build_payload "$color" "$title" "$text" "${fields[@]}")" || payload=""
  notify "$payload"
  echo "다음 할 일"
  echo "  1. 발생기에서 loadtest/k6/run.sh가 읽도록 ${measure_env}를 발생기 저장소 루트의 .env로 옮긴다(docs/design/infra/load-test.md 2-1절)"
  echo "  2. 측정 동안 main 머지를 멈추거나 Jenkins 잡을 끈다. 배포가 돌면 측정 모드가 풀린다"
  echo "  3. 끝나면 script/loadtest/measure-mode.sh off, 발생기의 .env를 지운다"
}

cmd_off() {
  # 이미 꺼져 있으면(기록 파일·측정 전용 키 모두 없음) 다시 띄우지도, 알리지도 않는다(ISE-228).
  # 끈 사람이 한 번 더 실행하면 서버가 1분 가까이 재기동되고 "알 수 없음" 알림이 남았다(2026-11-09).
  # Note: 둘 중 하나라도 남아 있으면 끄기가 중간에 실패한 상태라 끝까지 끈다. FORCE=1은 강제로 다시 띄울 때 쓴다
  if [[ ! -f "$mode_file" && ! -f "${measure_env}" && "${FORCE:-0}" != 1 ]]; then
    echo "측정 모드가 이미 꺼져 있다(${mode_file}·${measure_env} 없음). 스택을 다시 띄우지 않고 알림도 보내지 않는다"
    echo "운영 설정으로 강제로 다시 띄워야 하면 FORCE=1을 붙여 다시 실행한다"
    return 0
  fi
  IMAGE_TAG="$(current_tag)"
  export IMAGE_TAG
  # 켜져 있던 시간을 알림에 넣으려고, 기록 파일을 지우기 전에 시작 시각을 읽는다
  local started="" overrides="알 수 없음" duration="알 수 없음" start_epoch
  if [[ -f "$mode_file" ]]; then
    started="$(sed -n 's/^started=//p' "$mode_file")"
    overrides="$(sed -n 's/^overrides=//p' "$mode_file")"
  fi
  if [[ -n "$started" ]] && start_epoch="$(date -u -d "$started" +%s 2> /dev/null)"; then
    duration="$(( ($(date -u +%s) - start_epoch) / 60 ))분"
  fi
  run_compose --env-file .env -f compose.yml -f compose.deploy.yml up -d --remove-orphans --wait --wait-timeout 420
  # 운영 키로 돌아온 뒤에 측정 전용 키 파일을 지운다. 되돌리기가 실패하면 파일을 남겨 다시 시도할 수 있게 한다(set -e)
  rm -f "${measure_env}" "$mode_file"
  echo "== 측정 모드를 껐다(커밋 ${IMAGE_TAG:0:7}). 발생기의 .env도 지운다"
  local smoke="통과" smoke_rc=0
  if [[ "$dry" == 1 ]]; then
    echo "DRY_RUN: script/deploy/smoke.sh"
    smoke="DRY_RUN(실행 안 함)"
  else
    script/deploy/smoke.sh || { smoke_rc=$?; smoke="실패(종료 코드 ${smoke_rc})"; }
  fi
  local color='#1E7F4F' title="[측정 모드 끔] 운영 설정으로 돌아왔습니다" payload
  local text="운영 키·추적 샘플링 1.0·닫힌 9090으로 돌아왔습니다. 발생기의 .env도 지워 주세요."
  if (( smoke_rc != 0 )); then
    color='#D12B3A'
    title="[측정 모드 끔·스모크 실패] 서버 확인이 필요합니다"
    text="운영 설정으로 되돌렸지만 스모크가 실패했습니다. 서버 상태를 확인해 주세요. 발생기의 .env도 지워 주세요."
  fi
  payload="$(build_payload "$color" "$title" "$text" \
    측정자 "$(slack_escape "$MEASURER")" 시각 "$(kst_now)" 커밋 "<$repo/commit/$IMAGE_TAG|${IMAGE_TAG:0:7}>" \
    "덮어쓰기 파일" "${overrides:-알 수 없음}" "켜져 있던 시간" "$duration" "스모크 결과" "$smoke")" || payload=""
  notify "$payload"
  # 스모크가 실패하면 알림은 보내되 종료 코드로도 알린다(기존 동작 유지)
  return "$smoke_rc"
}

cmd_status() {
  if [[ -f "$mode_file" ]]; then
    echo "측정 모드: 켜짐"
    sed -e 's/^started=/시작: /' -e 's/^overrides=/덮어쓰기 파일: /' -e 's/^commit=/커밋: /' -e 's/^measurer=/켠 측정자: /' "$mode_file"
  else
    echo "측정 모드: 꺼짐"
  fi
  if [[ -f "${measure_env}" ]]; then
    echo "${measure_env}: 있음(권한 $(stat -c %a "${measure_env}" 2>/dev/null || stat -f %Lp "${measure_env}"))"
  else
    echo "${measure_env}: 없음"
  fi
}

case "${1:-}" in
  on) shift; require_measurer; cmd_on "$@" ;;
  off) require_measurer; cmd_off ;;
  status) cmd_status ;;
  *) usage ;;
esac
