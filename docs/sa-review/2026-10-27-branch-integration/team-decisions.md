# 팀 결정 안건

아래 추천은 미채택 상태다. 빈 결정란에 선택·예외·담당자를 적고, SA와 다른 선택은 팀 합의 후 정본·ADR에 반영한다. 담당은 역할 제안이며 개인 배정이 아니다.

기능·검증 요약은 [종합 검토](README.md), 설계 상태는 [정본 대조](source-notes.md)를 따른다. ‘필수’는 dev 통합 전에 답이 필요하다는 뜻이며 모든 대안 구현을 요구하지 않는다.

## D01. 코드 기준과 이름

- 현상: 준현만 회원·세션 업무를 구현했다. 패키지는 `iseonjwa`/`com.highquality`/`com.iseonjwa`, 모듈은 `gateway`/`gateway-edge`, `eureka`/`eureka-registry`로 갈린다.
- 선택지: A 준현 기준과 이름을 우선 유지 / B 준현 기능을 공통 이름으로 정렬 / C 다른 기반에 인증을 재이식한다.
- 추천: B. 패키지는 `com.iseonjwa`, 모듈은 `gateway`·`eureka`를 제안한다. 여러 브랜치의 패키지와 맞추되 기능 기준은 유지한다. 비용은 imports·컴포넌트 스캔·advice 범위·자동구성 imports·테스트 필터·실행 도구를 함께 바꾸는 일이다. Gradle group은 Java 패키지와 별도로 정하며 전역 문자열 치환하지 않는다.
- 통합 전: 필수. 관련 역할: 공통 담당·각 서비스 담당·통합 담당.
- 결정: ______

## D02. 공통 모듈 경계와 #60

현상: SA 사본은 response/web 분리, #60은 단일 common+Gateway 미의존+응답 DTO 복제+계약 테스트를 제안한다. 현재 권우 구현도 Gateway가 common에 의존하므로 #60의 완성 구현은 아니다. [근거](common-contracts.md#이벤트outbox와-의존성-경계).

| 선택지 | 이점 | 감수 비용 |
| --- | --- | --- |
| A #60 단일 common, Gateway 미의존 | 서비스 측 모듈·빌드 구성이 작다 | Gateway 응답 복제·변경 동기화·계약 테스트 담당이 필요하다. User/Gateway JWT 검증 공유 방식도 별도로 정한다 |
| B 최소 분리: common-response·common-auth 공유, 나머지는 common 내 패키지 | 순수 응답과 JWT를 공유하고 모듈 수를 줄인다 | common의 Servlet/JPA/Kafka 선택 로딩·의존 경계를 관리한다. 현재 web/messaging 이동 비용이 있다 |
| C 준현의 common/response/web/auth/messaging 유지 | 현재 연결과 테스트를 가장 적게 바꾸고 Gateway의 MVC/JPA 유입을 구조로 막는다 | 작은 모듈·비어 있는 책임까지 관리하는 비용이 남는다 |

- 추천: 첫 통합은 C. B는 인증 안정화 후 단순화 후보로 둔다. A도 가능하지만 모듈 수를 줄이는 대신 공개 DTO와 JWT 검증의 동기화 책임이 생기므로 지금 더 싸다고 단정하지 않는다.
- 조건부 자동 구성은 모듈 수의 대체물이 아니다. 적용한다면 Servlet 조건·누락 의존·중복 빈·Actuator/Mock PG 제외를 검증한다. [Spring Boot 공식 근거](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html).
- 통합 전: 필수. 관련 역할: 공통 담당·Gateway 담당·빌드 담당.
- 결정: ______

## D03. 응답 API·HTTP·trace·errors

