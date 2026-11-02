---
title: "Notion보다 저장소 기록 우선"을 개인 전역 지침에 넣은 AI 처리를 바로잡아 팀 규칙(AGENTS.md)으로 올림
date: 2026-11-02
updated: 2026-11-02
judge: Junhyun An
ai: Claude Code
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# "Notion보다 저장소 기록 우선"을 개인 전역 지침에 넣은 AI 처리를 바로잡아 팀 규칙(AGENTS.md)으로 올림

## 맥락
- 해결하려던 작업: 프론트 결정을 Notion 대신 저장소 docs에 기록하기로 한 판단([기록](2026-11-02-records-in-docs-over-notion.md))을 지침으로 남긴다.
- AI 활용 방식: 사용자가 "Notion보다 로컬기록을 우선한다는 판단도 전역 지침에 남겨줘"라고 요청했다.

## AI 제안
Claude Code가 "전역 지침"을 개인 전역 지침으로 해석해 `~/.claude/CLAUDE.md`와 `~/.codex/AGENTS.md`에 "기록 위치" 절을 더했다. 팀 규칙(`AGENTS.md`)은 고치지 않았다.

## 판단
- 결과: 거부 (AI의 해석이 틀렸다는 지적)
- 판단자: Junhyun An
- 근거: "개인 전역지침이 아니라, 팀 규칙으로 남기는거였어"
- 검토한 대안: 개인 전역 지침, `CLAUDE.local.md`

## 반영
- 개인 전역 지침 두 곳에 더한 절을 지웠다.
- `AGENTS.md` 대전제 4를 "결정은 이유와 함께 저장소에 남긴다"로 넓혔다. 결정·논의·학습 기록은 Notion보다 저장소 `docs/`에 먼저 남기고, Notion은 사용자가 요청할 때만 고치며, 어긋나면 저장소 기록을 따르고 대체한 Notion 항목을 적는다.
- `AGENTS.md` 프로젝트 절과 `docs/README.md` 외부 원본 절의 "확정 여부는 Notion DB 상태를 따른다"에 "저장소 `docs/decisions/`가 대체한 결정은 저장소 기록을 따른다"를 더했다.
- 팀 규칙 변경이라 ISE-105 PR 본문에 이유를 적고 팀원 확인을 받는다.

## 검증
`script/records-check.sh` 통과

## 판단 이력
- 2026-11-02 거부: 개인 전역 지침이 아니라 팀 규칙으로 남긴다

## 발표·면접 포인트
- 기록을 어디에 두느냐는 개인 습관이 아니라 팀이 같은 곳을 보게 하는 협업 규칙이라 팀 지침에 넣었다. AI가 "전역"을 개인 범위로 해석한 것을 바로잡았다.
