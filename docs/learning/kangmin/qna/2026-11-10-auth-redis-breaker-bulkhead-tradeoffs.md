---
title: Gateway auth-redis 차단기·벌크헤드 권장 설정은 무엇을 바꾸고, 바꾸면 어떤 문제가 생기나
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 중급
tags: [circuit-breaker, bulkhead, sliding-window, resilience4j, spring-cloud-circuitbreaker, gateway, redis, littles-law]
---

# Gateway auth-redis 차단기·벌크헤드 권장 설정은 무엇을 바꾸고, 바꾸면 어떤 문제가 생기나

## 질문
11/10 측정 뒤 개선을 하나씩 세팅하기로 하고, 첫 번째인 Gateway 차단기와 벌크헤드를 다시 상세히 설명하고 감수할 점으로 생길 문제를 알려 달라고 했다.

## 짧은 답
- 인증이 필요한 요청마다 Gateway가 Redis에 로그아웃 목록을 묻는다(`GatewayAuthenticationFilter.java:89-92`, 시한 100ms). 이 호출을 감싸는 순서는 바깥부터 `시간 제한 → 차단기 → 벌크헤드 → Redis 조회`다(spring-cloud-circuitbreaker-resilience4j 5.0.3 `ReactiveResilience4JCircuitBreaker.run` 바이트코드로 확인).
- 그래서 벌크헤드 거절(`BulkheadFullException`)도 차단기에서 실패로 세어진다. 서버 CPU가 차서 호출이 길어지면 벌크헤드 25가 차고, 거절이 실패로 쌓여 차단기가 열린다. 그러면 10초 동안 인증 요청 전체가 503이 된다. 11/9 Q1의 "2ms 빠른 실패로 열림"은 이 경로로 설명된다(추정, 거절이 실패로 세어진 지표는 따로 보지 않음).
- 권장 설정은 시간 창 10초, 최소 500건, 느린 호출 100ms·80%, 열림 5초, 반열림 20건, 벌크헤드 100이다. 대가는 "진짜 장애를 늦게, 또는 저트래픽에서는 아예 감지하지 못하는 것"과 "빠른 거절 대신 느린 응답"이다.

## 설명

### 바꿀 값
| 키 | 지금 | 권장 | 바꾸는 이유 |
|---|---|---|---|
| `sliding-window-type` | 건수 기준(기본값) | `TIME_BASED` | 건수 20은 1,000/s에서 0.02초 분량이라 순간 지연 하나로 열린다 |
| `sliding-window-size` | 20(건) | 10(초) | |
| `minimum-number-of-calls` | 20 | 500 | 표본이 적을 때 우연히 열리지 않게 한다 |
| `failure-rate-threshold` | 50 | 50(유지) | 진짜 오류 감지는 그대로 둔다 |
| `slow-call-duration-threshold` | 50ms | 100ms | 재기동 직후 50~80ms 호출로 열렸다(Redis 자체는 1ms 미만) |
| `slow-call-rate-threshold` | 50 | 80 | |
| `wait-duration-in-open-state` | 10s | 5s | 열렸을 때 전체 거절 시간을 줄인다 |
| `permitted-number-of-calls-in-half-open-state` | 5 | 20 | 시험 호출 5건이면 우연히 3건(60%)만 느려도 50% 기준을 넘어 다시 열린다. 20건이면 비율이 덜 흔들린다(처음 쓴 "하나만 느려도"는 틀려 고쳤다) |
| bulkhead `max-concurrent-calls` | 25(기본값, 대기 0) | 100 | 1,200/s에서 남은 자리 0이었다 |

