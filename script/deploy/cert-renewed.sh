#!/usr/bin/env bash
# HTTPS 인증서가 갱신되면 certbot이 부른다(갱신 훅, ISE-278). 프론트 nginx가 새 인증서를 다시 읽게 한다.
#   설치(이 파일이 든 배포 뒤 서버 저장소 루트에서 한 번, 이 파일을 고치면 다시):
#     sudo install -m 755 script/deploy/cert-renewed.sh /etc/letsencrypt/renewal-hooks/deploy/iseonjwa-nginx.sh
#   certbot은 renewal-hooks/deploy/의 실행 파일을 갱신에 성공할 때마다 돌린다. 저장소 안 경로를 --deploy-hook으로 걸지 않는 것은
#   배포·되돌림이 저장소를 다른 커밋으로 바꿔 파일이 없어질 수 있고, 인증서를 처음 받을 때(PR 663 배포 시점)는 이 파일이 아직 없어
#   certbot이 훅 경로 검사에서 멈추기 때문이다.
# 인증서는 /etc/letsencrypt를 폴더째 마운트해 컨테이너가 새 파일을 바로 보지만, nginx는 다시 읽기 전까지 옛 인증서를 쓴다.
# Note: compose 명령(docker compose exec)은 IMAGE_TAG 같은 배포 변수가 있어야 해서 쓰지 않고, 라벨로 컨테이너를 찾는다.
# 컨테이너를 다시 만들지 않으므로 서버에서 compose를 손으로 띄우지 않는 규칙(docs/guides/deploy-server-access.md 4절)과 겹치지 않는다.
set -euo pipefail
container=$(docker ps -q --filter label=com.docker.compose.project=iseonjwa --filter label=com.docker.compose.service=frontend)
if [[ -z "$container" ]]; then
  echo "frontend 컨테이너가 떠 있지 않다. 다음 배포에서 컨테이너가 새로 뜰 때 새 인증서를 읽는다" >&2
  exit 0
fi
# 설정 검사가 실패하면 다시 읽지 않는다(지금 인증서로 계속 서빙한다). 실패는 certbot 로그와 cert-check.sh 알림으로 드러난다
docker exec "$container" nginx -t
docker exec "$container" nginx -s reload
echo "nginx가 새 인증서를 다시 읽었다"
