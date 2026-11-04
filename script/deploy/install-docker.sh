#!/usr/bin/env bash
# Ubuntu 서버(배포 EC2, Jenkins EC2)에 Docker Engine과 compose 플러그인을 설치한다(ISE-174).
#   bash script/deploy/install-docker.sh
# Docker 공식 문서의 Ubuntu apt 저장소 설치 순서를 그대로 따른다. 끝나면 다시 로그인해야 sudo 없이 docker를 쓴다.
set -euo pipefail
sudo apt-get update
sudo apt-get install -y ca-certificates curl git jq
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
codename=$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
docker --version
docker compose version
echo "설치했다. 로그아웃 뒤 다시 접속하면 sudo 없이 docker를 쓸 수 있다"
