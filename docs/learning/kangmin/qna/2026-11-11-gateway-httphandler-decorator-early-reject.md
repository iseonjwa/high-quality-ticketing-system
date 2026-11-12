---
title: HttpHandler 데코레이터로 동시 처리 수 상한을 관측보다 앞에서 거절하는 원리
date: 2026-11-11
updated: 2026-11-11
asker: kangmin
level: 중급
tags: [gateway, webflux, decorator-pattern, observability, micrometer, load-shedding, reactor]
---

# HttpHandler 데코레이터로 동시 처리 수 상한을 관측보다 앞에서 거절하는 원리

## 질문
H1에서 고른 "B. HttpHandler 데코레이터"를 자세히 설명해 달라. 요청이 Gateway 안에서 어떤 층을 지나는지, 데코레이터가 어떻게 그 앞에 끼어드는지, 다른 위치와 무엇이 다른지, 거절된 요청에서 무엇이 사라지는지, 7차에서 무엇으로 확인하는지.

```
요청 도착 → ① HttpWebHandlerAdapter: 요청 객체 생성 + 서버 관측·추적 스팬·http 지표 시작
          → ② WebFilter: 요청 로그(RequestLogWebFilter)
          → ③ 라우트 매칭
          → ④ GlobalFilter: 동시 수 상한(-200)  ← 6차까지의 거절 위치
          → ⑤ 인증 필터(-100)
```

## 짧은 답
- 6차까지는 ④에서 거절해서, 거절된 요청도 ①\~③의 비용(요청 객체, 관측·스팬·지표, 요청 로그, 라우트 매칭)을 다 냈다. 그래서 거절 비율이 25%에서 40%로 늘어도 한 건당 Gateway CPU가 0.66\~0.71ms로 그대로였다고 본다(추정).
- 서버 관측은 WebFilter가 아니라 ①의 `HttpWebHandlerAdapter`가 연다. 그보다 앞에 끼어들 수 있는 곳은 어댑터 바깥을 감싸는 **HttpHandler 데코레이터**뿐이다(Netty 단계 제외).
- 데코레이터는 어댑터와 같은 `HttpHandler` 인터페이스를 구현한 래퍼다. 상한을 넘으면 안쪽(어댑터)을 아예 부르지 않고 503을 직접 쓴다. 그러면 ①\~⑤가 전부 일어나지 않는다.
- 대가: 거절된 요청에는 스팬·traceId·요청 로그·`http_server_requests` 503 집계가 없다. 거절 카운터(`gateway_in_flight_rejected_total`)만 남는다. 받은 요청의 관측은 그대로다.
- 7차에서 한 건당 Gateway CPU가 내려가는지로 확인한다. 안 내려가면 "거절 비용의 큰 몫은 관측"이라는 가설이 틀린 것이다.

이 노트는 [관측 비용 가설 노트](2026-11-11-observation-cost-hypothesis-and-handler-decorator.md)를 소스 위치·패턴·코드 수준으로 이어 쓴 것이다.

## 1. 요청 하나가 지나는 층
### 1-1. 쉬운 그림
택배 물류센터로 비유하면 이렇다. 트럭에서 상자를 내리고(Netty), 송장을 붙이고 CCTV 기록을 시작한 뒤(어댑터, 관측), 입고 대장에 적고(요청 로그), 어느 라인으로 보낼지 정하고(라우트 매칭), 라인 입구에서 "지금 꽉 찼다"며 돌려보낸다(④). 돌려보낸 상자도 송장·CCTV·대장 비용은 이미 냈다. B안은 트럭에서 내리자마자 문 앞에서 돌려보내는 것이다.

### 1-2. 층별 표
Spring Boot 4.1.2, spring-web·spring-webflux 7.0.10, Spring Cloud Gateway Server WebFlux 5.0.4(저장소 `build.gradle`의 `spring-cloud-dependencies:2025.1.4`) 소스로 확인했다. 소스는 Gradle 캐시의 `*-sources.jar`다.

