---
title: Gateway는 왜 만료된 Access 토큰에 TOKEN_EXPIRED가 아니라 UNAUTHORIZED를 주는가 (보안상 숨긴 것인가)
date: 2026-11-02
updated: 2026-11-02
asker: Junhyun An
level: 중급
tags: [jwt, gateway, spring-security, error-contract, rfc6750]
---

# Gateway는 왜 만료된 Access 토큰에 TOKEN_EXPIRED가 아니라 UNAUTHORIZED를 주는가 (보안상 숨긴 것인가)

## 질문
게이트웨이가 만료된 토큰에 왜 TOKEN_EXPIRED를 안 주지? 보안상 그렇게 설계한 건가.

(ISE-110 API 클라이언트를 만들다가, SA 11-3에는 만료 코드 `TOKEN_EXPIRED`가 있는데 실제 Gateway는 만료에도 `UNAUTHORIZED`를 준다는 것을 확인했다.)

## 짧은 답
- 보안 때문에 정한 것이 아니다. Gateway 필터가 디코더의 모든 실패를 `catch` 하나로 잡아 401 `UNAUTHORIZED`로 응답하게 짜여 있고, 만료를 따로 가르는 분기가 없을 뿐이다.
- 이 차이는 10/27 통합 리뷰가 이미 "수정 후보"로 적었고, 팀 결정 문서의 수정 체크리스트에도 있지만 아직 닫히지 않았다. user-service의 `UserErrorCode.TOKEN_EXPIRED`는 정의만 있고 쓰는 곳이 없다.
- 만료를 알려 줘도 보안상 잃는 것은 거의 없다. 만료 시각(`exp`)은 토큰 안에 평문으로 들어 있어 토큰을 가진 사람은 이미 읽을 수 있다. 또 서명 검사를 통과한 토큰만 만료 판정까지 가므로 위조 토큰에 "만료"가 나가지 않는다.
- 반대로 하나로 묶는 것도 표준적인 방식이다. RFC 6750은 만료·폐기·형식 오류를 모두 `invalid_token` 하나로 묶는다. 그래서 클라이언트는 코드보다 "401이면 재발급"으로 판단하는 편이 견고하다.

## 자세히

### 1. 지금 코드가 만료를 구분하지 못하는 이유
`GatewayAuthenticationFilter`는 Access 토큰을 이렇게 검증한다.

```java
try {
    jwt = decoder.decode(token);
} catch (JwtException | IllegalArgumentException exception) {
    return errors.write(exchange, 401, "UNAUTHORIZED", "유효한 인증 토큰이 필요합니다");
}
```

디코더(`TokenDecoders.create`)는 Spring Security의 `NimbusJwtDecoder`(HS256)에 검증기 몇 개를 붙인 것이다.

| 순서 | 무엇을 검사하나 | 실패하면 |
|---|---|---|
| 1 | 서명(HMAC) | `BadJwtException` |
| 2 | `exp`(허용 오차 0초, `JwtTimestampValidator`), `iss`, `aud`·토큰 종류 | `JwtValidationException`. 만료면 오류 설명이 "Jwt expired at ..."이다 |

두 예외 모두 `JwtException`의 하위 타입이다. 그래서 위 `catch` 하나에 서명 위조, 만료, 다른 종류 토큰이 모두 걸려 같은 응답이 된다. Spring은 Nimbus 쪽의 claim 검사를 끄고 자기 검증기로만 `exp`를 보므로, 서명이 틀린 토큰은 1단계에서 끝나고 만료 판정까지 가지 않는다.

`TOKEN_REVOKED`만 따로 나오는 것은 검증을 통과한 뒤 Redis 블랙리스트를 조회하는 별도 단계가 있기 때문이다.

### 2. 만료를 알려 주면 위험한가
위험하지 않은 이유는 두 가지다.

- **`exp`는 비밀이 아니다.** JWT 본문은 암호화가 아니라 base64url 인코딩이다. 토큰을 가진 사람은 서버에 묻지 않아도 언제 만료되는지 안다.
- **서명이 먼저다.** 만료라고 답하는 것은 서명이 맞는 토큰뿐이다. 더 드러나는 정보는 "이 토큰이 한때 진짜였다"인데, 그 토큰을 가진 사람에게는 새 정보가 아니다.

비교: 정말 묶어야 하는 것은 로그인 실패 사유다. "없는 이메일"과 "틀린 비밀번호"를 나누면 어떤 이메일이 가입돼 있는지 알아낼 수 있다(사용자 열거). 이 프로젝트도 둘을 `INVALID_CREDENTIALS` 하나로 묶는다. 토큰 만료는 상대가 모르던 사실을 알려 주는 것이 아니라서 이 경우와 다르다.

### 3. 구분하면 얻는 것, 묶으면 얻는 것

