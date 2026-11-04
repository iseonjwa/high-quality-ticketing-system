# AI 설계 검증 #7: 계층형 아키텍처 (M1 코드 기준)

> 2026-10-27 · 실행: Claude Code가 저장소 코드를 직접 읽고 SA 14장 #7 프롬프트의 네 질문에 답했다.
> 기준 커밋: dev `f6df9f1` · 관련: SA 13장(4계층, 결정 기록 #22), SA 14장 #7
> 상태: **판단 완료(2026-10-27, kangmin). 부분 수용.** 판단 기록: [docs/ai-judgments/kangmin/2026-10-27-layered-architecture-check7.md](../ai-judgments/kangmin/2026-10-27-layered-architecture-check7.md)

## 대상과 범위

- 프롬프트는 Reservation 서비스를 대상으로 한다. 그런데 M1 시점의 Reservation에는 계층별 빈 패키지(`concert`·`queue`·`reservation`·`lottery` × `presentation`·`application`·`domain`·`infrastructure`, `package-info.java` 16개)만 있고 클래스가 없다.
- 그래서 4계층으로 실제 구현된 유일한 서비스인 **user-service**(클래스 16개)에 네 질문을 그대로 적용했다. Reservation은 패키지 구조만 SA 13장과 대조했다.
- Reservation 선점 코드가 생기면(M2, ISE-7 등) 같은 프롬프트로 한 번 더 실행해야 한다. 발제 필수 항목이 겨냥하는 곳은 선점 규칙이기 때문이다.

## 실행한 프롬프트 (SA 14장 원문)

```
Reservation 서비스를 표현/응용/도메인/인프라 4계층으로 설계했어. (계층별 클래스와 책임 붙여넣기)
1) 좌석 선점 가능 여부, 선점 만료 규칙 같은 비즈니스 규칙이 응용 계층으로 새지 않고 도메인 계층에 모여 있는지
2) Redis·Kafka 접근이 인프라 계층에 격리되고, 도메인·응용 계층은 인터페이스에만 의존하는지 (의존 역전)
3) 표현 계층 DTO와 도메인 엔티티가 직접 결합되어 있지 않은지
4) ReservationService가 너무 많은 책임을 가진 Fat Service가 아닌지
검토해줘.
```

## user-service 계층 구성

| 계층 | 클래스 | 책임 |
|---|---|---|
| presentation | `AuthController`, `AdminUserController`, `AuthDtos` | 요청 검증, 응답 감싸기 |
| application | `AuthService`, `AuthResult`, `UserErrorCode` | 가입·로그인·재발급·로그아웃·비밀번호 변경·초기화·내 정보·운영자 시드, 트랜잭션 경계 |
| domain | `User`(엔티티), `UserRepository`·`AuthTokens`·`AuthSessions`(인터페이스) | 사용자 상태, 저장·토큰·세션 포트 |
| infrastructure | `JpaUserRepository`·`SpringDataUserRepository`, `JwtTokens`, `RedisSessionStore` | JPA, JWT 발급·검증, Redis 세션·블랙리스트 |

## 검토 결과

| # | 질문 | 판정 | 요약 |
|---|---|---|---|
| 1 | 비즈니스 규칙이 도메인에 모였나 | **부분 충족** | 규칙 다수가 `AuthService`에 있고 `User`는 거의 getter뿐(빈약한 도메인 모델) |
| 2 | Redis·Kafka가 인프라에 격리되고 의존 역전이 되었나 | **대체로 충족, 위반 2곳** | 포트(인터페이스)를 도메인에 두고 인프라가 구현함. 인프라 → 응용 의존 1곳, 응용의 영속 기술 예외 의존 1곳 |
| 3 | 표현 DTO와 도메인 엔티티가 분리됐나 | **대체로 충족, 1곳** | 엔티티는 노출되지 않음. 토큰 응답은 도메인 레코드 `AuthTokens.Pair`를 그대로 응답 본문으로 씀 |
| 4 | Fat Service인가 | **경계선** | `AuthService` 239줄, 공개 메서드 8개. 인증·프로필 조회·관리자 기능·기동 시드를 한 클래스가 맡음 |

### 1. 비즈니스 규칙의 위치 — 부분 충족

`User`(`user-service/.../domain/User.java`)는 상태 판단이 `isLocked()` 하나뿐이고 나머지는 getter다. 규칙은 `AuthService`에 있다.

| 규칙 | 현재 위치 | 도메인으로 옮길 후보 |
|---|---|---|
| 잠금·비밀번호 변경 필요 시 토큰 발급 불가 | `AuthService.validateTokenIssueAllowed` (198행) | `User.canIssueToken()` 또는 `User.ensureTokenIssuable()` |
| 비밀번호 바이트 길이 8~72 | `AuthService.validateNewPassword`·`validatePasswordBytes` (223~234행) | 비밀번호 정책 값 객체 |
| 이메일 정규화(공백 제거, 소문자) | `AuthService.normalize` (219행) | `Email` 값 객체 또는 `User` 생성자 |
| 운영자 계정은 ADMIN 역할이어야 함 | `AuthService.seedAdmin` (150행) | 운영자 생성 팩터리 |

예외로 볼 수 있는 것: 로그인 실패 횟수와 잠금은 `UserRepository.recordLoginFailure`의 UPDATE로만 바꾼다. 코드 주석대로 동시 로그인 시도에도 횟수가 정확해야 해서 엔티티 메서드가 아니라 DB 원자 갱신을 고른 것이다(ADR 0007). 규칙이 저장소 쿼리에 들어간 대신 정합성을 얻었다.

### 2. 의존 역전과 인프라 격리 — 대체로 충족

- 충족: 응용 계층은 `UserRepository`·`AuthTokens`·`AuthSessions` 인터페이스(도메인 패키지)에만 의존하고, Redis(`StringRedisTemplate`)와 JWT 라이브러리는 `infrastructure`에서만 import한다. Kafka는 user-service에 없다.
- 위반 1: `infrastructure/security/RedisSessionStore.java`가 `com.iseonjwa.user.application.UserErrorCode`를 import한다(5행). 인프라가 응용 계층에 의존하는 역방향이다. SA 13장의 "infrastructure → domain 인터페이스 구현"과 어긋난다. 에러 코드를 도메인으로 옮기거나, 인프라는 도메인 예외를 던지고 응용 계층이 에러 코드로 바꾸는 방법이 있다.
- 위반 2: `AuthService.signup`이 Spring의 `DataIntegrityViolationException`을 잡아 이메일 중복으로 바꾼다(66행). 응용 계층이 영속 기술의 예외 종류를 안다. `UserRepository.existsByEmail`로 먼저 확인하는 방식은 동시 가입에서 경쟁이 생기므로, 저장소 구현이 중복을 도메인 예외(예: `DuplicateEmailException`)로 바꿔 던지는 쪽이 낫다.
- 참고: `PasswordEncoder`(spring-security-crypto)를 응용 계층이 직접 쓴다. 해시 알고리즘을 바꿀 일이 없다면 그대로 두는 것이 단순하다.

### 3. DTO와 엔티티 분리 — 대체로 충족

- 충족: 요청은 `presentation/AuthDtos`, 응답은 `application/AuthResult`(`SignupResult`·`Me`·`PasswordReset`) 레코드로 나눴다. `User` 엔티티는 응답으로 나가지 않는다.
- 1곳: 로그인·재발급은 `AuthService`가 돌려준 `AuthTokens.Pair`(도메인 포트의 레코드)를 컨트롤러가 그대로 응답 본문에 넣는다(`AuthController` 37·42행). 응답 필드 이름(`accessTokenExpiresIn`)이 도메인 타입에 묶여, 응답 형식을 바꾸려면 도메인 포트를 고쳐야 한다. `AuthResult.Tokens` 같은 응답용 레코드로 변환하면 풀린다.

### 4. Fat Service — 경계선

- `AuthService`는 239줄, 공개 메서드 8개(`signup`, `login`, `changePassword`, `resetPassword`, `refresh`, `logout`, `me`, `seedAdmin`)다.
- 책임이 네 갈래로 섞여 있다: 인증 흐름(login·refresh·logout·changePassword), 회원가입, 관리자 기능(resetPassword), 기동 시드(seedAdmin), 조회(me).
- 지금 크기로는 읽기 어려운 수준은 아니다. 다만 M2 이후 회원 기능이 늘면 `AuthService`(인증), `UserService`(가입·조회), `AdminUserService`(초기화·시드)로 나누는 것을 제안한다.

### Reservation 패키지 구조 (SA 13장 대조)

- 기능 모듈(`concert`·`queue`·`reservation`·`lottery`)마다 4계층 패키지가 있어 SA 13장 구조와 같다.
- 서비스 루트에 `global/`(설정, 로컬 확인용 컨트롤러)과 `infrastructure/messaging/LocalMessagingProbe`(local 프로필 전용 Kafka 확인)가 있다. 기능 모듈 밖의 인프라 패키지는 SA 13장 그림에 없다. 진단용이면 `global/`로 옮기거나, 공통 인프라 위치로 SA에 적어 두는 것이 좋다.

## 판단 (2026-10-27)

| # | AI 제안 | 판단 | 근거 | 후속 |
|---|---|---|---|---|
| A | 규칙을 도메인으로 옮기기 | **부분 수용** | 토큰 발급 가능 여부(잠금·비밀번호 변경 필요)와 이메일 정규화는 `User`로 옮겨 단위 테스트를 쉽게 한다. 비밀번호 72바이트 제한은 BCrypt 기술 제약이라 서비스에 둔다 | user-service 담당과 맞춰 수정 |
| B | `RedisSessionStore`의 응용 계층 의존 제거 | **수용** | 인프라 → 응용 방향은 SA 13장 의존 방향을 직접 어긴다. 에러 코드를 도메인 쪽으로 옮기면 되는 작은 수정이다 | 같음 |
| C | 이메일 중복을 도메인 예외로 | **수용** | 응용 계층이 DB 기술 예외를 알면 안 된다. 저장소 구현에서 바꿔 던져도 DB 유니크 제약으로 동시 가입 정합성은 유지된다 | 같음 |
| D | 토큰 응답용 레코드 분리 | **거부** | 응답 형식과 포트 필드가 지금 같고 바꿀 계획이 없다. 분리하면 클래스와 변환 코드만 늘어난다. 응답 형식을 바꿀 때 분리한다 | 없음 |
| E | `AuthService` 분리 | **보류** | 239줄·공개 메서드 8개는 아직 읽기 어려운 크기가 아니다. 회원 기능이 늘면(M2 이후) 인증·회원·관리자로 나눈다 | 회원 기능 추가 때 재검토 |
| F | Reservation 루트 `infrastructure/messaging` 위치 정리 | **수용** | 기능 모듈 밖 인프라 패키지는 SA 13장 그림에 없다. 예매 코드 구조를 정할 때 함께 정리한다 | 예매 담당에게 넘김 |
| G | Reservation 선점 코드가 생기면 #7 재실행 | **수용** | 발제 필수 항목이 겨냥하는 선점 규칙을 이번에는 검증하지 못했다 | 선점 코드 작성 뒤 재실행 |

A~C는 다른 팀원이 작성한 user-service 코드를 고치는 일이라 실제 수정은 담당과 맞춘다.

## 판단할 때 쓴 선택지

판단 전에 제시한 선택지다. 발제 기준(거부 1건 이상)은 다른 항목으로 이미 채웠으므로 실제로 고칠지를 기준으로 골랐다.

| # | AI 제안 | 선택지 |
|---|---|---|
| A | 토큰 발급 가능 여부·비밀번호 정책·이메일 정규화를 도메인(`User`, 값 객체)으로 옮긴다 | 수용(M2 전 리팩터링) / 부분 수용 / 거부(지금 규모에서는 서비스에 두는 편이 단순) |
| B | `RedisSessionStore`의 응용 계층 의존 제거 | 수용 / 거부 |
| C | 이메일 중복을 도메인 예외로 바꾸고 응용 계층에서 `DataIntegrityViolationException` 제거 | 수용 / 거부 |
| D | 토큰 응답용 레코드 분리 | 수용 / 거부 |
| E | `AuthService` 분리 | 지금 수용 / 회원 기능이 늘면 분리(보류) / 거부 |
| F | Reservation 루트의 `infrastructure/messaging` 위치 정리 | 수용 / 거부 |
| G | Reservation 선점 코드가 생기면 #7 재실행 | 수용 / 거부 |