| 순서 | 층(클래스) | 하는 일 | 소스 위치 | 그림 번호 |
|---|---|---|---|---|
| 0 | Reactor Netty | 소켓에서 HTTP 바이트를 읽어 요청을 해석 | (Reactor Netty 내부, 읽지 않음) | 그림 밖 |
| 1 | `ReactorHttpHandlerAdapter` | Netty 요청·응답을 Spring의 `ServerHttpRequest`·`ServerHttpResponse`로 감싸고 `HttpHandler`를 부른다 | spring-web `http/server/reactive/ReactorHttpHandlerAdapter.java:55-66` | 그림 밖 |
| 2 | **HttpHandler 데코레이터(B안)** | 상한 확인. 넘치면 여기서 503 | 저장소 `gateway/.../overload/InFlightLimitHandlerDecorator.java:76-95` | ① 앞 |
| 3 | `HttpWebHandlerAdapter` | `ServerWebExchange`(요청·응답·속성을 묶은 요청 객체)를 만들고, 관측 문맥을 만들고, 안쪽 체인 전체를 관측으로 감싼다 | spring-web `web/server/adapter/HttpWebHandlerAdapter.java:292-326`(exchange 생성 305, 관측 문맥 311-314, `.tap(ObservationSignalListener)` 323) | ① |
| 4 | `ExceptionHandlingWebHandler` | 안쪽에서 난 예외를 `WebExceptionHandler`(우리 `GatewayExceptionHandler`, `@Order(-2)`)로 응답으로 바꾼다 | spring-web `web/server/handler/ExceptionHandlingWebHandler.java:74` | ①과 ② 사이 |
| 5 | `FilteringWebHandler`(spring-web) → `DefaultWebFilterChain` | `WebFilter`를 순서대로 부르고 끝나면 안쪽 핸들러를 부른다 | spring-web `web/server/handler/DefaultWebFilterChain.java:103-110` | ② |
| 5-1 | `RequestLogWebFilter`(order 1) | 요청 로그 한 줄 | 저장소 `gateway/.../observability/RequestLogWebFilter.java:22-31` | ② |
| 6 | `DispatcherHandler`(빈 이름 `webHandler`) | 등록된 `HandlerMapping`에 차례로 물어 처리할 핸들러를 찾는다 | spring-webflux `web/reactive/DispatcherHandler.java:138-146`, 빈 등록 `web/reactive/config/WebFluxConfigurationSupport.java:126` | ③ |
| 7 | `RoutePredicateHandlerMapping` | 모든 라우트의 조건(predicate)을 평가해 맞는 라우트를 exchange 속성에 넣는다 | gateway `handler/RoutePredicateHandlerMapping.java:80-100`, `lookupRoute` 131-140 | ③ |
| 8 | `FilteringWebHandler`(Gateway) | `GlobalFilter`와 라우트 필터를 합쳐 order로 정렬하고 체인을 돈다 | gateway `handler/FilteringWebHandler.java:99-107`, 합치기·정렬 121-123 | ④⑤ |
| 8-1 | 6차까지의 상한 `GlobalFilter`(-200) | 상한 확인 | (d8d593e2에서 데코레이터로 옮김) | ④ |
| 8-2 | `GatewayAuthenticationFilter`(-100), `EntryTokenFilter`(-90) | JWT 검증·Redis 조회, 입장 토큰 확인 | 저장소 `gateway/.../security/` | ⑤ |
| 9 | 라우팅 필터(Netty 클라이언트) | 뒤쪽 서비스 호출 | (읽지 않음) | ⑤ 뒤 |

이 층들이 어떻게 조립되는지는 `WebHttpHandlerBuilder.build()`에 다 있다(spring-web `web/server/adapter/WebHttpHandlerBuilder.java:427-458`).

```java
WebHandler decorated = new FilteringWebHandler(this.webHandler, this.filters);       // 428: DispatcherHandler를 WebFilter로 감쌈
decorated = new ExceptionHandlingWebHandler(decorated, this.exceptionHandlers);     // 429: 그것을 예외 처리로 감쌈
HttpWebHandlerAdapter adapted = new HttpWebHandlerAdapter(decorated);               // 431: 그것을 어댑터로 감쌈(관측은 여기)
...
return (this.httpHandlerDecorator != null ? this.httpHandlerDecorator.apply(adapted) : adapted); // 458: 마지막으로 데코레이터가 어댑터를 감쌈
```