| 관점 | 하나로 묶음 (지금) | 만료를 구분 |
|---|---|---|
| 클라이언트 동작 | 401이면 재발급을 시도한다. 위조 토큰도 재발급을 한 번 시도하지만, Refresh 토큰이 없거나 거절되면 로그인 화면으로 간다 | 만료일 때만 재발급하고 나머지는 바로 로그인 화면으로 보낼 수 있다. 왕복 한 번을 아낀다 |
| 정보 노출 | 가장 적다 | 서명이 맞았다는 사실만 더 드러난다. 실질 위험은 낮다 |
| 운영·관측 | 만료와 위조 시도가 같은 숫자로 섞인다 | 로그·지표에서 나뉜다. 위조 시도가 갑자기 늘어나는 것을 따로 볼 수 있다 |
| 계약 | SA 11-3과 어긋난다 | SA 11-3과 맞는다 |

관측 이득만 원하면 응답은 하나로 두고 서버 로그에만 이유(만료·서명·종류)를 남기는 절충도 있다.

### 4. 표준은 어떻게 하나
RFC 6750(OAuth 2.0 Bearer Token) 3.1절은 401 응답의 `WWW-Authenticate` 헤더에 `error="invalid_token"`을 쓰고, 이 코드가 "만료, 폐기, 형식 오류, 그 밖의 이유로 유효하지 않음"을 모두 뜻한다고 정한다. 구분이 필요하면 사람이 읽는 `error_description`("The access token expired")에 쓴다. 즉 표준은 "코드는 하나, 설명으로 구분"이다. 이 프로젝트 Gateway는 `WWW-Authenticate` 헤더를 쓰지 않고 공통 실패 본문의 `code`로 구분한다.

### 5. 구분하도록 고친다면
- 만료 검증기를 감싸 고유한 오류 코드(예: `token_expired`)를 붙이고, 필터에서 `JwtValidationException`의 오류 목록에 그 코드가 있으면 401 `TOKEN_EXPIRED`로 응답한다.
- 오류 설명 문자열("Jwt expired")을 비교하는 방식은 Spring 버전이 바뀌면 깨지기 쉬워 피한다.
- 회귀 테스트는 두 개면 된다. 만료된 정상 토큰 → `TOKEN_EXPIRED`, 서명이 틀린 만료 토큰 → `UNAUTHORIZED`(서명을 먼저 본다는 것을 고정한다).

## 이 프로젝트에서는
- 응답 위치: `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`의 `decoder.decode` `catch`
- 검증기 구성: `common/src/main/java/com/iseonjwa/common/auth/TokenDecoders.java`
- 계약: `docs/core/SA MASTER.md` 11-3(`UNAUTHORIZED` · `TOKEN_EXPIRED` · `TOKEN_REVOKED` 401)
- 이미 알려진 차이: `docs/sa-review/2026-10-27-branch-integration/auth-review.md` "신뢰 경계와 남은 결함·한계" 표의 수정 후보("만료 JWT도 일반 UNAUTHORIZED로 응답하며 SA의 TOKEN_EXPIRED 구분을 잃는다"), `team-decisions.md` 수정 체크리스트 첫 항목(미완료). 리뷰의 "J"는 통합 전 Junhyun 브랜치이고, 지금 Gateway 필터가 그 브랜치에서 왔다(통합 커밋 `bc4e15a` 뒤로 바뀌지 않았다)
- 프론트(ISE-110)는 코드가 아니라 401이면 재발급한다. 나중에 Gateway가 `TOKEN_EXPIRED`를 주도록 바뀌어도 프론트는 고칠 것이 없다. 디자인 시스템 6절의 "로그인 만료 401 `TOKEN_EXPIRED`" 표기는 실제 동작에 맞춰 고쳤다
- 남은 결정: Gateway를 SA대로 고칠지, SA를 RFC 6750처럼 "401은 하나, 설명으로 구분"으로 바꿀지. 어느 쪽이든 리뷰 체크리스트 항목을 닫아야 한다

## 면접 포인트
- **만료된 토큰에는 어떤 응답을 주나?** 401을 주고 재발급을 유도한다. RFC 6750은 `invalid_token` 하나로 묶고 설명으로 구분한다. 만료를 따로 알려 줘도 `exp`는 토큰에 평문으로 있고 서명을 먼저 검증하므로 보안 손실이 거의 없다. 반면 로그인 실패 사유는 사용자 열거 때문에 묶는다.
- **클라이언트는 무엇을 보고 재발급하나?** 코드보다 401 자체를 본다. 서버가 코드를 바꿔도 깨지지 않는다. 동시에 받은 401은 재발급 하나를 함께 기다린다. 이 프로젝트처럼 Refresh 토큰을 재발급마다 교체하면 두 번째 재발급이 `TOKEN_REVOKED`로 실패하기 때문이다.
- **JWT 검증 순서가 왜 중요한가?** 서명이 틀린 토큰의 claim은 누가 썼는지 모르므로 믿을 수 없다. 서명을 먼저 보고, 통과한 토큰에만 만료·발급자·대상을 검사해야 위조 토큰에 세부 판단 결과를 흘리지 않는다.

## 참고
- RFC 6750 3.1 Error Codes: https://www.rfc-editor.org/rfc/rfc6750#section-3.1
- Spring Security `NimbusJwtDecoder`, `JwtTimestampValidator`(이 프로젝트는 Spring Boot 4.1.2 BOM)
- 확인한 코드: 위 두 파일, `user-service/.../UserErrorCode.java`(`TOKEN_EXPIRED` 정의만 있고 사용처 없음, `grep`으로 확인)
