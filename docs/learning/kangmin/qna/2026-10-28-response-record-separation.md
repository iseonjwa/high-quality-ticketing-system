---
title: AI 검증 #7의 "응답 레코드 분리 거부"는 무엇을 거부한 것인가
date: 2026-10-28
updated: 2026-10-28
asker: GGangMY
level: 중급
tags: [layered-architecture, dto, record, port, yagni]
---

# AI 검증 #7의 "응답 레코드 분리 거부"는 무엇을 거부한 것인가

## 질문
응답 레코드 분리 거부가 무슨 내용이지

## 짧은 답
- 로그인·재발급 API가 도메인 포트 `AuthTokens`의 레코드 `AuthTokens.Pair`를 그대로 HTTP 응답 본문으로 내보낸다.
- AI는 "응답 필드 이름이 도메인 타입에 묶이니 `AuthResult.Tokens` 같은 응답 전용 레코드로 변환하라"고 제안했다(제안 D).
- 지금은 두 레코드의 필드가 완전히 같고 응답 형식을 바꿀 계획도 없어서, 분리하면 클래스 하나와 변환 코드만 늘어난다. 그래서 거부하고 "응답 형식을 바꿀 때 분리한다"는 조건을 남겼다.

## 자세히

### 현재 흐름
```
JwtTokens.issue()  ──new Pair(...)──▶  AuthService.login()/refresh()  ──Pair──▶  AuthController  ──JSON──▶ 클라이언트
(infrastructure)                       (application)                              (presentation)
```
- `AuthTokens.Pair(accessToken, refreshToken, accessTokenExpiresIn)`는 `domain` 패키지의 포트 인터페이스 안에 있다.
- 다른 응답(`SignupResult`·`Me`·`PasswordReset`)은 `application/AuthResult`에 응답용 레코드로 따로 있는데, 토큰 응답만 예외다.

### 선택지 비교
| | 분리한다 (제안 D) | 그대로 둔다 (선택) |
|---|---|---|
| 구조 | `AuthService`가 `Pair`를 `AuthResult.Tokens`로 바꿔 반환 | `Pair`를 그대로 반환 |
| 얻는 것 | 응답 형식과 도메인 포트를 따로 바꿀 수 있다. 다른 응답들과 모양이 같아진다 | 클래스·변환 코드가 없다 |
| 잃는 것 | 필드가 같은 레코드 하나와 매핑 코드가 생긴다 | 응답 필드 이름을 바꾸려면 도메인 포트를 고쳐야 한다 |

### 고른 이유
- 두 레코드가 갈라질 이유가 지금 없다. 필드 세 개가 1:1로 같고 응답 형식(SA 6장, `#19`)은 이미 확정됐다.
- 결합이 문제가 되는 것은 한쪽만 바꾸고 싶을 때다. 그 시점이 오면 레코드 하나와 메서드 두 개만 고치면 되어 나중에 분리하는 비용이 작다. 이런 판단을 YAGNI(You Aren't Gonna Need It, 필요할 때 만든다)라고 한다.
- 같은 검증에서 의존 방향 위반 두 곳은 고치기로 했다. 그쪽은 SA 13장 규칙을 직접 어기는 문제이고, 이쪽은 규칙 위반이 아니라 "나중에 바꾸기 어려워질 수 있다"는 예방 제안이라 무게가 다르다.

### 감수한 점
- 응답에 필드를 더하거나 이름을 바꾸면(예: `tokenType: "Bearer"` 추가) 도메인 포트가 바뀌고 `JwtTokens`까지 영향을 받는다.
- 응답 레코드가 `application`과 `domain` 두 곳에 흩어져 있어 처음 보는 사람이 헷갈릴 수 있다.

### 다시 열 조건
- 토큰 응답 형식을 바꿀 때(필드 추가, 이름 변경, 리프레시 토큰을 쿠키로 옮김 등)
- 도메인 쪽 `Pair`에 응답으로 내보내면 안 되는 값이 생길 때

## 이 프로젝트에서는
- 레코드: `user-service/src/main/java/com/iseonjwa/user/domain/AuthTokens.java` 7행
- 반환: `user-service/src/main/java/com/iseonjwa/user/application/AuthService.java` 71행(`login`), 115행(`refresh`)
- 응답: `user-service/src/main/java/com/iseonjwa/user/presentation/AuthController.java`의 `/auth/login`, `/auth/refresh`
- 검증 원문: `docs/sa-review/06-layered-architecture-check.md` 3번 점검 항목, 제안 D
- 판단 기록: `docs/ai-judgments/kangmin/2026-10-27-layered-architecture-check7.md`

## 면접 포인트
- "DTO와 도메인 객체를 항상 분리해야 하나요?" → 엔티티는 지연 로딩, 노출하면 안 되는 필드, 변경 전파 때문에 분리하는 게 기본이다. 불변 값 레코드이고 필드가 응답과 완전히 같다면, 갈라질 이유가 생길 때까지 미루는 것도 근거 있는 선택이다. 중요한 건 "언제 분리할지" 조건을 정해 두는 것이다.
- "그럼 왜 다른 응답은 분리했나요?" → `SignupResult`·`Me`는 `User` 엔티티에서 일부 필드만 골라 내보내야 해서 분리가 필수였다. `Pair`는 처음부터 응답과 같은 모양의 값 객체다.

## 참고
- 코드 확인: 위 경로를 10/28에 읽어 확인했다. `Pair`를 쓰는 곳은 `JwtTokens`·`AuthService`·`AuthController` 세 곳뿐이다.
