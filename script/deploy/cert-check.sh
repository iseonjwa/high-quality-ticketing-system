#!/usr/bin/env bash
# 배포 서버가 실제로 내보내는 HTTPS 인증서의 남은 기간을 보고, 얼마 남지 않았으면 슬랙으로 알린다(ISE-278).
#   script/deploy/cert-check.sh          (서버 저장소 루트에서. crontab에 6시간마다 등록한다)
# Let's Encrypt IP 인증서는 약 6일(160시간)짜리이고, certbot은 수명의 절반(약 80시간)이 남으면 갱신한다.
# 그래서 남은 시간이 CERT_MIN_HOURS(기본 48시간)보다 적으면 갱신이나 nginx 다시 읽기가 실패한 것으로 보고 알린다.
# 파일이 아니라 443이 내보내는 인증서를 본다. 갱신은 됐는데 nginx가 다시 읽지 못한 경우도 잡으려는 것이다.
#
# 환경 변수: CERT_MIN_HOURS(기본 48), CERT_CHECK_ADDR(기본 127.0.0.1:443), DRY_RUN=1(슬랙에 보내지 않고 출력만)
# 알림은 SLACK_WEBHOOK_MONITORING으로 간다(환경 변수가 없으면 .env에서 그 키만 읽는다). 주소는 출력하지 않는다.
# 종료 코드: 0 충분히 남음, 1 얼마 남지 않음 또는 확인 실패(알림을 보냈거나 보내려 했음)
set -uo pipefail
cd "$(dirname "$0")/../.."
min_hours="${CERT_MIN_HOURS:-48}"
addr="${CERT_CHECK_ADDR:-127.0.0.1:443}"
dry="${DRY_RUN:-0}"

webhook() {
  local url="${SLACK_WEBHOOK_MONITORING:-}"
  if [[ -z "$url" && -f .env ]]; then
    url="$(sed -n 's/^SLACK_WEBHOOK_MONITORING=//p' .env | tail -n 1)"
    url="${url%\"}"; url="${url#\"}"
  fi
  printf '%s' "$url"
}

# JSON 문자열 하나로 감싼다(역슬래시와 큰따옴표만 이스케이프한다. 메시지에 줄바꿈은 \n으로 쓴다)
json_str() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }

notify() {
  local text="$1" url payload
  payload="{\"text\": $(json_str "$text")}"
  if [[ "$dry" == 1 ]]; then
    echo "DRY_RUN: 슬랙 알림(보내지 않음)"
    echo "$payload"
    return 0
  fi
  url="$(webhook)"
  if [[ -z "$url" ]]; then
    echo "경고: SLACK_WEBHOOK_MONITORING이 비어 슬랙 알림을 보내지 않았다" >&2
    return 0
  fi
  if printf '%s' "$payload" | curl -fsS --max-time 5 -H 'Content-Type: application/json; charset=utf-8' --data-binary @- "$url" > /dev/null 2>&1; then
    echo "슬랙 알림을 보냈다(SLACK_WEBHOOK_MONITORING)"
  else
    echo "경고: 슬랙 알림을 보내지 못했다(SLACK_WEBHOOK_MONITORING)" >&2
  fi
}

runbook="docs/design/infra/deployment.md \"HTTPS 인증서\""
cert="$(echo | openssl s_client -connect "$addr" 2> /dev/null | openssl x509 2> /dev/null)"
if [[ -z "$cert" ]]; then
  echo "인증서를 읽지 못했다: $addr"
  notify "[배포 서버] HTTPS 인증서를 확인하지 못했습니다($addr). 프론트 nginx가 떠 있는지 확인해 주세요. 절차: $runbook"
  exit 1
fi
end="$(printf '%s\n' "$cert" | openssl x509 -noout -enddate | sed 's/^notAfter=//')"
if printf '%s\n' "$cert" | openssl x509 -noout -checkend $(( min_hours * 3600 )) > /dev/null; then
  echo "인증서가 ${min_hours}시간 넘게 남았다(만료 $end)"
  exit 0
fi
echo "인증서가 ${min_hours}시간 안에 만료된다(만료 $end)"
notify "[배포 서버] HTTPS 인증서가 ${min_hours}시간 안에 만료됩니다(만료 $end). 자동 갱신이나 nginx 다시 읽기가 실패했을 수 있습니다. 서버에서 sudo certbot renew 로 갱신하고 로그(/var/log/letsencrypt/letsencrypt.log)를 확인해 주세요. 절차: $runbook"
exit 1
