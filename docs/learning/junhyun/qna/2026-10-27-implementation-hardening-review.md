---
title: 현재 구현에서 어떤 부분을 먼저 보강하면 좋은가
date: 2026-10-27
updated: 2026-10-27
asker: Junhyun An
level: 중급
tags: [code-review, authentication, concurrency, redis, configuration]
---

# 현재 구현에서 어떤 부분을 먼저 보강하면 좋은가

## 질문

> 그리고 구현상 보강할 부분도 있을까?

## 짧은 답

현재 구현한 M1 인증·공통 기반에서는 로그인과 비밀번호 초기화의 경합, 잠금 상태와 Refresh 재발급의 조합을 먼저 확인할 만하다.
기동 직후 Redis 연결 준비와 잘못된 인증 설정을 기동 시 거절하는 검증도 보강 후보이다.
세 가지 분기는 실제 서비스 클래스로 실행했고, Redis 첫 요청 실패는 기존 관찰 기록과 현재 설정을 확인했다.
이번 작업은 검토와 진단이다. 제품 코드 수정이나 새 설계 채택으로 기록하지 않는다.

## 후속 보강

사용자가 "해당 사항들 다 보강하고, 각각을 보강할때 커밋해줘"라고 요청해 구현을 진행한다. 아래 자세히와 최초 검증은 보강 전의 조사 기록이다.

| 항목 | 보강 상태 |
|---|---|
| 비밀번호 초기화·변경과 로그인 경합 | 사용자 행 잠금·최신 해시 재확인 적용. 실제 PostgreSQL·Redis·HTTP 통합 테스트 18개 통과 |
| 잠금 상태와 Refresh 정책 | 활성 세션 확인 뒤 잠금 423·변경 필요 403. 거절 시 미회전, 폐기 토큰 401 우선 처리. 통합 테스트 20개 통과 |
| Gateway Redis 연결 준비 | 준비 완료 전 PING, 제한 재시도·실패 시 기동 중단. Gateway 테스트 42개와 실제 재기동의 첫 보호 요청 확인 |
| 인증 설정 기동 검증 | 진행 전 |

구현 판단은 [수용 기록](../../../ai-judgments/junhyun/2026-10-27-auth-hardening.md), 동시성 범위는 [ADR 0010](../../../decisions/0010-auth-session-serialization.md)에 있다.

## 자세히

### 1. 비밀번호 초기화와 로그인 경합

[AuthService.login](../../../../user-service/src/main/java/iseonjwa/user/application/AuthService.java)은 사용자를 읽고 비밀번호를 비교한 뒤 토큰을 발급해 Refresh를 저장한다.
[resetPassword](../../../../user-service/src/main/java/iseonjwa/user/application/AuthService.java)는 Refresh를 지운 뒤 비밀번호를 갱신한다. 서로 다른 저장소·트랜잭션의 단계를 묶는 동시성 제어는 없다.

다음 순서를 단위 진단으로 확인했다.

1. 로그인이 기존 사용자 상태와 비밀번호 해시를 읽고 비밀번호 비교에서 대기한다.
2. 관리자 초기화가 Refresh 폐기와 비밀번호 변경을 끝낸다.
3. 먼저 시작한 로그인은 이전 사용자 스냅샷으로 검사를 마치고 토큰 발급·Refresh 저장을 실행한다.

진단에서는 `revokeRefresh` → `updatePassword` → `save` 호출 순서와 로그인 성공 반환을 확인했다. 저장소·세션은 mock이므로 실제 DB와 Redis에 저장된 상태까지 확인한 것은 아니다.
실제 JPA 경로는 사용자 조회 뒤 별도 트랜잭션의 네이티브 UPDATE를 사용한다. DB·HTTP를 포함한 경합 테스트로 이 순서와 보안 요구를 검증하는 것을 우선 제안한다.

초기화가 끝난 뒤 이전 자격 정보로 새 세션이 살아나지 않아야 한다면, 인증 상태와 세션 발급·폐기의 일관성을 보장하는 제어가 필요하다.
재조회 한 번만 추가해도 조회 뒤의 경합 창은 남을 수 있으므로, 사용자별 동시성 제어나 버전·폐기 확인 방식의 보장 범위를 정해야 한다.
이 순서에서 토큰 발급 시각은 초기화 뒤가 될 수 있으므로 단순 `revoked-after` 시간 비교만으로 해결된다고 단정하지 않는다.

### 2. 잠금 상태와 Refresh 재발급 정책

`login`은 잠금과 비밀번호 변경 필요 상태를 확인한다. 반면 [refresh](../../../../user-service/src/main/java/iseonjwa/user/application/AuthService.java)는 사용자를 찾은 뒤 이 상태를 확인하지 않고 토큰 발급·회전을 수행한다.
잠긴 User와 유효하다고 가정한 Refresh를 전달했을 때 `issue`와 `rotate`가 실행되는 것을 단위 진단으로 확인했다.

기존 통합 테스트에는 로그인 잠금, 관리자 초기화 뒤 Refresh 폐기, Refresh의 동시 재사용 거절이 있다. 잠긴 계정의 기존 Refresh 재발급 조합은 없다.
다음 정책을 먼저 구분해야 한다.

- 잠금이 신규 비밀번호 로그인만 막는 정책이면 기존 세션 재발급 허용은 의도한 동작일 수 있다.
- 잠금 중 신규 토큰 발급까지 금지한다면 Refresh 경로에도 상태 검사와 회귀 테스트가 필요하다.

