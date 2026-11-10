#!/usr/bin/env bash
# Loki 설정 검사와 건별 알림 규칙(infra/loki/rules) 동작 검사. compose와 같은 Loki 이미지를 쓴다.
# 1) -verify-config로 설정 키를 확인한다.
# 2) 평가 주기를 짧게 줄인 Loki를 임시로 띄우고, 정상 줄 4건(사건 종류마다 하나)과 미끼 줄 3건을 /loki/api/v1/push로 넣은 뒤
#    ruler API(/prometheus/api/v1/rules, /alerts)를 jq로 확인한다. 정상 줄만 알림이 되고 미끼는 알림이 안 되어야 한다.
# 정상 줄은 payment-service의 PaymentAlertLogTest가 고정한 문자열과 같다(환불·대사 줄은 필드 값만 다른 같은 형식). 형식을 바꾸면 세 곳(규칙, 테스트, 이 파일)을 함께 바꾼다.
# Alertmanager는 띄우지 않는다. ruler가 보내지 못하는 오류 로그는 무시하고, 알림 상태만 본다.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v jq > /dev/null || { echo "jq가 필요합니다" >&2; exit 1; }
command -v curl > /dev/null || { echo "curl이 필요합니다" >&2; exit 1; }
image=$(grep -m1 'image: grafana/loki' compose.yml | awk '{print $2}')

work=$(mktemp -d)
container="loki-rules-check-$$"
cleanup() {
  docker rm -f "$container" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# 평가 주기만 줄인 임시 설정. 나머지는 운영 설정 그대로다.
sed 's/evaluation_interval: 1m/evaluation_interval: 5s/' infra/loki/loki.yml > "$work/loki.yml"
grep -q 'evaluation_interval: 5s' "$work/loki.yml" || { echo "loki.yml의 evaluation_interval을 찾지 못했다" >&2; exit 1; }

echo "[1/3] 설정 검사(-verify-config)"
docker run --rm -v "$PWD/infra/loki/loki.yml:/etc/loki/loki.yml:ro" "$image" \
  -config.file=/etc/loki/loki.yml -verify-config 2>&1 | grep -q 'config is valid' \
  || { echo "Loki 설정 검사 실패" >&2; exit 1; }

echo "[2/3] 임시 Loki 기동"
docker run -d --name "$container" -p 127.0.0.1::3100 \
  -v "$work/loki.yml:/etc/loki/loki.yml:ro" -v "$PWD/infra/loki/rules:/etc/loki/rules/fake:ro" \
  "$image" -config.file=/etc/loki/loki.yml > /dev/null
port=$(docker port "$container" 3100/tcp | head -1 | sed 's/.*://')
base="http://127.0.0.1:$port"
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/ready" || true)" = 200 ] && break
  sleep 1
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$base/ready" || true)" = 200 ] || { docker logs "$container" 2>&1 | tail -20 >&2; echo "Loki가 준비되지 않았다" >&2; exit 1; }

push() { # push <service> <level> <줄>
  local ts body
  ts="$(date +%s)000000000"
  body=$(jq -n --arg service "$1" --arg level "$2" --arg ts "$ts" --arg line "$3" \
    '{streams: [{stream: {service: $service, level: $level, env: "preview"}, values: [[$ts, $line]]}]}')
  curl -sf -X POST -H 'Content-Type: application/json' --data "$body" "$base/loki/api/v1/push" \
    || { echo "push 실패: $3" >&2; exit 1; }
}
prefix='2026-11-09T14:03:12.345+09:00 ERROR 1 --- [payment-service] [   scheduling-1] c.i.p.observability.PaymentAlertLog      : '
# 정상 줄 4건(사건 종류마다 하나)
push payment-service ERROR "${prefix}event=PAYMENT_NEEDS_MANUAL_CHECK paymentId=1001 userId=45 reservationId=7001 paidAt=2026-11-09T14:03:12+09:00 reason=PG_UNRESOLVED"
push payment-service ERROR "${prefix}event=DEAD_LETTER_OPEN paymentId=- userId=- reservationId=- paidAt=- eventKey=payment.command-0-12 reason=역직렬화·봉투 오류: bad json"
push payment-service ERROR "${prefix}event=REFUND_NEEDS_MANUAL_CHECK paymentId=1001 userId=45 reservationId=7001 paidAt=2026-11-09T14:03:12+09:00 refundId=301 reason=REFUND_REJECTED"
push payment-service ERROR "${prefix}event=RECONCILIATION_MISMATCH_OPEN paymentId=- userId=- reservationId=7002 paidAt=- rule=RC-3"
# 미끼 3건: 다른 서비스의 같은 이름 로그, ERROR가 아닌 레벨, 형식이 다른 줄
push reservation-service ERROR "${prefix}event=DEAD_LETTER_OPEN eventKey=reservation-0-5 reason=봉투 오류"
push payment-service INFO "${prefix}event=PAYMENT_NEEDS_MANUAL_CHECK paymentId=1002 userId=46 reservationId=7002 paidAt=2026-11-09T14:03:12+09:00 reason=PG_UNRESOLVED"
push payment-service ERROR "${prefix}event=PAYMENT_NEEDS_MANUAL_CHECK paymentId=1003 broken-format"

