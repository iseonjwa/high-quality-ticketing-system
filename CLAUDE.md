@AGENTS.md

# Claude Code 전용

규칙의 정본은 `AGENTS.md`와 `.agents/skills/`다. 이 파일에는 Claude Code에만 있는 기능을 적는다.

- 공용 스킬은 `script/setup-agents.sh`를 실행하면 `.claude/skills`에 연결된다. clone한 뒤 한 번, pull로 스킬이 바뀌면 다시 실행한다. 스크립트는 자기가 만든 항목만 지우므로 같은 폴더에 둔 개인 스킬은 남는다.
- `.claude/settings.json`의 `UserPromptSubmit` 훅(`script/judgment-reminder.sh`)이 사용자 입력마다 AI 판단 기록 규칙을 짧게 상기시킨다. 판단인지는 메시지 내용으로 가리고, 판단이 아니면 무시한다. 끄려면 개인 설정 `.claude/settings.local.json`에 `"disableAllHooks": true`를 두거나 `/hooks`에서 끈다.
- 개인 설정은 `CLAUDE.local.md`나 `.claude/settings.local.json`에 둔다.