안에서 밖으로 차례로 감싼다. 데코레이터는 **가장 마지막에, 가장 바깥에** 씌워진다. 그래서 요청은 데코레이터를 가장 먼저 만난다.

### 1-3. 관측이 ①에서 시작한다는 근거
`HttpWebHandlerAdapter.handle()`의 끝부분이다(`HttpWebHandlerAdapter.java:320-325`).

```java
return getDelegate().handle(exchange)                       // 안쪽: 예외 처리 → WebFilter → DispatcherHandler 전부
        .doOnSuccess(aVoid -> logResponse(exchange))
        .onErrorResume(ex -> handleUnresolvedError(exchange, observationContext, ex))
        .tap(() -> new ObservationSignalListener(observationContext))   // 안쪽 전체를 관측으로 감쌈
        .then(exchange.cleanupMultipart())
        .then(Mono.defer(response::setComplete));
```

- `ObservationSignalListener`는 구독 시작 때 `observation.start()`(405-406), 끝날 때 `doOnTerminate`에서 응답 커밋 시점에 `observation.stop()`을 부른다(438-450).
- `addToContext`(400-402)가 Reactor 문맥(Context, 비동기 체인을 따라 전달되는 값 묶음)에 관측을 넣는다. WebFilter와 GlobalFilter가 traceId를 볼 수 있는 이유이고, 5차 JFR의 "Reactor 연산자(관측 문맥 전파 포함) 24.9%"가 나오는 곳 중 하나다.
- `observation.stop()`이 불리면 Micrometer 핸들러가 `http_server_requests` 지표를 기록하고 추적 스팬을 끝낸다. 핸들러 내부는 읽지 않았다.

## 2. 데코레이터 패턴
### 2-1. 개념
**데코레이터 패턴**(Decorator pattern)은 어떤 객체를 같은 인터페이스를 구현한 다른 객체로 감싸, 원래 객체를 고치지 않고 앞뒤에 일을 더하는 방법이다. 감싸는 쪽은 바깥에서 보면 원래 객체와 똑같이 생겼기 때문에, 부르는 쪽(여기서는 Netty 어댑터)은 무엇이 감싸였는지 모른다.

| 구성 | 이 사례 |
|---|---|
| 공통 인터페이스 | `HttpHandler` — `Mono<Void> handle(ServerHttpRequest, ServerHttpResponse)` 하나 |
| 원래 객체(안쪽) | `HttpWebHandlerAdapter` |
| 데코레이터(바깥) | `InFlightLimitHandlerDecorator.decorate()`가 돌려주는 람다 |
| 더한 일 | 앞: 자리 잡기·거절 / 뒤: 자리 반납 |

Spring 자신도 이 패턴을 겹겹이 쓴다. 1-2절의 `FilteringWebHandler`와 `ExceptionHandlingWebHandler`는 둘 다 `WebHandlerDecorator`를 상속한다(`ExceptionHandlingWebHandler.java:39`, spring-web `FilteringWebHandler.java:34`). 차이는 감싸는 인터페이스다. 그 둘은 `WebHandler`(exchange를 받음)를 감싸고, 우리 것은 한 층 바깥의 `HttpHandler`(아직 exchange가 없음)를 감싼다.

