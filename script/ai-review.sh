#!/usr/bin/env bash
# PR 하나를 작성 세션과 다른 Claude Code 세션으로 리뷰한다(docs/guides/ai-code-review.md 7-1절).
#   script/ai-review.sh <PR 번호> [--post] [--engine claude|codex] [--model <모델>]
# PR 머리 커밋을 임시 worktree로 꺼내 그 안에서 읽기 전용으로 리뷰한다. claude는 도구를 Read, Grep, Glob만 주고,
# codex는 read-only 샌드박스로 돈다. Claude 채팅(웹·앱)만 쓰면 docs/guides/ai-code-review.md 8절의 붙여 넣기 방법을 쓴다.
# 결과는 build/ai-review/pr-<번호>-<시각>.md에 쓴다. --post를 주면 같은 내용을 PR 코멘트로 올린다.
# 프롬프트는 docs/guides/ai-code-review-prompt.md의 "## 프롬프트" 절이다.
# 필요한 것: gh(로그인), git, 그리고 claude(Claude Code CLI) 또는 codex(Codex CLI). 종료 코드: 0 성공, 1 실패, 2 사용법 오류
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"

usage() { echo "사용법: script/ai-review.sh <PR 번호> [--post] [--engine claude|codex] [--model <모델>]" >&2; exit 2; }
[[ $# -ge 1 && "$1" =~ ^[0-9]+$ ]] || usage
pr="$1"; shift
post=0
engine="${AI_REVIEW_ENGINE:-claude}"
model="${AI_REVIEW_MODEL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --post) post=1; shift ;;
    --engine) [[ $# -ge 2 && ( "$2" == claude || "$2" == codex ) ]] || usage; engine="$2"; shift 2 ;;
    --model) [[ $# -ge 2 ]] || usage; model="$2"; shift 2 ;;
    *) usage ;;
  esac
done
# 모델을 주지 않으면 claude는 opus, codex는 자기 설정(~/.codex/config.toml)의 기본 모델을 쓴다
[[ "$engine" == claude && -z "$model" ]] && model=opus
for tool in gh git "$engine"; do
  command -v "$tool" > /dev/null || { echo "$tool 이 없습니다" >&2; exit 1; }
done

prompt_file="$root/docs/guides/ai-code-review-prompt.md"
# "## 프롬프트" 다음 줄부터 다음 "## " 제목 앞까지가 리뷰어에게 줄 지시다
instructions="$(awk '/^## 프롬프트$/ {on=1; next} on && /^## / {exit} on {print}' "$prompt_file")"
[[ -n "$instructions" ]] || { echo "$prompt_file 에서 '## 프롬프트' 절을 찾지 못했습니다" >&2; exit 1; }

out_dir="$root/build/ai-review"
mkdir -p "$out_dir"
out="$out_dir/pr-${pr}-$(date +%Y%m%d-%H%M%S).md"
work="$(mktemp -d "${TMPDIR:-/tmp}/ai-review-${pr}-XXXXXX")"

# Note: 리뷰어가 PR 시점의 주변 코드를 읽어야 하므로 지금 작업 폴더가 아니라 PR 머리 커밋을 따로 꺼낸다.
#       끝나면(실패해도) worktree와 임시 ref를 지운다
ref="refs/ai-review/pr-${pr}"
cleanup() {
  git worktree remove --force "$work/tree" > /dev/null 2>&1 || true
  git update-ref -d "$ref" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

git fetch --quiet origin "pull/${pr}/head:${ref}"
git worktree add --quiet --detach "$work/tree" "$ref"

{
  echo "# PR #${pr}"
  gh pr view "$pr" --json title,body,baseRefName,headRefName \
    --template '제목: {{.title}}{{"\n"}}브랜치: {{.headRefName}} → {{.baseRefName}}{{"\n\n"}}{{.body}}{{"\n"}}'
  echo
  echo "# diff"
  echo '```diff'
  gh pr diff "$pr"
  echo '```'
} > "$work/context.md"

echo "PR #${pr} 리뷰 중(${engine}${model:+, 모델 ${model}}). 결과: ${out}" >&2
# 작업 폴더는 PR 머리 커밋이다. 도구는 읽기 전용만 준다(파일 수정·명령 실행 없음)
# Note: --setting-sources user로 프로젝트 설정(.claude/settings.json)의 훅을 끈다. 판단 기록 안내 훅이 리뷰 결과에 섞여 나왔다
printf '%s\n\n아래는 PR 설명과 diff다. 주변 코드는 지금 작업 폴더(PR 머리 커밋)에서 읽어라.\n\n%s\n' \
  "$instructions" "$(cat "$work/context.md")" > "$work/prompt.md"
if [[ "$engine" == claude ]]; then
  ( cd "$work/tree" && claude -p --model "$model" --tools "Read,Grep,Glob" --no-session-persistence --setting-sources user < "$work/prompt.md" ) > "$out"
else
  # Note: codex exec는 마지막 답만 -o 파일에 쓴다. 진행 기록(stdout)은 버린다
  codex exec -s read-only -C "$work/tree" --ephemeral ${model:+-m "$model"} -o "$out" - < "$work/prompt.md" > /dev/null
fi

[[ -s "$out" ]] || { echo "리뷰 결과가 비었습니다" >&2; exit 1; }
if [[ "$post" == 1 ]]; then
  {
    echo "<!-- script/ai-review.sh, ${engine} ${model} -->"
    echo "**AI 리뷰** (작성 세션과 다른 세션, \`script/ai-review.sh\`, \`${engine}${model:+ ${model}}\`). 작성자는 지적마다 수용·거부로 답한다."
    echo
    cat "$out"
  } > "$work/comment.md"
  gh pr comment "$pr" --body-file "$work/comment.md"
fi
echo "$out"
