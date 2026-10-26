#!/usr/bin/env bash
# 학습·AI 판단 기록이 약속한 구조를 지키는지 검사한다. 규칙: AGENTS.md, .agents/skills/ai-judgment, learning-note
# 사용: script/records-check.sh          전체 검사
#      script/records-check.sh --hook   에이전트 종료 훅용. 어긋남이 있으면 exit 2로 에이전트에게 알린다
# 기록을 남겼는지(내용)는 검사하지 못한다. 구조와 색인, 필수 머리말, 비밀값 흔적만 본다.
set -uo pipefail

mode=check
case "${1:-}" in
  "") ;;
  --hook) mode=hook ;;
  -h|--help) sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
esac

if [[ "$mode" == hook ]]; then
  input="$(cat 2>/dev/null || true)"
  # 훅이 이미 에이전트를 한 번 이어서 실행시켰다면 다시 막지 않는다(무한 반복 방지)
  if grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true' <<< "$input"; then
    exit 0
  fi
fi

root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "git 저장소가 아니다" >&2; exit 2; }
cd "$root"

errors=""
warns=""
err() { errors+="  - $1"$'\n'; }
warn() { warns+="  - $1"$'\n'; }

# $1: 파일, $2: 머리말 키. 값을 출력한다
front() {
  awk -v key="$2" '
    NR == 1 && $0 != "---" { exit }
    NR > 1 && $0 == "---" { exit }
    NR > 1 { i = index($0, ":"); if (i > 0 && substr($0, 1, i - 1) == key) { v = substr($0, i + 1); sub(/^ +/, "", v); print v; exit } }
  ' "$1"
}

# $1: 색인 파일, $2: 찾을 문자열. 색인에 있으면 성공
linked() { grep -Fq -- "$2" "$1"; }

aj=docs/ai-judgments
if [[ -d "$aj" ]]; then
  for entry in "$aj"/*; do
    [[ -e "$entry" ]] || continue
    name=$(basename "$entry")
    if [[ -f "$entry" ]]; then
      [[ "$name" == README.md ]] || err "$entry: docs/ai-judgments 바로 아래에는 README.md만 둔다. 기록은 <이름>/ 폴더에 둔다"
      continue
    fi
    [[ -f "$aj/README.md" ]] && ! linked "$aj/README.md" "$name/README.md" && err "$aj/README.md: 팀원별 기록 표에 $name/README.md 링크가 없다"
    idx="$entry/README.md"
    if [[ ! -f "$idx" ]]; then
      err "$idx: 없다. 개별 기록 색인과 일지 목록을 둔다"
      idx=/dev/null
    fi
    for f in "$entry"/*.md; do
      [[ -e "$f" ]] || continue
      base=$(basename "$f")
      [[ "$base" == README.md ]] && continue
      if [[ ! "$base" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9]+(-[a-z0-9]+)*\.md$ ]]; then
        err "$f: 파일 이름은 YYYY-MM-DD-<영문-kebab-slug>.md"
        continue
      fi
      for key in title date updated judge ai verdict area presentation shared; do
        [[ -n "$(front "$f" "$key")" ]] || err "$f: 머리말 ${key}가 없다"
      done
      v=$(front "$f" verdict); case "$v" in 수용|"부분 수용"|거부|보류|"") ;; *) err "$f: verdict는 수용|부분 수용|거부|보류 중 하나 (현재: $v)" ;; esac
      p=$(front "$f" presentation); case "$p" in 후보|아님|"") ;; *) err "$f: presentation은 후보|아님 중 하나 (현재: $p)" ;; esac
      s=$(front "$f" shared); case "$s" in 예|아니오|"") ;; *) err "$f: shared는 예|아니오 중 하나 (현재: $s)" ;; esac
      [[ "$idx" != /dev/null ]] && ! linked "$idx" "$base" && err "$idx: $base 링크가 없다"
      if [[ "$s" == 예 && -f "$aj/README.md" ]] && ! linked "$aj/README.md" "$name/$base"; then
        err "$aj/README.md: shared: 예인 ${name}/${base}가 팀 공통 판단 표에 없다"
      fi
      grep -q '근거: *미기재' "$f" && warn "$f: 근거가 미기재다. 판단자가 채운다"
    done
    for f in "$entry"/daily/*; do
      [[ -e "$f" ]] || continue
      base=$(basename "$f")
      if [[ ! "$base" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\.md$ ]]; then
        err "$f: 일지 파일 이름은 YYYY-MM-DD.md"
        continue
      fi
      [[ "$idx" != /dev/null ]] && ! linked "$idx" "daily/$base" && err "$idx: 일지 daily/$base 링크가 없다"
    done
  done
fi

lr=docs/learning
if [[ -d "$lr" ]]; then
  for entry in "$lr"/*/; do
    [[ -d "$entry" ]] || continue
    name=$(basename "$entry")
    [[ -f "$lr/README.md" ]] && ! linked "$lr/README.md" "$name/README.md" && err "$lr/README.md: 표에 $name/README.md 링크가 없다"
    if [[ ! -f "${entry}README.md" ]]; then
      err "${entry}README.md: 없다"
      continue
    fi
    for f in "${entry}"qna/*.md; do
      [[ -e "$f" ]] || continue
      linked "${entry}README.md" "qna/$(basename "$f")" || err "${entry}README.md: 질문 노트 qna/$(basename "$f") 링크가 없다"
    done
  done
fi

# 비밀값 흔적: 추적 중이거나 새로 만든 파일만 본다(.gitignore 제외)
secret_pattern='ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
hits=$( { git ls-files; git ls-files --others --exclude-standard; } | sort -u | while IFS= read -r f; do
  [[ -f "$f" && "$f" != script/records-check.sh ]] && grep -IEnH -- "$secret_pattern" "$f"
done | cut -c1-120)
[[ -z "$hits" ]] || err "비밀값으로 보이는 문자열이 있다. 지우고 값을 폐기한다:
$hits"

[[ -z "$warns" ]] || printf 'records-check 경고:\n%s' "$warns"

if [[ -z "$errors" ]]; then
  echo "records-check: OK"
  exit 0
fi

message="records-check: 기록 구조가 어긋났다.
${errors}규칙: docs/ai-judgments/README.md, docs/learning/README.md, .agents/skills/ai-judgment/SKILL.md"
if [[ "$mode" == hook ]]; then
  echo "$message" >&2
  exit 2
fi
echo "$message"
exit 1