### 2-2. 끼어드는 원리(흐름도)
```
Netty
  │  ReactorHttpHandlerAdapter.apply()        요청·응답 래퍼만 만든 상태
  ▼
┌──────────────────────────────────────────────────────────────┐
│ InFlightLimitHandlerDecorator (HttpHandler)                  │
│   inFlight + 1 > 64 ?                                        │
│     ├─ 예 ──→ inFlight - 1, 거절 카운터 + 1                   │
│     │         503 + Retry-After 2 직접 쓰기 ──→ 응답 끝       │
│     │         (안쪽을 부르지 않음: ①~⑤ 없음)                  │
│     └─ 아니오 ↓                                              │
│   ┌──────────────────────────────────────────────────────┐   │
│   │ HttpWebHandlerAdapter (HttpHandler)            ①     │   │
│   │   exchange 생성, 관측 시작                            │   │
│   │   ┌──────────────────────────────────────────────┐   │   │
│   │   │ ExceptionHandlingWebHandler                  │   │   │
│   │   │   ┌──────────────────────────────────────┐   │   │   │
│   │   │   │ WebFilter 체인: RequestLog      ②    │   │   │   │
│   │   │   │   DispatcherHandler → 라우트 매칭 ③  │   │   │   │
│   │   │   │     GlobalFilter: 인증 ⑤ → 뒤쪽 호출 │   │   │   │
│   │   │   └──────────────────────────────────────┘   │   │   │
│   │   └──────────────────────────────────────────────┘   │   │
│   │   관측 종료(응답 커밋 때)                             │   │
│   └──────────────────────────────────────────────────────┘   │
│   doFinally: inFlight - 1                                    │
└──────────────────────────────────────────────────────────────┘
```

### 2-3. 코드 수준 흐름
저장소 구현(`InFlightLimitHandlerDecorator.java:71-95`)을 줄여 옮긴다. 읽기만 했고 고치지 않았다.

```java
@Override
public void customize(WebHttpHandlerBuilder builder) {      // Boot가 HttpHandler를 만들기 직전에 불러 준다
    builder.httpHandlerDecorator(this::decorate);
}

HttpHandler decorate(HttpHandler delegate) {                // delegate = HttpWebHandlerAdapter
    return (request, response) -> handle(request, response, delegate);
}

private Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response, HttpHandler delegate) {
    if (EXEMPT.matches(request.getPath().pathWithinApplication())) {   // /api/v1/admin/** 는 상한 밖
        return delegate.handle(request, response);
    }
    return Mono.defer(() -> {                                          // (가) 구독될 때 자리를 잡는다
        if (inFlight.incrementAndGet() > maxRequests) {
            inFlight.decrementAndGet();
            rejected.increment();
            return errors.write(response, 503, OVERLOADED, "...", retryAfter);  // (나) 안쪽을 부르지 않는다
        }
        return Mono.defer(() -> delegate.handle(request, response))     // (다) 안쪽 예외도 오류 신호로
                .doFinally(signal -> inFlight.decrementAndGet());        // (라) 성공·오류·취소 모두 한 번 반납
    });
}
```

| 표시 | 왜 이렇게 쓰나 |
|---|---|
| (가) `Mono.defer` | Reactor의 `Mono`는 조립(assembly)과 구독(subscription)이 따로다. `defer` 없이 바깥에서 `incrementAndGet()`을 하면 조립만 되고 구독되지 않은 경우 자리가 영영 반납되지 않는다. `defer`는 "구독되는 순간 이 람다를 실행하라"는 뜻이다 |
| (나) 직접 503 쓰기 | 데코레이터는 `ExceptionHandlingWebHandler`보다 바깥이라, 여기서 예외를 던지면 `GatewayExceptionHandler`가 받지 못한다. 그래서 `GatewayErrorWriter.write(ServerHttpResponse, ...)`(`GatewayErrorWriter.java:40`)로 응답을 직접 쓴다. exchange가 아직 없어서 응답 객체만 받는 판이 따로 필요했다(b63950e6) |
| (다) 안쪽도 `defer` | `delegate.handle()`이 `Mono`를 돌려주기 전에 예외를 바로 던지면(동기 예외) `doFinally`가 붙기도 전에 터진다. `defer` 안에서 부르면 그 예외가 오류 신호로 바뀌어 (라)가 실행된다 |
| (라) `doFinally` | 완료·오류·취소(클라이언트가 연결을 끊음) 세 신호 모두에서 한 번 실행된다. 어댑터의 Mono는 `response.setComplete()`까지 포함하므로(`HttpWebHandlerAdapter.java:324-325`) 자리는 응답을 다 쓸 때까지 잡힌다 |

