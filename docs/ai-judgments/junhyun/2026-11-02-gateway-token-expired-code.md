---
title: Gateway가 만료된 Access 토큰에 401 TOKEN_EXPIRED를 주도록 SA대로 고치는 안을 선택
date: 2026-11-02
updated: 2026-11-02
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# Gateway가 만료된 Access 토큰에 401 TOKEN_EXPIRED를 주도록 SA대로 고치는 안을 선택

## 맥락
- 해결하려던 작업: ISE-110 프론트 API 클라이언트를 만들다가 Gateway가 만료된 Access 토큰에도 `TOKEN_EXPIRED`가 아니라 `UNAUTHORIZED`를 주는 것을 확인했다. 사용자가 "왜 TOKEN_EXPIRED를 안 주지? 보안상 그렇게 설계한 건가"라고 물었다.
- AI 활용 방식: AI가 Gateway 필터·공통 디코더 코드, 10/27 통합 리뷰 문서, 커밋 이력을 확인해 원인을 설명하고 두 가지 처리 안을 냈다. 설명은 [학습 노트](../../learning/junhyun/qna/2026-11-02-gateway-expired-token-error-code.md)에 있다.

## AI 제안
- 원인: 보안 설계가 아니다. 필터가 디코더의 모든 실패를 `catch (JwtException ...)` 하나로 받는다. 10/27 통합 리뷰가 수정 후보로 적었고 `team-decisions.md` 체크리스트에 남아 있었다.
- 보안 평가: 만료를 알려 줘도 잃는 것이 거의 없다. `exp`는 토큰 안에 평문으로 있고, 서명을 먼저 검증하므로 위조 토큰에는 만료라고 나가지 않는다. 묶어야 하는 것은 로그인 실패 사유(사용자 열거)다.
- 1안(추천): Gateway를 SA 11-3대로 고친다. 리뷰 방향과 같고, 변경이 작고, 로그에서 만료와 위조 시도를 나눠 볼 수 있다. 만료 검증기에 고유 오류 코드를 붙이고, 필터가 그 코드일 때만 `TOKEN_EXPIRED`를 준다. 테스트는 "만료된 정상 토큰 → `TOKEN_EXPIRED`", "서명이 틀린 만료 토큰 → `UNAUTHORIZED`".
- 2안: SA를 RFC 6750처럼 "401은 하나, 설명으로 구분"으로 바꾼다.
- 어느 안이든 프론트는 401이면 코드와 상관없이 재발급하므로 고칠 것이 없다.

## 판단
- 결과: 수용(1안 선택)
- 판단자: Junhyun An
- 근거: 미기재("1번으로 해줘")
- 검토한 대안: 2안(SA를 단일 401 코드로 변경)

## 반영
- Linear ISE-131, 브랜치 `fix/ISE-131-gateway-token-expired`
- `common/src/main/java/com/iseonjwa/common/auth/TokenDecoders.java`: 만료 시각이 지난 실패에만 `token_expired` 오류 코드를 붙이고 `isExpired(JwtException)`를 둔다
- `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`: 만료면 401 `TOKEN_EXPIRED`, 그 밖은 `UNAUTHORIZED`
- 프론트 목이 만료에 `TOKEN_EXPIRED`, 로그아웃된 토큰에 `TOKEN_REVOKED`를 주게 맞췄다. 설계 요약·디자인 시스템 6절 표기를 고쳤다
- `docs/sa-review/2026-10-27-branch-integration/team-decisions.md` 체크리스트 항목에 반영 사실을 적었다(빈 `jti` 거절은 남음)

## 검증
- `./gradlew :common:test :gateway:test :user-service:test` 통과(common 49, gateway 48, user-service 35). 새 테스트: 디코더의 만료 판별 3건, 실제 디코더와 서명한 토큰으로 Gateway 필터 3건(만료 → `TOKEN_EXPIRED`, 서명이 틀린 만료 → `UNAUTHORIZED`, 형식 오류 → `UNAUTHORIZED`)
- `frontend` 테스트 62개 통과. 401 세 코드 모두에서 재발급하는 테스트를 더했다

## 판단 이력
- 2026-11-02 수용: 1안을 골랐다.

## 발표·면접 포인트
- 에러 응답을 하나로 묶을지는 "상대가 모르던 정보를 주는가"로 판단한다. 토큰 만료 시각은 토큰을 가진 사람이 이미 아는 정보라 구분해도 되고, 로그인 실패 사유는 계정 존재 여부를 알려 주므로 묶는다.
