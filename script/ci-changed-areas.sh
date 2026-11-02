#!/usr/bin/env bash
# CI에서 바뀐 파일을 보고 Java 검사와 프론트 검사가 필요한지 정한다. 쓰는 곳: .github/workflows/build.yml, frontend.yml
# 사용: script/ci-changed-areas.sh <비교 기준 커밋> [대상 커밋, 기본 HEAD]
# 결과: java=true|false, frontend=true|false를 $GITHUB_OUTPUT(없으면 표준 출력)에 쓴다.
# frontend/ 밖의 파일이 하나라도 바뀌면 Java 검사를 돈다. 판단할 수 없으면 둘 다 돈다.
set -uo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

base="${1:-}"
head="${2:-HEAD}"
out="${GITHUB_OUTPUT:-/dev/stdout}"

write_result() {
  echo "java=$1" >> "$out"
  echo "frontend=$2" >> "$out"
  echo "ci-changed-areas: java=$1 frontend=$2 ($3)" >&2
}

# 새 브랜치의 첫 push는 비교 기준이 0으로 채워져 온다. 기록에 없는 커밋도 비교할 수 없다.
if [[ -z "$base" || "$base" =~ ^0+$ ]] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
  write_result true true "비교 기준 없음"
  exit 0
fi

# 세 점 비교는 공통 조상부터 대상까지라, 그사이 기준 브랜치에 들어온 변경은 섞이지 않는다.
if ! changed="$(git diff --name-only "${base}...${head}")"; then
  write_result true true "비교 실패"
  exit 0
fi

java=false
frontend=false
count=0
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  count=$((count + 1))
  case "$path" in
    frontend/*) frontend=true ;;
    .github/workflows/frontend.yml) frontend=true ;;
    script/ci-changed-areas.sh) frontend=true; java=true ;;
    *) java=true ;;
  esac
done <<< "$changed"

write_result "$java" "$frontend" "바뀐 파일 ${count}개"
