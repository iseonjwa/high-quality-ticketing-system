# 0013. 공통 모듈은 common 하나로 두고, 메시징만 만들 때 common-messaging으로 분리한다

- 상태: 제안
- 날짜: 2026-10-27
- 결정자: kwonwoo (팀 리뷰 필요: SA 13장, 10/27 안건 2번)
- 관련 AI 판단: [ai-judgments/kwonwoo/2026-10-27-common-module-minimal-split.md](../ai-judgments/kwonwoo/2026-10-27-common-module-minimal-split.md)

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