### 2-4. 연결점이 둘인데 왜 `WebHttpHandlerBuilderCustomizer`인가
| 연결점 | 등록 경로 | 관리 포트(9080)에도 걸리나 |
|---|---|---|
| `WebHttpHandlerBuilderCustomizer` 빈(Boot) | `HttpHandlerAutoConfiguration.java:53-60`에서 메인 HttpHandler를 만들 때만 `customize()`를 부른다 | 아니다. 관리 자식 문맥은 `WebFluxManagementChildContextConfiguration.java:50-51`에서 `WebHttpHandlerBuilder.applicationContext(ctx).build()`만 부르고 customizer를 쓰지 않는다 |
| `HttpHandlerDecoratorFactory` 빈(spring-web) | `WebHttpHandlerBuilder.applicationContext()`가 문맥에서 찾아 붙인다(`WebHttpHandlerBuilder.java:191-193`) | 걸릴 수 있다. 빈 찾기(`orderedStream`)가 부모 문맥까지 찾으므로(spring-beans `DefaultListableBeanFactory.java:511-512`, 576-577) 관리 자식 문맥이 메인 문맥의 팩토리 빈을 가져간다고 읽힌다. 실행으로는 확인 못 함 |

액추에이터(`/actuator/prometheus` 수집, 헬스 체크)가 과부하 때 상한에 걸려 503이 나면 지표 수집이 끊긴다. customizer 쪽은 소스상 관리 포트를 건드리지 않는다.

## 3. 다른 거절 위치와 비교
| 선택지 | 거절 위치 | 거절 때 건너뛰는 것 | 장점 | 단점 |
|---|---|---|---|---|
| (6차) GlobalFilter -200 | ④ | 인증(JWT·Redis), 뒤쪽 호출 | Gateway 필터라 팀원이 읽기 쉽다. 거절도 추적·로그에 남는다 | ①\~③ 비용을 다 낸다. 6차 결과대로 CPU가 안 줄었다 |
| A. 맨 앞 WebFilter | ② 앞 | 요청 로그, 라우트 매칭, 인증 | `WebFilter`라 익숙하다. exchange가 있어 기존 오류 응답 도우미를 그대로 쓴다. traceId가 남는다 | 관측은 ①에서 이미 시작돼 건너뛰지 못한다. 관측 비용 가설을 시험할 수 없다 |
| **B. HttpHandler 데코레이터** | ① 앞 | exchange 생성, 관측·스팬·지표, 요청 로그, 라우트 매칭, 인증 | Spring 공식 연결점으로 Netty까지 내려가지 않고 가장 앞이다. 관리 포트에 영향이 없다 | 거절에 traceId·로그·503 집계가 없다. 필터 층보다 바깥이라 읽는 사람이 위치를 알아야 한다. 예외 처리기보다 바깥이라 응답을 직접 써야 한다 |
| C. `ObservationPredicate` | ①은 지나고 관측만 끔 | 관측(지표·스팬) | 위치를 안 바꾸고 관측만 끌 수 있다 | Micrometer는 관측을 만들 때 predicate를 물어 거짓이면 NOOP을 준다(micrometer-observation 1.17.2 `Observation.java:200`, `ObservationRegistry.java:202-209`). 그 순간은 ①이라 아직 "거절될지"를 모른다. 상한 판단을 predicate 안으로 끌어오면 판단이 두 곳으로 갈린다. exchange 생성·요청 로그·라우트 매칭은 그대로 낸다 |
| D. Netty 단계 | 0(Spring 앞) | 위 전부 + `ReactorServerHttpRequest`·응답 래퍼 생성(`ReactorHttpHandlerAdapter.java:58-59`) | 가장 싸다 | Reactor Netty API(`NettyServerCustomizer`, 채널 핸들러)로 응답을 직접 써야 해 Spring의 경로 매칭·오류 본문 형식을 다시 만든다. 테스트가 어렵다. 관리 포트와 메인 포트를 구분하는 처리도 직접 해야 한다. 데코레이터와 견주어 아끼는 양은 래퍼 두 개 정도라 작다고 본다(추정, 재지 않음) |

고른 이유: H1의 목적은 "거절 한 건의 비용 줄이기"이고, 가장 클 것으로 본 관측을 건너뛰는 위치 중 Spring 공식 연결점은 B뿐이다. D는 더 싸지만 아끼는 몫에 견주어 만들고 지킬 코드가 크다.

