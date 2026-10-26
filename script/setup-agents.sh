#!/usr/bin/env bash
# 공용 스킬(.agents/skills)을 Claude Code가 찾는 위치(.claude/skills)에 연결한다.
# 사용: script/setup-agents.sh   (clone한 뒤 한 번, pull로 스킬이 추가·삭제·변경되면 다시 실행)
# 정본은 .agents/skills다. 이 스크립트가 만든 항목은 Git에 올리지 않는다(.gitignore).
# 심볼릭 링크를 만들 수 없는 환경(Windows 기본 설정)에서는 복사한다. 복사본은 원본이 바뀔 때마다 다시 실행해야 한다.
# 이 스크립트가 만든 항목만 지우고 바꾼다. 같은 폴더에 둔 개인 스킬은 건드리지 않는다.
# Codex는 .agents/skills를 직접 읽으므로 이 스크립트가 필요 없다.
set -euo pipefail
cd "$(dirname "$0")/.."

# 이 스크립트가 만든 복사본 목록. 링크는 가리키는 원본으로 알아보고, 복사본은 이 목록으로 알아본다.
manifest=.claude/.setup-agents-files
linked=0
copied=0
skipped=0

# 폴더 자체가 링크면 그 안을 지우거나 덮어쓸 때 링크가 가리키는 원본(다른 폴더)이 바뀐다
if [[ -L .claude/skills ]]; then
  echo ".claude/skills가 심볼릭 링크라 중단한다. 링크를 지우고 다시 실행한다." >&2
  exit 1
fi
mkdir -p .claude/skills

# $1: 경로. 이 스크립트가 만든 항목이면 성공
# 링크는 원본 폴더를 가리킬 때만 우리 것이다. 같은 이름을 사용자가 직접 만든 폴더로 바꿨다면 건드리지 않는다.
is_ours() {
  if [[ -L "$1" ]]; then
    case "$(readlink "$1")" in
      ../../.agents/skills/*) return 0 ;;
    esac
    return 1
  fi
  [[ -f "$manifest" ]] && grep -Fxq "copy $1" "$manifest"
}

# 이전 실행 결과(원본이 사라진 링크, 복사본)를 정리한다
for entry in .claude/skills/*; do
  [[ -e "$entry" || -L "$entry" ]] || continue
  if is_ours "$entry"; then
    rm -rf "$entry"
  fi
done
: > "$manifest"

# $1: 연결할 원본(저장소 루트 기준), $2: 만들 위치. 링크 대상은 만들 위치에서 본 상대 경로로 둔다.
place() {
  local src="$1" dst="$2" rel="../../$1"
  if [[ -e "$dst" || -L "$dst" ]]; then
    echo "건너뜀: $dst (직접 둔 파일이 있다. 공용 항목을 쓰려면 옮기거나 이름을 바꾼다)" >&2
    skipped=$((skipped + 1))
    return
  fi
  if ln -s "$rel" "$dst" 2>/dev/null && [[ -L "$dst" && -e "$dst" ]]; then
    linked=$((linked + 1))
  else
    rm -rf "$dst"
    cp -R "$src" "$dst"
    echo "copy $dst" >> "$manifest"
    copied=$((copied + 1))
  fi
}

for dir in .agents/skills/*/; do
  name=$(basename "$dir")
  [[ -f "$dir/SKILL.md" ]] || continue
  place ".agents/skills/$name" ".claude/skills/$name"
done

printf '연결 %d개, 복사 %d개, 건너뜀 %d개 (.claude/skills)\n' "$linked" "$copied" "$skipped"
if [[ $copied -gt 0 ]]; then
  echo "복사본은 원본이 바뀌면 다시 실행해야 한다."
fi
