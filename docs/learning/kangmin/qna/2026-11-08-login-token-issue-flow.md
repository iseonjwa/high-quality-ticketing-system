---
title: 로그인 토큰은 어디서 발급되고, 측정용으로 대량으로 받으려면 무엇을 거치나
date: 2026-11-08
updated: 2026-11-08
asker: kangmin
level: 기초
tags: [jwt, access-token, refresh-token, user-service, gateway, load-test]
---

# 로그인 토큰은 어디서 발급되고, 측정용으로 대량으로 받으려면 무엇을 거치나

## 질문
(측정용 토큰 준비 이야기 중) 로그인 토큰 발급? 어디서?

## 짧은 답
- **user-service의 `POST /api/v1/auth/login`**이 발급한다. 이메일·비밀번호를 확인하고 Access 토큰(30분)과 Refresh 토큰(14일)을 함께 준다.
- 서명은 `JwtTokens`(`user-service/.../infrastructure/security/JwtTokens.java`)가 HMAC 키 `JWT_ACCESS_SECRET`으로 한다.
- 검증은 Gateway(`GatewayAuthenticationFilter`)가 같은 키로 한다. 그래서 이 키를 가진 쪽은 로그인 없이도 유효한 토큰을 만들 수 있고, k6의 `TOKEN_MODE=sign`이 바로 그 방식이다.

## 자세히

### 1. 발급 흐름 (코드로 확인)
```
클라이언트 → Gateway → user-service POST /api/v1/auth/login {email, password}
  AuthService.login
    ① authenticate: 이메일로 사용자 조회, 비밀번호 해시 비교
    ② 트랜잭션 안에서 사용자 행 잠금, 토큰 발급 가능 상태인지 확인(잠금·비밀번호 변경 필요 등)
    ③ 로그인 실패 횟수 초기화
    ④ JwtTokens.issue → Access·Refresh 서명
    ⑤ sessions.save → Refresh 토큰을 Redis 세션에 저장(14일)
  ← { accessToken, refreshToken, expiresIn }
```

| 토큰 | 유효 시간 | 키 | 쓰는 곳 |
|---|---|---|---|
| Access | 1,800초(30분) | `JWT_ACCESS_SECRET` | 요청마다 `Authorization: Bearer` |
| Refresh | 1,209,600초(14일) | `JWT_REFRESH_SECRET`(Access와 달라야 기동됨) | `POST /api/v1/auth/refresh`로 새 Access 받기 |

값은 `config-repo/user-service.yml`의 `auth.access-seconds`·`refresh-seconds`다.

### 2. 다른 토큰과 구분
| 토큰 | 발급 | 검증 | 키 |
|---|---|---|---|
| 로그인 Access | user-service 로그인 | Gateway | `JWT_ACCESS_SECRET` |
| 입장 토큰 | reservation-service 대기열 토큰 API(READY 뒤) | Gateway `EntryTokenFilter`(좌석 조회·선점 앞) | `ENTRY_TOKEN_SECRET` |
| 결제 토큰 | reservation-service 선점 성공 때 | payment-service | 결제 토큰 키(ADR 0025) |

### 3. 측정용으로 대량으로 받을 때
- 방법: 사용자마다 `POST /api/v1/users`(가입) → `POST /api/v1/auth/login` → 응답의 `accessToken`을 파일에 쓴다. ISE-201이 이 흐름이다.
- 비용: 로그인은 비밀번호 해시 비교(BCrypt 계열, TS-11)를 하는데, 이 계산은 일부러 느리게 만든다. 그래서 수만 명을 짧은 시간에 로그인시키면 user-service CPU가 먼저 바쁘다. 얼마나 걸리는지는 재지 않았다(확인할 질문).
- 만료: Access가 30분이라, 받은 뒤 30분 안에 측정을 끝내야 한다. 길어지면 Refresh로 다시 받는다.
- 추첨: 응모는 Access 토큰만 쓴다. L2(응모자 2,000명)는 이 방식으로 충분할 것으로 보인다. L1(약 10만 명)은 사용자 수를 줄이거나 재사용해야 한다.

## 이 프로젝트에서는
- 로컬 k6는 서버와 같은 키로 직접 서명해 로그인 단계를 건너뛴다. 배포 서버에서는 그 키가 운영 키라 발생기에 두지 않는 쪽이 팀 문서(ADR 0046, ISE-201)의 방향이다.

## 면접 포인트
- 발급(user-service)과 검증(Gateway)이 같은 대칭 키를 쓰면, 키 하나가 "토큰을 만들 수 있는 권한"이 된다. 측정 도구에 키를 넣지 않는 이유다.
- Refresh를 Redis에 저장해 로그아웃·비밀번호 변경 때 무효화할 수 있다.

## 참고
- `user-service/src/main/java/com/iseonjwa/user/presentation/AuthController.java`
- `user-service/src/main/java/com/iseonjwa/user/application/AuthService.java` `login`
- `user-service/src/main/java/com/iseonjwa/user/infrastructure/security/JwtTokens.java`
- `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`
