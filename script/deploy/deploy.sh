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
  # 새 이미지를 받기 전에 옛 버전 이미지를 지운다. 이번 커밋, 직전 성공 커밋, 그 앞의 성공 커밋은 남긴다
  local older=""
  [[ -f "$state/previous" ]] && older="$(cat "$state/previous")"
  prune_old_images "$sha" "$previous" "$older"
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
  echo "== 이미지 받기가 ${PULL_ATTEMPTS}번 모두 실패했다. 디스크 여유: $(disk_free)" >&2
  return 1
}

# Note: 배포마다 서비스 9개의 이미지가 커밋 SHA 태그로 쌓인다. 지우지 않으면 디스크가 차서 새 이미지를 받지 못한다.
# 11/12 배포가 그렇게 실패했다(TS-60: 이미지 197개 32.7GB, 디스크 100%, `no space left on device`).
# 가장 최근에 만들어진 KEEP_IMAGE_VERSIONS개 버전과 인자로 받은 커밋의 이미지만 남긴다.
# 지운 버전으로 되돌려야 하면(Jenkins의 TAG) GHCR에서 다시 받는다.
KEEP_IMAGE_VERSIONS="${KEEP_IMAGE_VERSIONS:-3}"
IMAGE_REPO_PREFIX="${IMAGE_REPO_PREFIX:-ghcr.io/iseonjwa/}"

disk_free() {
  df -h / 2> /dev/null | awk 'NR==2 {print $4 " 남음(" $5 " 사용)"}'
}

# 인자: 꼭 남길 커밋 SHA들(빈 값은 무시한다). 정리가 실패해도 배포를 막지 않는다.
# 실행 중인 컨테이너가 쓰는 이미지는 docker가 지우기를 거절하므로 남는다.
prune_old_images() {
  local keep=" $* " tags tag image rank=0 removed=0
  # 우리 이미지 중 태그가 40자 SHA인 것의 태그를, 이미지가 만들어진 시각 최신순으로 중복 없이 모은다
  tags="$(docker images --format '{{.CreatedAt}}|{{.Repository}}|{{.Tag}}' 2> /dev/null \
    | awk -F'|' -v prefix="$IMAGE_REPO_PREFIX" 'index($2, prefix) == 1 && $3 ~ /^[0-9a-f]{40}$/ {print $1 "|" $3}' \
    | sort -r | cut -d'|' -f2 | awk '!seen[$0]++')" || true
  for tag in $tags; do
    rank=$(( rank + 1 ))
    if (( rank <= KEEP_IMAGE_VERSIONS )) || [[ "$keep" == *" $tag "* ]]; then
      continue
    fi
    while IFS= read -r image; do
      [[ -n "$image" ]] && docker rmi "$image" > /dev/null 2>&1 || true
    done < <(docker images --format '{{.Repository}}:{{.Tag}}' 2> /dev/null | grep -F ":$tag" | grep -F "$IMAGE_REPO_PREFIX" || true)
    removed=$(( removed + 1 ))
  done
  # 태그가 없는 이미지(받다가 실패한 조각 등)도 지운다. 태그가 있는 이미지는 건드리지 않는다
  docker image prune -f > /dev/null 2>&1 || true
  echo "== 옛 이미지 정리: 버전 ${removed}개를 지웠다. 디스크 여유: $(disk_free)"
}

# 시험이 함수만 불러 쓸 수 있게, source로 읽으면 배포를 시작하지 않는다.
[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0
main "$@"
exit $?
