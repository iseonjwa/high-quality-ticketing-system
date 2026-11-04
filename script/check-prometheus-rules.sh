#!/usr/bin/env bash
# Prometheus 알림 규칙 문법 검사와 규칙 단위 테스트(promtool), Alertmanager 설정·경로 검사(amtool).
# compose와 같은 Prometheus·Alertmanager 이미지를 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."
image=$(grep -m1 'image: prom/prometheus' compose.yml | awk '{print $2}')
run() { docker run --rm -v "$PWD/infra:/infra:ro" -w /infra/prometheus/tests --entrypoint promtool "$image" "$@"; }
run check rules /infra/prometheus/rules/payment-alerts.yml
run test rules payment-alerts.test.yml
am_image=$(grep -m1 'image: prom/alertmanager' compose.yml | awk '{print $2}')
am() { docker run --rm -v "$PWD/infra:/infra:ro" --entrypoint amtool "$am_image" "$@"; }
am check-config /infra/alertmanager/alertmanager.yml
# 결제 규칙의 두 긴급도가 모두 슬랙 수신자로 가는지 확인한다
for urgency in immediate threshold; do
  am config routes test --config.file=/infra/alertmanager/alertmanager.yml --verify.receivers=slack-monitoring \
    env=local service=payment severity=critical urgency="$urgency"
done
