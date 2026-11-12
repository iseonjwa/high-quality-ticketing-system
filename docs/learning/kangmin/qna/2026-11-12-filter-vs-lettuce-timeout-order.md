---
title: 인증 필터 시한과 Lettuce 명령 시한은 어느 쪽이 바깥이고, Lettuce 300·필터 200과 Lettuce 200·필터 300은 기능이 어떻게 다른가
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [timeout, redis, lettuce, reactor, netty, gateway, circuit-breaker]
---

# 인증 필터 시한과 Lettuce 명령 시한은 어느 쪽이 바깥이고, Lettuce 300·필터 200과 Lettuce 200·필터 300은 기능이 어떻게 다른가

## 질문
1. 필터가 더 안이고 Lettuce가 더 밖인가? 어떤 순서로 시한을 거는 것이 정상인가? Lettuce 300ms는 걸릴 일이 없는 값 아닌가?
2. Lettuce 300ms·필터 200ms(지금)와 Lettuce 200ms·필터 300ms는 기능적으로 무엇이 다른가? 원칙, 이 서비스 구조, 비유로 설명하고 왜 그렇게 해야 하는지 납득시켜 달라.

## 짧은 답
- 필터가 바깥, Lettuce가 안쪽이다. 두 시계는 거의 같은 구간을 잰다. 그래서 둘 중 먼저 울리는 하나만 의미가 있다.
- 사용자가 받는 응답(503), 차단기 집계, 정상 요청은 두 배치에서 같다. 다른 것은 네 가지다. ① 끊기는 시각이 정확한가 ② 원인이 어떤 이름으로 남는가 ③ Lettuce 시계가 보지 못하는 구간을 누가 잡는가 ④ 장애 중 bulkhead 자리를 얼마나 붙잡는가.
- "안쪽 시한 < 바깥 시한" 원칙(Lettuce 200·필터 300)은 안쪽 시계가 **더 정확하고 원인을 더 구체적으로 말해 줄 때** 맞다. 여기서는 둘 다 반대다. Lettuce 시계는 100ms 눈금이라 늦게 울리고(바이트코드 확인), 그 예외는 연결 오류와 같은 `redis_error` 칸으로 들어간다.
- 그래서 지금 배치(필터 200·Lettuce 300)가 맞다. 이유는 "Lettuce가 안전망이라서"가 아니다. **정확하고 이름을 붙일 수 있는 시계(필터)를 살리고, 끌 수 없는 거친 시계(Lettuce)를 방해하지 않을 자리로 비켜 둔 것이다.** Lettuce 300이라는 값 자체에는 인증 경로에서 기능이 없다.

## 자세히

### 1. 확인한 사실
| 사실 | 근거 |
|---|---|
| 필터 `Mono.timeout(200ms)`이 Lettuce가 돌려준 `Mono`를 감싼다. 필터가 바깥이다 | `GatewayAuthenticationFilter.java` 98~99행 |
| Lettuce 명령 시한은 명령을 채널 writer에 넘기는 `write()`에서 시작한다. 연결이 끊겨 버퍼에 쌓인 명령도 같다 | Lettuce 7.5.2 `CommandExpiryWriter.write` → `potentiallyExpire` 바이트코드 |
| Lettuce 시한 타이머는 Netty `HashedWheelTimer`이고 눈금이 100ms다. 마감을 넘긴 뒤 다음 눈금에서 울리므로 최대 약 100ms 늦다 | Lettuce `DefaultClientResources`가 `new HashedWheelTimer(ThreadFactory)`를 쓰고, Netty 4.2.19의 이 생성자는 100ms를 넘긴다(바이트코드) |
| 필터 시한은 Reactor `parallel` 스케줄러의 예약 작업이라 ms 단위로 울린다 | Reactor `Mono.timeout(Duration)` 문서 기준(바이트코드는 안 봄) |
| Lettuce 시한은 끌 수 없다. Spring Boot 4.1.2가 `TimeoutOptions.enabled()`를 조건 없이 켜고, 값을 안 주면 Lettuce 기본 60초다 | `LettuceConnectionConfiguration`, `RedisURI.DEFAULT_TIMEOUT = 60` 바이트코드 |
| Lettuce 시한 초과는 `RedisException` 또는 스프링 `DataAccessException`으로 오고, 분류기는 둘 다 `redis_error`로 센다. 연결 오류와 같은 칸이다 | `AuthRedisFailures.classifyOne` |
| 차단기는 기준(100ms)을 넘긴 실패를 `SLOW_ERROR`로 실패율·느린 비율에 함께 넣는다. 200ms든 300ms든 같다 | Resilience4j 2.3.0 `CircuitBreakerMetrics.onError` 바이트코드 |

