---
title: resilience4j.circuitbreaker.instances.auth-redis 설정값은 코드 어디서 읽나
date: 2026-11-11
updated: 2026-11-11
asker: kangmin
level: 중급
tags: [resilience4j, spring-boot, configuration-properties, auto-configuration, spring-cloud-circuitbreaker, config-server, gateway]
---

# resilience4j.circuitbreaker.instances.auth-redis 설정값은 코드 어디서 읽나

## 질문
팀원(예매 흐름 담당)이 물었다. "`config-repo/gateway.yml` 맨 아래 `resilience4j.circuitbreaker.instances.auth-redis` 값을 코드에서 어디서 가져다 쓰나? `@Value("${resilience4j.circuitbreaker.xxxx}")` 같은 식인가?" 함께 "상수가 아니라 yml에서 읽고, 측정 모드에서 재배포 없이 바꿀 수 있다"는 설명이 맞는지도 확인한다.

## 짧은 답
- 우리 코드에 `resilience4j.*` 키를 읽는 `@Value`는 없다. 라이브러리 자동 구성(auto-configuration, 클래스패스에 라이브러리가 있으면 Spring Boot가 빈을 대신 만들어 주는 기능)이 `resilience4j.circuitbreaker` 키 묶음을 `CircuitBreakerProperties`(`@ConfigurationProperties`)에 바인딩(binding, 설정 키를 자바 객체 필드에 채우는 일)하고, 기동할 때 `instances` 아래 이름마다 차단기를 만들어 `CircuitBreakerRegistry`(이름으로 차단기를 보관하는 등록소)에 넣는다.
- 코드는 이름 `"auth-redis"`만 안다. `GatewayAuthenticationFilter`가 `breakers.create("auth-redis")`로 꺼내고 `redisBreaker.run(...)`으로 감싼다. bulkhead(동시 실행 수 제한)도 같은 이름으로 등록소에서 찾는다.
- 우리가 만든 키(`auth.redis-timeout`, `iseonjwa.gateway.in-flight.*`)는 생성자 `@Value`로 읽는다.
- 값은 프로세스가 뜰 때 한 번 읽는다. config-repo를 고치든 측정 모드로 바꾸든 Gateway가 다시 떠야 반영된다. 측정 모드가 "재배포 없이"인 것은 이미지 빌드·코드 수정·config-repo 커밋이 필요 없다는 뜻이지, 재기동이 없다는 뜻이 아니다. `measure-mode.sh on`이 gateway 컨테이너를 다시 만든다.

## 자세히

### 1. 우리 코드에서 `auth-redis`를 쓰는 곳
| 파일:줄 | 하는 일 |
|---|---|
| `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java:30` | 이름 상수 `AUTH_REDIS_BREAKER = "auth-redis"` |
| 같은 파일 `:41` | 생성자가 `ReactiveCircuitBreakerFactory`를 주입받는다. 이 빈은 라이브러리 자동 구성이 만든다 |
| 같은 파일 `:46` | `breakers.create(AUTH_REDIS_BREAKER)`로 이름을 넘겨 차단기 래퍼를 얻는다 |
| 같은 파일 `:102` | `redisBreaker.run(lookup)`. bulkhead → 시한(TimeLimiter) → 차단기 순으로 감싼 호출이 여기서 돈다 |
| `gateway/src/main/java/com/iseonjwa/gateway/security/AuthRedisBreakerEvents.java:20-24` | 같은 이름의 차단기에 상태 전환 로그 customizer(생성 직후 손볼 수 있게 라이브러리가 열어 둔 확장점)를 붙인다 |
| `gateway/src/main/java/com/iseonjwa/gateway/security/AuthRedisFailures.java:146` | `CircuitBreakerRegistry.find("auth-redis")`로 현재 상태를 읽어 로그에 남긴다 |

설정값 자체는 `config-repo/gateway.yml:73-94`에 있다.

### 2. 값이 들어가는 길
확인한 버전: `spring-cloud-circuitbreaker-resilience4j` 5.0.3, `resilience4j-spring-boot3`·`resilience4j-spring6`·`resilience4j-circuitbreaker` 2.3.0(Gradle 캐시의 소스 jar). Gateway는 `spring-cloud-starter-circuitbreaker-reactor-resilience4j`(`gateway/build.gradle:10`)를 쓰고, 이 모듈의 pom이 `resilience4j-spring-boot3`를 compile 의존성으로 가져온다.

