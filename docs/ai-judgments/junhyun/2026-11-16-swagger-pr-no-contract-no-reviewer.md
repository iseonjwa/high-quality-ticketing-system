---
title: Swagger PR(#685)에 contract 이슈와 다른 담당 리뷰어를 두자는 AI 제안을 거부하고 바로 머지하기로 함
date: 2026-11-16
updated: 2026-11-16
judge: junhyun
ai: Claude Code
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# Swagger PR(#685)에 contract 이슈와 다른 담당 리뷰어를 두자는 AI 제안을 거부하고 바로 머지하기로 함

## 맥락
- 해결하려던 작업: Swagger UI를 Gateway의 `/api/docs`에 붙이는 PR #685(ISE-295, ADR 0063)를 dev에 머지하는 일.
- AI 활용 방식: 구현과 Opus 셀프 리뷰를 마친 뒤, 팀 규칙에 걸리는 두 가지를 AI가 사용자에게 물었다.

## AI 제안
1. config-repo(`application.yml`, `gateway.yml`)는 공유 설정이라 `contract-issues.md` 1절에 해당한다. 받는 쪽이 할 일은 없지만 알림용 `contract` 이슈를 올릴지 물었다.
2. 다른 담당 영역인 좌석 조회·선점 컨트롤러에 문서 어노테이션을 붙였으니, `git-workflow.md` 6-2절대로 그 담당을 리뷰어로 넣을지 물었다.

## 판단
- 결과: 거부
- 판단자: junhyun
- 근거: "ㄴㄴ 그냥 머지할게." 그 밖의 이유는 미기재.
- 검토한 대안: 이슈만 올리고 리뷰어는 생략, 또는 그 반대. 고르지 않았다.

## 반영
- contract 이슈를 올리지 않았고, PR #685에 리뷰어를 지정하지 않았다. 머지는 사용자가 직접 한다.
- PR 본문 체크 항목에 "config-repo를 바꿨지만 받는 쪽이 할 일은 없어 아직 올리지 않았다"고 적어 두었다.

## 검증
- PR #685 CI 통과(`build`, `records-check`, `images` 재실행 통과), 머지 가능(CLEAN) 상태에서 판단했다.

## 판단 이력
- 2026-11-16 거부: contract 이슈와 리뷰어 지정 없이 머지하기로 했다.
