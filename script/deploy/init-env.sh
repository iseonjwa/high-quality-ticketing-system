#!/usr/bin/env bash
# 배포 서버의 .env를 처음 한 번 만든다(ISE-174). 이미 있으면 덮어쓰지 않고, 빠진 키만 더한다.
#   script/deploy/init-env.sh
# 키와 값 형식은 로컬의 ./gradlew initLocalEnv(LocalEnvironment)와 같다. 비밀값은 출력하지 않는다.
# SAMPLE_USER_*는 시연용 샘플 계정이다(script/deploy/seed-demo.sh가 가입시킨다).
# 만든 뒤 사람이 채우는 값: SLACK_WEBHOOK_MONITORING(선택, 슬랙 알림), SLACK_WEBHOOK_LOADTEST(선택, 측정 모드 알림 전용 채널), TOSS_SECRET_KEY·TOSS_CLIENT_KEY(선택, 토스 시연 때만).
# 관리자 비밀번호는 서버에서 직접 읽는다.
set -euo pipefail
cd "$(dirname "$0")/../.."
file=.env
umask 077
touch "$file"
chmod 600 "$file"

hex() { openssl rand -hex "$1"; }

# 결제 토큰 키 쌍(ES256, ADR 0055). 예매 서비스가 개인키(PKCS#8 DER)로 서명하고 결제 서비스가 공개키(X.509 DER)로 검증한다.
# 둘은 한 쌍이다. 둘 다 없을 때만 만들고, 하나만 있으면 새로 만들지 않고 멈춘다(하나만 새로 만들면 짝이 맞지 않아 모든 결제가 403이 된다).
# base64는 GNU(-w0)와 macOS 옵션이 달라 tr로 줄바꿈을 지운다. 값은 출력하지 않는다
token_private=""
token_public=""
has_private=0
has_public=0
grep -q "^PAYMENT_TOKEN_PRIVATE_KEY=" "$file" && has_private=1
grep -q "^PAYMENT_TOKEN_PUBLIC_KEY=" "$file" && has_public=1
if (( has_private != has_public )); then
  missing=PAYMENT_TOKEN_PUBLIC_KEY
  (( has_private )) || missing=PAYMENT_TOKEN_PRIVATE_KEY
  echo "${missing}가 .env에 없다. 결제 토큰 키는 한 쌍이라 하나만 있으면 새로 만들지 않는다. 두 줄을 모두 지우고 다시 실행하거나 짝이 되는 키를 직접 넣는다" >&2
  exit 1
fi
if (( ! has_private )); then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -outform DER -out "$tmp/private.der" 2>/dev/null
  openssl pkey -inform DER -in "$tmp/private.der" -pubout -outform DER -out "$tmp/public.der" 2>/dev/null
  token_private=$(base64 < "$tmp/private.der" | tr -d '\n')
  token_public=$(base64 < "$tmp/public.der" | tr -d '\n')
fi
value_for() {
  case "$1" in
    DB_USER) echo iseonjwa ;;
    ADMIN_EMAIL) echo admin@iseonjwa.local ;;
    JWT_ACCESS_SECRET|JWT_REFRESH_SECRET|ENTRY_TOKEN_SECRET|BILLING_KEY_SECRET) hex 32 ;;
    PAYMENT_TOKEN_PRIVATE_KEY) echo "$token_private" ;;
    PAYMENT_TOKEN_PUBLIC_KEY) echo "$token_public" ;;
    DB_PASSWORD) hex 24 ;;
    STACK_ENV) echo deploy ;;
    SAMPLE_USER_EMAIL) echo sample.user@iseonjwa.dev ;;
    # 공백이 있으면 셸에서 .env를 source할 때 깨지므로 공백 없이 둔다
    SAMPLE_USER_NICKNAME) echo 샘플사용자 ;;
    SAMPLE_USER_PASSWORD) hex 12 ;;
    # 비워 두면 mock이고 토스 키도 비어 있다. 토스는 시연 때만 사람이 채운다. ANTHROPIC_API_KEY가 비면 assistant는 검색 모드다
    SLACK_WEBHOOK_MONITORING|SLACK_WEBHOOK_LOADTEST|PAYMENT_PG_PROVIDER|PAYMENT_PG_MOCK_BYPASS|TOSS_SECRET_KEY|TOSS_CLIENT_KEY|ANTHROPIC_API_KEY) echo "" ;;
    *) hex 16 ;;
  esac
}

keys=(DB_USER DB_PASSWORD JWT_ACCESS_SECRET JWT_REFRESH_SECRET PAYMENT_TOKEN_PRIVATE_KEY PAYMENT_TOKEN_PUBLIC_KEY ENTRY_TOKEN_SECRET
      BILLING_KEY_SECRET ADMIN_EMAIL ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD STACK_ENV SLACK_WEBHOOK_MONITORING
      SLACK_WEBHOOK_LOADTEST SAMPLE_USER_EMAIL SAMPLE_USER_PASSWORD SAMPLE_USER_NICKNAME PAYMENT_PG_PROVIDER PAYMENT_PG_MOCK_BYPASS TOSS_SECRET_KEY TOSS_CLIENT_KEY ANTHROPIC_API_KEY)
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
echo "측정 모드 알림을 전용 채널로 받으려면 SLACK_WEBHOOK_LOADTEST에 넣는다. 비우면 SLACK_WEBHOOK_MONITORING으로 간다"
