#!/usr/bin/env bash
# Prometheus 설정·알림 규칙 문법 검사와 규칙 단위 테스트(promtool). compose와 같은 Prometheus 이미지를 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."
image=$(grep -m1 'image: prom/prometheus' compose.yml | awk '{print $2}')
run() { docker run --rm -v "$PWD/infra:/infra:ro" -w /infra/prometheus/tests --entrypoint promtool "$image" "$@"; }
run check rules /infra/prometheus/rules/payment-alerts.yml
run test rules payment-alerts.test.yml
