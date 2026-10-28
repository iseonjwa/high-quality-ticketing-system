---
title: ISE-47 user-service 계층 정리는 무엇을 왜 바꿨고 어떻게 검증했나
date: 2026-10-28
updated: 2026-10-28
asker: GGangMY
level: 중급
tags: [layered-architecture, dip, refactoring, mutation-testing, user-service, anemic-domain-model]
---

# ISE-47 user-service 계층 정리는 무엇을 왜 바꿨고 어떻게 검증했나

## 질문
이번 내용 파악이 안 됐어. 작성 진행해 주고 리팩토링도 진행해 줘. 그리고 상세히 풀어서 설명해 줘.

배경 개념은 [계층형 아키텍처와 DDD 노트](2026-10-28-layered-architecture-vs-ddd.md)와 [4계층 노트](2026-10-27-four-layer-architecture.md)에 있다.

## 짧은 답
- 리팩터링 전에 **지금 동작을 고정하는 테스트(안전망)** 를 먼저 보강했다. 바꾼 뒤에도 같은 테스트가 통과하면 동작이 그대로라는 근거가 된다.
- **B**: `UserErrorCode`를 application → domain으로 옮겨 infrastructure가 application을 import하던 역방향 의존을 없앴다.
- **C**: `DataIntegrityViolationException`(Spring의 DB 예외)을 잡는 곳을 `AuthService` → `JpaUserRepository`로 옮겼다. 응용 계층이 영속성 기술을 모르게 된다.
- **A**: 잠금 확인, 토큰 발급 가능 여부, 이메일 정규화를 `AuthService`의 private 메서드에서 `User`의 메서드로 옮겼다. 규칙을 그 데이터를 가진 객체가 지킨다.
- 검증: 테스트 28 → 35개 모두 통과, 변이 테스트 7건 모두 실패로 잡힘, `./gradlew build` 통과, 계층 위반 import 0건.

## 자세히

### 0. 순서를 이렇게 잡은 이유

| 순서 | 단계 | 이유 |
|---|---|---|
| 1 | 안전망 테스트 | 리팩터링은 "동작은 그대로, 구조만 바꿈"이다. 그걸 증명할 테스트가 먼저 있어야 한다 |
| 2 | B | 가장 작은 변경(파일 이동 + import 3줄). 이후 C·A가 domain의 `UserErrorCode`를 쓰므로 먼저 해야 한다 |
| 3 | C | B가 끝나야 infrastructure가 `UserErrorCode`를 domain에서 가져올 수 있다 |
| 4 | A | 가장 큰 변경. 앞의 둘이 끝난 깨끗한 상태에서 한다 |
| 5 | `User` 단위 테스트 | 옮긴 규칙을 스프링 없이 직접 검사한다 |

### 1. 안전망 테스트

**찾은 빈 곳**: 중복 가입 테스트(`중복_이메일은_409이고_잘못된_비밀번호는_401이다`)는 이미 있었지만 **HTTP 상태 409만** 확인했다. C에서 변환 위치를 옮기다 실수로 다른 409 코드(예: 공통 `IDEMPOTENCY_KEY_REUSED`)를 던져도 이 테스트는 통과한다.

| 변경 | 지키는 것 |
|---|---|
| 기존 테스트에 `assertEquals("EMAIL_DUPLICATED", code(duplicated))` 추가 | C단계: 에러 코드가 바뀌지 않음 |
| 새 테스트 `대소문자만_다른_이메일도_같은_계정으로_본다`: 대문자로 바꾼 이메일 가입은 409 `EMAIL_DUPLICATED`, 같은 대문자 이메일로 로그인은 200 | A단계: 정규화를 옮겨도 가입·로그인이 같은 규칙을 씀 |

- 앞뒤 공백은 API 테스트에 넣지 않았다. 요청 DTO의 `@Email` 검증이 공백 섞인 값을 400으로 먼저 막을 수 있어서, 정규화까지 도달하는지가 보장되지 않는다. 공백 제거는 `User` 단위 테스트가 맡는다.
- `signup`은 저장 전에 중복을 미리 조회하지 않아서, 순차 중복이든 동시 중복이든 똑같이 DB 유니크 위반 경로를 탄다. 그래도 이슈 완료 기준("같은 이메일 동시 가입이 한 건만 성공")을 직접 보이려고 `같은_이메일_동시_가입은_하나만_성공한다`를 추가했다. 기존 `Refresh_동시_재사용은_하나만_성공한다`와 같은 방식(스레드 2개 + `CountDownLatch`로 동시 출발)으로 응답이 {201, 409}이고 DB 행이 1개인지 본다.

