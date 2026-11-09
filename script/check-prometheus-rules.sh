#!/usr/bin/env bash
# Prometheus 알림 규칙 문법 검사와 규칙 단위 테스트(promtool), Alertmanager 설정·경로 검사(amtool).
# compose와 같은 Prometheus·Alertmanager 이미지를 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."
image=$(grep -m1 'image: prom/prometheus' compose.yml | awk '{print $2}')
run() { docker run --rm -v "$PWD/infra:/infra:ro" -w /infra/prometheus/tests --entrypoint promtool "$image" "$@"; }
run check rules /infra/prometheus/rules/payment-alerts.yml /infra/prometheus/rules/lottery-alerts.yml
run test rules payment-alerts.test.yml lottery-alerts.test.yml
# 설정 파일 검사(규칙 경로와 external_labels의 STACK_ENV 확장 포함)
for config in prometheus.yml prometheus-local.yml; do
  docker run --rm -e STACK_ENV=local -v "$PWD/infra:/infra:ro" -v "$PWD/infra/prometheus/rules:/etc/prometheus/rules:ro" \
    --entrypoint promtool "$image" check config "/infra/$config"
done
am_image=$(grep -m1 'image: prom/alertmanager' compose.yml | awk '{print $2}')
am() { docker run --rm -v "$PWD/infra:/infra:ro" --entrypoint amtool "$am_image" "$@"; }
am check-config /infra/alertmanager/alertmanager.yml
# 경로 검사: 수신자가 하나뿐이라 수신자 이름만으로는 못 가린다. 즉시 확인 알림만 1시간 반복 하위 경로를 타는지 본다.
routes() { am config routes test --config.file=/infra/alertmanager/alertmanager.yml --tree env=local service=payment severity=critical urgency="$1"; }
routes immediate | grep -q '{urgency="immediate"}  receiver: slack-monitoring' || { echo "immediate 알림이 하위 경로를 타지 않는다" >&2; exit 1; }
if routes threshold | grep -q 'urgency="immediate"'; then echo "threshold 알림이 immediate 경로를 탄다" >&2; exit 1; fi
# 건별 알림(Loki 규칙, ISE-183): event 라벨이 있으면 urgency=immediate여도 건별 수신자로 간다. event가 없는 즉시 알림은 그대로다.
event_labels="env=preview service=payment severity=critical urgency=immediate event=PAYMENT_NEEDS_MANUAL_CHECK"
am config routes test --config.file=/infra/alertmanager/alertmanager.yml --verify.receivers=slack-monitoring-event $event_labels > /dev/null \
  || { echo "event 알림이 건별 수신자(slack-monitoring-event)로 가지 않는다" >&2; exit 1; }
am config routes test --config.file=/infra/alertmanager/alertmanager.yml --verify.receivers=slack-monitoring \
  env=preview service=payment severity=critical urgency=immediate > /dev/null \
  || { echo "event가 없는 즉시 알림이 기존 수신자(slack-monitoring)로 가지 않는다" >&2; exit 1; }
# 건별 수신자는 해결 메시지를 보내지 않고 기존 수신자는 그대로 보낸다. 수신자 이름은 위 경로 검사가 이미 확인했다.
am_config=$(cat infra/alertmanager/alertmanager.yml)
echo "$am_config" | awk '/- name: slack-monitoring-event/{f=1} f' | grep -q 'send_resolved: false' \
  || { echo "slack-monitoring-event가 send_resolved: false가 아니다" >&2; exit 1; }
# 메시지 템플릿이 문법 오류 없이 그려지는지 본다(값은 amtool 예시 데이터).
for t in title fallback text footer color; do
  am template render --template.glob='/infra/alertmanager/templates/*.tmpl' --template.text="{{ template \"iseonjwa.slack.$t\" . }}" > /dev/null
done
# event 라벨이 있는 알림은 꼬리말이 달라지고, 문장에 이미 있는 라벨은 본문 라벨 줄에서 빠진다(amtool 예시 대신 JSON 데이터로 그린다).
data_dir=$(mktemp -d)
trap 'rm -rf "$data_dir"' EXIT
# Note: mktemp -d는 0700으로 만든다. 컨테이너의 amtool은 nobody로 돌아 그대로면 파일을 열지 못한다(Linux CI에서 permission denied).
chmod 755 "$data_dir"
cat > "$data_dir/event.json" <<'JSON'
{"receiver": "slack-monitoring-event", "status": "firing",
 "alerts": [{"status": "firing", "startsAt": "2026-11-09T05:03:12Z", "endsAt": "0001-01-01T00:00:00Z",
   "labels": {"alertname": "PaymentNeedsManualCheckEvent", "env": "preview", "service": "payment", "severity": "critical",
              "urgency": "immediate", "event": "PAYMENT_NEEDS_MANUAL_CHECK", "paymentId": "1001", "userId": "45",
              "reservationId": "7001", "paidAt": "2026-11-09T14:03:12+09:00", "reason": "PG_UNRESOLVED"},
   "annotations": {"summary": "결제가 운영자 확인 상태가 됐습니다", "runbook": "docs/design/payment/manual-recovery.md 2절"}}],
 "groupLabels": {}, "commonAnnotations": {},
 "commonLabels": {"alertname": "PaymentNeedsManualCheckEvent", "env": "preview", "service": "payment", "severity": "critical",
                  "urgency": "immediate", "event": "PAYMENT_NEEDS_MANUAL_CHECK", "paymentId": "1001"},
 "externalURL": "http://localhost:9093"}
JSON
chmod 644 "$data_dir/event.json"
render_event() {
  docker run --rm -v "$PWD/infra:/infra:ro" -v "$data_dir:/data:ro" --entrypoint amtool "$am_image" template render \
    --template.glob='/infra/alertmanager/templates/*.tmpl' --template.data=/data/event.json --template.text="{{ template \"iseonjwa.slack.$1\" . }}"
}
render_event footer | grep -q '건별 알림 · 같은 사건은 한 번만 보냅니다' || { echo "건별 알림 꼬리말이 그려지지 않는다" >&2; exit 1; }
event_text=$(render_event text)
echo "$event_text" | grep -q 'paymentId=1001' || { echo "건별 알림 본문에 paymentId 라벨이 없다" >&2; exit 1; }
if echo "$event_text" | grep -Eq 'userId=|paidAt=|event=|reason='; then echo "건별 알림 본문에 문장과 겹치는 라벨이 남아 있다" >&2; exit 1; fi
echo "Alertmanager 설정·경로·템플릿 검사 통과"