### 2. 비유: 식당 홀 매니저와 주방 직원
- 손님 = 사용자 요청, 홀 매니저 = 인증 필터, 주방 직원 = Lettuce, 냉장 창고 = Redis.
- 홀 매니저는 **초 단위 디지털 시계**를 찬다. 주방 직원은 **100ms마다 한 번만 보는 벽시계**를 본다.
- 손님 주문이 들어오면 매니저가 시계를 누르고 주방에 넘긴다. 직원은 받자마자 창고로 가며 자기 시계를 본다. 둘이 재는 시간은 거의 같다.
- **지금 배치(매니저 200, 직원 300)**: 창고가 답이 없으면 200ms 정각에 매니저가 "지금 확인할 수 없습니다"라고 손님께 말하고 주문을 거둔다. 장부에는 "창고 무응답(`lookup_timeout`)"이라고 적는다. 직원의 벽시계는 300ms가 되기 전에 이미 할 일이 없어졌다.
- **뒤집은 배치(직원 200, 매니저 300)**: 직원은 벽시계를 100ms마다 보므로 200ms를 넘긴 뒤 다음 눈금(200~300ms 사이)에야 "포기"를 외친다. 매니저는 그 말을 받아 손님께 같은 말을 한다. 장부에는 직원 쪽 사고 칸(`redis_error`)에 적히는데, 이 칸에는 "창고 문이 잠김(연결 끊김)"도 함께 적힌다.
- 손님이 듣는 말은 같다. 다른 것은 **몇 시에 듣는지(정확히 200 대 200~300 사이)**와 **장부에 어느 칸으로 적히는지**다.

### 3. 상황별 비교 (이 서비스 구조)
| 상황 | 지금: 필터 200 · Lettuce 300 | 뒤집음: Lettuce 200 · 필터 300 |
|---|---|---|
| 정상(1~7ms) | 성공. 두 타이머는 응답 때 취소 | 같음 |
| Redis 150ms에 응답 | 느린 성공(100ms 기준 초과) | 같음 |
| Redis 250ms에 응답 | 200ms에 503, `lookup_timeout` | **실행마다 다르다.** Lettuce가 울릴 눈금이 250 전이면 503, 뒤면 성공 |
| Redis 멈춤 | 약 200ms에 503, `lookup_timeout` | 200~300ms에 503, `redis_error`(연결 오류와 섞임) |
| 재연결 중(명령이 버퍼에 쌓임) | 약 200ms, `lookup_timeout` | 200~300ms, `redis_error`. Lettuce 시계는 버퍼에 넣을 때 이미 시작 |
| 첫 연결 수립이 늦음(`write()` 전, 기동 직후) | 약 200ms, `lookup_timeout` | Lettuce 시계가 아직 안 돎 → 필터가 300ms에 `lookup_timeout`. 기동 웜업 `PING`이 있어 드물다 |
| 장애 중 bulkhead 50 통과량(계산값) | 50 ÷ 0.2 = 250건/s | 50 ÷ 0.2~0.3 = 167~250건/s |
| 차단기 집계 | 실패 + 느림(`SLOW_ERROR`) | 같음 |
| 기동 웜업 `PING` | Lettuce 300(실제 300~400) + 바깥 2초 | Lettuce 200(실제 200~300) + 바깥 2초. 차이 없음 |