[ADR 0007](../../../decisions/0007-login-lockout-admin-reset.md)은 잠긴 계정의 비밀번호 로그인 거절과 기존 Access의 만료 전 유효함을 명시한다. Refresh의 잠금 정책은 별도로 확정할 필요가 있다.
따라서 현재 동작을 정책 확인 없이 무조건 취약점이나 버그로 단정하지 않는다.

### 3. Gateway 기동 직후 Redis 연결 준비

[ADR 0005](../../../decisions/0005-auth-redis-fail-closed.md)에 Gateway 첫 인증 요청이 Redis 연결 수립 때문에 100ms를 넘겨 503이 된 관찰이 있다. 이번에는 재기동·HTTP로 재현하지 않았다.
현재 [GatewayAuthenticationFilter](../../../../gateway/src/main/java/iseonjwa/gateway/security/GatewayAuthenticationFilter.java)는 Redis 조회에 100ms를 적용하며, 앱 내부에서 기동 시 연결을 준비하는 코드는 찾지 못했다.

Compose는 앱의 `/actuator/health/readiness`를 확인한다. Spring Boot는 기본 readiness 그룹에 Redis 같은 외부 의존 상태를 자동으로 포함하지 않는다.
Redis 컨테이너 자체의 PING 성공과 Gateway의 연결·명령 준비는 구분해야 한다.

기동 준비 단계에서 앱이 쓰는 Redis 연결을 예열하고 첫 보호 요청까지 확인하는 보강을 제안한다. 장애 시 요청 차단과 짧은 요청 제한 시간은 기존 정책대로 유지한다.
Redis의 계속된 장애를 전체 앱 readiness에 반영할지는 공개 API 가용성까지 영향을 주므로 별도 판단이다. liveness에 외부 의존을 무조건 넣지 않는다.

### 4. 인증 설정의 기동 시 검증

[JwtTokens](../../../../user-service/src/main/java/iseonjwa/user/infrastructure/security/JwtTokens.java)는 Access·Refresh 키 분리와 키 길이를 검사한다. 만료 초가 양수인지 검사하는 코드는 없다.
`access-seconds=-1`로 실제 `JwtTokens`를 만들면 생성자는 성공하고, `issue` 호출에서 `expiresAt must be after issuedAt` 예외가 발생했다.

토큰 만료 시간과 로그인 실패 제한처럼 양수여야 하는 값은 생성자 검증 또는 `@ConfigurationProperties`와 Bean Validation으로 기동 시 거절하는 것을 제안한다.
이 진단은 생성·발급 시점을 확인한 것이며, 잘못된 설정으로 전체 서버를 띄워 HTTP 응답까지 확인하지는 않았다.

## 이 프로젝트에서는

검토 범위는 이미 구현한 User 인증, Gateway 인증·설정, 공통 계약과 관련 테스트이다. 아직 없는 예매·결제·Outbox의 업무 구현을 완료된 기능처럼 평가하지 않는다.
현재 설계는 [ADR 0008](../../../decisions/0008-internal-trust-and-auth-design.md)의 SA 유지 결정을 따른다.

비밀번호 초기화·잠금 직후 기존 Access를 즉시 무효화하는 A3는 SA 확인 뒤로 보류돼 있다. 이번 검토가 A3나 새 서명 방식의 적용 승인으로 이어지는 것은 아니다.
잠금의 Refresh 적용 범위와 로그인·초기화 경합의 보장 수준을 정한 뒤 해당 동작을 고친다. 연결 준비와 설정 검증은 기존 인증 모델 안에서 보강할 수 있다.

## 최초 검토의 검증 근거와 한계

Java 21과 저장소의 실제 `AuthService`, `JwtTokens` 클래스를 사용했다. 제품 코드는 수정하지 않았다.
임시 Gradle init script로 `:user-service:classes`와 테스트 classpath를 준비하고, 저장소·세션·비밀번호 비교를 Mockito로 제어한 진단 프로그램을 실행했다.

실행 명령:

```text
python3 /tmp/iseonjwa-implementation-review-wgxujttm/run.py
```

최종 실행은 종료 코드 0이며 다음 세 결과를 출력했다.

```text
PROBE 1: locked user + valid refresh -> issue() and rotate() both called
PROBE 2: password reset completed -> earlier login still calls issue() and save()
PROBE 3: access-seconds=-1 -> constructor accepts; issue() throws expiresAt must be after issuedAt
```

진단 소스와 로그는 같은 임시 디렉터리에 있다. 현재 장비의 재현 자료이며 저장소에 포함한 자동 테스트는 아니다.
HTTP·PostgreSQL·Redis 통합 재현, Redis 첫 요청 실패의 재관찰, 전체 빌드·테스트, 운영 환경 검증은 이번에 수행하지 않았다.

## 면접 포인트

- Refresh 자체의 원자적 회전과 로그인·비밀번호 초기화 사이의 일관성은 다른 경합이다. 저장소별 원자 연산 하나만으로 전체 흐름의 일관성이 보장되지는 않는다.
- readiness 성공이 모든 외부 연결의 준비를 자동으로 보장하지 않는다. 실제 의존과 가용성 정책을 보고 어떤 준비 조건을 반영할지 정한다.

## 참고

- [Spring Boot 4.1.2 Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html): readiness와 외부 의존성 확인의 선택·주의점.
- [Spring Boot 설정 검증](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation): `@ConfigurationProperties`, `@Validated`, Bean Validation을 통한 설정 검증.
- [Redis PING](https://redis.io/docs/latest/commands/ping/): Redis 연결과 응답 확인.
- [AuthIntegrationTest](../../../../user-service/src/test/java/iseonjwa/user/AuthIntegrationTest.java): 현재 로그인·Refresh·초기화 통합 테스트의 범위.
