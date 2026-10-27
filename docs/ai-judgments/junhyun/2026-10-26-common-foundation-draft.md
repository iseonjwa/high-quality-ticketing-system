---
title: SA 기준으로 공통 모듈 개인 초안 구현
date: 2026-10-26
updated: 2026-10-26
judge: Junhyun An
ai: Codex
verdict: 부분 수용
area: 범위·일정
presentation: 아님
shared: 예
---

# SA 기준으로 공통 모듈 개인 초안 구현

## 맥락

Linear에서 담당 이슈를 골라 개인 브랜치로 작업하려 했지만 Iseonjwa에는 초기 안내 이슈 4개만 있었다. 저장소에는 문서만 있었고 main과 dev는 같은 커밋이었다.

## AI 제안

기존 Linear 이슈를 지정하거나 SA 기준으로 새 작업을 선정하도록 제안했다. 첫 작업 대안은 공통 모듈 개인 초안, 빌드 기반만 구현, 계약 합의 자료 작성이었다.

## 판단

- 결과: 부분 수용
- 판단자: Junhyun An
- 근거: 미기재
- 사용자 선택: "SA 기준으로 새 작업 선정", "개인 공통 모듈 초안"
- 변경 요청: Java 25 대신 Java 21을 선택하고 SA 수정 지시
- 후속 지시: Java 21을 반영한 전체 계획의 구현 요청

## 반영

[ISE-5](https://linear.app/iseonjwa/issue/ISE-5)에 범위와 검증 기준을 기록한다. 브랜치는 codex/ise-5-junhyun-common-foundation이다. [ADR 0002](../../decisions/0002-common-foundation-draft.md)에 개인 초안의 빌드·모듈 경계를 남긴다.

## 검증

검증 결과는 [워크스페이스 가이드](../../guides/workspace.md)에 기록한다. 인증·결제·Gateway·Outbox와 팀 통합은 후속 범위다.

## 판단 이력

- 2026-10-26 부분 수용: 공통 모듈 개인 초안 구현을 선택하고 Java 21로 수정한 계획의 구현 지시

## 발표·면접 포인트

팀 계약이 확정되기 전 개인 비교용 코드와 테스트를 준비하되, 개인 초안을 팀 합의로 기록하지 않는다.