**변이 테스트(mutation testing)로 안전망 확인**: 코드를 일부러 틀리게 바꿔 테스트가 실패하는지 본다. 실패하지 않으면 그 테스트는 그 부분을 지키지 못한다.

| 변이 | 결과 |
|---|---|
| M1 `EMAIL_DUPLICATED` → `IDEMPOTENCY_KEY_REUSED` (둘 다 409) | 강화한 기존 테스트 + 새 테스트 실패 |
| M2 정규화에서 `toLowerCase` 제거 | 새 테스트 실패 |

### 2. B: `UserErrorCode`를 domain으로

```
전: infrastructure.RedisSessionStore ──import──▶ application.UserErrorCode   (역방향, 위반)
후: infrastructure.RedisSessionStore ──import──▶ domain.UserErrorCode        (허용)
    application.AuthService          ──import──▶ domain.UserErrorCode        (허용)
```

- 4계층 + DIP에서 화살표는 모두 domain을 향해야 한다. infrastructure → application은 반대 방향이다.
- 선택지와 고른 이유

| 선택지 | 장점 | 대가 |
|---|---|---|
| **domain으로 이동 (선택, b1)** | 파일 이동 하나로 해결. 업무 에러 코드는 업무 규칙의 일부라 domain에 있어도 자연스럽다 | domain이 공통 모듈의 `ErrorCode` 인터페이스를 import한다(바깥 기술이 아니라 팀 계약이라 `BaseEntity`와 같은 종류로 봄) |
| `global`로 이동 | domain을 더 순수하게 둠 | A에서 `User`가 에러 코드를 던지려면 domain → global 의존이 새로 생긴다 |
| 인프라에서 도메인 예외를 던지고 응용이 변환 | 가장 엄격 | 예외 클래스와 변환 코드가 늘어난다 |

### 3. C: DB 예외 변환을 저장소 구현으로

```java
// 전: AuthService (application)
try { users.save(...); }
catch (DataIntegrityViolationException e) { throw new BusinessException(EMAIL_DUPLICATED); }

// 후: JpaUserRepository (infrastructure)
try { return repository.saveAndFlush(user); }
catch (DataIntegrityViolationException e) { throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED); }
```

- `DataIntegrityViolationException`은 Spring의 영속성 예외다. 응용 계층이 이걸 알면 저장소를 JPA가 아닌 것으로 바꿀 때 서비스도 고쳐야 한다.
- 저장소 인터페이스(`UserRepository.save`)에 "같은 이메일이면 `EMAIL_DUPLICATED`로 실패" 계약을 주석으로 적었다. 서비스는 이 계약만 믿는다.
- **동시 가입 정합성은 그대로다.** 두 요청이 동시에 와도 DB의 `email UNIQUE` 제약이 하나만 통과시킨다. 바뀐 것은 예외를 "어디서 번역하느냐"뿐이다.
- c1(무결성 위반 전부를 이메일 중복으로)을 고른 이유: 지금 `users`에서 저장 중 날 수 있는 무결성 위반은 email UNIQUE뿐이다(role CHECK는 코드가 상수만 넣음). 제약이 늘면 제약 이름으로 나눠야 한다는 점을 `Note:` 주석으로 남겼다.
- `saveAndFlush`는 원래 코드 그대로 두었다. 즉시 SQL을 보내 유니크 위반이 이 `try` 안에서 나게 하려는 것이다. PK가 IDENTITY라 `save`만으로도 INSERT가 바로 나갈 수 있지만, 그 차이는 확인하지 않았고 바꿀 이유도 없어 유지했다.

### 4. A: 규칙을 `User`로

| 전 (`AuthService` private) | 후 (`User`) | 쓰는 곳 |
|---|---|---|
| `validateUnlocked(user)` | `user.ensureUnlocked()` | 인증(`authenticate`), 비밀번호 변경 |
| `validateTokenIssueAllowed(user)` | `user.ensureTokenIssuable()` | 로그인, 토큰 재발급 |
| `normalize(email)` | `User.normalizeEmail(email)` (static) + 생성자에서 자동 정규화 | 조회(로그인·운영자 시드), 저장(생성자) |