장애 판정 기준이 실행마다 흔들리는 것(250ms 행)은 장애 기준으로서 나쁜 성질이다. 같은 Redis 상태가 어떤 요청은 통과시키고 어떤 요청은 503으로 만든다.

### 4. 원칙과 왜 여기서는 뒤집나
원칙 "안쪽 시한 < 바깥 시한"의 근거는 두 가지다.
1. **안쪽이 원인을 더 잘 안다.** Redis에 가까운 쪽이 "무엇이 막혔는지"를 구체적으로 말해 준다.
2. **바깥은 안쪽이 못 보는 구간까지 덮는다.** 그래서 바깥은 전체 마감(안전망)이 된다.

결제 서비스의 DB가 이 원칙대로다. 서버 `statement_timeout` 1.5초가 먼저 걸리면 PostgreSQL이 잠금을 풀고 "쿼리 취소(57014)"라는 구체적 이유를 준다. 연결도 다시 쓸 수 있다. JDBC `socketTimeout` 2초는 서버가 아예 답하지 못할 때만 걸린다. 안쪽이 더 정확하고 더 많이 알려 준다.

Gateway 인증에서는 두 근거가 모두 성립하지 않는다.
1. 안쪽(Lettuce)이 원인을 더 잘 알려 주지 않는다. 시한 초과가 연결 오류와 같은 `redis_error`로 온다. 오히려 바깥 필터가 전용 예외(`RedisLookupTimeout`)로 "응답 없음"을 따로 말한다.
2. 안쪽 시계가 더 거칠다(100ms 눈금). 바깥 시계가 더 정확하다.

전제가 거꾸로이므로 결론도 거꾸로다. **정확하고 이름이 분명한 쪽이 먼저 울리게 한다.** 원칙은 "안쪽이 짧아야 한다"는 문장이 아니라 "원인을 가장 정확히 아는 시계가 먼저 울려야 한다"는 목적이다.

### 5. 그러면 Lettuce 300이라는 값은 무엇인가
- 인증 경로에서는 기능이 없다. 걸리지 않고, 경보 역할도 기대하기 어렵다. Lettuce가 먼저 울리려면 필터 타이머가 100ms 넘게 늦어야 하는데, 그 정도 정체면 두 타이머가 함께 늦는다(추론).
- 그래도 값은 하나 있어야 한다. Lettuce 시한은 끌 수 없고, 비우면 60초다.
- 지켜야 할 조건은 "필터 200보다 커야 한다" 하나다. 눈금 때문에 Lettuce는 정한 값보다 늦게 울릴 수는 있어도 일찍 울리지는 않는다. 그러니 200보다 크면 어떤 값이든 인증에서는 동작이 같다.
- 그중 작은 값을 고르면 Gateway에서 필터 같은 마감 없이 쓰는 Redis 명령(지금은 웜업 `PING`뿐)이 60초씩 매달리지 않는다. 300은 "필터보다 확실히 뒤이면서 60초보다 훨씬 작은 값"이라는 뜻으로 읽는 것이 정직하다. 팀 기록(ADR 0050)에 적힌 이유는 "필터 시한보다 커야 원인이 `lookup_timeout`으로 남는다"뿐이다.
- 고칠 점: 설정 주석에 "인증 경로에서는 걸리지 않는다. 필터보다 크기만 하면 된다"를 적어 두면 같은 오해가 줄어든다(코드 변경 전, 제안).

### 6. 원칙대로 가고 싶다면
Lettuce 200·필터 300으로 가면서 단점을 메우는 방법도 있다. 다만 고칠 것이 많다.
- 분류기에서 Lettuce 시한 초과(`RedisCommandTimeoutException`, 스프링 `QueryTimeoutException`)를 연결 오류와 다른 이름으로 센다.
- 눈금 흔들림을 줄이려면 Lettuce `ClientResources`의 타이머 눈금을 줄인다. 타이머 스레드가 더 자주 깨므로 CPU가 병목인 Gateway에는 비용이다(측정 안 함).
- ADR 0050 결정 4와 순서 테스트(`AuthRedisResilienceContextTest` 126행)를 바꾼다.
얻는 것은 "`lookup_timeout` = Lettuce 밖에서 시간이 샜다(첫 연결 등)"라는 진단 한 갈래다. 지금 그 갈래가 필요한 장애 기록은 없다.

