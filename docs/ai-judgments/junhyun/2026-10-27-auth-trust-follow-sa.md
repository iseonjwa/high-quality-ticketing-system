---
title: 신뢰 모델: AI 추천(서비스 JWT 직접 검증 + RS256) 대신 SA 7-2·7-3 유지
date: 2026-10-27
updated: 2026-10-27
judge: Junhyun An
ai: Claude Code, Codex (별도 세션)
verdict: 거부
area: 기타
presentation: 아님
shared: 예
---

# 신뢰 모델: AI 추천(서비스 JWT 직접 검증 + RS256) 대신 SA 7-2·7-3 유지

## 맥락
- 해결하려던 작업: ADR 0006으로 서비스 단독 방어가 약해진 뒤 내부 신뢰 모델과 인증 설계를 정하는 중이었다.
- AI 활용 방식: 위협 모델과 선택지(A 네트워크 신뢰, B Gateway 내부 서명, C 서비스 JWT 직접 검증)를 비교받고 D1 신뢰 방식, D2 서명 방식, D3 적용 범위를 골랐다.

## AI 제안
- D1: C. 서비스가 사용자 JWT를 직접 검증하고 `X-User-*` 헤더 신뢰를 없앤다.
- D2: Access를 RS256 비대칭으로 바꾼다. D3: Access만 먼저 바꾸고 입장·결제 토큰은 팀 합의 뒤로 한다.
- 사용자가 먼저 이 세 추천을 선택했다([C 선택 기록](2026-10-27-service-jwt-verification-choice.md), [ADR 0009](../../decisions/0009-service-access-jwt-verification.md)).

## 판단
- 결과: 거부. D1~D3의 AI 추천을 철회하고 SA 7-2·7-3대로 한다. Gateway가 Access JWT(HS256)를 1차 검증하고 서비스는 `X-User-*`를 신뢰한다. 서비스 직접 검증과 비대칭 서명은 MVP 이후로 미룬다.
- 판단자: Junhyun An
- 근거: "비대칭 서명은 MVP이후로 미루는 게 맞는 것같다". SA 7-3이 비대칭 서명을 MVP 이후로 둔 것과 같은 방향이다.
- 사용자 원문(발췌): "게이트웨이에서 1차 검증을 하는 줄 알았는데 아니었어? … 음 비대칭 서명은 MVP이후로 미루는 게 맞는 것같다. 일단 방금 논의한 부분은 SA대로 하자. 내가 고민했던 기록만 남기고. … 설계 조금만 더 하자"
- 함께 한 판단(수용): 보강 후보 중 "A4까지 너 의견대로 해줘"로 AI 의견(A1 `/internal/**` 차단, A2 `@RequireRole`, A4 포트 비공개 전제 명시·검사를 지금, A3는 SA 합의 뒤)을 받아들였다. 이유는 미기재다.
- 함께 한 판단(수용): 응답 형식을 SA v3.1(#19)의 성공 `{status, message, data, traceId}`·실패 `{status, code, message, errors, traceId}`로 정렬하라고 지시했다.
- 검토한 대안: A(채택, SA), B, C.
- 팀 합의 상태: SA 유지이므로 팀 설계와 같다. 보강안(ADR 0008의 A1~A4)은 선택 전이다.

## 반영
- [ADR 0008](../../decisions/0008-internal-trust-and-auth-design.md)을 "SA 유지, 고민 기록 보존, 보강 후보"로 고쳤다. [ADR 0009](../../decisions/0009-service-access-jwt-verification.md)는 철회 상태로 남겼다.
- 응답 형식은 `SuccessResponse`·`FailureResponse`로 코드를 정렬했다.

## 검증
A1·A2·A4 적용 뒤 Gateway·common-web 테스트와 Python 14건(포트 검사 5건 포함)이 통과했다. 응답 형식 정렬 뒤 모듈 테스트 80건이 통과했고, Compose 스택을 다시 빌드해 `smoke-workspace.py --redis-failure` 54건이 통과했다. 철회한 설계는 구현하지 않았으므로 검증할 코드가 없다.

## 판단 이력
- 2026-10-27 거부: D1~D3 AI 추천 철회, SA 유지. 고민 기록은 보존
- 2026-10-27 수용: 응답 형식을 SA #19로 정렬
- 2026-10-27 수용: 보강 후보 A1·A2·A4를 AI 의견대로 적용, A3 보류

## 발표·면접 포인트
- 보안 보강안을 검토했지만 팀이 합의한 SA의 단계(비대칭은 MVP 이후)를 지키기로 하고, 검토 과정과 위협 모델은 기록으로 남겼다.
