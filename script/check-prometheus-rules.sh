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
# 메시지 템플릿이 문법 오류 없이 그려지는지 본다(값은 amtool 예시 데이터).
for t in title fallback text footer color; do
  am template render --template.glob='/infra/alertmanager/templates/*.tmpl' --template.text="{{ template \"iseonjwa.slack.$t\" . }}" > /dev/null
done
echo "Alertmanager 설정·경로·템플릿 검사 통과"
