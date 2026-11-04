---
title: 프론트 결정을 Notion 결정 기록에 반영하자는 제안 대신 저장소 docs에 기록
date: 2026-11-02
updated: 2026-11-02
judge: Junhyun An
ai: Claude Code
verdict: 거부
area: 협업
presentation: 아님
shared: 예
---

# 프론트 결정을 Notion 결정 기록에 반영하자는 제안 대신 저장소 docs에 기록

## 맥락
- 해결하려던 작업: 프론트 스택을 Notion 「SA 기획·설계 결정 기록」 #7(정적 HTML·JS)에서 React SPA로 바꾼 결정을 어디에 남길지 정한다.
- AI 활용 방식: ADR 0040을 쓰면서 Claude Code가 Notion 결정 기록 #7의 상태·바뀐 이유·ADR 링크를 함께 갱신해도 되는지 물었다.

## AI 제안
ADR 0040과 함께 Notion 결정 기록 #7을 갱신한다. `AGENTS.md`가 결정의 확정 여부를 Notion DB 상태로 따르게 하기 때문이다.

## 판단
- 결과: 거부
- 판단자: Junhyun An
- 근거: "Notion보다는 지금 프로젝트 docs 내부에 주로 기록하는 방식으로 진행해줘. 다른 팀원들도 공유받을 수 있게"
- 검토한 대안: Notion #7 갱신, Notion #7에 ADR 링크 한 줄만 남기기

## 반영
- Notion은 고치지 않았다.
- [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md) 상태와 결과에 "프론트 결정은 이 저장소 문서를 정본으로 두고, Notion #7은 고치지 않는다"를 적었다.
- 팀원용 요약 [`docs/design/frontend/README.md`](../../design/frontend/README.md)의 "팀원이 알아 둘 것"에 Notion #7이 옛 결정으로 남아 있다는 점을 적었다.
- 같은 날 이 판단을 팀 규칙으로 올렸다. `AGENTS.md` 대전제 4에 "결정·논의·학습 기록은 Notion보다 저장소 `docs/`에 먼저 남기고, Notion은 사용자가 요청할 때만 고친다"를 넣었다. 처음에 AI가 개인 전역 지침에 넣었다가 사용자 지적으로 옮겼다([기록](2026-11-02-docs-first-as-team-rule.md)).

## 검증
해당 없음

## 판단 이력
- 2026-11-02 거부: Notion 대신 저장소 docs에 기록한다
- 2026-11-02 팀 규칙화: `AGENTS.md` 대전제 4에 반영(ISE-105 PR)

## 발표·면접 포인트
- 결정 기록을 코드와 같은 저장소에 두면 PR로 리뷰되고 팀원이 같은 곳에서 본다. 대신 Notion에 남은 옛 결정과 어긋날 수 있어, 어긋난다는 사실을 저장소 문서에 적었다.