- **왜 옮기나**: 이 규칙들은 `User`의 상태(`lockedAt`, `passwordChangeRequired`, `email`)로만 판단한다. 데이터를 가진 객체가 규칙을 지키면 ① 같은 판단이 여러 유스케이스(로그인·재발급·비밀번호 변경)에 흩어지지 않고 ② 스프링 없이 `new User(...)`로 테스트할 수 있다. 빈약한 도메인 모델(getter만 있는 엔티티)에서 한 걸음 나아간 것이다.
- **생성자 정규화**: 전에는 "저장 전에 서비스가 `normalize`를 불러야 한다"는 규칙을 호출하는 쪽이 기억해야 했다. 지금은 `User`를 만들면 항상 정규화되어, 정규화되지 않은 이메일이 저장될 길이 없다(불변식을 객체가 지킴). 조회는 엔티티가 없으니 같은 static 메서드를 쓴다.
- **옮기지 않은 것**: 비밀번호 바이트 제한(8~72바이트)은 BCrypt가 72바이트 넘는 부분을 무시하는 기술 제약이라 서비스에 남겼다(#7 판단 A 부분 수용). 실패 횟수 증가·잠금은 동시 로그인 실패에도 정확해야 해서 원래대로 저장소의 조건부 UPDATE가 맡는다.
- **대가**: domain(`User`)이 `BusinessException`(공통 모듈)을 던진다. 도메인 전용 예외 클래스를 만드는 방법도 있지만, 지금은 공통 예외 처리기가 `BusinessException`을 응답 형식으로 바꿔 주므로 클래스를 늘리지 않았다.

### 5. `User` 단위 테스트 5개 (`UserTest`)

스프링을 띄우지 않는 순수 JUnit 테스트다. 잠금·임시 비밀번호 상태는 실제로는 저장소 UPDATE로만 바뀌므로, 테스트에서는 `ReflectionTestUtils.setField`로 필드를 직접 채웠다.

| 테스트 | 확인 |
|---|---|
| 이메일 정규화 | `"  Kim@Example.COM "` → `kim@example.com` (생성자와 static 둘 다) |
| 정상 계정 | 두 검사 모두 통과 |
| 잠긴 계정 | 두 검사 모두 `ACCOUNT_LOCKED` |
| 임시 비밀번호 계정 | `ensureUnlocked`는 통과, `ensureTokenIssuable`만 `PASSWORD_CHANGE_REQUIRED` (비밀번호 변경은 해야 하므로) |
| 둘 다 | 잠금을 먼저 알림 (기존 순서 유지) |

### 6. 검증 결과 (2026-10-28 실행)

| 확인 | 결과 |
|---|---|
| 테스트 수 | 전 28개(설정 8 + 통합 20) → 후 35개(설정 8 + 통합 22 + 단위 5), 실패 0 |
| 변이 M3 임시 비밀번호 검사 제거 | 3개 실패 (통합 2 + 단위 1) |
| 변이 M4 잠금 검사 제거 | 5개 실패 (통합 3 + 단위 2) |
| 변이 M5 생성자 정규화 제거 | 2개 실패 (통합 1 + 단위 1) |
| 변이 M6 저장소 변환 코드 변경 | 2개 실패 (통합 2) |
| 변이 M7 저장소 변환 제거(DB 예외 그대로) | 3개 실패 (동시 가입 포함 통합 3) |
| `./gradlew build` | 통과 |
| infrastructure → application import | 0건 |
| application → `org.springframework.dao`·JPA import | 0건 |

## 이 프로젝트에서는
- 근거: AI 설계 검증 `#7` 판단(`docs/sa-review/06-layered-architecture-check.md` "판단 (2026-10-27)" A·B·C), SA 13장 의존 표
- 바뀐 파일: `domain/UserErrorCode.java`(이동), `domain/User.java`, `domain/UserRepository.java`, `application/AuthService.java`, `infrastructure/persistence/JpaUserRepository.java`, `infrastructure/security/RedisSessionStore.java`, 테스트 2개
- 작업: Linear ISE-47

## 면접 포인트
- "리팩터링을 어떻게 안전하게 했나요?": 동작을 고정하는 테스트를 먼저 보강했고, 그 테스트가 실제로 잡는지 변이 테스트로 확인했다. 기존 테스트가 상태 코드만 봐서 에러 코드가 바뀌어도 통과하는 빈 곳을 찾아 막았다.
- "예외 변환은 왜 저장소에서?": 응용 계층이 영속성 기술 예외를 알면 계층 규칙(DIP)을 어긴다. 정합성은 DB 유니크 제약이 지키므로 번역 위치만 옮겨도 동시 가입 안전성은 그대로다.
- "도메인이 예외를 던져도 되나요?": 규칙 위반을 알리는 것은 도메인의 책임이다. 대가는 공통 예외 클래스 의존이고, 도메인 전용 예외 + 응용 변환이라는 더 엄격한 대안이 있다.

## 확인할 질문
- user-service는 결제 담당이 M1에서 쓴 코드다. 착수 전에 알리기로 했는데, Slack 초안만 만들고 실제로 보냈는지는 확인하지 못했다. PR 리뷰어로 결제 담당을 지정한다.
- `User.isPasswordChangeRequired()` getter는 이제 main 코드에서 쓰지 않는다. 지울지 남길지(다른 getter와 일관성) 리뷰에서 정한다.
