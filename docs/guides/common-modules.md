# 공통 모듈 사용 가이드

[ISE-5](https://linear.app/iseonjwa/issue/ISE-5)에서 준현이 만든 공통 코드의 사용 계약이다. 2026-10-27 통합 회의에서 모듈 하나(`common`)와 패키지 이름 `com.iseonjwa`로 정했다([ADR 0013](../decisions/0013-single-common-module.md), [ADR 0014](../decisions/0014-package-name-com-iseonjwa.md)). 응답 형식은 SA MASTER v3.1(#19)을 따른다: 성공 `{status, message, data, traceId}`, 실패 `{status, code, message, errors, traceId}`(2026-10-27 정렬). 처음에는 개인 초안이었고 이후 실제 Boot 앱과 인증으로 확장했다([ADR 0002](../decisions/0002-common-foundation-draft.md), [ADR 0004](../decisions/0004-workspace-auth-bootstrap.md)). 빌드·실행·검증은 [워크스페이스 가이드](workspace.md)를 따른다.

## 패키지와 사용 계약

모든 서비스는 `implementation project(':common')` 하나만 의존한다. 아래 표의 "서비스가 직접 선언" 라이브러리는 `common`에서 `compileOnly`라 전달되지 않는다. Gateway(WebFlux)가 MVC·JPA·Kafka를 받으면 기동하지 못하기 때문이다. `common/build.gradle`에서 이를 `implementation`·`api`로 바꾸면 `GatewayClasspathTest`가 실패한다.

| 패키지 (`com.iseonjwa.common.`) | 제공 내용 | 서비스가 직접 선언·적용 조건 |
|---|---|---|
| `response`, `identity` | SuccessResponse, FailureResponse, FieldError, ErrorCode, CommonErrorCode, BusinessException, IdentityHeaders, Roles | 없음(Spring 의존 없음). 성공의 data는 null이어도 내보내고 실패의 errors는 검증 실패일 때만 내보내는 Jackson 애너테이션 사용 |
| `auth` | TokenType, TokenDecoders | spring-security-oauth2-jose. Gateway와 User가 같은 JWT 계약으로 검증 |
| `web` | CommonWebConfiguration, CommonExceptionHandler, GatewayIdentityFilter, RequireRole | spring-boot-starter-webmvc. `@Import(CommonWebConfiguration.class)` |
| `messaging` | CommonMessagingConfiguration | spring-boot-starter-kafka. `@Import(CommonMessagingConfiguration.class)` |
| `persistence`, `event` | EventEnvelope, BaseEntity, CurrentActorProvider, CommonJpaConfiguration | spring-boot-starter-data-jpa. `@Import(CommonJpaConfiguration.class)`. 이벤트는 일반 DTO. 감사는 JPA 콜백과 Spring 빈 주입 사용 |
| `messaging.outbox`, `messaging.inbox` | OutboxWriter·OutboxRelay·OutboxEventSender, ProcessedEventService | spring-jdbc, spring-kafka. `@Import(CommonOutboxConfiguration.class)`(`CommonJpaConfiguration`도 함께). 서비스 Flyway가 `outbox`·`processed_event` 테이블을 만든다 |

### MVC 응답

서비스가 Spring MVC를 의존하고 설정에서 다음을 선언한다. 공통 패키지 전체를 component scan 대상으로 넣는 대신 서비스 패키지만 스캔한다.

```java
@Import(CommonWebConfiguration.class)
```

성공 응답은 컨트롤러가 SuccessResponse.of(status, message, data, traceId)로 직접 생성한다. 실제 HTTP 상태는 ResponseEntity에서도 동일하게 지정한다. 자동 응답 감싸기는 제공하지 않는다. 202 응답의 Location과 nextPollAfterSeconds는 해당 서비스가 제공하며, 본문이 없는 성공은 200과 data NULL을 사용한다.

실패는 BusinessException의 ErrorCode나 Spring MVC 예외의 HTTP 상태로 응답한다. 도메인별 에러 코드는 각 서비스에서 ErrorCode를 구현한다. 검증 오류에는 필드와 사유를 싣고, 예상하지 못한 예외는 500 공통 문구만 내보낸다. traceId는 기존 MDC 값을 사용하며 없으면 NULL이다. 추적 ID 생성과 전파는 서비스·관측성 설정의 후속 범위다.

예외 처리 대상 컨트롤러 패키지는 `com.iseonjwa`다. Actuator 등 다른 패키지에는 적용하지 않는다. Mock PG는 CommonWebConfiguration을 import하지 않아야 한다. Gateway는 WebFlux용 처리가 필요하므로 `web` 패키지를 사용하지 않는다. Kafka 이벤트에는 응답 클래스를 사용하지 않는다.

컨트롤러 매핑 전 404·405에는 패키지 정보가 없으므로 별도 라우팅 예외 처리기가 `/api/v1/` 경로만 처리한다. 405의 Allow 헤더 등 기존 HTTP 헤더는 유지한다.

### 사용자 컨텍스트

GatewayIdentityFilter는 Gateway가 전달한 X-User-Id·X-User-Role 헤더를 요청 속성(`currentUserId`, `currentUserRole`)으로 옮긴다. 경로로 인증 여부를 판단하지 않는다. 헤더가 있으면 형식만 검증해 잘못되면 401이고, 없으면 속성 없이 통과한다. 사용자 정보가 필요한 컨트롤러가 `@RequestAttribute`로 받으면 속성이 없을 때 공통 예외 처리기가 401을 반환한다. 인증 필요 여부는 Gateway의 `AccessPolicy`가 정한다.

### 역할 확인

컨트롤러 메서드나 클래스에 `@RequireRole(Roles.ADMIN)`처럼 허용할 역할을 선언하면 인터셉터가 호출 전에 `currentUserRole` 속성을 확인한다. 속성이 없으면 401 UNAUTHORIZED, 허용한 역할이 아니면 403 FORBIDDEN이며 둘 다 공통 실패 응답이다. 메서드의 선언이 클래스보다 우선한다. 어노테이션이 없는 API는 역할을 확인하지 않는다(로그인 필요 여부는 Gateway가 정한다). 소유자 검증 같은 도메인 인가는 각 서비스의 Application 계층에서 한다.

### 토큰 계약

`auth` 패키지의 TokenDecoders는 HS256 서명, 만료, issuer, audience, 토큰 종류(access·refresh), 역할(USER·ADMIN), sub·jti·iat 존재를 검증한다. Access와 Refresh는 서명 키와 audience가 달라 서로 대신 쓸 수 없다. 서명 키는 32바이트 이상이어야 한다.

### 감사와 이벤트

JPA 서비스는 BaseEntity를 상속하고 CommonJpaConfiguration을 import한다. CurrentActorProvider 빈은 서비스가 제공한다. 신뢰할 수 있는 사용자 컨텍스트이면 사용자 ID를, Kafka 소비자·스케줄러이면 Optional.empty()를 반환한다. `persistence` 패키지는 헤더를 해석하지 않는다. 서비스가 GatewayIdentityFilter가 만든 `currentUserId` 요청 속성을 읽어 공급한다(User·Reservation·Payment의 설정 참고).

Hibernate가 리스너를 Spring 빈으로 만들도록 SpringBeanContainer를 연결해야 한다. 테스트의 JpaAuditingTest.Config가 수동 EntityManagerFactory 연결 예제다. Boot 서비스에서는 자동 설정으로 연결되며, user-service의 통합 테스트가 실제 PostgreSQL의 NOT NULL 감사 컬럼에 삽입되는지로 확인한다. 감사 설정은 시계 commonAuditingClock을 제공하고 생성·수정 시각은 UTC Instant로 기록한다.

생성 시 created_at·created_by·updated_at·updated_by를 기록한다. 수정 시 생성 정보를 유지하고 updated_at·updated_by만 갱신한다. 시스템 수정은 이전 사용자 ID를 남기지 않고 updated_by를 NULL로 만든다.

논리 삭제는 허용된 도메인이 markDeleted를 호출한다. 공통 삭제 필터와 version 필드는 없다. 예매·결제 등 상태로 관리하는 도메인에서 삭제 기능을 호출하지 않는다. 기록용 테이블은 SA대로 BaseEntity를 상속하지 않는다.

EventEnvelope는 SA 10-2의 eventId·eventType·version·occurredAt·producer·traceId·payload를 제공한다. 전송·소비, 도메인별 이벤트 payload, Outbox·멱등 처리·재시도·DLQ는 구현하지 않았다.

## 팀 비교 때 확인할 질문

1. 공통 응답은 어디까지 통일하고 MVC·WebFlux의 예외 처리는 왜 분리하는가?
2. 시스템 작업이 사용자 요청 뒤 엔티티를 수정하면 감사 사용자 ID는 무엇으로 남아야 하는가?

### Outbox와 처리 이력

`CommonOutboxConfiguration`을 `@Import`하면 발행기(`OutboxWriter`), 릴레이(`OutboxRelay`와 스케줄러), 처리 이력(`ProcessedEventService`)이 생긴다. 스캔하지 않으므로 Kafka가 없는 서비스에는 빈이 없다([ADR 0013](../decisions/0013-single-common-module.md)).

- **쓰기**: `OutboxWriter.write(topic, key, eventType, payload)`는 업무 변경과 같은 트랜잭션 안에서만 부를 수 있다(없으면 예외). 공통 봉투(`EventEnvelope`)를 만들어 `outbox` 행으로 쓰고, 상태 변경이 실제로 일어났을 때만 호출한다. 이벤트 페이로드와 발행기는 서비스가 만든다(예: 결제의 `PaymentResultPublisher`).
- **저장소는 JDBC**: 엔티티 없이 `JdbcTemplate`으로 쓰므로 서비스가 `@EntityScan`을 바꿀 필요가 없고, JPA 트랜잭션과 같은 연결을 쓴다. 테이블은 서비스 Flyway가 만든다(스키마는 [결제 데이터 모델](../design/payment/data-model.md) 6·7절). `ddl-auto: validate`는 이 테이블을 검증하지 않으므로 서비스가 마이그레이션 테스트로 확인한다.
- **릴레이**: 같은 키의 앞선 PENDING이 있으면 집지 않아 키별 순서를 지키고, 임대(`claimed_until`)와 `SKIP LOCKED`로 중복 집기를 막는다. 설정은 `messaging.outbox.batch-size`(20), `lease`(30s), `send-timeout`(12s), `interval`(500ms), `relay-enabled`다. `lease`는 `send-timeout`의 2배 이상이어야 하며 어긋나면 기동이 실패한다. 프로듀서 `max.block.ms`를 짧게(2초) 둬야 한다.
- **처리 이력**: `ProcessedEventService.processOnce(eventId, consumerGroup, action)`가 기록과 업무를 한 트랜잭션으로 묶는다. 이미 처리했으면 업무를 실행하지 않는다.
- **테스트**: 릴레이·처리 이력은 PostgreSQL 문법(`SKIP LOCKED`, `RETURNING`, `ON CONFLICT`)을 써서 `common`의 H2 테스트로는 검증하지 못한다. 쓰는 서비스의 PostgreSQL 통합 테스트로 확인한다(예: `payment-service`의 `messaging` 테스트).
- **전제**: 키별 순서는 같은 키의 쓰기가 업무 행 잠금으로 줄을 서서 id 순서와 커밋 순서가 같다는 전제에 기댄다.
