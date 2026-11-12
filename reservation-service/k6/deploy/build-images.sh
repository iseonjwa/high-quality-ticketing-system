#!/usr/bin/env bash
# 배포 서버 단계 측정용 reservation-service 이미지를 단계 커밋마다 만들고, 원하면 배포 서버에 넣는다.
# 측정 브랜치(experiment/ISE-79-k6-steps) 전용이다. GHCR에는 올리지 않는다(main 릴리스 이미지만 올라가는 저장소다).
# 사용(워크트리 루트, Git Bash):
#   bash reservation-service/k6/deploy/build-images.sh                    # 이미지 6개를 로컬에 만든다
#   SHIP=1 DEPLOY_SSH=iseonjwa bash reservation-service/k6/deploy/build-images.sh   # 만든 뒤 배포 서버에 docker load
# 결과 이미지: iseonjwa-k6steps/reservation-service:<태그>(steps.sh의 IMAGE_COMMIT), 라벨 k6steps.commit=<커밋>
#
# Note: 커밋마다 임시 워크트리를 만들어 :reservation-service:bootJar만 빌드하고, Dockerfile.step(저장소 Dockerfile의 실행 단계와 같음)에
# jar를 얹는다. 서버에 보낼 때는 이미지 여러 개를 한 묶음으로 저장해 같은 기반 레이어를 한 번만 보낸다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
D="$ROOT/reservation-service/k6/deploy"
source "$D/steps.sh"
cd "$ROOT"

# Note: Windows는 경로 260자 제한이 있어 깊은 임시 폴더에서는 체크아웃이 실패했다(Filename too long).
# 기본 작업 폴더를 원래 저장소의 .claude/worktrees/k6img로 짧게 두고 긴 경로도 허용한다
MAIN_ROOT="$(cd "$(git rev-parse --git-common-dir)/.." && pwd)"
WORK="${WORK:-$MAIN_ROOT/.claude/worktrees/k6img}"
mkdir -p "$WORK"
TAGS="${TAGS:-$IMAGE_TAGS}"
echo "[images] 작업 폴더 $WORK, 태그: $TAGS"
for tag in $TAGS; do
  commit="${IMAGE_COMMIT[$tag]}"
  if [ "$(docker image inspect -f '{{index .Config.Labels "k6steps.commit"}}' "$IMAGE_REPO:$tag" 2>/dev/null)" = "$commit" ] && [ "${REBUILD:-0}" != 1 ]; then
    echo "[images] $tag($commit): 이미 있다. 다시 만들려면 REBUILD=1"
    continue
  fi
  src="$WORK/src-$tag"; ctx="$WORK/ctx-$tag"
  git -c core.longpaths=true worktree add -q --detach "$src" "$commit"
  echo "[images] $tag($commit): bootJar"
  ( cd "$src" && ./gradlew -q :reservation-service:bootJar --no-daemon )
  mkdir -p "$ctx"
  cp "$src/reservation-service/build/libs/app.jar" "$ctx/app.jar"
  cp "$D/Dockerfile.step" "$ctx/Dockerfile"
  docker build -q --build-arg COMMIT="$commit" -t "$IMAGE_REPO:$tag" "$ctx" >/dev/null
  git worktree remove --force "$src"
  echo "[images] $tag($commit): $IMAGE_REPO:$tag"
done

if [ "${SHIP:-0}" = 1 ]; then
  : "${DEPLOY_SSH:?배포 서버 SSH 대상(예: ~/.ssh/config의 Host 이름)}"
  refs=(); for tag in $TAGS; do refs+=("$IMAGE_REPO:$tag"); done
  echo "[images] 배포 서버($DEPLOY_SSH)에 보낸다: ${refs[*]}"
  docker save "${refs[@]}" | gzip | ssh -o BatchMode=yes "$DEPLOY_SSH" 'gunzip | docker load'
  for tag in $TAGS; do
    got="$(ssh -o BatchMode=yes "$DEPLOY_SSH" docker image inspect -f "'{{index .Config.Labels \"k6steps.commit\"}}'" "$IMAGE_REPO:$tag")"
    [ "$got" = "${IMAGE_COMMIT[$tag]}" ] || { echo "[images] 서버의 $tag 이미지 커밋이 다르다: $got" >&2; exit 1; }
  done
  echo "[images] 서버에 넣었다. 지울 때: docker image rm ${refs[*]}"
fi