- 현상: #19의 JSON 형식은 결정되어 있지만 Java 타입/메서드, trace 주입, 빈 errors 처리, 만료 오류 코드가 다르다. DTO의 status만 바꾸면 실제 HTTP 상태는 바뀌지 않는다.
- 선택지: A 준현 공개 API 유지+얇은 편의 API 추가 / B 권우·효승 API로 이름까지 전환. trace는 명시적 주입 유지 또는 업무 응답에 한정한 자동 주입이다.
- 추천: A와 제한된 자동 주입. 효승의 메서드 검증 errors 처리·trace advice를 패턴으로 옮기고 권우의 `ok/created` 편의를 더한다. 본문 status와 HTTP 상태·Allow/Retry-After를 함께 검사한다. 비용은 호출부·직렬화 회귀와 WebFlux trace 전파 검증이다.
- #19의 200+data:null, 검증 오류만 errors, 기본 `/error`·깨진 JSON 포함, Actuator/Prometheus/Mock PG/Kafka 제외를 유지한다. ErrorCode 명칭·메서드와 외부 오류 목록은 하나로 고정한다. MDC 읽기만으로 분산 trace가 구현됐다고 보지 않는다.
- 통합 전: 필수. 관련 역할: 공통 담당·Gateway 담당·API 소비자 담당.
- 결정: ______

## D04. JWT 키·encoding·종류 검증