### 7. 추가 질문: 필터가 먼저 끊는 목적은 원인 구분인가, CPU 절감인가
- **원인 구분이 맞다.** ADR 0050 표의 근거가 "필터 시한보다 커야 원인이 `lookup_timeout`으로 남는다"이다. 여기에 필터 시계가 더 정확하다는 점(4절)이 더해진다.
- 단, Lettuce 예외 자체는 구분이 된다. 시한 초과는 `QueryTimeoutException`, 연결 실패는 `RedisConnectionFailureException`으로 온다. 이 둘을 같은 `redis_error` 칸에 넣는 것은 우리 분류 코드(`DataAccessException`이면 모두 `redis_error`)다. 그래서 "Lettuce가 먼저 터지면 구분할 수 없다"는 라이브러리의 한계가 아니라 **분류를 그렇게 짠 결과**다. 팀은 분류를 잘게 나누는 대신 필터가 먼저 울리게 했다.
- `lookup_timeout`의 뜻은 "200ms 안에 응답 신호가 없었다"이다. Redis가 느린지, 멈췄는지, Gateway가 바빠 응답을 늦게 받았는지는 이 이름만으로 갈리지 않는다.
- **CPU 절감 목적은 아니다.** 두 배치 모두 요청마다 타이머 두 개를 건다. 게다가 이미 보낸 Redis 명령은 되돌릴 수 없다. Redis 프로토콜(RESP)에는 보낸 명령을 취소하는 방법이 없다. 그래서 일찍 끊어도 Redis 쪽 일은 줄지 않는다. 일찍 끊어서 얻는 것은 bulkhead 자리를 빨리 돌려받는 것(장애 중 통과량, 3절)과 사용자가 덜 기다리는 것이다.

### 8. 원인 코드 지도
예외가 생기는 곳부터 503이 나갈 때까지의 경로다.
```
Lettuce 예외 ─(Spring 변환)→ DataAccessException ┐
필터 RedisLookupTimeout ────────────────────────┤→ bulkhead → 차단기(기록) → TimeLimiter
                                                 │   → NoFallbackAvailableException으로 감쌈(대체 응답 없는 run)
                                                 └→ 필터 onErrorMap: AuthRedisFailures.record(원인 사슬에서 분류) → 503 SERVICE_UNAVAILABLE
```
원인이 무엇이든 사용자가 받는 응답은 같은 503이다. 구분은 지표 `gateway_auth_redis_failures_total{cause}`와 WARN 로그(`exception=` 클래스 이름)에만 남는다.

| 원인 태그 | 잡는 곳 | 예외 | 뜻 |
|---|---|---|---|
| `lookup_timeout` | 인증 필터 | `AuthRedisFailures.RedisLookupTimeout`(우리가 만든 예외) | 필터 200ms 안에 응답 신호 없음 |
| `redis_error` | Lettuce → Spring 변환 | `QueryTimeoutException`(Lettuce `RedisCommandTimeoutException`, 명령 시한 초과) | Lettuce 명령 시한 초과. 지금 배치에서는 거의 안 나온다 |
| `redis_error` | 같음 | `RedisConnectionFailureException`(Lettuce `RedisConnectionException`, Netty `ChannelException`) | 연결 수립 실패·연결 끊김 |
| `redis_error` | 같음 | `RedisSystemException`(Lettuce `RedisCommandExecutionException` 등) | Redis가 오류로 답함 또는 그 밖의 Lettuce 오류 |
| `time_limiter` | spring-cloud-circuitbreaker TimeLimiter | `java.util.concurrent.TimeoutException` | 차단기 바깥 1초 시한 초과 |
| `bulkhead_full` | bulkhead | `BulkheadFullException` | 동시 50 자리가 다 참. 차단기 집계에서 뺌 |
| `breaker_open` | 차단기 | `CallNotPermittedException` | 차단기가 열려 호출 안 함 |
| `other` | — | 위에 없는 예외 | 분류 못 함 |