1. **설정 수집.** Gateway가 뜰 때 `spring.config.import`(`gateway/src/main/resources/application.yml`)로 Config Server에서 `config-repo/application.yml`·`gateway.yml`을 받는다. 측정 모드면 `SPRING_APPLICATION_JSON` 환경 변수가 그 위에 얹혀 같은 키를 덮는다.
2. **바인딩.** `CircuitBreakerAutoConfiguration`(`io.github.resilience4j.springboot3.circuitbreaker.autoconfigure`, 39줄)이 `@EnableConfigurationProperties(CircuitBreakerProperties.class)`를 건다. `CircuitBreakerProperties`(21줄)는 `@ConfigurationProperties(prefix = "resilience4j.circuitbreaker")`다. 그래서 `instances.auth-redis.sliding-window-size` 같은 키가 `Map<String, InstanceProperties>`의 `"auth-redis"` 항목으로 들어간다. bulkhead는 `BulkheadProperties`(21줄, prefix `resilience4j.bulkhead`)가 같은 방식이다. 완화 바인딩(relaxed binding, `sliding-window-size`와 `slidingWindowSize`를 같은 키로 보는 규칙) 덕에 yml은 케밥 표기, 측정 모드 JSON은 카멜 표기여도 같은 필드에 들어간다.
3. **등록소에 미리 생성.** `resilience4j-spring6`의 `CircuitBreakerConfiguration.initCircuitBreakerRegistry`(164줄)가 `getInstances().forEach(...)`로 이름마다 `registry.circuitBreaker(name, config)`를 부른다. bulkhead도 `BulkheadConfiguration`(75줄)에서 같다. 이 시점에 `auth-redis` 차단기가 yml 값으로 만들어진다.
4. **팩토리 빈.** `spring-cloud-circuitbreaker-resilience4j`의 `ReactiveResilience4JAutoConfiguration`(63-70줄)이 3의 `CircuitBreakerRegistry`·`TimeLimiterRegistry`를 받아 `ReactiveResilience4JCircuitBreakerFactory` 빈을 만든다. bulkhead가 켜져 있으면(`spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled`, 기본 true) 같은 클래스 76-96줄이 `ReactiveResilience4jBulkheadProvider`도 만든다.
5. **이름으로 꺼내기.** 필터의 `create("auth-redis")`는 `ReactiveResilience4JCircuitBreakerFactory.create(id, groupName)`(99-131줄)로 간다. 실제 호출 때 `ReactiveResilience4JCircuitBreaker`가 bulkhead를 감싸고(92-93줄), `circuitBreakerRegistry.circuitBreaker(id, config, tags)`(144줄)로 차단기를 얻는다. 등록소 구현 `InMemoryCircuitBreakerRegistry.circuitBreaker`(155-158줄)는 `computeIfAbsent`라서, 3에서 이미 만든 `auth-redis` 차단기(yml 값)를 그대로 돌려준다.

헷갈리기 쉬운 점이 하나 있다. 팩토리 104줄의 `circuitBreakerRegistry.getConfiguration(id)`는 `instances.<이름>`이 아니라 공유 설정 묶음 `configs.<이름>`을 찾는다. 우리는 `configs`를 쓰지 않으므로 여기서는 기본 설정이 넘어간다. 그래도 yml 값이 쓰이는 이유는 5의 `computeIfAbsent` 때문이다. 이름을 잘못 쓰면(예: `auth_redis`) 오류 없이 기본값 차단기가 새로 생기므로, 이름은 상수 하나로 맞춘다.

bulkhead 등록소도 `computeIfAbsent` 구조인지는 소스로 확인하지 못했다. 다만 `AuthRedisResilienceContextTest`가 설정한 동시 한도대로 거절되는 것을 확인하므로 같은 결과로 본다.

시한(TimeLimiter)은 `config-repo`에 `resilience4j.timelimiter`가 없어 라이브러리 기본값 1초가 쓰인다(팩토리 107-120줄, 없으면 WARN 로그를 남기고 기본값을 쓴다). 우리 안쪽 시한 `auth.redis-timeout`(200ms)이 먼저 걸리므로 바깥 1초는 마지막 안전망이다.

### 3. 우리가 만든 키는 `@Value`
| 키 | 읽는 곳 | 기본값(키가 없을 때) |
|---|---|---|
| `auth.redis-timeout` | `GatewayAuthenticationFilter.java:42` 생성자 `@Value` | 100ms. 운영 값은 `config-repo/gateway.yml:58`의 200ms |
| `iseonjwa.gateway.in-flight.max-requests` | `gateway/src/main/java/com/iseonjwa/gateway/overload/InFlightLimitFilter.java:47` `@Value` | 64 |
| `iseonjwa.gateway.in-flight.retry-after` | 같은 파일 `:48` `@Value` | 2s |
| `iseonjwa.gateway.in-flight.enabled` | 같은 파일 `:27` `@ConditionalOnProperty` | true(빈 등록 여부를 정한다) |

`@ConfigurationProperties` 클래스는 Gateway에 없다. 키가 두세 개라 `@Value`로 충분하다는 선택이다. 키가 늘거나 검증이 많아지면 `@ConfigurationProperties` + `@Validated`로 묶는 편이 읽기 쉽다.

