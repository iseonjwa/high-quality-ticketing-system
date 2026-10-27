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
- 사용자 원문: "게이트웨이에서 1차 검증을 하는 줄 알았는데 아니었어? … 음 비대칭 서명은 MVP이후로 미루는 게 맞는 것같다. 일단 방금 논의한 부분은 SA대로 하자. 내가 고민했던 기록만 남기고. 옆에있는 logistics-management의 인증 방식을 가져오는것도 고려해봐줘. 설계 조금만 더 하자"
- 함께 한 판단(수용): 응답 형식을 SA v3.1(#19)의 성공 `{status, message, data, traceId}`·실패 `{status, code, message, errors, traceId}`로 정렬하라고 지시했다.
- 검토한 대안: A(채택, SA), B, C. logistics-management의 방식은 비교 대상으로 추가했다.
- 팀 합의 상태: SA 유지이므로 팀 설계와 같다. 보강안(ADR 0008의 A1~A4)은 선택 전이다.

## 반영
- [ADR 0008](../../decisions/0008-internal-trust-and-auth-design.md)을 "SA 유지, 고민 기록 보존, logistics 비교와 보강 후보"로 고쳤다. [ADR 0009](../../decisions/0009-service-access-jwt-verification.md)는 철회 상태로 남겼다.
- 응답 형식은 `SuccessResponse`·`FailureResponse`로 코드를 정렬했다.

## 검증
응답 형식 정렬 뒤 모듈 테스트 80건이 통과했고, Compose 스택을 다시 빌드해 `smoke-workspace.py --redis-failure` 54건이 통과했다. 철회한 설계는 구현하지 않았으므로 검증할 코드가 없다.

## 판단 이력
- 2026-10-27 거부: D1~D3 AI 추천 철회, SA 유지. 고민 기록은 보존
- 2026-10-27 수용: 응답 형식을 SA #19로 정렬

## 발표·면접 포인트
- 보안 보강안을 검토했지만 팀이 합의한 SA의 단계(비대칭은 MVP 이후)를 지키기로 하고, 검토 과정과 위협 모델은 기록으로 남겼다. 이전 프로젝트(logistics)의 같은 모델과 비교해 가져올 후보만 추렸다.
