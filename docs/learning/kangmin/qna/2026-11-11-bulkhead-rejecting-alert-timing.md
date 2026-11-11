---
title: bulkhead 거절 경보의 "1%·5분"은 무슨 뜻이고, 슬랙 메시지는 언제 몇 번 가나
date: 2026-11-11
updated: 2026-11-11
asker: kangmin
level: 입문
tags: [prometheus, alertmanager, alert-rule, for, rate, repeat-interval, slack, gateway, bulkhead]
---

# bulkhead 거절 경보의 "1%·5분"은 무슨 뜻이고, 슬랙 메시지는 언제 몇 번 가나

## 질문
ADR 0050에서 넣은 `GatewayAuthRedisBulkheadRejecting` 경보 기준 "1%와 5분"은 정확히 무엇을 뜻하나? 슬랙으로 보내나? 기준에 닿으면 한 번 가나, 요청마다 가나?

## 짧은 답
- **1%**: 최근 5분 동안 auth-redis 호출 중 bulkhead가 거절한 비율이다. 초당 1,000건이면 5분 30만 건 중 3,000건(초당 약 10건)을 넘는 상태다(계산값).
- **5분**: 그 비율이 1%를 넘는 상태가 5분 동안 **끊기지 않고** 이어져야 울린다(`for: 5m`).
- **슬랙으로 간다.** Prometheus → Alertmanager → 슬랙 `#이선좌-성능-모니터링`(ADR 0045). 요청마다 가지 않는다. **울릴 때 한 번, 계속 울리면 4시간마다 한 번, 풀리면 "해결" 한 번**이다.

## 설명
### 1. 식이 세는 것
```
rate(bulkhead_full 거절 수 [5m]) / rate(auth-redis 호출 수 [5m]) > 0.01   for: 5m
```
| 부분 | 뜻 |
|---|---|
| `[5m]`의 rate | 지난 5분 동안의 초당 평균이다. 순간값이 아니다 |
| 분모 | 차단기를 지난 auth-redis 호출 전체(성공·실패·무시)다. 거절도 차단기에는 `ignored`로 들어오므로 분모에 포함된다 |
| `> 0.01` | 거절이 1%를 넘는다 |
| `for: 5m` | Prometheus가 15초마다 식을 계산한다(`evaluation_interval`). 참이면 먼저 "대기(pending)"가 되고, 5분 동안 계속 참이어야 "발생(firing)"이 된다. 중간에 한 번이라도 거짓이면 처음부터 다시 센다 |

그래서 울리려면 대략 "5분 평균 1% 초과"가 다시 5분 이어져야 한다. 단위 테스트(`infra/prometheus/tests/gateway-alerts.test.yml`)가 이 모양을 확인한다. 거절 2%가 이어지면 15분 시점에 울리고, 0.5%는 울리지 않는다.

### 2. 메시지가 가는 시점
Alertmanager 설정(`infra/alertmanager/alertmanager.yml`)의 기본 경로를 탄다. 이 경보는 `urgency=threshold`라 1시간 반복하는 immediate 경로를 타지 않는다.

| 시점 | 메시지 | 설정 |
|---|---|---|
| 발생 뒤 약 30초 | 첫 알림 1건 | `group_wait: 30s`(같은 묶음 알림을 30초 모아 한 메시지로) |
| 계속 발생 중 | 4시간마다 1건 | `repeat_interval: 4h` |
| 같은 묶음에 다른 알림이 더해짐 | 최대 5분 간격으로 갱신 1건 | `group_interval: 5m`, 묶음 기준은 `env`·`service`·`severity` |
| 비율이 1% 아래로 내려감 | "해결" 1건 | `send_resolved: true` |

요청마다 보내는 방식은 결제 건별 알림(`event` 라벨이 있는 Loki 규칙, 수신자 `slack-monitoring-event`)만 쓴다. 이 경보에는 `event` 라벨이 없다.

### 3. 이 기준이 놓치는 것과 피크 규칙
티켓 오픈처럼 30초~1분 동안만 몰리면 5분 규칙은 울리지 않을 가능성이 크다. 5분 평균으로 희석되고, `for: 5m` 동안 이어지지도 않기 때문이다. 5분 규칙은 "Gateway가 몇 분째 계속 넘치고 있다"는 **지속 과부하**를 잡는다.

대기열 담당 리뷰(PR `#487`)로 피크 규칙 `GatewayAuthRedisBulkheadRejectingPeak`를 더했다. 1분 평균 1%를 넘으면 `for` 없이 바로 울린다. 메시지가 가는 방식은 2절과 같다(같은 묶음이라 두 규칙이 함께 울리면 한 메시지에 담긴다). 1분 평균이라 20초 안팎보다 짧은 몰림은 여전히 못 잡는다(3차 G의 10초·3%는 약 0.5%, 계산값). 그보다 짧은 거절은 Grafana에서 `gateway_auth_redis_failures_total{cause="bulkhead_full"}`로 본다. 1%·5분·1분은 모두 ADR 0050의 `[가정]`이다.

두 규칙 모두 "Gateway 과부하"라고 단정하지 않는다. Redis가 느려져도 한 호출이 bulkhead 자리를 필터 시한(200ms)까지 붙잡아 초당 250건만 지나가므로 같은 경보가 울린다. `lookup_timeout`·`redis_error`가 함께 늘었으면 Redis 쪽이다.

## 확인한 것과 못 한 것
- 확인함: 두 규칙 식과 `for`(`infra/prometheus/rules/gateway-alerts.yml`), 평가 주기 15초(`infra/prometheus.yml`), Alertmanager 경로·묶음·반복 값, promtool 규칙 테스트 통과(2026-11-11 로컬, PR `#487` CI).
- 확인 못 함: 배포 서버 Alertmanager에 슬랙 웹훅이 들어가 있는지와 실제로 메시지가 도착하는지다. 이 규칙은 main 반영 전이라 배포 서버에 아직 없다.

## 면접 질문
- `for`를 두는 이유는? 한 번 튄 값으로 울리지 않게 하려고 둔다. 대가는 감지가 그만큼 늦어지는 것이다.
- 경보를 건수가 아니라 비율로 둔 이유는? 건수는 트래픽 크기에 따라 기준이 달라진다. 비율은 "사용자 몇 %가 거절당하나"라 트래픽이 바뀌어도 같은 뜻이다.

## 참고
- `docs/decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md` 결정 2
- Prometheus 문서 "Alerting rules"(`for`), Alertmanager 문서 "Configuration"(`group_wait`·`group_interval`·`repeat_interval`)
