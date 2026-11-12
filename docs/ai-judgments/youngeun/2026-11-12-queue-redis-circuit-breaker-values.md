---
title: 대기열 Redis 차단기 값을 Gateway auth-redis와 같게 두되, 느린 호출 기준은 AI가 제안한 50ms 대신 100ms로 올리고 공통 Redis 시한을 200ms로 늘렸다
date: 2026-11-12
updated: 2026-11-12
judge: youngeun
ai: Claude (Cowork, 코드 읽기와 값 비교)
verdict: 부분 수용
area: 대기열
presentation: 후보
shared: 예
---

# 대기열 Redis 차단기 값을 Gateway auth-redis와 같게 두되, 느린 호출 기준은 AI가 제안한 50ms 대신 100ms로 올리고 공통 Redis 시한을 200ms로 늘렸다

## 맥락
- 해결하려던 작업: ISE-59 중 "Resilience4j 서킷 브레이커". 1단계(503 + Retry-After, 판단 기록 1)는 Redis 호출이 하나씩 실패할 때만 다룬다. Redis가 계속 느리면 대기열 요청이 Tomcat 스레드를 시한만큼씩 붙잡고, 같은 스레드를 쓰는 좌석 조회·선점 API까지 느려질 수 있다.
- SA 9-4 값: 최근 20회 중 50% 실패 또는 50ms 초과면 OPEN, 10초 뒤 HALF_OPEN 5회. Gateway auth-redis는 11/10 배포 측정에서 건수 창 20이 순간 지연에 흔들려, ADR 0050에서 시간 창으로 바꿨다.
- 질문: "대기열 차단기도 gateway 차단기랑 기준을 똑같이 하는 게 좋을까? 아니면 실험해보면서 결정해야 하나?"
- AI 활용 방식: `gateway.yml`의 auth-redis 값, `config-repo`의 Redis 시한, payment-service의 Resilience4j 사용 방식, reservation-service의 Redis 사용처를 읽게 하고 값을 비교하게 했다.

## AI 제안
- 값: 창·최소 호출 수·실패율·열림 시간·시험 호출 수는 Gateway와 같게(TIME_BASED 10초, 500건, 50%, 5초, 20건). "얼마나 확실해야 열까, 얼마나 쉬었다 볼까"는 서비스마다 다를 이유가 적고, 팀에 설명하기 쉽다.
- 다르게 둘 것
  - 벌크헤드 없음: Gateway는 이벤트 루프라 Redis 응답을 기다리는 요청 수에 상한이 없어 50으로 묶었다. 대기열은 Tomcat 스레드 200개가 이미 상한이다.
  - 느린 호출 50ms: 느린 호출 기준은 시한보다 작아야 의미가 있다. reservation의 Redis 시한은 공통 100ms라서, Gateway처럼 "시한의 절반"으로 맞췄다. 평소 대기열 처리는 1.5~4ms다.
- 실험: 값 전체를 실험으로 정하기는 어렵다. Redis가 하나라 대기열에만 지연을 넣으면 Gateway 인증이 먼저 막힌다. "열리는지"는 단위 테스트로 보고, 일요일 재측정에서는 "정상 부하에서 잘못 열리지 않는지"(느린 호출 비율)만 본다.
- 차단기 하나(`queue-redis`)로 Redis 호출 네 가지(진입·순번·토큰·입장)를 묶는다. reservation-service에서 Redis를 쓰는 곳은 대기열뿐이다.
- 실패로 셀 예외는 `QueryTimeoutException`·`RedisConnectionFailureException`뿐이다. 다른 예외는 성공으로 세여 버그로는 열리지 않는다.
- 열려 있을 때는 열림 시간(5초)을 예외에 실어 `Retry-After`로 쓴다.
- 창 종류(TIME_BASED)는 설정으로 빼지 않고 코드에 고정한다. 창 종류를 바꾸면 `sliding-window-size`의 단위가 초에서 건으로 바뀌고, 건수 창 10건에서는 최소 500건이 뜻을 잃는다.

## 판단
- 결과: 부분 수용
- 판단자: youngeun
- 창·최소 호출 수·실패율·열림 시간·시험 호출 수를 Gateway와 같게 두는 것, 벌크헤드를 두지 않는 것, 차단기 하나로 묶는 것은 받아들였다.
- 느린 호출 기준은 바꿨다: "50ms는 너무 빨라". 100ms로 올리고, 시한이 기준보다 커야 하므로 공통 Redis 시한(`config-repo/application.yml`)을 100ms → 200ms로 늘렸다. Gateway(필터 200ms, 느린 호출 100ms)와 같은 비율이 된다.
- 공통 시한이 user-service에도 적용되는 것을 확인하고, reservation만 덮어쓰는 안 대신 공통 변경을 골랐다. user-service 담당에게 contract 이슈(ISE-260)로 알렸다.
- 이름은 AI가 제안한 `QueueRedisProtectionProperties`(payment의 `PgProtectionProperties`와 같은 꼴) 대신 `QueueRedisCircuitBreakerProperties`로 정했다. 설정 키는 사용자 제안으로 Resilience4j 빌더 메서드 이름(`slidingWindowSize`, `minimumNumberOfCalls` 등)과 같게 했다.
- 창 종류를 설정으로 빼자는 사용자 제안("slidingWindowType도 정하게 하는 게 나을 것 같은데? 아닌가?")은 AI 설명을 듣고 코드에 고정했다.
- 검토한 대안: 대기열 값을 실험으로 정하기(대기열에만 지연을 넣을 수 없어 제외), 느린 호출 50ms 유지, reservation만 시한 200ms로 덮어쓰기