### 감수할 점과 생길 수 있는 문제
| # | 문제 | 왜 생기나 | 영향 | 대응 |
|---|---|---|---|---|
| 1 | 저트래픽에서 차단기가 열리지 않는다 | 시간 창 10초 안에 최소 500건이 있어야 판정한다. 초당 50건 미만이면 Redis가 죽어도 판정을 시작하지 않는다(계산값) | 요청마다 100ms를 기다린 뒤 503이다. 결과(503)는 같고 응답만 100ms 늦다. Redis 복구를 돕는 "호출 멈춤"은 없다 | 저트래픽에서 Redis 부하는 작아 감수한다. 필요하면 최소 건수를 100~200으로 낮추고 다시 잰다 |
| 2 | 느린 호출 규칙이 사실상 꺼진다 | 시한이 100ms라 100ms를 넘는 호출은 느림이 되기 전에 시한 초과(실패)가 된다. 느린 호출 기준 100ms는 걸릴 호출이 없다 | Redis가 "60~99ms로 느리지만 살아 있는" 상태를 감지하지 못한다. 모든 인증 요청이 그만큼 느려진다 | 80ms로 낮추면 규칙이 살아난다. 다만 측정 E는 100ms로 했으니 80ms는 다시 재야 한다 |
| 3 | 빠른 거절이 느린 응답으로 바뀐다 | 벌크헤드가 크면 CPU 포화 때 거절 대신 대기가 쌓인다(리틀의 법칙: 동시 100 ÷ 호출 시간). 1,200/s에서 p95가 60ms → 367ms, 1,600/s에서 p99 8.8s였다 | 사용자는 503 대신 수 초 대기를 겪고, 클라이언트 시한에 걸리면 재시도가 몰린다 | 대기열 앞단이 입장 수를 제한하는 구조라 감수한다. 클라이언트 시한·재시도 간격을 함께 본다 |
| 4 | 다음 병목이 드러난다 | Gateway를 통과한 요청이 예매로 간다. 예매 DB 풀 5는 C3에서 대기 약 195건이었다 | 503(Gateway)이 504·503(예매 풀 시간 초과)으로 바뀐다. 한도 향상은 약 1,000/s 근처에서 멈춘다(추정) | 예매 DB 풀을 보류했으니 재측정 표에 "Gateway만 바꾼 값"으로 따로 적는다 |
| 5 | 진짜 장애 때 감지가 늦다 | 10초 창에서 실패율이 50%를 넘어야 열린다 | 장애 시작 후 수 초 동안 요청마다 100ms 시한까지 기다린다. 이 동안 벌크헤드 100이 차서 거절이 늘고, 그 거절이 실패로 세어져 결국 열린다 | 열림 5초로 거절 구간은 짧아진다 |
| 6 | 열림·반열림 주기가 짧아진다 | 열림 5초라 장애가 길면 5초마다 시험 호출 20건이 Redis로 간다 | 시험 호출이 실패하면 20건이 각각 100ms 뒤 503이다. 부하는 작다 | 감수한다 |
| 7 | 공용 설정이라 모든 환경에 적용된다 | `config-repo/gateway.yml`은 로컬·배포가 함께 쓴다 | 로컬 개발에서도 차단기가 덜 민감해진다. 필터 테스트는 차단기를 mock으로 바꿔 영향이 없다 | contract 이슈로 Gateway 담당이 정한다 |
| 8 | 벌크헤드 키가 운영 yml에서 먹는지 확인하지 않았다 | 측정 때는 `SPRING_APPLICATION_JSON`으로만 넣었다 | 키가 틀리면 조용히 25로 남는다 | 배포 뒤 `resilience4j_bulkhead_max_allowed_concurrent_calls{name="auth-redis"}`가 100인지 먼저 본다 |

### 보안 성질은 바뀌지 않는다
Redis 확인에 실패하면 지금처럼 503이다(fail-closed). 로그아웃한 토큰이 통과하는 일은 생기지 않는다. 통과시키는 fail-open은 보안상 교환이라 ADR 대상이고 이번 범위가 아니다.

## 확인 못 함
- 벌크헤드 거절이 실제로 차단기 실패율을 올린 비율(지표로 따로 보지 않음)
- Spring Cloud TimeLimiter 기본값(1초)이 이 경로에 걸리는지. 안쪽 시한 100ms가 먼저라 영향은 없다고 본다(추정)
- 80ms 기준의 오탐 여부

## 더 볼 것
- `docs/design/lottery/numbers-and-measurement.md` 3-4절 C1~E
- `loadtest/deploy/compose.gateway-bulkhead.yml`
- `docs/learning/kangmin/qna/2026-11-10-circuit-breaker-warmup-and-relaxing.md`
