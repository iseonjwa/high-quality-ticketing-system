---
title: Bulkhead·Circuit Breaker 말고 어떤 장애 대응 장치가 있고, 이 프로젝트는 무엇을 어디에 쓰나
date: 2026-11-09
updated: 2026-11-09
asker: kangmin
level: 중급
tags: [resilience4j, retry, timeout, rate-limiter, time-limiter, fallback, idempotency, dlq, outbox]
---

# Bulkhead·Circuit Breaker 말고 어떤 장애 대응 장치가 있고, 이 프로젝트는 무엇을 어디에 쓰나

## 질문
레질리언스에서 Bulkhead·Circuit Breaker 말고 Retry, Timeout 같은 장치가 또 무엇이 있었나.

## 짧은 답
- Resilience4j 코어 모듈은 6개다: Circuit Breaker, Bulkhead, Rate Limiter, Retry, Time Limiter, Cache. Fallback은 모듈이 아니라 이 장치들이 실패했을 때 대신 돌려줄 값을 정하는 방식이다.
- 이 프로젝트의 결제는 Resilience4j 중 Circuit Breaker·Bulkhead만 쓴다(`resilience4j-circuitbreaker`·`resilience4j-bulkhead` 2.3.0, `pg-integration.md` 5절). Retry·Timeout은 Resilience4j가 아니라 HTTP 클라이언트·Kafka·DB 풀 설정으로 한다.

## 자세히

### 1. Resilience4j 모듈
| 모듈 | 하는 일 | 이 프로젝트 |
|---|---|---|
| Circuit Breaker | 실패율이 기준을 넘으면 일정 시간 호출을 막는다 | 결제 `pg-approve`·`pg-recovery` |
| Bulkhead | 동시 호출 수를 제한해 한 의존성이 자원을 다 먹지 않게 한다 | 결제 `pg-approve`(동시 10, 대기 0)·`pg-recovery`(동시 2) |
| Rate Limiter | 시간당 호출 수를 제한한다 | Resilience4j로는 안 씀. 사용자 단위 요청 제한 ISE-5는 Backlog |
| Retry | 실패한 호출을 정해진 간격으로 다시 시도한다 | Resilience4j로는 안 씀(아래 2절의 다른 수단) |
| Time Limiter | 비동기 호출에 제한 시간을 건다 | 안 씀. 타임아웃은 HTTP 클라이언트 설정으로 |
| Cache | 결과를 캐시해 같은 호출을 줄인다 | 안 씀. 좌석 조회 캐시는 ISE-204·205(Backlog)에서 별도 검토 |

### 2. Resilience4j 밖에서 쓰는 장치
| 장치 | 이 프로젝트에서 | 근거 |
|---|---|---|
| Timeout | PG 연결 0.5초·응답 2초, Gateway 응답 3초, DB 커넥션 대기(결제 1초, 예매는 30초 → 1초 제안 ISE-196) | SA 9-4, `pg-integration.md`, ISE-79 B8 |
| Retry + Backoff | Kafka 소비자 1·2·4초 3회 뒤 DLQ, 자동결제는 허가 없을 때 1초 간격 무제한 | `ConsumerErrorHandlers.retryThenDeadLetter`, `PaymentCommandKafkaConfiguration` |
| 멱등키(Idempotency Key) | 같은 키로 다시 보내면 같은 결과. 재시도를 안전하게 만든다 | 결제 승인, 추첨 응모 |
| DLQ(Dead Letter Queue) | 끝내 실패한 메시지를 따로 모아 잃지 않는다 | `payment.result.dlq` 등 |
| Transactional Outbox | DB 변경과 이벤트 발행을 한 트랜잭션에 묶는다 | 결제·예매 outbox 릴레이(ISE-24, ISE-147) |
| 대사(Reconciliation) | 결과를 모르는 결제를 PG에 다시 물어 맞춘다 | 재확인 스케줄러(`pg-recovery`) |
| Backpressure | 받는 쪽이 감당할 만큼만 가져가 처리한다 | 자동결제 허가 대기(오프셋 유지) |
| Load Shedding(부하 버리기) | 넘치는 요청을 빨리 거절해 나머지를 지킨다 | 결제 503 `PG_UNAVAILABLE`(대기 0). L1의 Gateway 503이 이것인지는 확인 못 함 |
| Graceful Degradation | 일부 기능을 줄여 핵심을 지킨다 | 명시적 구현 없음(확인 못 함) |

### 3. 고르는 기준
- Timeout이 먼저다. 시한이 없으면 Retry·Circuit Breaker가 판단할 실패 자체가 늦게 온다.
- Retry는 멱등이 보장될 때만 쓴다. 아니면 이중 결제가 난다.
- Retry와 Circuit Breaker를 같이 쓰면 Retry가 바깥이어야 OPEN 상태에서 헛된 재시도가 줄어든다(일반적 권장, 이 프로젝트 코드로는 해당 없음).
- 사용자 요청은 빨리 실패(Load Shedding), 잃으면 안 되는 작업은 기다렸다 다시(Backpressure + Retry).
