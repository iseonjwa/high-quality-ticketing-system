#!/usr/bin/env bash
# 배포 결과 슬랙 메시지(#이선좌-배포상태알림)의 JSON을 표준 출력으로 낸다(ISE-175). Jenkinsfile의 post 단계가 부른다.
#   RESULT=success SHA=<40자> COMMIT_TITLE=... script/deploy/slack-payload.sh | curl ... "$SLACK_URL"
# 모양은 Alertmanager 알림(infra/alertmanager/templates/slack.tmpl)과 맞춘다: 왼쪽 색 띠, 굵은 제목, 짧은 필드, 꼬리말, 합니다체.
#
# 읽는 환경 변수
#   RESULT        success | rolledback | rollbackfailed | aborted(이미지 대기 초과) | cancelled(사람이 멈춤·40분 제한) | error
#   SHA           배포하려던 커밋(40자). 정하기 전에 실패했으면 비어 있다
#   COMMIT_TITLE  커밋 제목
#   PREVIOUS_SHA  배포 전 서버에 떠 있던 커밋(deploy.sh의 "직전 성공"). 모르면 비우거나 "없음"
#   ROLLBACK_SHA  되돌린 커밋(짧은 SHA)
#   TRIGGER       계기(예: main 새 커밋, 수동 재배포)
#   DURATION_SEC  걸린 시간(초)
#   BUILD_NUMBER  Jenkins 빌드 번호
#   PUBLIC_URL    서비스 공개 주소(예: http://52.63.10.174). 비우면 바로 가기 필드를 뺀다
set -euo pipefail
repo=https://github.com/iseonjwa/high-quality-ticketing-system
sha="${SHA:-}"
short="${sha:0:7}"
previous="${PREVIOUS_SHA:-}"
[[ "$previous" =~ ^[0-9a-f]{40}$ ]] || previous=""
rollback="${ROLLBACK_SHA:-}"
seconds="${DURATION_SEC:-0}"
[[ "$seconds" =~ ^[0-9]+$ ]] || seconds=0
if (( seconds >= 60 )); then duration="$(( seconds / 60 ))분 $(( seconds % 60 ))초"; else duration="${seconds}초"; fi

case "${RESULT:-error}" in
  success)
    color='#1E7F4F'; headline="[배포 성공] 배포 서버에 새 버전을 올렸습니다" ;;
  rolledback)
    color='#F59F00'; headline="[배포 실패] 새 버전이 확인을 통과하지 못해 직전 버전${rollback:+(${rollback})}으로 되돌렸습니다" ;;
  rollbackfailed)
    color='#D12B3A'; headline="[배포 실패] 배포와 되돌림이 모두 실패했습니다. 서버 확인이 필요합니다" ;;
  aborted)
    color='#8A94A6'; headline="[배포 중단] 이미지가 20분 안에 GHCR에 올라오지 않았습니다" ;;
  cancelled)
    color='#8A94A6'; headline="[배포 멈춤] 배포가 중간에 멈췄습니다(사람이 멈췄거나 40분 제한). 서버 상태를 확인해 주세요" ;;
  *)
    color='#D12B3A'; headline="[배포 오류] 파이프라인이 실패했습니다. Jenkins 로그를 확인해 주세요" ;;
esac

jq -n \
  --arg repo "$repo" --arg sha "$sha" --arg short "$short" --arg title "${COMMIT_TITLE:-}" \
  --arg previous "$previous" --arg color "$color" --arg headline "$headline" \
  --arg trigger "${TRIGGER:-}" --arg duration "$duration" --arg build "${BUILD_NUMBER:-?}" \
  --arg site "${PUBLIC_URL:-}" '
  def link($url; $label): "<\($url)|\($label)>";
  {attachments: [{
    color: $color,
    fallback: ([$headline, ([$short, $title] | map(select(. != "")) | join(" "))] | map(select(. != "")) | join(": ")),
    title: $headline,
    title_link: (if $sha != "" then "\($repo)/commit/\($sha)" else null end),
    text: (if $title != "" then "*\($title)*" elif $sha == "" then "배포할 커밋을 정하기 전에 끝났습니다" else "" end),
    mrkdwn_in: ["text", "fields"],
    fields: (
      [ {title: "커밋", value: (if $sha != "" then link("\($repo)/commit/\($sha)"; $short) else "-" end), short: true},
        {title: "직전 버전", value: (if $previous != "" then link("\($repo)/commit/\($previous)"; $previous[0:7]) else "없음" end), short: true},
        {title: "계기", value: (if $trigger != "" then $trigger else "-" end), short: true},
        {title: "걸린 시간", value: $duration, short: true} ]
      + (if $previous != "" and $sha != "" and $previous != $sha
         then [{title: "바뀐 내용", value: link("\($repo)/compare/\($previous)...\($sha)"; "\($previous[0:7])...\($short) 비교"), short: true}]
         else [] end)
      + (if $site != "" then [{title: "바로 가기", value: link($site; "서비스 열기"), short: true}] else [] end)
    ),
    footer: "이선좌 Jenkins #\($build) · 배포 서버",
    ts: (now | floor)
  } | if .title_link == null then del(.title_link) else . end]}'
