#!/usr/bin/env bash
# 학습·AI 판단 기록이 약속한 구조를 지키는지 검사한다. 규칙: AGENTS.md, .agents/skills/ai-judgment, learning-note
# 사용: script/records-check.sh          전체 검사
#      script/records-check.sh --hook   에이전트 종료 훅용. 어긋남이 있으면 exit 2로 에이전트에게 알린다
# 기록을 남겼는지(내용)는 검사하지 못한다. 구조와 색인, 필수 머리말, 비밀값 흔적만 본다.
# Note: Windows Git Bash는 프로세스를 하나 띄울 때마다 수십 ms가 든다. 훅 시한(30초) 안에 끝나도록
#       파일마다 외부 명령을 여러 번 띄우지 않는다. 머리말은 파일당 awk 한 번, 색인은 한 번 읽어 bash로 찾고,
#       비밀값은 git grep 한 번으로 본다(ISE-220). macOS 기본 bash 3.2에서도 돌도록 연관 배열을 쓰지 않는다.
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
  if [[ "$input" =~ \"stop_hook_active\"[[:space:]]*:[[:space:]]*true ]]; then
    exit 0
  fi
fi

root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "git 저장소가 아니다" >&2; exit 2; }
cd "$root"

errors=""
warns=""
err() { errors+="  - $1"$'\n'; }
warn() { warns+="  - $1"$'\n'; }

# $1: 파일. 머리말(첫 줄 ---부터 다음 --- 전까지)의 필수 키를 fm_<키> 변수에 담고,
# 본문 어디든 '근거: 미기재'가 있으면 fm_geun_missing=1로 둔다. 키가 두 번 나오면 처음 값을 쓴다.
read_front() {
  fm_title="" fm_date="" fm_updated="" fm_judge="" fm_ai="" fm_verdict="" fm_area="" fm_presentation="" fm_shared=""
  fm_geun_missing=0
  local out line rest k v
  out=$(awk '
    NR == 1 { if ($0 == "---") { infm = 1; next } }
    infm && $0 == "---" { infm = 0; next }
    infm { i = index($0, ":"); if (i > 0) { k = substr($0, 1, i - 1); if (!(k in seen)) { seen[k] = 1; v = substr($0, i + 1); sub(/^ +/, "", v); print "F\t" k "\t" v } } }
    /근거: *미기재/ { g = 1 }
    END { if (g) print "G" }
  ' "$1")
  while IFS= read -r line; do
    case "$line" in
      G) fm_geun_missing=1 ;;
      F$'\t'*)
        rest=${line#F$'\t'}
        k=${rest%%$'\t'*}
        v=${rest#*$'\t'}
        case "$k" in
          title) fm_title=$v ;; date) fm_date=$v ;; updated) fm_updated=$v ;; judge) fm_judge=$v ;;
          ai) fm_ai=$v ;; verdict) fm_verdict=$v ;; area) fm_area=$v ;;
          presentation) fm_presentation=$v ;; shared) fm_shared=$v ;;
        esac ;;
    esac
  done <<< "$out"
}

# $1: 색인 내용(문자열), $2: 찾을 문자열. 들어 있으면 성공(grep -F와 같은 부분 문자열 일치)
linked() { [[ "$1" == *"$2"* ]]; }

