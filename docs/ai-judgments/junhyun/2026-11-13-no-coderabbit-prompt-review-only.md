---
title: 호출 한도가 있어 CodeRabbit을 넣지 않고, AI 코드 리뷰는 저장소 프롬프트와 스크립트로만 하기로 함
date: 2026-11-13
updated: 2026-11-13
judge: Junhyun An
ai: Claude Code(Opus). CodeRabbit 공식 문서를 하위 에이전트가 읽어 설정 키와 한도를 정리함
verdict: 거부
area: 협업
presentation: 후보
shared: 예
---

# 호출 한도가 있어 CodeRabbit을 넣지 않고, AI 코드 리뷰는 저장소 프롬프트와 스크립트로만 하기로 함

## 맥락
- 해결하려던 작업: 머지된 PR 313개 중 GitHub 리뷰가 남은 PR이 12개뿐이라, 남은 기간 PR마다 리뷰 흔적을 남길 방법을 정하는 일. 4조와 travelpay는 CodeRabbit을 달아 리뷰의 절반 이상이 봇이었다.
- AI 활용 방식: Claude Code가 가이드 `ai-code-review.md` 7-2절과 ISE-271에 CodeRabbit을 선택지로 넣었다. 사용자가 "코드래빗을 적용할 생각도 있어"라고 하자 하위 에이전트가 CodeRabbit 공식 문서(docs.coderabbit.ai의 configuration, rate-limits, code-guidelines, review-commands)를 읽어 설정 키와 한도를 정리했다.

## AI 제안
CodeRabbit을 추가 리뷰어로 단다. `.coderabbit.yaml`에 한국어 리뷰, `dev` 대상 PR 자동 리뷰(`reviews.auto_review.base_branches`), 문서·생성물 경로 제외, 우리 점검 항목을 경로별 지시로 넣는다. `AGENTS.md`·`CLAUDE.md`는 CodeRabbit이 팀 지침으로 자동으로 읽는다. 조사에서 확인한 한도: 리뷰 한도는 개발자별로 시간당 회복되고(Essentials 5회, Team 8회, Advanced 10회), 오픈소스 등급은 프로젝트에 따라 달라지는 별도 한도가 있다(수치는 미확인).

## 판단
- 결과: 거부(CodeRabbit 도입)
- 판단자: Junhyun An
- 근거: "코드래빗 호출 rate limit 있으면 안넣을래." "코드래빗이 아니라 프롬프트로만 커버할래."
- 검토한 대안: CodeRabbit을 추가 리뷰어로 두고 우리 프롬프트 리뷰와 병행하는 안.

## 반영
- `docs/guides/ai-code-review.md` 7-2절을 "하지 않음(이유: 호출 한도)"으로 바꾸고, PR마다 남기는 리뷰는 `script/ai-review.sh`(Claude·codex)와 Claude 채팅 붙여 넣기 방법으로만 한다.
- 다른 조에서 가져온 리뷰 형식(Good-Ticket 동시성 리뷰 체크리스트, 4조의 "지적 → 반영 커밋 SHA 답글 → 재확인" 흐름)은 프롬프트와 답글 규칙으로 옮긴다(`ai-code-review-prompt.md`, `docs/presentation/evidence-formats.md`).
- ISE-271(팀이 정할 리뷰 방식)에서 CodeRabbit 안을 빼고 이 결정을 댓글로 알린다.

## 검증
- 프롬프트 리뷰는 PR #577로 시험했다: Claude 두 번(각 약 3분 30초), codex 한 번(55초). 짚은 곳이 모델마다 달랐다(`ai-code-review-prompt.md` 첫 단락).

## 판단 이력
- 2026-11-13 거부: 호출 한도 때문에 CodeRabbit을 넣지 않고 프롬프트로만 리뷰함

## 발표·면접 포인트
- 리뷰 봇을 달면 건수는 늘지만 호출 한도와 우리 설계 문서를 모르는 일반 리뷰라는 한계가 있다. 그래서 팀 설계·실패 경로를 아는 프롬프트를 저장소에 두고, 작성한 세션과 다른 모델로 돌려 지적마다 수용·거부를 남기는 방식을 골랐다.
