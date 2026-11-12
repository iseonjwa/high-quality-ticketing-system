---
title: 겹겹이 걸린 시한은 각각 어디서부터 재고 어떤 순서로 터져야 원인을 알 수 있나(Gateway 인증 계단과 config-repo 전체 시한 지도)
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [timeout, timeout-budget, gateway, redis, lettuce, resilience4j, circuit-breaker, hikari, postgresql, kafka, spring-cloud-gateway]
---

# 겹겹이 걸린 시한은 각각 어디서부터 재고 어떤 순서로 터져야 원인을 알 수 있나

## 질문
`config-repo/gateway.yml`에 Redis 관련 시한이 여러 개다(`spring.data.redis.timeout: 300ms`, `auth.redis-timeout: 200ms`, 차단기 `slow-call-duration-threshold: 100ms`, TimeLimiter 1초). auth-redis 타임아웃과 필터 Redis 타임아웃은 무엇이 다르고 왜 값이 다른가. 실제 흐름에서 의미 없는 값은 없나. 각 시계는 어디서부터 재고, 어떤 순서로 터져야 어디가 문제인지 알 수 있나. config-repo 전체의 시한 기준도 알고 싶다.

## 짧은 답
- `auth.redis-timeout: 200ms`가 "auth 타임아웃"(인증 필터 시한)이고, 인증 경로에서 **실제로 요청을 끊는 시계는 이것 하나**다. `auth-redis`는 차단기·bulkhead의 이름이며, 거기 딸린 100ms는 끊지 않는 **판정 기준**이다.
- 시계는 바깥이 안쪽을 품는다. 끝점은 모두 같은 사건(조회 응답)이고 시작점만 바깥일수록 이르다. 그래서 **원인 이름을 붙이고 싶은 층의 한도를 가장 작게** 둬야 그 층 이름(`lookup_timeout`)으로 남는다.
- Lettuce 300ms와 TimeLimiter 1초는 필터보다 늦게 오도록 일부러 뺀 값이라 정상이면 걸리지 않는다. 끊는 장치로는 의미가 없고, "필터를 방해하지 않기"와 "걸리면 Gateway가 굶고 있다는 경보"로 의미가 있다.
- Spring Cloud Gateway의 `response-timeout: 3s`는 요청 전체가 아니라 라우팅 구간(연결 얻기부터 응답 헤더 도착까지)만 잰다. 인증 시간과 응답 본문은 밖이다.
- 바깥 시한보다 안쪽 시한 합이 큰 곳이 두 군데 있다. 결제 승인(3초 대 최악 7.5초)은 멱등 키 재시도로 의도한 것이다. 예매 선점은 락 대기·문장 시한이 없어서 생긴 빈틈이다(추론).

## 자세히

### 1. 시한·판정·상태 시간을 먼저 나눈다
| 종류 | 하는 일 | 예 |
|---|---|---|
| 시한(timeout) | 넘으면 기다림을 끊고 오류를 낸다 | 필터 200ms, Lettuce 300ms, TimeLimiter 1초 |
| 판정 기준(threshold) | 끊지 않는다. 끝난 호출을 "느림"으로 분류한다 | 차단기 느린 호출 100ms |
| 상태 시간 | 상태를 유지하는 시간 | 차단기 열림 5초 |

### 2. 감싸는 순서와 시계의 시작점
바깥부터 TimeLimiter(1초) → 차단기 → bulkhead(50, 대기 0) → 필터 `Mono.timeout(200ms)` → Lettuce 명령(300ms) → 네트워크·Redis다(ADR 0050, spring-cloud-circuitbreaker-resilience4j 5.0.3 소스 확인).

```
TimeLimiter 1s   [ 구독 ─────────────────────────────── 응답 ]
차단기(기록)        [ 허가 ─────────────────────────── 응답 ]   > 100ms면 "느림"
필터 200ms              [ bulkhead 통과 뒤 구독 ──── 응답 ]
Lettuce 300ms                [ 명령 전송 ────────── 응답 ]
Redis 실제 일                          [0.3~1.9ms]
```
같은 조회에 대해 항상 TimeLimiter 시계 ≥ 차단기 시계 ≥ 필터 시계 ≥ Lettuce 시계다. Redis가 일한 시간은 극히 일부이고 나머지는 Gateway 쪽 대기(CPU, 이벤트 루프, GC)다. 그래서 Redis가 멀쩡해도 Gateway가 바쁘면 모든 시계가 함께 길어진다.

### 3. 선택지 비교: 시한 순서를 어떻게 둘까
| 안 | 순서 | 일어나는 일 |
|---|---|---|
| A. 모두 같게(3차 R3: 필터·Lettuce 100ms) | 필터 = Lettuce | 두 시계가 경쟁한다. Lettuce가 이기면 원인이 `redis_error`로 남아 "연결 오류"와 "응답 없음"이 섞인다(ADR 0050 56행) |
| B. 안쪽일수록 작게(Lettuce < 필터 < TimeLimiter) | 정석 | 가장 안쪽 Lettuce가 늘 먼저 터진다. 원인은 늘 `redis_error`이고, 필터가 만든 `lookup_timeout` 구분이 쓸모없어진다 |
| C. 이름 붙일 층만 가장 작게(느림 100 < 필터 200 < Lettuce 300 < TimeLimiter 1000) | 채택 | 필터만 실제로 끊고 `lookup_timeout`으로 남는다. 나머지는 안 걸려야 정상인 안전망이 된다 |