echo "[3/3] 규칙 평가 확인(ruler API)"
rules=$(curl -sf "$base/prometheus/api/v1/rules")
echo "$rules" | jq -e '[.data.groups[].rules[] | select(.type == "alerting") | .name] | sort
  == ["DeadLetterOpenEvent","PaymentNeedsManualCheckEvent","ReconciliationMismatchOpenEvent","RefundNeedsManualCheckEvent"]' > /dev/null \
  || { echo "Loki가 읽은 규칙이 기대와 다르다" >&2; echo "$rules" | jq -c '.data.groups[].rules[] | {name, health}' >&2; exit 1; }

alerts=""
for _ in $(seq 1 40); do
  alerts=$(curl -sf "$base/prometheus/api/v1/alerts" || true)
  [ -n "$alerts" ] && [ "$(echo "$alerts" | jq '.data.alerts | length')" -ge 4 ] && break
  sleep 3
done
# 미끼가 알림이 되는 경우를 잡으려고 한 번 더 평가될 시간을 준 뒤 센다
sleep 8
alerts=$(curl -sf "$base/prometheus/api/v1/alerts")
count=$(echo "$alerts" | jq '.data.alerts | length')
[ "$count" = 4 ] || { echo "알림이 4건이어야 하는데 ${count}건이다(미끼 줄이 알림이 되었거나 정상 줄이 빠졌다)" >&2; echo "$alerts" | jq -c '.data.alerts[] | {alertname: .labels.alertname, labels, state}' >&2; exit 1; }

# 평가 오류가 없어야 한다(알림이 있으면 health는 ok다)
rules=$(curl -sf "$base/prometheus/api/v1/rules")
echo "$rules" | jq -e '[.data.groups[].rules[] | .health] | all(. == "ok" or . == "unknown")' > /dev/null \
  || { echo "규칙 평가 오류" >&2; echo "$rules" | jq -c '.data.groups[].rules[] | {name, health, lastError}' >&2; exit 1; }

echo "$alerts" | jq -e '
  (.data.alerts | map(select(.labels.alertname == "PaymentNeedsManualCheckEvent")) | .[0]) as $p
  | ($p.labels | .event == "PAYMENT_NEEDS_MANUAL_CHECK" and .env == "preview" and .paymentId == "1001" and .userId == "45"
      and .reservationId == "7001" and .paidAt == "2026-11-09T14:03:12+09:00" and .reason == "PG_UNRESOLVED"
      and .service == "payment" and .urgency == "immediate" and .severity == "critical")
    and ($p.annotations.summary | contains("(사유 PG_UNRESOLVED). 45번 유저가 261109 14:03에 결제한 건입니다"))' > /dev/null \
  || { echo "결제 운영자 확인 알림의 라벨·문구가 기대와 다르다" >&2; echo "$alerts" | jq -c '.data.alerts[] | select(.labels.alertname == "PaymentNeedsManualCheckEvent")' >&2; exit 1; }
echo "$alerts" | jq -e '
  (.data.alerts | map(select(.labels.alertname == "DeadLetterOpenEvent")) | .[0]) as $d
  | ($d.labels | .event == "DEAD_LETTER_OPEN" and .userId == "-" and .paymentId == "-" and .eventKey == "payment.command-0-12"
      and .service == "payment")
    and ($d.annotations.summary | contains("어느 유저의 건인지 알 수 없습니다"))' > /dev/null \
  || { echo "DLQ 알림의 라벨·문구가 기대와 다르다" >&2; echo "$alerts" | jq -c '.data.alerts[] | select(.labels.alertname == "DeadLetterOpenEvent")' >&2; exit 1; }
echo "$alerts" | jq -e '
  (.data.alerts | map(select(.labels.alertname == "RefundNeedsManualCheckEvent")) | .[0]) as $r
  | ($r.labels | .event == "REFUND_NEEDS_MANUAL_CHECK" and .refundId == "301" and .reason == "REFUND_REJECTED" and .userId == "45")
    and ($r.annotations.summary | contains("45번 유저가 261109 14:03에 결제한 건의 환불입니다"))' > /dev/null \
  || { echo "환불 알림의 라벨·문구가 기대와 다르다" >&2; echo "$alerts" | jq -c '.data.alerts[] | select(.labels.alertname == "RefundNeedsManualCheckEvent")' >&2; exit 1; }
echo "$alerts" | jq -e '
  (.data.alerts | map(select(.labels.alertname == "ReconciliationMismatchOpenEvent")) | .[0]) as $c
  | ($c.labels | .event == "RECONCILIATION_MISMATCH_OPEN" and .rule == "RC-3" and .reservationId == "7002" and .paymentId == "-")
    and ($c.annotations.summary | contains("결제 없이 예매 7002번에서 찾은 건입니다"))' > /dev/null \
  || { echo "대사 알림의 라벨·문구가 기대와 다르다" >&2; echo "$alerts" | jq -c '.data.alerts[] | select(.labels.alertname == "ReconciliationMismatchOpenEvent")' >&2; exit 1; }
echo "Loki 설정·건별 알림 규칙 검사 통과(정상 줄 4건만 알림, 미끼 3건은 무시)"