### 4. "재배포 없이 바꾼다"는 말의 정확한 뜻
- **config-repo만 고친 경우**: 실행 중인 Gateway에는 반영되지 않는다. `@RefreshScope`·Spring Cloud Bus가 저장소 어디에도 없고, 노출 엔드포인트도 `health,prometheus`뿐이라(`config-repo/application.yml:9-10`) `/actuator/refresh`를 부를 수 없다. Gateway를 다시 띄워야 Config Server에서 새 값을 받는다.
- **refresh가 있었다면**: 2의 3단계에서 이미 만든 차단기는 `computeIfAbsent` 때문에 남아 있으므로 값이 바뀌지 않을 것으로 본다. 확인 못 함.
- **측정 모드**: `loadtest/deploy/compose.gateway-bulkhead.yml`이 `AUTH_BULKHEAD`, `AUTH_SLOW_CALL` 같은 셸 변수를 `SPRING_APPLICATION_JSON`에 채워 gateway 환경 변수로 넣는다. `script/loadtest/measure-mode.sh on`은 이 파일을 `-f`로 얹어 `docker compose ... up -d`를 실행한다(205-206줄). compose는 환경 변수가 바뀐 gateway 컨테이너만 다시 만든다(파일 머리말의 "값을 바꿔 다시 on 하면 gateway만 다시 만들어진다"). 그래서 이미지 빌드와 config-repo 커밋 없이 값을 바꿀 수 있지만 Gateway 프로세스는 재기동되고, 재기동 직후에는 식은 상태(JIT 컴파일 전·연결 풀 비어 있음)라 워밍업 뒤에 잰다.

정리하면 "상수가 아니라 설정에서 읽는다"는 맞다. 다만 이름 `auth-redis`는 코드 상수이고, 시한 1초는 설정이 없어 라이브러리 기본값이다. "재배포 없이"는 "이미지·코드·운영 설정을 건드리지 않고 컨테이너 재기동만으로"로 고쳐 말하는 편이 정확하다.

## 이 프로젝트에서는
- 운영 값: `config-repo/gateway.yml:73-94`(ADR 0050). 측정용 덮어쓰기: `loadtest/deploy/compose.gateway-bulkhead.yml`.
- 바인딩 확인 테스트: `gateway/src/test/java/com/iseonjwa/gateway/security/AuthRedisResilienceContextTest.java:111`이 `config-repo/gateway.yml`을 얹어 등록소의 `auth-redis` 차단기가 `TIME_BASED`·최소 500건인지 확인한다. 2026-11-11 실행 결과 9개 모두 통과.
- 주석 어긋남: `GatewayAuthenticationFilter.java:38` 주석은 "운영 설정(config-repo)에는 키를 두지 않는다"고 하지만 `config-repo/gateway.yml:58`에 `redis-timeout: 200ms`가 있다. 주석이 옛 상태다.

## 면접 포인트
- `@Value`와 `@ConfigurationProperties`의 차이: 키 하나를 바로 주입하는가, 접두사 아래 묶음을 타입 있는 객체로 바인딩하는가. 라이브러리는 후자를 쓰고, 이름별 `Map`으로 여러 인스턴스 설정을 받는다.
- 자동 구성이 만든 빈을 어떻게 손보나: 같은 타입 빈을 직접 등록하면 `@ConditionalOnMissingBean`으로 물러나고, 일부만 바꿀 때는 `Customizer` 빈을 쓴다(우리 `AuthRedisBreakerEvents`).
- 설정 변경이 언제 반영되나: 기동 시 바인딩이 기본이다. 실행 중 반영은 `@RefreshScope`·`/actuator/refresh`·Bus가 필요하고, 이미 만들어진 객체(차단기 등록소의 인스턴스)는 refresh로도 바뀌지 않을 수 있다.

## 참고
- 소스: `spring-cloud-circuitbreaker-resilience4j-5.0.3-sources.jar`(`ReactiveResilience4JAutoConfiguration`, `ReactiveResilience4JCircuitBreakerFactory`, `ReactiveResilience4JCircuitBreaker`), `resilience4j-spring-boot3-2.3.0-sources.jar`(`CircuitBreakerAutoConfiguration`, `CircuitBreakerProperties`, `BulkheadProperties`), `resilience4j-spring6-2.3.0-sources.jar`(`CircuitBreakerConfiguration`, `BulkheadConfiguration`), `resilience4j-circuitbreaker-2.3.0-sources.jar`(`InMemoryCircuitBreakerRegistry`).
- 실행: `./gradlew :gateway:test --tests "*AuthRedisResilienceContextTest"` 통과(tests=9, failures=0).
