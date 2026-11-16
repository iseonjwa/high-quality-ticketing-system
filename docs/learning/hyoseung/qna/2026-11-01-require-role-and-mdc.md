---
title: @RequireRole은 무엇을 막나, Gateway와 어떻게 나눠 맡나, MDC는 무엇인가
date: 2026-11-01
asker: hyos1
level: 기초
tags: [gateway, authorization, require-role, interceptor, mdc, tracing]
---

# @RequireRole은 무엇을 막나, Gateway와 어떻게 나눠 맡나, MDC는 무엇인가

## 질문
`@RequireRole`의 "Gateway가 ADMIN 경로를 막는다"는 무슨 뜻인가? 직접 호출을 막는 것인가? `SecurityContextHolder`의 role이 ADMIN인지 보는 것인가? 그리고 `MDC`는 무엇인가?

## 답
### 이 프로젝트에는 서비스 안에 Spring Security가 없다
인증(JWT 검증)은 Gateway가 한 번만 한다(SA 7-2, ADR 0008). 서비스는 Gateway가 넘겨준 헤더를 믿는다. 그래서 `SecurityContextHolder.setAuthentication()`은 쓰지 않고, 요청 속성(request attribute)에 사용자 정보를 둔다.

```
클라이언트 ──JWT──▶ Gateway ──X-User-Id, X-User-Role 헤더──▶ reservation-service
```

### 단계별로 누가 무엇을 하나
| 단계 | 위치 | 하는 일 |
|---|---|---|
| 1 | Gateway `GatewayAuthenticationFilter` | 클라이언트가 보낸 `X-User-Id`·`X-User-Role` 헤더를 **항상 지운다**(위조 방지). JWT를 검증하고, 토큰 안의 값으로 헤더를 다시 넣는다 |
| 2 | Gateway `AccessPolicy` | `/api/v1/admin/**` 경로는 ADMIN 토큰만 통과시킨다 → 여기서 1차로 막는다 |
| 3 | 서비스 `GatewayIdentityFilter`(common) | 헤더를 읽어 요청 속성 `currentUserId`·`currentUserRole`에 넣는다 |
| 4 | 서비스 `RequireRoleInterceptor`(common) | 컨트롤러에 `@RequireRole(ADMIN)`이 있으면 `currentUserRole`이 ADMIN인지 본다. 없으면 401, 다르면 403 |

`@RequireRole`은 4번이다. Gateway가 이미 막지만 서비스에서 **한 번 더** 확인하는 이중 확인(심층 방어)이다. Gateway 경로 설정이 실수로 바뀌거나 새 관리자 경로가 정책에서 빠져도 서비스가 막는다.

### 직접 호출을 막는가? → 아니다
서비스 포트로 직접 요청하면서 `X-User-Role: ADMIN` 헤더를 손으로 넣으면 `@RequireRole`은 통과한다. 헤더 값만 보기 때문이다. 직접 호출을 막는 것은 **네트워크 격리**다. 서비스 포트를 외부에 열지 않고 Gateway만 연다(ADR 0008 T1, SA 9-2). 실제로 테스트 코드도 Gateway 없이 헤더를 직접 붙여 서비스를 부른다.

### MDC (Mapped Diagnostic Context)
로그용 "요청별 메모장"이다. 스레드마다 따로 있는 Map이라서 한 요청을 처리하는 동안 넣어 둔 값을 어디서든 꺼낼 수 있다.

- 이 프로젝트는 `spring-boot-starter-zipkin`(Micrometer Tracing)이 요청마다 `traceId`를 만들어 MDC에 자동으로 넣는다.
- `MDC.get("traceId")`로 꺼내 응답 본문 `traceId`에 담는다(SA 11-2 응답 형식).
- 같은 `traceId`가 그 요청의 모든 로그와 다른 서비스로 넘어간 호출에도 따라간다. 사용자가 에러 응답의 `traceId`를 알려 주면 Gateway → 예매 → 결제 로그를 한 번에 찾을 수 있다.

## 핵심 정리
- `@RequireRole`은 Gateway가 넘긴 역할 헤더(요청 속성)를 서비스에서 다시 확인하는 2차 방어다. Spring Security 컨텍스트가 아니다.
- 직접 호출 차단은 `@RequireRole`이 아니라 네트워크 격리가 맡는다.
- MDC는 요청(스레드)별 로그 값 저장소이고, `traceId`로 요청 하나를 여러 서비스 로그에서 추적한다.

## 참고
- `gateway/.../security/GatewayAuthenticationFilter.java`, `AccessPolicy.java`
- `common/.../web/GatewayIdentityFilter.java`, `RequireRoleInterceptor.java`, `RequireRole.java`
- `docs/decisions/0008-internal-trust-and-auth-design.md`