- 현상: 준현은 UTF-8 원문 키·Nimbus, 효승은 Base64 decode·JJWT다. 준현 발급은 JOSE 헤더 `typ=JWT`, payload `token_type=access/refresh`이며 #56의 입장·결제 ‘typ claim’은 위치를 명확히 해야 한다.
- 선택지: A 준현 발급/검증 계약 통일 / B Base64 키 계약으로 양쪽 일괄 변경. 종류는 현행 payload 이름을 유지하거나 JOSE typ까지 용도별로 명시한다.
- 추천: A. Access/Refresh는 기존 `token_type`·aud·키 분리·HS256 제한을 유지하고 jti nonblank를 보완한다. 입장/결제는 별도 키·aud·종류 값과 필수 claim을 표로 정한 후 구현한다. 비용은 설정 이름·키 바이트 의미를 하나로 문서화하고 교차 용도 토큰 거절을 검증하는 일이다.
- JOSE typ을 새로 구분한다면 실제 검증기도 함께 바꾼다. 문자열 이름만 바꾸어 [RFC 8725의 배타적 검증](https://www.rfc-editor.org/rfc/rfc8725.html#section-3)을 충족했다고 판단하지 않는다. 운영 키 encoding 변경은 기존 토큰 무효화·교체 계획이 필요하다.
- 통합 전: Access/Refresh 계약은 필수, 입장/결제 세부 값은 해당 구현 전 필수. 관련 역할: 인증 담당·예매 담당·결제 담당.
- 결정: ______

## D05. Redis 장애·타임아웃·기동 준비

- 현상: #48은 블랙리스트 fail-open 잠정이다. 준현은 100ms·서킷 뒤 보호 요청 503/Retry-After:1, 효승은 실패 시 통과한다. 준현은 기동 시 Redis PING 제한·재시도 실패로 기동을 중단한다.
- 선택지: A #48 fail-open 유지 / B 보호 API fail-closed / C API 위험도별 차등. 기동은 제한 재시도 후 실패 또는 기동 후 readiness로 복구 대기다.
- 추천: B와 기존의 제한된 기동 재시도. 로그아웃 Access로 내 정보·예약·관리자 요청이 가능하다는 위험을 줄이고 정책 분기를 적게 둔다. 비용은 Redis 장애 중 보호 API 503과 Redis 없는 cold start 실패다. 별도 결제 토큰만으로 다른 보호 API 위험까지 없어지지는 않는다.
- 가용성을 우선해 A를 선택하면 로그아웃 Access가 최대 Access TTL 동안 통과할 수 있음을 수용한다. 기동 가용성도 원하면 warmup 정책을 함께 바꿔야 한다. C는 경로 누락·분류 유지 비용이 커 첫 통합에는 권하지 않는다.
- 짧은 타임아웃·서킷·복구 검증은 모든 안에 필요하다. 100ms는 측정 전 잠정값이다. 차단 정책만으로 비밀번호 변경 전 Access까지 폐기되지는 않는다.
- 통합 전: 필수. 관련 역할: 인증 담당·Gateway 담당·인프라 담당.
- 결정: ______

## D06. Refresh·로그아웃·비밀번호·관리자 초기화

- 현상: 준현은 사용자당 Refresh 하나·회전 CAS다. 새 로그인은 이전 Refresh를 대체하고 로그아웃은 현재 Access jti와 사용자 Refresh를 폐기한다. 비밀번호 변경/초기화는 Refresh만 폐기하며 이미 발급된 Access는 만료 전 유효하다. seedAdmin은 재기동 시 기존 ADMIN 잠금을 푼다.
- 선택지: 세션 A 단일 Refresh 유지 / B 기기별 세션. Access X 현재 TTL까지 허용 / Y 사용자 단위 즉시 폐기. 관리자 P 재기동 자동 해제 / Q 명시적 운영 복구.
- 추천: A·X·Q를 첫 통합 범위로 제안한다. 기존 회전 로직을 재사용하고 관리자의 잠금 해제를 재기동의 부수 효과에서 분리한다. 비용은 Access 기본 30분의 잔여 유효 위험과 별도 관리자 복구 절차다. 비밀번호 초기화를 탈취 대응 수단으로 보장하려면 X 대신 Y를 택해야 한다.
- Y는 사용자별 토큰 버전/폐기 시각 검증 또는 발급 jti 추적이 필요하다. 조회 비용·Redis 장애 정책·기존 토큰 전환까지 함께 설계한다. B도 저장 키·로그아웃 범위·동시성 테스트를 다시 만든다.
- DB 잠금+Redis Lua는 분산 트랜잭션이 아니다. 실패 후 재로그인·재인증 복구 의미와 임시 비밀번호 일회 응답/no-store·강제 변경 범위를 문서화한다.
- 통합 전: 필수. 관련 역할: 인증 담당·관리자 기능 담당·인프라 담당.
- 결정: ______

## D07. 헤더 신뢰와 외부 노출

- 현상: 현재 SA와 구현은 Gateway 인증 뒤 내부 서비스가 사용자 헤더를 신뢰한다. 서비스 JWT 재검증 ADR 0009는 철회 상태다. 외부 직접 접근이 가능하면 형식상 유효한 헤더로 사칭할 수 있다.
- 선택지: A Gateway 단일 인증+내부 접근 통제 / B 서비스 JWT 재검증 추가 / C 서비스 간 인증까지 별도 도입.
- 추천: 첫 통합은 A를 유지하되 직접 포트 차단·위조 헤더 제거·내부 경로 차단을 실제 HTTP로 입증한다. 격리할 수 없는 배포 환경이면 B를 다시 논의한다. [Spring Security의 헤더 인증 경고](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/web/authentication/preauth/RequestHeaderAuthenticationFilter.html)가 조건부 재검토의 근거다.
- B는 validator 배포·키 관리·중복 검증 비용이 든다. HMAC 검증 키 배포는 발급 권한도 넓히므로 비대칭 서명 비용을 함께 비교한다. 이번 추천만으로 철회 결정을 뒤집지 않는다.
- 통합 전: 필수. 관련 역할: Gateway 담당·인프라 담당·각 서비스 담당.
- 결정: ______

## D08. 라우트 소유권과 공개 범위

- 현상: 준현만 `/admin/users/**`를 User로 우선 전달한다. 다른 브랜치는 admin 전체를 Reservation으로 보낸다. 준현은 notifications 라우트가 없고 공개 공연 GET은 효승보다 좁다.
- 선택지: A 준현 세부 우선순위+SA에 필요한 알림 경로 보완 / B admin 경로를 전면 재설계. 공개 공연은 명시 목록 또는 하위 경로 전체 공개다.
- 추천: A와 명시 목록. 사용자 관리→User, 예매 관리·알림은 SA의 실제 소유 서비스 확인 후 연결한다. 로그인·Refresh·공개 비밀번호 변경의 경로 정책도 User 업무와 묶는다. 비용은 경로·HTTP method·역할·대상 서비스의 표와 라우팅 회귀 유지다.
- 내부 경로·관리자·공개 API 우선순위를 테스트한다. 인증 통합을 사용자별 요청 제한 H1 구현 완료로 표시하지 않는다.
- 통합 전: 필수. 관련 역할: Gateway 담당·User 담당·예매/알림 담당.
- 결정: ______

## D09. DB·Redis 영속성·감사·이벤트

- 현상: PostgreSQL 17/18, 공통/서비스별 계정, Redis AOF/비영속 설정이 다르다. 준현 감사는 시스템 수정 actor를 NULL로 기록하며 효승 표준 감사는 해당 DB 갱신 검증이 없다. Outbox 실행 코드는 어느 브랜치에도 없다.
- 선택지: DB A 서비스별 컨테이너 유지 / B 단일 컨테이너의 DB·role 분리. Redis X 인증 상태 영속 / Y 유실 수용+세션 폐기 대책. 감사는 준현 유지 또는 표준 리스너 보완이다.
- 추천: A·X와 준현 감사 유지, 서비스별 자격 증명 패턴 이식. 최초 PostgreSQL 버전은 기준 17을 유지하고 18 전환은 별도 검증한다. 인증 상태 유실과 감사를 추정에 맡기지 않는 이점이 있다. 비용은 컨테이너 자원·계정 변수·AOF 복구 검증·커스텀 감사 리스너 유지다.
- AOF 설정만으로 내구성을 보장하지 않는다. Redis 재시작 후 블랙리스트·Refresh 복구를 확인한다. B도 DB/role 실제 생성·권한 격리·healthcheck 검증이 필요하다. 기존 17 볼륨을 이름만 바꿔 18에 사용하지 않는다.
- EventEnvelope는 한 타입으로 유지하고 eventId/occurredAt를 최초 생성 때 고정한다. 삭제 조회·Outbox 릴레이/재시도·DLQ는 도메인 후속 작업으로 남긴다.
- 통합 전: 저장소·감사 기본 계약은 필수, Outbox는 후속 범위. 관련 역할: 인프라 담당·공통 담당·각 데이터 소유 서비스 담당.
- 결정: ______

## D10. Config와 테스트 격리

- 현상: Config 필수 import와 test 비활성화 방법, Eureka의 Config 의존, 파일 위치·환경 변수 이름이 다르다. 권우 optional 로컬 .env import는 Config Server optional과 다르다.
- 선택지: A 배포/통합 실행에서 필수 Config, test만 독립 / B 실행 환경에서도 optional. Eureka는 기준의 Config 의존을 유지하거나 별도 부트스트랩 설정으로 분리한다.
- 추천: A와 기존 Eureka 경로 유지. 누락 설정으로 기동하는 것을 막고 단위 테스트만 외부 서비스 없이 실행한다. 비용은 시작 순서·Config 실패 테스트·프로필별 설정 관리다. [공식 Config 동작](https://docs.spring.io/spring-cloud-config/reference/client.html#config-data-import)에 맞춰 검증한다.
- 효승 test resources와 강민 test 프로필 중 한 방식을 정본으로 정한다. Config 파일을 테스트 입력에 포함한다. test 우회가 배포에 적용되지 않는 검증을 남긴다.
- 통합 전: 필수. 관련 역할: 빌드 담당·Config 담당·각 서비스 담당.
- 결정: ______

## D11. 버전·빌드 도구·관측 전환 시점

- 현상: Java/Boot/Cloud는 같지만 Gradle 9.1.0/9.8.2, Groovy/Kotlin DSL, catalog·수집 방식·관리 포트가 다르다. [공식 호환표](https://spring.io/projects/spring-cloud/#overview)상 Boot 4.1.2+Cloud 2025.1.4를 통합 때문에 바꿀 근거는 없다.
- 선택지: A 준현 실행 경로 안정화 후 강민 catalog/convention 별도 전환 / B 첫 통합에서 전환. 관측은 정적 목록 유지 또는 효승 Eureka 동적 수집으로 전환한다.
- 추천: A, 처음에는 정적 수집·관리 9080 유지. 강민 Config 테스트 입력·DB 테스트 패턴은 먼저 이식할 수 있다. 비용은 두 단계 변경이지만 인증 실패와 빌드 전환 실패의 원인을 분리한다.
- 새 wrapper·이미지 버전은 팀 환경·레지스트리 가용성·checksum/digest를 확인한다. 동적 수집 채택 시 Eureka가 제공하는 업무 포트와 관리 포트를 구별하고 실제 target UP을 검증한다.
- 통합 전: 첫 적용 범위는 필수, 도구 전환은 후속 가능. 관련 역할: 빌드 담당·인프라 담당·관측 담당.
- 결정: ______

## D12. 검증·작업 소유권·dev 진입 조건

- 현상: 브랜치별 검증 범위가 다르고 통합본은 아직 없다. 준현 DB/Redis 인증 20건, 전체 스택·장애/복구·외부 노출이 이번 조사에서 미검증이다.
- 선택지: A 통합 담당 1명+영역별 리뷰, 단계별 검증 / B 각 브랜치를 순서대로 통째 병합한 뒤 수정.
- 추천: A. 공통·인증·Gateway·인프라 변경에 소유 역할을 두고 미결정 계약이 남으면 해당 변경을 dev로 넘기지 않는다. 비용은 담당 간 검토와 독립 통합 환경이며, 충돌 해결 책임과 테스트 범위가 분명해진다.
- 기본 CI는 빌드·단위/계약·격리 DB/Redis 통합·Config 실패·기록 검사를 포함한다. 최초 dev PR에는 전체 스택·Redis 장애/복구·cold start·접근 통제 결과도 요구한다. 부하 용량은 별도 검증 전 주장하지 않는다.
- 통합 전: 필수. 관련 역할: 통합 담당·각 영역 리뷰 담당. 상세 순서는 [통합 계획](integration-plan.md)을 따른다.
- 결정: ______

## 정책 결정과 별도로 처리할 수정 체크리스트

아래는 회의 선택지를 늘릴 항목이 아니라 채택 코드가 정해진 계약을 지키도록 수정·회귀 검증할 항목이다. 정적 지적과 실행 재현을 구분한다.

- [ ] JWT 빈 jti 거절, TOKEN_EXPIRED 구분을 계약에 맞춘다. 효승 verifier를 택하면 exp 필수·iss/aud/종류·알고리즘 제한도 보강한다.
- [ ] 입력/반환값 검증의 errors와 HTTP/body status, Allow·Retry-After, null data/traceId·빈 errors 직렬화를 검사한다.
- [ ] trace advice·`/error`가 Actuator/Mock PG에 개입하지 않게 하고 패키지 변경 후 스캔·자동구성 등록·테스트 필터를 정렬한다.
- [ ] 인증 필터·JWT decoder·블랙리스트·서킷 설정·BaseEntity·EventEnvelope의 중복 정본을 제거한다.
- [ ] 채택한 Compose의 `pg_isready`, 실제 DB/role 생성, 외부 bind, 관리 포트 healthcheck·수집 주소를 검증한다.
- [ ] gradlew 실행 권한·wrapper 검증값·Config 테스트 입력·관리자 seed의 합의된 동작을 반영한다.
- [ ] DB/Redis 실패 후 재로그인·재인증 복구와 감사 actor NULL을 실제 인프라에서 검증한다.

## 복사해서 답할 결정 입력칸

선택지 기호와 예외만 먼저 적어도 된다. 미정은 그대로 남긴다.

```text
D01 기준/패키지/모듈명:
D02 공통 모듈 A/B/C:
D03 응답 API/trace/오류명:
D04 JWT 키 encoding/종류 계약:
D05 fail-open/closed/차등 + warmup:
D06 세션 A/B + Access X/Y + 관리자 P/Q:
D07 신뢰 경계 A/B/C:
D08 관리자/알림 소유 + 공개 경로:
D09 DB A/B + Redis X/Y + 감사:
D10 Config/test/Eureka:
D11 빌드·관측 전환 시점:
D12 통합 담당/영역 리뷰/필수 검증:
예외 또는 보류:
```
