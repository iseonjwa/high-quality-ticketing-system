#!/usr/bin/env bash
# 배포 서버의 .env를 처음 한 번 만든다(ISE-174). 이미 있으면 덮어쓰지 않고, 빠진 키만 더한다.
#   script/deploy/init-env.sh
# 키와 값 형식은 로컬의 ./gradlew initLocalEnv(LocalEnvironment)와 같다. 비밀값은 출력하지 않는다.
# SAMPLE_USER_*는 시연용 샘플 계정이다(script/deploy/seed-demo.sh가 가입시킨다).
# 만든 뒤 사람이 채우는 값: SLACK_WEBHOOK_MONITORING(선택, 슬랙 알림), TOSS_SECRET_KEY·TOSS_CLIENT_KEY(선택, 토스 시연 때만).
# 관리자 비밀번호는 서버에서 직접 읽는다.
set -euo pipefail
cd "$(dirname "$0")/../.."
file=.env
umask 077
touch "$file"
chmod 600 "$file"

hex() { openssl rand -hex "$1"; }
value_for() {
  case "$1" in
    DB_USER) echo iseonjwa ;;
    ADMIN_EMAIL) echo admin@iseonjwa.local ;;
    JWT_ACCESS_SECRET|JWT_REFRESH_SECRET|PAYMENT_TOKEN_SECRET|ENTRY_TOKEN_SECRET|BILLING_KEY_SECRET) hex 32 ;;
    DB_PASSWORD) hex 24 ;;
    STACK_ENV) echo deploy ;;
    SAMPLE_USER_EMAIL) echo sample.user@iseonjwa.dev ;;
    # 공백이 있으면 셸에서 .env를 source할 때 깨지므로 공백 없이 둔다
    SAMPLE_USER_NICKNAME) echo 샘플사용자 ;;
    SAMPLE_USER_PASSWORD) hex 12 ;;
    # 비워 두면 mock이고 토스 키도 비어 있다. 토스는 시연 때만 사람이 채운다. ANTHROPIC_API_KEY가 비면 assistant는 검색 모드다
    SLACK_WEBHOOK_MONITORING|PAYMENT_PG_PROVIDER|TOSS_SECRET_KEY|TOSS_CLIENT_KEY|ANTHROPIC_API_KEY) echo "" ;;
    *) hex 16 ;;
  esac
}

keys=(DB_USER DB_PASSWORD JWT_ACCESS_SECRET JWT_REFRESH_SECRET PAYMENT_TOKEN_SECRET ENTRY_TOKEN_SECRET
      BILLING_KEY_SECRET ADMIN_EMAIL ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD STACK_ENV SLACK_WEBHOOK_MONITORING
      SAMPLE_USER_EMAIL SAMPLE_USER_PASSWORD SAMPLE_USER_NICKNAME PAYMENT_PG_PROVIDER TOSS_SECRET_KEY TOSS_CLIENT_KEY ANTHROPIC_API_KEY)
added=()
# 마지막 줄에 줄바꿈이 없으면 새 키가 그 줄에 붙지 않게 먼저 줄을 바꾼다
if [[ -s "$file" && "$(tail -c1 "$file")" != "" ]]; then echo >> "$file"; fi
for key in "${keys[@]}"; do
  if ! grep -q "^${key}=" "$file"; then
    echo "${key}=$(value_for "$key")" >> "$file"
    added+=("$key")
  fi
done

if (( ${#added[@]} )); then
  echo ".env에 더한 키: ${added[*]}"
else
  echo ".env에 빠진 키가 없다"
fi
echo "AI 상담(Claude)을 켜려면 .env의 ANTHROPIC_API_KEY에 키를 넣는다. 비워 두면 검색 모드로만 답한다(채팅·저장소에 붙이지 않는다)"
echo "슬랙 알림을 받으려면 .env의 SLACK_WEBHOOK_MONITORING에 웹훅 주소를 넣는다(채팅·저장소에 붙이지 않는다)"
