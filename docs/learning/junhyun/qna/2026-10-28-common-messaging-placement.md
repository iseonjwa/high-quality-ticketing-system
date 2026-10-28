---
title: Outbox·처리 이력 같은 공통 메시징 코드를 common에 둘지, 별도 모듈로 나눌지
date: 2026-10-28
updated: 2026-10-28
asker: Junhyun An
level: 중급
tags: [multi-module, gradle, outbox, jpa, architecture]
---

# Outbox·처리 이력 같은 공통 메시징 코드를 common에 둘지, 별도 모듈로 나눌지

## 질문

> common 모듈로 옮기자 일단. 나누는 것도 고려하고싶긴한데... 어떤 이점이 있는지 트레이드오프는 뭔지 자세히좀 알려줄래?

## 짧은 답

"나눈다"는 두 가지로 나뉜다.
1. **일반 부품과 결제 전용 부품을 나누기**: 어느 쪽을 고르든 필요하다. 공통 모듈은 결제 도메인(`Payment`)을 알 수 없으므로 Outbox 엔진은 공통에, 결제 이벤트 내용과 발행기는 결제 서비스에 둔다.
2. **common 안의 패키지로 둘지, 별도 Gradle 모듈(`common-messaging`)로 둘지**: 지금은 패키지가 맞다. 팀이 10/27 통합 회의에서 그렇게 정했고(ADR 0013), 별도 모듈의 이점(격리를 빌드 구조로 보장)이 필요해지는 시점은 아직 오지 않았다. 패키지 경계만 지켜 두면 나중에 모듈로 빼는 일은 기계적이다.

## 이 프로젝트에서 무엇이 어디로 가는가

| 구분 | 위치 | 이유 |
|---|---|---|
| `OutboxWriter`, 저장소, 릴레이, `OutboxEventSender` 포트와 Kafka 구현, 처리 이력(`processed_event`) | `common.messaging` | 어떤 서비스가 써도 같은 규칙이어야 한다(임대, 키별 순서, 중복 방지) |
| `PaymentApprovedEvent`, `PaymentDeclinedEvent`, `PaymentResultPublisher`, `PaymentTransitionService` 연결 | `payment-service` | `Payment` 엔티티와 결제 상태 규칙에 의존한다. 공통 모듈이 서비스를 의존하면 순환이 된다 |
| `outbox`·`processed_event` 테이블 DDL | 각 서비스의 Flyway | DB가 서비스마다 따로라 테이블도 서비스마다 만든다 |

## 옮길 때 부딪히는 실제 문제 (common의 현재 제약)

1. **Gateway 격리**: `common`은 JPA·Kafka·MVC를 `compileOnly`로만 가진다. Gateway(WebFlux)가 이것을 받으면 기동하지 못하기 때문이다. Outbox가 쓰는 `spring-tx`, `spring-boot`, `spring-kafka`도 같은 방식으로 `compileOnly`가 되고, 쓰는 서비스가 직접 선언한다.
2. **Spring Data를 쓰기 어렵다**: 서비스의 `@SpringBootApplication`은 자기 패키지만 스캔한다. `common`의 Spring Data 저장소와 엔티티를 찾게 하려면 서비스가 `@EnableJpaRepositories`·`@EntityScan`으로 `common` 패키지를 추가해야 하고, 이 설정은 서비스 자신의 스캔 기본값을 덮어써서 실수하기 쉽다. 그래서 공통 쪽 저장소는 엔티티 없이 `JdbcTemplate`으로 쓴다. `JpaTransactionManager`가 같은 JDBC 연결을 노출하므로 JPA로 바꾼 상태와 한 트랜잭션이다(롤백·커밋 테스트로 확인).
3. **테스트 환경이 다르다**: `common`의 테스트는 H2다. 릴레이의 `FOR UPDATE SKIP LOCKED`·`ON CONFLICT`는 PostgreSQL 문법이라 H2로 검증되지 않는다. 그래서 동시성·임대 테스트는 PostgreSQL이 있는 서비스 쪽에 남거나, `common`에 PostgreSQL 테스트 인프라를 따로 만들어야 한다.
4. **스키마 계약이 복제된다**: 테이블은 서비스 Flyway가 만든다. 서비스마다 같은 DDL을 복사하므로 어긋날 수 있다. 엔티티가 없어 `ddl-auto: validate`가 검증하지 못하므로 서비스의 마이그레이션 테스트가 확인하고, 표준 DDL은 `docs/guides/common-modules.md`에 둔다.
5. **변경 파급**: `common`을 고치면 모든 서비스가 다시 빌드·테스트된다.

## 별도 모듈(`common-messaging`)로 나누면

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 의존 격리 | Gateway·User는 아예 의존하지 않는다. `GatewayClasspathTest` 같은 규율 없이 빌드 구조가 보장한다 | 모듈 추가: `settings.gradle`, `build.gradle`, `verifyWorkspace` 연결 |
| 의존 선언 | `implementation`으로 JPA·Kafka를 명시해 `compileOnly` 우회가 필요 없다 | 사실상 Reservation·Payment 둘만 쓰는 모듈이라 지금은 관리 비용이 더 크다 |
| 테스트 | 자체 PostgreSQL 테스트를 둘 수 있다 | 팀 합의(ADR 0013, 10/27 통합 회의)를 뒤집어야 하고, 반효승·이강민과 다시 맞춰야 한다 |

되돌리는 비용이 대칭이 아니다. 패키지에서 모듈로 빼는 것은 파일 이동과 `build.gradle` 추가로 끝나지만, 모듈에서 패키지로 합치는 것은 합의를 또 거친다. 그래서 먼저 패키지로 시작하고 격리가 실제로 깨지거나 테스트 환경 차이가 아프면 모듈로 뺀다.

## 더 깊이

- 서비스 간 코드 공유 방식은 공유 라이브러리(여기), 서비스마다 복제, 별도 서비스(플랫폼 서비스) 순으로 결합도가 낮아지지만 운영 비용이 늘어난다. Outbox는 서비스 DB와 같은 트랜잭션에 묶여야 하므로 별도 서비스가 될 수 없고, 라이브러리나 복제 둘 중 하나다.
- "공통 모듈이 도메인을 몰라야 한다"는 의존 방향 원칙(의존성 역전)이 이 분리의 기준이다. 포트(`OutboxEventSender`)를 공통이 정의하고 구현을 바꿔 끼우는 구조도 같은 원리다.

## 면접에서 나올 만한 질문

- 여러 서비스가 쓰는 Outbox를 공유 라이브러리로 만들 때 생기는 문제는 무엇인가(스키마 복제, 엔티티 스캔, 테스트 환경 차이).
- 공통 모듈이 특정 서비스의 도메인을 알면 왜 안 되는가.