## 4. 거절된 요청에서 사라지는 것과 남는 것
| 항목 | 6차(④ 거절) | B안(① 앞 거절) | 이유 |
|---|---|---|---|
| 추적 스팬 | 있음 | 없음 | 관측이 시작되지 않는다 |
| 응답 본문 `traceId` | 있음 | `null` | 스팬이 없으니 traceId도 없다 |
| 요청 로그 한 줄 | 있음 | 없음 | ② `RequestLogWebFilter`까지 가지 않는다(주석 `RequestLogWebFilter.java:23-25`에도 적혀 있다) |
| `http_server_requests{status="503"}` | 집계됨 | 집계 안 됨 | 이 지표는 관측 종료 때 기록된다 |
| `gateway_in_flight_rejected_total` | 있음 | 있음 | 데코레이터 안에서 직접 센다(`InFlightLimitHandlerDecorator.java:88`) |
| `gateway_in_flight_requests`, `gateway_in_flight_limit` | 있음 | 있음 | 같은 데코레이터의 게이지 |
| 받은 요청의 관측·로그 | 있음 | 있음 | 상한 안의 요청은 어댑터를 그대로 지난다 |

### 강민이 보류한 "관측 축소"와 범위가 다른 이유
| | 관측 축소(C1·C2·경로별 관측 끄기, 보류) | B안 |
|---|---|---|
| 대상 | **받아서 처리하는 모든 요청** | **상한에 걸려 거절되는 요청만** |
| 잃는 것 | 성공·실패 요청의 로그·스팬·지표 일부 → 장애 분석 능력이 준다 | 이미 503으로 끝난 요청의 개별 기록 → 수는 카운터로 남는다 |
| 관측 장치 자체 | 설정을 바꾼다(샘플링, 로그 끄기) | 바꾸지 않는다 |

[관측 축소 보류 판단](../../../ai-judgments/kangmin/2026-11-11-defer-gateway-observability-reduction.md)의 바꿀 조건이 "동시 수 상한을 넣고도 거절된 요청의 관측 비용 때문에 서버 CPU가 내려오지 않을 때"였다. 6차가 그 상황이고, B안은 그 조건에 맞춰 범위를 거절된 요청으로만 좁힌 대응이다. 보류한 관측 축소를 되살린 것이 아니다.

7차 비교 주의: 6차의 `http_server_requests` 요청 수에는 거절이 들어 있었고 7차에는 빠진다. 요청 수 비교는 k6 수와 `gateway_in_flight_rejected_total`로 한다.

## 5. 관리 포트와 Boot 버전 배경
### 관리 포트(9080) 액추에이터가 영향이 없는 이유
- Gateway의 액추에이터는 관리 포트 9080에서 따로 뜬다(`compose.local.yml`에서 `18080:9080`). 관리 포트가 다르면 Boot는 관리용 자식 애플리케이션 문맥을 만들고, 그 문맥이 자기 `HttpHandler`를 따로 만든다(`WebFluxManagementChildContextConfiguration.java:50-51`).
- 그 HttpHandler는 `WebHttpHandlerBuilderCustomizer`를 부르지 않는다. 메인 HttpHandler를 만드는 `HttpHandlerAutoConfiguration.java:60`만 customizer를 부른다. 그래서 데코레이터는 9080에 씌워지지 않는다.
- 과부하 때도 Prometheus 수집과 헬스 체크는 상한에 걸리지 않는다. 실제 배포에서 거절이 많은 순간 `/actuator/prometheus`가 200인지는 7차에서 볼 것이다(아직 확인 못 함).

### 관측이 어댑터로 옮겨진 배경
| 확인한 것 | 근거 |
|---|---|
| spring-web 6.0에는 reactive `ServerHttpObservationFilter`(WebFilter)가 있었다 | spring-web 6.1.21 `web/filter/reactive/ServerHttpObservationFilter.java:51` `@since 6.0` |
| 6.1에서 "`WebHttpHandlerBuilder`를 쓰라"며 사라질 예정으로 표시됐다 | 같은 파일 52-55 `@Deprecated(since = "6.1", forRemoval = true)` |
| 7.0.10에는 그 클래스가 없다(같은 이름은 서블릿용 `web/filter/ServerHttpObservationFilter.java`뿐) | 7.0.10 `web/filter/reactive/` 목록 |
| 어댑터의 관측 설정 메서드는 6.1부터다 | `HttpWebHandlerAdapter.java:204-230` `@since 6.1` |

