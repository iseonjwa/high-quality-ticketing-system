@AGENTS.md

# Claude Code 전용

규칙의 정본은 `AGENTS.md`와 `.agents/skills/`다. 이 파일에는 Claude Code에만 있는 기능을 적는다.

- 공용 스킬은 `script/setup-agents.sh`를 실행하면 `.claude/skills`에 연결된다. clone한 뒤 한 번, pull로 스킬이 바뀌면 다시 실행한다. 스크립트는 자기가 만든 항목만 지우므로 같은 폴더에 둔 개인 스킬은 남는다.
- `.claude/settings.json`의 `UserPromptSubmit` 훅(`script/judgment-reminder.sh`)이 사용자 입력마다 AI 판단 기록 규칙을 짧게 상기시킨다. 판단인지는 메시지 내용으로 가리고, 판단이 아니면 무시한다. 끄려면 개인 설정 `.claude/settings.local.json`에 `"disableAllHooks": true`를 두거나 `/hooks`에서 끈다.
- 같은 설정의 `Stop` 훅(`script/records-check.sh --hook`)이 에이전트가 끝나기 전에 기록 구조(사람별 폴더, 색인, 필수 머리말, 공통 판단 표)와 비밀값 흔적을 검사한다. 어긋나면 에이전트가 한 번 더 이어서 고친다. 같은 검사가 PR과 main push마다 GitHub Actions(`records-check`)로도 돈다. 기록을 남겼는지(내용)는 검사하지 못한다.
- 개인 설정은 `CLAUDE.local.md`나 `.claude/settings.local.json`에 둔다.
