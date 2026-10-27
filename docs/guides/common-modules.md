# 공통 모듈 사용 가이드

[ISE-5](https://linear.app/iseonjwa/issue/ISE-5)에서 준현이 만든 공통 모듈의 사용 계약이다. 응답 6키, 모듈 분리, 패키지명 iseonjwa는 팀 합의 전이다. 처음에는 개인 초안이었고 이후 실제 Boot 앱과 인증으로 확장했다([ADR 0002](../decisions/0002-common-foundation-draft.md), [ADR 0004](../decisions/0004-workspace-auth-bootstrap.md)). 빌드·실행·검증은 [워크스페이스 가이드](workspace.md)를 따른다.

## 모듈과 사용 계약

| 모듈 | 제공 내용 | 의존·적용 조건 |
|---|---|---|
| common-response | ApiResponse, FieldError, ErrorCode, CommonErrorCode, BusinessException, IdentityHeaders, Roles | Spring 의존 없음. NULL 필드 보존용 Jackson 애너테이션 사용 |
| common-auth | TokenType, TokenDecoders | common-response, spring-security-oauth2-jose. Gateway와 User가 같은 JWT 계약으로 검증 |
| common-web | CommonWebConfiguration, CommonExceptionHandler, GatewayIdentityFilter | common-response, Spring MVC. 설정을 명시적으로 import |
| common-messaging | CommonMessagingConfiguration | Boot Kafka 연결 기반(spring-boot-starter-kafka) |
| common | EventEnvelope, BaseEntity, CurrentActorProvider, CommonJpaConfiguration | 이벤트는 일반 DTO. 감사는 JPA 콜백과 Spring 빈 주입 사용 |

### MVC 응답

서비스가 common-web을 의존하고 설정에서 다음을 선언한다. 공통 패키지 전체를 component scan 대상으로 넣는 대신 서비스 패키지만 스캔한다.

```java
@Import(CommonWebConfiguration.class)
```

성공 응답은 컨트롤러가 ApiResponse.success(status, data, traceId)로 직접 생성한다. 실제 HTTP 상태는 ResponseEntity에서도 동일하게 지정한다. 자동 응답 감싸기는 제공하지 않는다. 202 응답의 Location과 nextPollAfterSeconds는 해당 서비스가 제공하며, 본문이 없는 성공은 200과 data NULL을 사용한다.

실패는 BusinessException의 ErrorCode나 Spring MVC 예외의 HTTP 상태로 응답한다. 도메인별 에러 코드는 각 서비스에서 ErrorCode를 구현한다. 검증 오류에는 필드와 사유를 싣고, 예상하지 못한 예외는 500 공통 문구만 내보낸다. traceId는 기존 MDC 값을 사용하며 없으면 NULL이다. 추적 ID 생성과 전파는 서비스·관측성 설정의 후속 범위다.

예외 처리 대상 컨트롤러 패키지는 iseonjwa다. Actuator 등 다른 패키지에는 적용하지 않는다. Mock PG는 common-web을 import하지 않아야 한다. Gateway는 WebFlux용 처리가 필요하므로 common-web을 사용하지 않는다. Kafka 이벤트에는 ApiResponse를 사용하지 않는다.

컨트롤러 매핑 전 404·405에는 패키지 정보가 없으므로 별도 라우팅 예외 처리기가 `/api/v1/` 경로만 처리한다. 405의 Allow 헤더 등 기존 HTTP 헤더는 유지한다.

### 사용자 컨텍스트

GatewayIdentityFilter는 Gateway가 전달한 X-User-Id·X-User-Role 헤더를 요청 속성(`currentUserId`, `currentUserRole`)으로 옮긴다. 경로로 인증 여부를 판단하지 않는다. 헤더가 있으면 형식만 검증해 잘못되면 401이고, 없으면 속성 없이 통과한다. 사용자 정보가 필요한 컨트롤러가 `@RequestAttribute`로 받으면 속성이 없을 때 공통 예외 처리기가 401을 반환한다. 인증 필요 여부는 Gateway의 `AccessPolicy`가 정한다.

### 토큰 계약

common-auth의 TokenDecoders는 HS256 서명, 만료, issuer, audience, 토큰 종류(access·refresh), 역할(USER·ADMIN), sub·jti·iat 존재를 검증한다. Access와 Refresh는 서명 키와 audience가 달라 서로 대신 쓸 수 없다. 서명 키는 32바이트 이상이어야 한다.

### 감사와 이벤트

JPA 서비스는 BaseEntity를 상속하고 CommonJpaConfiguration을 import한다. CurrentActorProvider 빈은 서비스가 제공한다. 신뢰할 수 있는 사용자 컨텍스트이면 사용자 ID를, Kafka 소비자·스케줄러이면 Optional.empty()를 반환한다. common 모듈은 헤더를 해석하지 않는다. 서비스가 GatewayIdentityFilter가 만든 `currentUserId` 요청 속성을 읽어 공급한다(User·Reservation·Payment의 설정 참고).

Hibernate가 리스너를 Spring 빈으로 만들도록 SpringBeanContainer를 연결해야 한다. 테스트의 JpaAuditingTest.Config가 수동 EntityManagerFactory 연결 예제다. Boot 서비스에서는 자동 설정으로 연결되며, user-service의 통합 테스트가 실제 PostgreSQL의 NOT NULL 감사 컬럼에 삽입되는지로 확인한다. 감사 설정은 시계 commonAuditingClock을 제공하고 생성·수정 시각은 UTC Instant로 기록한다.

생성 시 created_at·created_by·updated_at·updated_by를 기록한다. 수정 시 생성 정보를 유지하고 updated_at·updated_by만 갱신한다. 시스템 수정은 이전 사용자 ID를 남기지 않고 updated_by를 NULL로 만든다.

논리 삭제는 허용된 도메인이 markDeleted를 호출한다. 공통 삭제 필터와 version 필드는 없다. 예매·결제 등 상태로 관리하는 도메인에서 삭제 기능을 호출하지 않는다. 기록용 테이블은 SA대로 BaseEntity를 상속하지 않는다.

EventEnvelope는 SA 10-2의 eventId·eventType·version·occurredAt·producer·traceId·payload를 제공한다. 전송·소비, 도메인별 이벤트 payload, Outbox·멱등 처리·재시도·DLQ는 구현하지 않았다.

## 팀 비교 때 확인할 질문

1. 공통 응답은 어디까지 통일하고 MVC·WebFlux의 예외 처리는 왜 분리하는가?
2. 시스템 작업이 사용자 요청 뒤 엔티티를 수정하면 감사 사용자 ID는 무엇으로 남아야 하는가?