aj=docs/ai-judgments
aj_index=""
[[ -f "$aj/README.md" ]] && aj_index=$(<"$aj/README.md")
if [[ -d "$aj" ]]; then
  for entry in "$aj"/*; do
    [[ -e "$entry" ]] || continue
    name=${entry##*/}
    if [[ -f "$entry" ]]; then
      [[ "$name" == README.md ]] || err "$entry: docs/ai-judgments 바로 아래에는 README.md만 둔다. 기록은 <이름>/ 폴더에 둔다"
      continue
    fi
    [[ -f "$aj/README.md" ]] && ! linked "$aj_index" "$name/README.md" && err "$aj/README.md: 팀원별 기록 표에 $name/README.md 링크가 없다"
    idx="$entry/README.md"
    idx_content=""
    if [[ ! -f "$idx" ]]; then
      err "$idx: 없다. 개별 기록 색인과 일지 목록을 둔다"
      idx=/dev/null
    else
      idx_content=$(<"$idx")
    fi
    for f in "$entry"/*.md; do
      [[ -e "$f" ]] || continue
      base=${f##*/}
      [[ "$base" == README.md ]] && continue
      if [[ ! "$base" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9]+(-[a-z0-9]+)*\.md$ ]]; then
        err "$f: 파일 이름은 YYYY-MM-DD-<영문-kebab-slug>.md"
        continue
      fi
      read_front "$f"
      for key in title date updated judge ai verdict area presentation shared; do
        var="fm_$key"
        [[ -n "${!var}" ]] || err "$f: 머리말 ${key}가 없다"
      done
      v=$fm_verdict; case "$v" in 수용|"부분 수용"|거부|보류|"") ;; *) err "$f: verdict는 수용|부분 수용|거부|보류 중 하나 (현재: $v)" ;; esac
      p=$fm_presentation; case "$p" in 후보|아님|"") ;; *) err "$f: presentation은 후보|아님 중 하나 (현재: $p)" ;; esac
      s=$fm_shared; case "$s" in 예|아니오|"") ;; *) err "$f: shared는 예|아니오 중 하나 (현재: $s)" ;; esac
      [[ "$idx" != /dev/null ]] && ! linked "$idx_content" "$base" && err "$idx: $base 링크가 없다"
      if [[ "$s" == 예 && -f "$aj/README.md" ]] && ! linked "$aj_index" "$name/$base"; then
        err "$aj/README.md: shared: 예인 ${name}/${base}가 팀 공통 판단 표에 없다"
      fi
      [[ "$fm_geun_missing" == 1 ]] && warn "$f: 근거가 미기재다. 판단자가 채운다"
    done
    for f in "$entry"/daily/*; do
      [[ -e "$f" ]] || continue
      base=${f##*/}
      if [[ ! "$base" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\.md$ ]]; then
        err "$f: 일지 파일 이름은 YYYY-MM-DD.md"
        continue
      fi
      [[ "$idx" != /dev/null ]] && ! linked "$idx_content" "daily/$base" && err "$idx: 일지 daily/$base 링크가 없다"
    done
  done
fi

lr=docs/learning
lr_index=""
[[ -f "$lr/README.md" ]] && lr_index=$(<"$lr/README.md")
if [[ -d "$lr" ]]; then
  for entry in "$lr"/*/; do
    [[ -d "$entry" ]] || continue
    name=${entry%/}; name=${name##*/}
    [[ -f "$lr/README.md" ]] && ! linked "$lr_index" "$name/README.md" && err "$lr/README.md: 표에 $name/README.md 링크가 없다"
    if [[ ! -f "${entry}README.md" ]]; then
      err "${entry}README.md: 없다"
      continue
    fi
    member_index=$(<"${entry}README.md")
    for f in "${entry}"qna/*.md; do
      [[ -e "$f" ]] || continue
      linked "$member_index" "qna/${f##*/}" || err "${entry}README.md: 질문 노트 qna/${f##*/} 링크가 없다"
    done
  done
fi

# 비밀값 흔적: 추적 중이거나 새로 만든 파일만 본다(.gitignore 제외). git grep 한 번으로 모든 파일을 본다
# Note: core.quotePath=false가 없으면 한글 경로가 "docs/\353..."처럼 8진수로 나와 어느 파일인지 읽기 어렵다.
#       이전 방식(git ls-files + [[ -f ]])은 그 따옴표 경로를 파일로 찾지 못해 한글 이름 파일의 비밀값을 건너뛰었다(ISE-220)
secret_pattern='ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'
hits=$(git -c core.quotePath=false grep -IEnH --untracked -e "$secret_pattern" -- . ':!script/records-check.sh' 2>/dev/null | cut -c1-120)
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
