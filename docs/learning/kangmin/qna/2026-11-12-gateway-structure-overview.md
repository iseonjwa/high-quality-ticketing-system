---
title: 우리 Gateway는 어떤 구조로 짜여 있나(요청이 지나는 층, 필터 순서, 라우트, 인증·입장 토큰, auth-redis 보호, 관측)
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [gateway, spring-cloud-gateway, webflux, jwt, redis, resilience4j, circuit-breaker, bulkhead, in-flight-limit, observability]
---

# 우리 Gateway는 어떤 구조로 짜여 있나

## 질문
우리 서버 Gateway가 어떤 구조로 짜여 있는지 상세히 분석해 달라.

## 짧은 답
- Spring Cloud Gateway(WebFlux, 리액티브) 한 모듈이고, 자바 코드는 12개 클래스 859줄이다. 라우트·시한·차단기 값은 코드가 아니라 `config-repo/gateway.yml`에 있다.
- 요청은 바깥부터 **동시 처리 수 상한(HttpHandler 데코레이터) → 서버 관측 → 요청 로그(WebFilter) → 인증(GlobalFilter -100) → 입장 토큰(GlobalFilter -90) → 라우팅(lb://서비스)** 순서로 지난다. 순서가 곧 비용 설계다. 싸게 거절할 수 있는 것일수록 바깥에 있다.
- 인증은 Gateway에서만 한다. JWT 서명·만료는 메모리에서, 로그아웃 여부는 Redis 블랙리스트로 확인하고, 통과하면 `X-User-Id`·`X-User-Role`을 **지우고 다시 넣어** 서비스로 넘긴다. 서비스는 그 헤더만 믿는다.
- Redis 조회는 필터 시한(200ms) + bulkhead(동시 50) + 차단기(auth-redis)로 감싸고, 실패하면 무조건 503이다(fail-closed). 실패 원인은 6가지로 나눠 세고 로그는 원인별 초당 1건으로 줄인다.

## 자세히

### 1. 모듈 구성
| 패키지 | 클래스 | 역할 |
|---|---|---|
| (루트) | `GatewayApplication` | 기동 |
| `overload` | `InFlightLimitHandlerDecorator` | 동시 처리 수 상한(기본 64), 넘치면 503 `GATEWAY_OVERLOADED` + `Retry-After: 2` |
| `observability` | `RequestLogWebFilter` | 요청 한 줄 로그. 운영은 `problems` 모드(5xx·취소·200ms 이상만) |
| `security` | `AccessPolicy` | 경로·메서드 → PUBLIC·USER·ADMIN·INTERNAL 판정. 인증 요구를 정하는 유일한 곳 |
| | `GatewayAuthenticationFilter` | Access 토큰 검증, 블랙리스트 조회, 사용자 헤더 교체 |
| | `EntryTokenPolicy` | 입장 토큰이 필요한 경로(좌석 조회 GET, 선점 POST) |
| | `EntryTokenFilter` | 입장 토큰의 서명·sub·회차 일치 확인, jti를 `X-Entry-Token-Id`로 전달 |
| | `AuthRedisFailures` | Redis 실패 원인 분류·카운터·로그 제한 |
| | `AuthRedisBreakerEvents` | 차단기 상태 전환 WARN 로그 |
| | `GatewayConfiguration` | JWT 디코더 두 개, 기동 시 Redis PING 예열 |
| | `GatewayErrorWriter` | 모든 거절 응답을 공통 `FailureResponse` JSON으로 씀 |
| | `GatewayExceptionHandler` | 라우트 없음(404)·405·하위 서비스 오류를 같은 형식으로 |

의존성: `spring-cloud-starter-gateway-server-webflux`, Eureka 클라이언트 + LoadBalancer(`lb://`), Config 클라이언트, resilience4j 리액티브 차단기, `spring-boot-starter-data-redis-reactive`, `spring-security-oauth2-jose`(JWT 디코딩만, Spring Security 필터 체인은 쓰지 않음), `common` 모듈.

### 2. 요청이 지나는 층

```
Netty
 └ HttpHandler 데코레이터: InFlightLimitHandlerDecorator   ← 관측·라우트 매칭보다 바깥. 거절 비용 최소
    └ HttpWebHandlerAdapter: 서버 관측(스팬, traceId, http_server_requests)
       └ WebFilter: RequestLogWebFilter (order 1)
          └ DispatcherHandler → RoutePredicateHandlerMapping(라우트 고르기)
             └ GlobalFilter 체인
                ├ GatewayAuthenticationFilter (-100)  인증·헤더 교체
                ├ EntryTokenFilter (-90)              입장 토큰
                ├ (SCG 내장) RouteToRequestUrl, ReactiveLoadBalancerClientFilter
                └ NettyRoutingFilter → user / reservation / payment / assistant / mock-pg
 오류: GatewayExceptionHandler(@Order(-2))가 FailureResponse 형식으로 씀
```

층마다 감수한 점이 있다.
- 상한 거절은 관측 바깥이라 스팬·traceId·요청 로그가 없고 `gateway.in.flight.rejected` 카운터만 남는다. 거절 한 건의 CPU를 줄이려고 고른 대가다.
- `/api/v1/admin/**`는 상한에서 빠진다. 장애 시연 패널이 과부하 중에도 동작해야 해서다. 액추에이터는 관리 포트(9080)라 처음부터 이 경로를 지나지 않는다.
- 자리 잡기는 `Mono.defer` 안에서 한다. 조립만 되고 구독되지 않은 요청이 자리를 잡은 채 남지 않게 하려는 것이다. 반납은 `doFinally`라 성공·오류·취소 모두 한 번이다.

### 3. 라우트(`config-repo/gateway.yml`)
| id | 대상 | 경로 | 특이 사항 |
|---|---|---|---|
| `mock-pg-demo` | `lb://mock-pg` | 장애 주입·원장 요약 두 경로만 | `/mock-pg/...`로 경로 재작성, `Authorization` 제거. `/admin/**`보다 먼저 맞도록 맨 앞 |
| `user` | `lb://user-service` | `/users/**`, `/auth/**`, `/admin/users/**` | reservation의 `/admin/**`보다 앞 |
| `reservation` | `lb://reservation-service` | 공연·회차·예매·추첨, 나머지 `/admin/**` | |
| `payment` | `lb://payment-service` | `/payments/**` | |
| `assistant` | `lb://assistant-service` | `/assistant/**` | 응답 시한 10초(전역 3초, ADR 0049) |

전역 HTTP 클라이언트 시한은 연결 1초, 응답 3초다. 응답 시한은 라우팅 구간만 잰다(인증 시간 제외).

### 4. 인증 흐름(`GatewayAuthenticationFilter`)
1. `AccessPolicy.of(method, path)`로 판정한다. 라우터와 같은 `PathPattern`과 이미 파싱된 경로를 쓰므로, 인코딩·matrix parameter로 경로를 바꿔 우회하는 공격이 라우터와 다른 판정을 받지 않는다.
2. `INTERNAL`(`/internal/**`, `/api/v1/internal/**`)은 외부에서 존재하지 않는 것처럼 404.
3. `PUBLIC`(회원가입·로그인·재발급·비밀번호 변경 POST, 공연·추첨 공개 조회 GET)은 토큰 없이 통과하지만 **사용자 헤더는 지운다**. 클라이언트가 `X-User-Id`를 직접 넣어 사칭하는 것을 막는다.
4. 나머지는 `Bearer` 토큰 → `JwtDecoder`(HMAC, issuer·type 확인). 만료면 `TOKEN_EXPIRED`, 그 밖은 `UNAUTHORIZED`(둘 다 401).
5. `ADMIN` 경로는 `role` 클레임이 ADMIN이 아니면 403.
6. Redis `auth:blacklist:<jti>` 존재 여부로 로그아웃 토큰을 거른다(401 `TOKEN_REVOKED`).
7. 통과하면 사용자 헤더를 토큰 값으로 교체한다. 서비스 쪽 `common/.../GatewayIdentityFilter`는 경로로 판단하지 않고 이 헤더 형식만 확인해 요청 속성으로 옮긴다.

선택지와 고른 이유: 인증을 각 서비스(Spring Security)에서 할 수도 있었다. Gateway 한 곳에 두면 경로 정책이 한 파일(`AccessPolicy`)에 모이고 서비스는 헤더만 믿으면 된다. 대가는 서비스가 Gateway를 거치지 않는 호출을 막아 줄 장치가 네트워크 경계뿐이라는 점이다.

### 5. 입장 토큰(`EntryTokenFilter`)
- 대상은 `GET /schedules/{id}/seats`, `POST /schedules/{id}/reservations` 둘이다. 대기열을 거치지 않은 요청을 막는다.
- Redis나 서비스를 부르지 않고 토큰만 본다(서명·typ·aud·exp는 `EntryTokenDecoder`, sub = 인증 필터가 넣은 `X-User-Id`, 클레임 회차 = 경로 회차). 그래서 인증 필터(-100) **뒤**(-90)여야 한다.
- 이유는 응답에 담지 않는다(403 `ENTRY_TOKEN_INVALID` 한 가지).
- 통과하면 jti를 `X-Entry-Token-Id`로 넘겨 선점 API가 소모 여부를 확인한다(ADR 0053). 클라이언트가 보낸 같은 헤더는 모든 경로에서 지우되, 헤더가 있을 때만 요청을 복사해 피크 경로의 할당을 늘리지 않는다.

### 6. auth-redis 보호 계단
| 층 | 값 | 하는 일 |
|---|---|---|
| 필터 시한 `auth.redis-timeout` | 200ms | 실제로 요청을 끊는 시계. 넘으면 `RedisLookupTimeout` |
| bulkhead `auth-redis` | 동시 50, 대기 0 | 자기 과부하 보호. 넘치면 즉시 거절 |
| 차단기 `auth-redis` | 10초 시간 창, 최소 500건, 실패 50% 또는 100ms 초과 80% | Redis 장애 판정. bulkhead 거절은 실패로 안 셈 |
| TimeLimiter(라이브러리 기본) | 1초 | 정상이면 걸리지 않는 바깥 안전망 |
| Lettuce 명령 시한 | 300ms | 필터 시한보다 길게 둬 원인이 `lookup_timeout`으로 남게 함(ADR 0050) |

실패는 모두 503 `SERVICE_UNAVAILABLE`(+`Retry-After: 1`)이다. 블랙리스트를 확인하지 못하면 로그아웃된 토큰을 통과시킬 수 있으므로 통과(fail-open) 대신 거절(fail-closed)을 골랐다. 대가는 Redis 장애가 곧 로그인 필요 API 전체의 503이라는 점이다.

`AuthRedisFailures`는 원인을 `lookup_timeout`·`time_limiter`·`bulkhead_full`·`breaker_open`·`redis_error`·`other` 6개 태그로 고정해 센다(예외 이름을 태그로 쓰면 시계열이 늘어난다). 대체 응답 없는 `run()`은 모든 실패를 `NoFallbackAvailableException`으로 감싸므로 원인 사슬을 따라가 분류한다. 로그는 원인별 초당 1건이고 그 사이 건수는 `suppressed`로 합친다. Gateway CPU가 병목이라서다.

### 7. 관측과 오류 형식
- 지표: `gateway.in.flight.requests`·`.limit`·`.rejected`, `gateway.auth.redis.failures{cause}`, SCG 라우트 지표, `http_server_requests`.
- 추적: 공통 설정의 샘플링(기본 0.1, 배포 서버 1.0). `GatewayErrorWriter`는 현재 스팬의 traceId를 응답 본문에 넣는다. 상한 거절은 스팬 전이라 traceId가 null이다.
- 기동: Config Server를 `fail-fast`로 최대 6회 재시도해 받고, 준비 완료 전 Redis PING으로 연결을 열어 첫 보호 요청의 시한을 연결 수립에 쓰지 않게 한다.

## 이 프로젝트에서는
- 코드: `gateway/src/main/java/com/iseonjwa/gateway/` (12개 클래스)
- 설정: `config-repo/gateway.yml`(라우트·시한·상한·차단기), `config-repo/application.yml`(관리 포트, 추적, Redis 기본값)
- 공유 계약: `common/.../auth/`(토큰 디코더), `common/.../identity/IdentityHeaders`, `common/.../web/GatewayIdentityFilter`
- 테스트: `gateway/src/test/` 12개 클래스, 131건 통과(2026-11-12 `./gradlew :gateway:test`, 입력 변경이 없어 Gradle이 19:53 실행 결과를 재사용)
- 관련 결정: ADR 0006(경로 판정), 0015(입장 토큰 sub 비교), 0049(assistant 시한), 0050(auth-redis 값), 0053(입장 토큰 jti 전달)

## 면접 포인트
- Gateway에서 인증하면 서비스는 무엇을 믿나: Gateway가 지우고 다시 넣은 헤더만 믿는다. 클라이언트가 보낸 같은 헤더는 공개 경로에서도 지운다. 남은 위험은 Gateway를 우회한 내부 호출이고 네트워크 경계로 막는다.
- 과부하 거절을 왜 필터가 아니라 HttpHandler 데코레이터에서 하나: Spring Boot 4에서는 서버 관측이 WebFilter 체인 전체를 감싸므로, 필터에서 거절하면 거절 한 건도 관측·라우트 매칭 비용을 낸다. 바깥에서 거절하면 싸지만 거절 요청의 추적이 사라진다.
- Redis가 죽으면 인증은 어떻게 되나: 블랙리스트 확인이 불가능하면 503으로 거절한다(fail-closed). 차단기가 열리면 Redis를 부르지 않고 바로 거절해 Gateway 자원을 지킨다.

## 참고
- 코드와 설정을 직접 읽은 결과(2026-11-12 dev `70dad179`)
- Spring Cloud Gateway 필터 순서: 같은 `Ordered` 값 체계에서 작은 값이 먼저 실행된다