**고른 이유**: 운영자가 지표 한 줄로 "Redis 응답 없음"과 "연결 끊김"과 "Gateway 정체"를 가를 수 있어야 한다. 그러려면 원인 이름을 정하는 층이 하나여야 하고, 그 층이 반드시 먼저 터져야 한다.

**감수한 점**: 진짜 Redis 멈춤 때 사용자가 503까지 최대 200ms를 기다린다(SA 9-5의 100ms보다 길다). Lettuce 300ms는 인증 경로에서 끊는 역할을 잃는다.

### 4. 어떤 순서로 터져야 원인을 아나
| 지표 `cause` | 먼저 터진 것 | 해석 |
|---|---|---|
| `lookup_timeout` | 필터 200ms | Redis 응답 없음(설계대로의 장애 신호) |
| `redis_error` | 연결 오류 또는 Lettuce 300ms | 연결 오류면 Redis·네트워크. 시한 초과 예외면 필터 시계가 제때 못 울렸다 |
| `time_limiter` | TimeLimiter 1초 | 안쪽 시계가 모두 못 울림. Gateway 스케줄러 정체 |
| `bulkhead_full` | 자리 50개 다 참 | Gateway 과부하, 또는 Redis가 느려 자리를 오래 붙잡음 |
| `breaker_open` | 이미 열린 차단기 | 앞선 원인의 결과 |

정상이면 장애 때 `lookup_timeout`과 연결 오류만 늘어야 한다. `time_limiter`가 0이 아니면 Redis보다 Gateway를 먼저 본다.

### 5. 200ms로 올려도 시한 초과는 두 번 세어진다
Resilience4j 2.3.0 `CircuitBreakerMetrics.onError(duration)`는 걸린 시간이 느린 호출 기준보다 길면 `SLOW_ERROR`로 기록한다(바이트코드로 확인). `SLOW_ERROR`는 실패율과 느린 호출 비율 둘 다에 들어간다. 필터 시한 초과는 늘 200ms 이상 걸리므로 100ms 기준을 항상 넘는다. 즉 시한 초과는 100ms일 때도 200ms일 때도 두 비율에 함께 들어간다. 200ms로 올린 효과는 이중 집계를 없앤 것이 아니라 **100~200ms에 끝난 호출이 실패가 아닌 "느린 성공"으로 남는 구간을 만든 것**이다.

### 6. Gateway `response-timeout`이 재는 범위
Spring Cloud Gateway 5.0.4 `NettyRoutingFilter`는 `getHttpClientMono(...).flatMapMany(요청 전송·응답 연결)`에 `Flux.timeout(responseTimeout)`을 걸고 초과를 504로 바꾼다. `HttpClientFactory`는 Reactor Netty의 `responseTimeout`(읽기 사이 간격)을 부르지 않는다(둘 다 바이트코드 확인). 따라서 시계는 라우팅 필터가 구독한 순간(인증 뒤)에 시작해 응답 헤더가 도착하면 멈춘다. 연결 얻기와 `connect-timeout` 1초는 안에 들고, 인증 시간과 본문 전송은 밖이다.

### 7. 시한 예산(timeout budget) 점검 원칙
- 바깥 시한 > 안쪽 시한 합이어야 바깥이 안쪽 일을 헛되게 끊지 않는다.
- 지킬 수 없으면 두 가지 중 하나로 보완한다. (1) 바깥이 끊어도 안쪽 결과를 나중에 받을 수 있게 한다(멱등 키). (2) 안쪽 시한을 줄여 합을 맞춘다.
- 같은 층 안에서는 "서버가 먼저 정리하고, 클라이언트는 서버가 못 할 때만 포기"하게 둔다. PostgreSQL `statement_timeout` 1.5초 < JDBC `socketTimeout` 2초가 그 예다. 서버 취소(SQLState 57014)는 잠금을 풀고 연결을 다시 쓸 수 있지만 소켓 시한은 연결을 버린다.

