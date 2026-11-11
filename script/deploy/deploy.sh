#!/usr/bin/env bash
# 배포 서버에서 커밋 하나를 배포한다(ISE-174). Jenkins(ISE-175)도 SSH로 이 스크립트를 부른다.
#   script/deploy/deploy.sh <40자 커밋 SHA>
#
# 순서: 저장소를 그 커밋으로 맞춤 → GHCR에서 이미지 받기 → compose up(헬스까지 기다림) → 스모크 테스트.
# 실패하면 직전에 성공한 커밋으로 되돌리고 실패로 끝낸다.
# 종료 코드: 0 성공, 1 실패했고 되돌림 성공(또는 되돌릴 커밋 없음), 2 사용법 오류, 3 되돌림도 실패.
#
# Note: 이 스크립트는 git checkout으로 자기 파일이 바뀌는 중에 돈다. bash는 파일을 읽으며 실행하므로,
# 전체를 main 함수 안에 두어 실행 전에 끝까지 읽게 한다.

main() {
  set -euo pipefail
  local sha="${1:-}"
  if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "사용법: script/deploy/deploy.sh <40자 커밋 SHA>" >&2
    return 2
  fi

  cd "$(dirname "$0")/../.."
  local state=.deploy
  mkdir -p "$state"
  local previous=""
  [[ -f "$state/current" ]] && previous="$(cat "$state/current")"

  echo "== 배포 시작: $sha (직전 성공: ${previous:-없음})"
  if deploy_once "$sha"; then
    [[ -n "$previous" && "$previous" != "$sha" ]] && echo "$previous" > "$state/previous"
    echo "$sha" > "$state/current"
    echo "== 배포 성공: $sha"
    return 0
  fi

  echo "== 배포 실패: $sha" >&2
  if [[ -z "$previous" || "$previous" == "$sha" ]]; then
    echo "== 되돌릴 직전 커밋이 없다. 사람이 확인한다" >&2
    return 1
  fi
  echo "== 직전 성공 커밋으로 되돌린다: $previous" >&2
  if deploy_once "$previous"; then
    echo "== 되돌림 성공: $previous" >&2
    return 1
  fi
  echo "== 되돌림도 실패했다. 서버에 접속해 확인한다" >&2
  return 3
}

deploy_once() {
  local sha="$1"
  local compose=(docker compose -f compose.yml -f compose.deploy.yml)
  # compose 파일·config-repo·관측 설정을 이미지와 같은 커밋으로 맞춘다.
  git fetch --quiet origin || return 1
  git checkout --quiet --detach "$sha" || return 1
  export IMAGE_TAG="$sha"
  pull_with_retry "${compose[@]}" || return 1
  # --wait: healthcheck가 있는 컨테이너가 healthy가 될 때까지 기다린다. 앱 기동(마이그레이션 포함)을 넉넉히 본다.
  "${compose[@]}" up -d --remove-orphans --wait --wait-timeout 420 || return 1
  script/deploy/smoke.sh || return 1
}

# Note: 11/11 배포에서 pull이 38초 만에 `context canceled`로 끝났고, containerd가 공유 레이어를
# "locked: unavailable"로 거절했다(TS-55). 이미지는 GHCR에 있었고 Jenkins 재실행으로 받혔으므로
# 일시적 실패로 보고 몇 번 다시 받는다. 마지막까지 실패하면 지금처럼 배포 실패 → 되돌림으로 간다.
PULL_ATTEMPTS="${PULL_ATTEMPTS:-3}"
PULL_RETRY_DELAY="${PULL_RETRY_DELAY:-10}"

# 인자는 compose 명령 앞부분이다(예: docker compose -f compose.yml -f compose.deploy.yml).
pull_with_retry() {
  local attempt
  for (( attempt = 1; attempt <= PULL_ATTEMPTS; attempt++ )); do
    "$@" pull --quiet && return 0
    if (( attempt < PULL_ATTEMPTS )); then
      echo "== 이미지 받기 실패($attempt/$PULL_ATTEMPTS). ${PULL_RETRY_DELAY}초 뒤 다시 받는다" >&2
      sleep "$PULL_RETRY_DELAY"
    fi
  done
  echo "== 이미지 받기가 $PULL_ATTEMPTS번 모두 실패했다" >&2
  return 1
}

# 시험이 함수만 불러 쓸 수 있게, source로 읽으면 배포를 시작하지 않는다.
[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0
main "$@"
exit $?
