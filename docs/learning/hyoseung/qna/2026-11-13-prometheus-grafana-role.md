---
title: Prometheus와 Grafana는 무엇이고 부하 측정에서 어떻게 쓰는가
date: 2026-11-13
asker: hyos1
level: 기초
tags: [prometheus, grafana, metrics, load-test, observability]
---

# Prometheus와 Grafana의 역할

## 질문

Prometheus나 Grafana가 무엇이고, 지금 측정에서 안 써도 되는가.

## 답

| | 하는 일 |
|---|---|
| Prometheus | 몇 초마다 각 서비스의 `/actuator/prometheus`에서 지표(커넥션 사용·대기, GC, 요청 수 등)를 긁어 와 시간 순서로 저장한다. 질의 언어(PromQL)로 꺼낸다 |
| Grafana | Prometheus에 저장된 지표를 그래프·대시보드로 보여 준다 |

- 측정 스크립트는 이미 Prometheus를 쓴다. 회차마다 PromQL로 `hikaricp_connections_usage`(커넥션 쥔 시간), `hikaricp_connections_pending`(대기), `jvm_gc_pause`, `gateway_in_flight_rejected_total`을 물어 숫자로 남긴다. 표를 만들려면 숫자가 필요해서다.
- Grafana는 수치 비교에는 필요 없지만, 발표·이력서용으로 변화(예: 커넥션 대기 감소)를 한 장으로 보여 줄 때 쓴다. 같은 시간 범위를 지정하면 측정을 다시 하지 않고 캡처할 수 있다.

## 더 볼 것

- 측정 스크립트: `C:\Users\hyozz\k6-practice\results\tools\compare-p4b.sh`의 `prom()` 함수