## 이 프로젝트에서는
| 흐름 | 바깥 | 안쪽 합(최악) | 판정 |
|---|---|---|---|
| 인증 | Gateway에 요청 전체 시한 없음 | 필터 200ms + 라우팅 3s | 계단(100 < 200 < 300 < 1000)은 지켜진다 |
| 결제 승인 | Gateway 3s | 연결 대기 1s + 문장 1.5s + PG 0.5+2s + 연결 대기 1s + 문장 1.5s = 7.5s(계산값) | 바깥이 먼저 끊는다. 504 뒤 결제는 계속되고 같은 멱등 키 재시도로 결과를 받는다(ADR 0018, S-26). 의도한 어긋남 |
| 좌석 선점 | Gateway 3s | Hikari 기본 30s + 락 대기 무한 + 문장 시한 없음 | 빈틈(추론). SA 9-4의 선점 `lock_timeout` 500ms가 코드에 없다. ADR 0035가 M4로 미뤘다 |
| 추첨 자동결제 | 선점 30분(정산 상한 60분) | Kafka 대기 약 3.3분 + 판정 10초 + 재조회 최장 약 21분 ≈ 25분 | 지켜진다(`[가정]`). 허가 거절 재시도는 1초 간격 무제한이라 PG가 30분 넘게 막히면 선점 만료가 먼저 온다 |

주요 설정 위치:
- Gateway: `config-repo/gateway.yml` 9·10행(연결 1s·응답 3s), 43행(assistant 10s), 53행(Lettuce 300ms), 58행(필터 200ms), 89·91행(느린 호출 100ms·열림 5s), 101행(bulkhead 대기 0)
- 결제: `config-repo/payment-service.yml` 11·14·19·21행(연결 대기 1s·검사 1s·소켓 2s·문장 1.5s), 26·27·29행(Kafka 10s·5s·2s), 37·38행(임대 30s·전송 12s). PG 연결 0.5s·읽기 2s는 설정에 값이 없어 `PgProperties.java` 기본값
- 예매: `config-repo/reservation-service.yml` 29행(입장 토큰 10분), 34행(입장 주기 500ms). 선점 5분은 `Schedule.java`, 결제 토큰 = 선점 만료 − 10초는 `PaymentTokenIssuer.java`
- 공통: `config-repo/application.yml` 51·52행(Redis 명령 100ms·연결 1s), `application-prod.yml` 7·13행·`eureka.yml` 10행(Eureka 계열 5초)

확인할 질문(측정·확인 안 함):
- 코드 주석 두 곳이 옛 값 100ms를 말한다(`GatewayAuthenticationFilter.java` 39행, `AuthRedisFailures.java` 51행).
- prod 프로필에 Eureka `lease-renewal`·`lease-expiration`이 없어 기본 30·90초다. 죽은 인스턴스가 목록에서 늦게 빠질 수 있다.
- 입장 토큰 TTL이 설정(`queue.entry-token-ttl`)과 상수(`EntryTokenClaims.TTL`) 두 곳에 있다.
- `assistant-service.yml` 12행 주석의 "읽기 사이 간격"은 6절의 확인과 다르다.

## 면접 포인트
- **"타임아웃을 여러 겹 걸 때 값은 어떻게 정했나?"** 시계는 바깥이 안쪽을 품어 바깥이 항상 길게 잰다. 그래서 원인 이름을 붙일 층 하나를 가장 짧게 두고, 나머지는 그보다 길게 둬 안전망으로 썼다. 느림 판정 100 < 필터 200 < Lettuce 300 < TimeLimiter 1000이다. 같게 두었을 때 Lettuce가 먼저 터져 원인이 섞인 측정이 근거다.
- **"한 번도 안 걸리는 타임아웃은 왜 두나?"** 정상이면 0이어야 하는 지표로 쓴다. 바깥 TimeLimiter가 걸렸다면 안쪽 타이머가 제때 못 돌았다는 뜻이라 Redis가 아니라 Gateway 정체를 먼저 의심한다.
- **"Gateway 3초를 넘는 결제는 어떻게 되나?"** 안쪽 합이 3초를 넘을 수 있음을 알고 받아들였다. 504 뒤에도 결제는 끝까지 진행되고, 클라이언트가 같은 멱등 키로 다시 물으면 결과를 받는다.

## 참고
- 이 세션에서 바이트코드로 확인: Spring Boot 4.1.2 `LettuceConnectionConfiguration`(`TimeoutOptions.enabled()`), Resilience4j 2.3.0 `CircuitBreakerMetrics.onError`(`SLOW_ERROR`), Spring Cloud Gateway 5.0.4 `NettyRoutingFilter`(`Flux.timeout` → `GATEWAY_TIMEOUT`)·`HttpClientFactory`
- `docs/decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md`, `docs/decisions/0018-payment-pg-permit-before-tx1.md`, `docs/decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md`
- `docs/design/infra/gateway-auth-redis-resilience.md`, `docs/core/SA MASTER.md` 9-4
- 관련 노트: [2026-11-10 Gateway 스레드·벌크헤드·연결 풀 수치](2026-11-10-gateway-threads-bulkhead-vs-server-spec.md), [2026-11-11 추첨 선점 30분 근거](2026-11-11-lottery-hold-30min-basis.md)
- 라이브러리 기본값(문서 기준, 측정 안 함): HikariCP `connectionTimeout` 30초, PostgreSQL `lock_timeout` 0(무한), Kafka `max.poll.interval.ms` 5분, Resilience4j TimeLimiter 1초
