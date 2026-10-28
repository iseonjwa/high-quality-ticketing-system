# 0013. 공통 모듈은 common 하나로 두고, 메시징만 만들 때 common-messaging으로 분리한다

- 상태: 채택 (2026-10-27 통합 회의. 메시징도 common에 두고 @Import 방식으로 적용. 아래 "통합 회의 반영" 참고)
- 날짜: 2026-10-27
- 결정자: kwonwoo (팀 리뷰 필요: SA 13장, 10/27 안건 2번)
- 관련 AI 판단: [ai-judgments/kwonwoo/2026-10-27-common-module-minimal-split.md](../ai-judgments/kwonwoo/2026-10-27-common-module-minimal-split.md)

## 통합 회의 반영 (2026-10-27 통합 회의)

팀은 기준 브랜치의 5개 모듈 대신 "common 하나로 묶고 내부에 각각의 폴더로 관리"하기로 했다. 통합 브랜치에는 이 ADR의 원안과 다르게 다음처럼 적용했다.

| 항목 | 이 ADR 원안 | 통합 브랜치 적용 |
|---|---|---|
| 메시징 | 만들 때 `common-messaging`으로 분리 | `common`의 `messaging` 패키지. 사용자 확인으로 함께 묶었다 |
| 공통 설정을 가져오는 방법 | MVC 서비스가 `scanBasePackages`에 공통 패키지를 넣음 | 기준 브랜치 방식 유지. 서비스가 `@Import(CommonWebConfiguration.class)`처럼 필요한 설정만 가져온다 |
| 격리 규칙 | 웹·JPA·Kafka는 `compileOnly` | 같음. JWT(oauth2-jose)도 `compileOnly`로 두고 Gateway·User가 직접 선언한다 |
| 격리 확인 | Gateway 기동 테스트 | `GatewayClasspathTest`가 Gateway 클래스패스에 `DispatcherServlet`·Hibernate·`KafkaTemplate` 클래스 파일이 없는지 확인한다 |
| 패키지 | `com.highquality.common` | `com.iseonjwa.common.{identity,response,auth,web,persistence,event,messaging}` |

- 검증: `common/build.gradle`의 `spring-webmvc`나 `spring-boot-starter-kafka`를 `api`로 바꾸면 `GatewayClasspathTest`가 실패하는 것을 확인했다. 처음에는 `ClassUtils.isPresent`로 확인했는데, 서블릿 API가 없으면 `spring-webmvc` jar가 있어도 `DispatcherServlet`을 로드하지 못해 false가 나와 변이를 잡지 못했다. 그래서 클래스 파일 존재로 바꿨다.
- Kafka가 없는 User 서비스가 메시징 빈을 만드는 문제는 `CommonMessagingConfiguration`을 Reservation·Payment만 `@Import`하는 것으로 막는다. 원안이 걱정한 "스캔하면 릴레이 빈이 생긴다"는 스캔을 쓰지 않아 해당하지 않는다.

## 맥락
- SA 13장은 공통 코드를 `common`(이벤트 봉투, BaseEntity), `common-response`(응답 DTO·ErrorCode, Gateway 포함), `common-web`(MVC 예외 처리기), `common-messaging`(Outbox·processed_event·DLQ) 네 모듈로 나눈다.
- Gateway를 WebFlux로 바꾸기로 했다([판단 기록](../ai-judgments/kwonwoo/2026-10-27-gateway-webflux.md)). Spring Boot는 클래스패스에 `spring-webmvc`가 있으면 서블릿으로 뜨므로, Gateway가 쓰는 공통 코드에 MVC 의존이 섞이면 안 된다.
- 지금 공통으로 둘 코드는 응답 형식, 에러 코드, MVC 예외 처리기 정도로 적다. Kafka는 Reservation·Payment만 쓰고 Gateway·User는 쓰지 않는다(SA 4-2, 10-1).

## 결정
- `common` 하나에 응답 형식(`ApiResponse`, `ErrorResponse`), 에러 코드(`ErrorCode`, `CommonErrorCode`), `BusinessException`, MVC 예외 처리기(`GlobalExceptionHandler`)를 둔다.
- 격리 규칙은 다음과 같다(`common/build.gradle` 주석에 적음).
  1. 웹 스택·JPA·Kafka는 `compileOnly`로만 둔다.
  2. 자동 구성을 두지 않고, MVC 서비스가 `scanBasePackages`에 `com.highquality.common`을 넣어 가져온다. Gateway는 스캔하지 않는다.
  3. Kafka를 쓰는 코드는 넣지 않는다.
- Outbox·processed_event·재시도·DLQ는 결제 담당이 만들 때 `common-messaging` 모듈로 분리하고 Reservation·Payment만 의존한다.

## 검토한 대안
- SA 13장대로 네 모듈로 분리: 격리를 빌드 구조로 보장한다. 하지만 지금 공통 코드 양에 비해 모듈 관리 비용이 크다. `common-messaging`은 쓰는 코드가 생길 때 만든다.
- 메시징도 `common`에 넣기: 서비스들이 `com.highquality.common`을 스캔하므로, Kafka가 없는 User 서비스가 릴레이 빈을 만들다 기동에 실패하거나 outbox 엔티티를 User DB에 만들려 한다. `@ConditionalOnClass` 등으로 막을 수 있지만, 규칙 하나만 빠져도 깨진다.
- 메시징을 서비스마다 구현: 틀리기 쉬운 코드(트랜잭션 경계, 중복 처리)가 두 벌이 되고, SAGA 양쪽 규칙이 어긋나기 쉽다.

## 결과
- 얻는 것: 모듈 수가 적고 구조가 단순하다.
- 검증 결과
  - Gateway가 `common`을 의존해도 Netty(WebFlux)로 기동하고, `DispatcherServlet`이 클래스패스에 없으며, 예외 처리기 빈이 등록되지 않는다(`GatewayEdgeApplicationTests`).
  - User 서비스는 없는 경로에 공통 실패 형식 404를 낸다(`UserServiceApplicationTests`).
- 감수하는 것: 격리를 빌드 구조가 아니라 규칙과 리뷰로 지킨다. 누가 `common`에 웹 스택을 `implementation`으로 넣으면 Gateway 테스트가 실패한다. 이 실패가 안전장치 역할을 한다.
- 후속 작업
  - 10/27 공통 모듈 비교·병합 때 이 구조를 제안한다.
  - SA 13장 반영 여부는 팀이 정한다.