- Spring 변환 짝: `LettuceExceptionConverter` 바이트코드에 위 클래스들이 모두 나온다(Spring Data Redis 4.1.2). 어느 예외가 어느 예외로 바뀌는지의 짝은 소스를 끝까지 읽지 않았고, 클래스 이름과 관례로 적었다. 반응형 연결이 이 변환을 쓰는 것(`onErrorMap(translateException())`)은 바이트코드로 확인했다.
- **`TimeoutException`이란**: JDK의 `java.util.concurrent.TimeoutException`이다. "정해진 시간 안에 결과가 오지 않았다"는 일반 예외다. Reactor의 `Mono.timeout(Duration)`이 대체값 없이 시한을 넘기면 이 예외를 낸다. TimeLimiter도 이 방식이라 같은 예외를 낸다. 그래서 필터는 `timeout(redisTimeout, Mono.error(RedisLookupTimeout::new))`처럼 대체 오류를 넘겨 **일부러 `TimeoutException`을 내지 않는다**(`GatewayAuthenticationFilter.java` 97행 주석). 둘 다 `TimeoutException`이면 필터 200ms 초과인지 TimeLimiter 1초 초과인지 구별할 수 없기 때문이다.
- 분류 순서: 대체 응답 없이 `run()`을 쓰면 라이브러리가 모든 실패를 `NoFallbackAvailableException`으로 감싼다. 분류기는 원인 사슬을 바깥부터 보며 처음 분류되는 예외를 쓴다(`AuthRedisFailures.java` 236~249행).

## 이 프로젝트에서는
- 코드: `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java` 96~107행, `AuthRedisFailures.java` 분류 메서드, `GatewayConfiguration.java` 19~21행(웜업)
- 설정: `config-repo/gateway.yml` 49~58행
- 결정: `docs/decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md` 결정 4
- 대비: 예매·사용자 서비스에는 사용처 마감이 없다. 그래서 거기서는 Lettuce 100ms(실제 100~200ms)가 유일한 시계이고 실제로 걸린다.

## 면접 포인트
- **"안쪽 시한이 바깥보다 길면 원칙 위반 아닌가?"** 원칙의 목적은 원인을 가장 정확히 아는 시계가 먼저 울리게 하는 것이다. Lettuce 시계는 100ms 눈금이라 늦게 울리고 시한 초과를 연결 오류와 구분하지 못한다. 바깥 필터 시계가 더 정확하고 전용 예외로 원인을 남긴다. 그래서 필터를 먼저 울리게 했다.
- **"그럼 Lettuce 300ms는 왜 있나?"** 기능이 있어서가 아니라 끌 수 없어서다. 비우면 60초가 되므로 필터를 방해하지 않는 선에서 작게 두었다. 인증 경로에서는 걸리지 않는 값이라고 문서에 적었다.

## 참고
- 이 세션에서 바이트코드로 확인: Lettuce 7.5.2 `CommandExpiryWriter`·`DefaultClientResources`·`RedisURI`, Netty 4.2.19 `HashedWheelTimer`, Spring Boot 4.1.2 `LettuceConnectionConfiguration`, Resilience4j 2.3.0 `CircuitBreakerMetrics`
- 판단 기록: [걸릴 일이 없는 Lettuce 시한을 안전망으로 유지하자는 결론을 거부함](../../../ai-judgments/kangmin/2026-11-12-reject-keep-dead-lettuce-timeout.md)
- 같은 날 노트: `2026-11-12-timeout-layers-and-config-repo-map.md`, `2026-11-12-redis-timeout-consistency-timing-size.md`
