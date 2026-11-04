# 공용 스킬

Claude Code와 Codex가 함께 쓰는 스킬의 정본이다. 스킬마다 `<이름>/SKILL.md` 하나다. 언제 쓰고 무엇을 기록하는지는 루트 `AGENTS.md`의 "공용 스킬" 절에 있다.

| 스킬 | 하는 일 |
|---|---|
| [ai-judgment](ai-judgment/SKILL.md) | AI 응답·제안에 대한 사용자 판단(수용·거부·보류·선택)을 판단한 순간 기록한다 |
| [learning-note](learning-note/SKILL.md) | 질문 답과 하루 학습을 개인 학습 노트로 남긴다 |
| [adr](adr/SKILL.md) | 되돌리기 어려운 결정을 기록한다 |
| [troubleshooting](troubleshooting/SKILL.md) | 해결한 문제를 문제 해결 기록에 남긴다 |

## 규칙
- **폴더는 평평하게 둔다**: 묶음 폴더를 만들지 않는다. Claude Code와 Codex 모두 `skills/<이름>/SKILL.md` 형태만 찾는다.
- **머리말**: `name`(폴더 이름과 같게)과 `description`(언제 쓰는지, 예시 요청 포함)을 쓴다. 본문 절차는 어느 도구에서도 읽히게 쓴다.
- **추가·수정·삭제는 PR에서 판단한다.** PR 본문에 이유를 쓴다.
- **연결**: Codex는 이 폴더를 직접 읽는다. Claude Code는 `script/setup-agents.sh`를 실행하면 `.claude/skills/`에서 찾는다. 스킬을 바꾼 변경을 pull하면 다시 실행한다.