## 반영
브랜치 `feat/ISE-59-queue-redis-fallback`.
- 의존성: `resilience4j-circuitbreaker`, `resilience4j-micrometer`
- infrastructure
  - `QueueRedisCircuitBreakerProperties`(prefix `queue.redis.circuit-breaker`)
  - `QueueRedisCircuitBreakerConfiguration`: 차단기 보관함, `queue-redis` 차단기, 지표 연결. TIME_BASED 고정, `recordExceptions(QueryTimeoutException, RedisConnectionFailureException)`
  - `RedisQueueRepository.call()`: 차단기로 Redis 호출을 감싼다. `CallNotPermittedException`이면 열림 시간을 실어 `QueueStorageUnavailableException`으로 바꾼다
- domain: `QueueStorageUnavailableException`에 `Optional<Duration> retryAfter()`. 생성자는 Java 관례대로 원인을 마지막에 둔다(사용자 지적)
- presentation: `QueueExceptionHandler`는 `retryAfter`가 있으면 그 값, 없으면 `pollMin`을 `Retry-After`로 쓴다
- 설정: `config-repo/reservation-service.yml`, `application-test.yml`

| 값 | 대기열 | Gateway auth-redis (ADR 0050) | SA 9-4 |
|---|---|---|---|
| 창 | 시간 10초 | 시간 10초 | 최근 20회 |
| 최소 호출 수 | 500 | 500 | (없음) |
| 실패율 | 50% | 50% | 50% |
| 느린 호출 | 100ms 넘는 호출 80% | 100ms 넘는 호출 80% | 50ms 초과 |
| 열림 / 시험 호출 | 5초 / 20건 | 5초 / 20건 | 10초 / 5회 |
| 시한 | Lettuce 200ms (공통) | 필터 200ms, Lettuce 300ms | 100ms |
| 벌크헤드 | 없음 | 50 | (없음) |

## 검증
- `RedisQueueRepositoryCircuitBreakerTest` 6건 통과: 운영 규칙에 최소 호출 수·시험 호출 수만 2로 줄였다. 시한 초과·연결 실패가 쌓이면 열리고 열린 동안 Redis를 부르지 않는다, 열림 때 `retryAfter`는 5초, 버그 예외로는 안 열린다, 닫혀 있으면 결과를 그대로 돌려준다, 시험 호출 성공 시 닫히고 실패 시 다시 열린다
- `QueueStorageUnavailableApiTest`: 열림 때 `Retry-After: 5`(기본 3초와 구별)
- 기존 `RedisQueueRepositoryTest`·`RedisQueueRepositoryFailureTest`·`QueueApiTest` 통과. 같은 실행에서 `LotteryPaymentMetricsTest` 실패: 로컬 Redis 시계가 PC보다 약 0.7초 앞서서 생긴 문제로, 이번 변경과 무관
- 미확인: 실제 부하에서 느린 호출 비율. 일요일 재측정에서 `resilience4j_circuitbreaker_slow_call_rate{name="queue-redis"}`가 정상 부하에서 낮게 유지되는지 본다. 높으면 기준을 다시 본다

## 판단 이력
- 2026-11-12 수용: 창·실패율·열림 시간 등은 Gateway와 같게, 벌크헤드 없음
- 2026-11-12 수정: 느린 호출 50ms → 100ms, 공통 Redis 시한 100ms → 200ms (contract ISE-260)
- 2026-11-12 수정: 이름 `QueueRedisProtectionProperties` 제안 대신 `QueueRedisCircuitBreakerProperties`, 설정 키는 Resilience4j 메서드 이름과 같게
- 2026-11-12 수용: 창 종류는 코드에 고정
- 2026-11-12 판단 기록 1 보완: 1에서는 예외에 재시도 시간을 담지 않았다. 이번에는 열림 시간을 담는다. 열림 시간은 infrastructure가 아는 사실이고, `Retry-After`로 쓸지는 여전히 처리기가 정한다

## 발표·면접 포인트
- 같은 Redis 차단기라도 실행 모델에 따라 필요한 보호가 다르다. 이벤트 루프(Gateway)는 기다리는 요청 수에 상한이 없어 벌크헤드가 필요하고, 스레드 풀(Tomcat)은 스레드 수가 이미 상한이다.
- 느린 호출 기준은 시한보다 작아야 의미가 있다. 기준을 올리면서 시한도 함께 올렸고, 공유 설정이라 다른 서비스 담당에게 contract 이슈로 알렸다.
