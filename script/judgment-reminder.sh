#!/usr/bin/env bash
# UserPromptSubmit 훅: 사용자가 AI 응답·제안을 두고 판단하는 순간을 놓치지 않도록 입력마다 기록 규칙을 상기시킨다.
# 판단인지 아닌지는 에이전트가 메시지 내용으로 가린다. 키워드로 거르면 "2안으로", "그건 아닌데" 같은 판단을 놓친다.
# 규칙 정본: .agents/skills/ai-judgment/SKILL.md
set -euo pipefail

# 훅 입력(JSON)은 쓰지 않지만, 보내는 쪽이 막히지 않게 읽어서 버린다
cat > /dev/null

cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"[AI 판단 기록] 이 메시지가 AI(Claude Code, codex, ChatGPT 등)의 응답·제안·리뷰·코드에 대한 사용자 판단(수용, 부분 수용, 거부, 보류, 선택지 고르기, 팀·튜터 결정 전달, AI 답이 틀렸다는 지적)을 담고 있으면 .agents/skills/ai-judgment/SKILL.md 절차대로 docs/ai-judgments/에 기록하고 답 끝에 '기록: <경로>'를 남긴다. 판단이 아니면 이 안내를 무시한다."}}
JSON