Spring Boot 3.2가 Spring Framework 6.1을 쓴다는 대응은 로컬 소스로 확인하지 않았다. 옮긴 이유(필터 순서와 무관하게 전체 체인을 관측하려는 것으로 보임)도 공식 설명을 읽지 않아 확인 못 했다. 관측을 필터로 두면 그보다 앞 필터의 오류나 시간이 빠지는데, 어댑터는 예외 처리기와 모든 필터를 감싸므로 빠짐이 없다는 점은 1-3절 코드 구조에서 읽힌다.

## 6. 7차에서 무엇으로 확인하나
가설: 거절 한 건 비용의 큰 몫은 ①\~③(관측·요청 로그·라우트 매칭)이다. 이것을 건너뛰면 한 건당 Gateway CPU와 서버 CPU가 내려가고, 남은 CPU로 받은 요청을 더 처리해 굿풋이 오른다.

| 지표 | 6차 기준(L64, 2,500/s) | 성공 판정 방향 | 출처 |
|---|---|---|---|
| 한 건당 Gateway CPU(Gateway CPU 코어 × 1,000 ÷ 달성 요청/초) | 0.71ms(거절 35%) | 거절 비율이 비슷할 때 의미 있게 낮아짐. 거절 비율이 클수록 더 낮아져야 함(6차는 거절 비율과 무관하게 평평했음) | `pidstat`·`docker stats` |
| 서버 CPU 평균 | 99% | 같은 부하에서 낮아짐 | `mpstat` |
| 굿풋(200 응답이면서 200ms 안) | 1,608/s | 같거나 높아짐 | k6 |
| 성공 p95 | 104ms | 200ms 안 유지 | k6 |
| 거절 수 | 25,050(Prometheus) | k6 503 수와 `gateway_in_flight_rejected_total`로 비교 | k6·Prometheus |

판정 기준의 정확한 "의미 있게"의 폭은 정하지 않았다. 확인할 질문: 반복 변동(6차 L128 두 회차의 굿풋 차이 5%)보다 큰 차이를 기준으로 삼을지.

| 결과 | 뜻 | 다음 |
|---|---|---|
| 한 건당 CPU가 내려가고 굿풋이 오름 | 가설이 맞다. 거절 비용의 큰 몫이 ①\~③이었다 | B안 유지. 서버 CPU 90% 목표에 얼마나 남았는지 다시 본다 |
| 한 건당 CPU는 내려갔는데 서버 CPU·굿풋이 그대로 | Gateway는 아꼈지만 병목이 다른 곳(뒤쪽 서비스, Redis, CPU 차례 대기)으로 옮겨 갔다 | 서비스별 CPU를 보고 다음 병목을 찾는다 |
| 한 건당 CPU가 그대로 | 가설이 틀렸다. 거절 비용은 ①\~③이 아니라 Netty HTTP 처리(5차 JFR 17.6%)나 연결 처리 쪽이다 | B안의 대가(거절 기록 없음)를 다시 따져 A안이나 6차 위치로 되돌리는 것도 검토한다. 비용이 Netty 쪽이면 D안(Netty 단계) 또는 연결 수 자체를 줄이는 쪽(발생기 keep-alive, 앞단 제한)을 본다 |

추가로 볼 것: 거절이 많은 순간 9080 액추에이터가 정상인지, 7차 Prometheus 쿼리 식을 원자료와 함께 남기는지(6차 원자료에는 식이 없었다).

## 확인 못 한 것
- Micrometer 관측 핸들러(지표 기록·스팬 내보내기)의 내부 비용 구성은 읽지 않았다.
- 관리 자식 문맥이 부모의 `HttpHandlerDecoratorFactory`를 실제로 가져가는지는 실행으로 확인하지 않았다(소스 읽기만).
- Reactor Netty 내부(HTTP 해석, 연결 처리)는 읽지 않았다.
- B안의 실제 CPU 효과는 7차 측정 전이라 모른다.
