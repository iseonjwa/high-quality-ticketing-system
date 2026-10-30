# 설계 상태와 공식 근거

2026-10-27에 Notion 결정 DB의 상태와 본문을 읽었다. 아래는 조사 근거이며 새로운 팀 결정이 아니다. 저장소 SA 사본, 이전 SA v3, 현재 결정 기록의 표현이 다르면 이 표의 상태와 원문을 함께 확인한다.

## 현재 결정 상태

원본: [SA 기획·설계 결정 기록](https://app.notion.com/p/4037bd823b75802eb9f6e4a6af0226b7). 데이터 소스 `collection://13c7bd82-3b75-828e-b61a-0728267cc483`를 조회했고 `has_more=false`였다. 아래 표에 필요한 항목만 선택했으며 전체 결정 목록을 조사했다고 뜻하지 않는다.

| ID | 항목 | 조회 상태 | 이번 비교에서 지킬 구분 |
| --- | --- | --- | --- |
| 9 | 역할 분담 | 결정 | 공통 담당과 도메인 담당의 소유 범위를 통합 후에도 명시한다 |
| 10 | 서비스 구성 | 결정 | 업무 서비스 수를 공통 코드 편의 때문에 바꾸지 않는다 |
| 14 | 모듈 경계·분리 조건 | 잠정 결정 | 모듈 경계의 다른 안을 비교할 수 있다 |
| 15 | 데이터 공통 규칙 | 잠정 결정 | 감사·삭제·버전·Flyway 규칙은 구현과 대조한다 |
| 19 | API 공통 규칙 | 결정 | 성공·실패 분리, HTTP 숫자 status, 양쪽 traceId, Gateway 오류·기본 오류 포함 |
| 21 | 인증 토큰 방식 | 잠정 결정 | Access+Refresh, Authorization 헤더, Redis 로그아웃 블랙리스트 |
| 23 | 회원 정보·관리자 계정 | 잠정 결정 | 계정·관리자 정책을 구현과 대조한다 |
| 30 | 기술 스택·Spring Cloud 구성 | 잠정 결정 | Java 21, Boot 4.1.x, Cloud 2025.1.x, native Config 서버 포함 |
| 48 | 인프라 장애 시 동작 | 잠정 결정 | 블랙리스트 fail-open과 짧은 타임아웃·서킷이 잠정안이다 |
| 56 | 입장·결제 토큰 규칙 | 잠정 결정 | 용도별 키와 typ·aud 구분, HMAC 한계와 claim 이름을 확인한다 |
| 57 | 외부 노출과 관리자 접근 | 잠정 결정 | Gateway만 외부 공개, 내부 서비스 expose, 관리 UI loopback |
| 59 | 레이스 컨디션 해결 방식 | 잠정 결정 | 이번 인증·공통 통합이 업무 동시성 구현 완료를 뜻하지 않는다 |
| 60 | 공통 모듈 세분성 | 제안 | 10/27 새 제안이며 채택된 결정으로 쓰지 않는다 |

### #19: 응답 형식은 이미 정해진 계약이다

[원문](https://app.notion.com/p/fd87bd823b7582eb9d9081d60ade0544), 마지막 편집 2026-10-26 19:47 KST. 성공은 `{status,message,data,traceId}`, 실패는 `{status,code,message,errors,traceId}`이다. `errors`는 검증 오류에서만 `[{field,reason}]`을 사용한다.

HTTP 상태를 보존하고 본문 없는 성공은 200 + `data:null`을 사용한다. Gateway 401·403·429, Spring `/error`, 깨진 JSON도 같은 실패 계약으로 검사한다. Actuator·Prometheus·Mock PG 내부 API·Kafka 이벤트는 API 응답으로 감싸지 않는다.

### #48: 인증 장애 정책은 다시 결정할 수 있는 잠정안이다

[원문](https://app.notion.com/p/2837bd823b758375b61d8157fcfeedee), 마지막 편집 2026-10-26 19:50 KST. Redis 블랙리스트 실패는 fail-open이고, 로그아웃한 Access가 최대 30분 통과하는 위험을 감수한다고 적혀 있다. 보안 요구가 커지면 closed로 바꾸는 조건도 적혀 있다.

Redis 호출은 짧은 타임아웃과 서킷 브레이커를 사용한다. 초기 타임아웃 100ms는 잠정값이며 측정으로 조정한다. 코드에서 실패를 통과·차단하는지만 보고 타임아웃·서킷까지 구현됐다고 판단하면 안 된다.

### #60: 모듈 하나로 시작하는 안도 실제 논의 대상이다

[원문](https://app.notion.com/p/4067bd823b7580b98b6cdbac52fc8594), 마지막 편집 2026-10-27 14:39 KST. `common` 하나 안에 response/web/jpa/messaging 패키지를 두고 Gateway는 이 모듈에 의존하지 않는 안이다. Gateway 오류 DTO를 복제하고 계약 테스트로 두 형태가 같은지 검사한다.

원문은 WebFlux Gateway에 MVC 또는 JPA 의존성을 넣었을 때 기동 실패를 관찰한 근거를 제시한다. 이는 원문 작성자의 검증 결과이며 이번 조사에서 재실행한 결과와 구분한다. Kwonwoo 브랜치의 MVC Gateway를 선택한다면 WebFlux/MVC 충돌 전제가 달라진다고도 명시한다.

다만 현재 고정 커밋 `60994695c70fcb4e0c8c2ec11128fd7bb868de33`의 [Gateway 의존성](https://github.com/iseonjwa/high-quality-ticketing-system/blob/60994695c70fcb4e0c8c2ec11128fd7bb868de33/gateway-edge/build.gradle#L7)은 WebFlux다. 5개 구현 브랜치 모두 WebFlux이며, #60의 MVC 설명은 이번 코드 스냅샷과 다르다. 모듈 대안은 비교하되 Gateway 현황은 코드 기준으로 읽는다.

### #30·#56·#57: 기술 선택과 신뢰 경계

[기술 스택](https://app.notion.com/p/2df7bd823b7582048be78192e66f58b7)은 Config 클라이언트에서 필수 import와 test 프로필 비활성화를 적고 있다. Config 서버 추가는 개인 요청에서 시작했으며 팀 전체 확정으로 표기하지 않는다는 문구가 있다.

[토큰 규칙](https://app.notion.com/p/4057bd823b7580918545d2577fd629df)은 입장·결제 키 분리와 typ·aud 검증을 잠정 채택했다. HMAC 검증자는 발급도 할 수 있다는 한계를 인정하고 비대칭 서명은 MVP 이후 후보로 뒀다.

[외부 노출](https://app.notion.com/p/4057bd823b7580b78282cf4f1c1e337b)은 Gateway만 외부 공개하고 내부 서비스에 직접 접근하지 못하게 하는 전제다. 사용자 헤더 신뢰는 이 전제가 실제로 지켜질 때의 계약으로 검토해야 한다.

## 외부 공식 근거

2026-10-27에 아래 페이지를 직접 열어 확인했다. 공식 문서가 선택의 유일한 정답을 정해 주는 것은 아니며, 프로젝트에 적용하는 추천은 종합 보고서에서 구분한다.

| 근거 | 확인 내용 | 연결되는 논의 |
| --- | --- | --- |
| [Spring Cloud 공식 호환표](https://spring.io/projects/spring-cloud/#overview) | 2025.1.x는 2025.1.2부터 Boot 4.1.x를 지원한다 | 5개 브랜치의 Boot 4.1.2 / Cloud 2025.1.4 조합 자체를 통합 때문에 바꿀 근거는 없다 |
| [Spring Cloud Config Client](https://docs.spring.io/spring-cloud-config/reference/client.html#config-data-import) | `optional:`을 제거하면 Config 서버 연결 실패 시 클라이언트 기동이 실패한다 | 로컬·test 독립성과 배포 환경의 설정 누락 감지를 프로필별로 구분한다 |
| [Spring Boot 자동구성](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) | imports 파일과 조건부 자동구성으로 라이브러리를 로딩하며 `ConditionalOnClass`·`ConditionalOnMissingBean` 등으로 적용 범위를 제어한다 | 모듈 수와 별개로 Gateway 클래스패스, MVC·JPA 전이, 자동구성 등록 범위를 함께 본다 |
| [Spring Security 헤더 사전 인증](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/web/authentication/preauth/RequestHeaderAuthenticationFilter.html) | 이 필터 자체는 신원을 검증하지 않는다. 외부 인증 체계에 우회 경로가 있으면 헤더 설정만으로 사칭할 수 있다고 명시한다 | Gateway의 외부 사용자 헤더 제거와 내부 서비스 직접 접근 통제가 함께 필요하다 |
| [RFC 8725 §3.1·3.9·3.11·3.12](https://www.rfc-editor.org/rfc/rfc8725.html#section-3) | 허용 알고리즘 제한, audience 검증, 토큰 종류별 배타적 검증을 요구한다. 용도 구분에 키·필수 claim·aud·iss를 조합할 수 있다 | Access/Refresh/입장/결제를 한 검증기로 혼용하지 않고 계약 테스트를 둔다 |

RFC의 명시적 `typ`은 JOSE **헤더** 파라미터다. SA의 `typ` claim과 구현의 payload `typ`을 같은 위치라고 취급하면 안 된다. 커스텀 claim으로도 용도를 구분할 수 있지만 헤더와 payload의 이름·값·검증 위치를 각각 정해야 한다.

## 조회 방법과 한계

Notion `fetch`로 SA v3·v3.1과 결정 DB 스키마를 읽고, SQL로 필요한 결정의 ID·제목·상태·URL을 조회했다. #15·#19·#21·#30·#48·#56·#57·#60은 각 페이지 본문도 읽었다. 10/27 이후 바뀌는 결정과 원격 브랜치는 재확인이 필요하다.

Notion 문서·결정 상태는 변경하지 않았다. 새 추천을 ADR의 확정 결정으로 등록하지 않았다.
