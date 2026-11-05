---
title: 팀원에게 받은 공개 키와 IP로 배포 서버 SSH 접속을 열어 주는 방법
date: 2026-11-05
updated: 2026-11-05
asker: Junhyun An
level: 기초
tags: [ssh, aws, security-group, deployment, team]
---

# 팀원에게 받은 공개 키와 IP로 배포 서버 SSH 접속을 열어 주는 방법

## 질문
팀원들이 배포 서버(VM)에 접속할 수 있게 필요한 정보를 받았다. 이걸로 어떻게 하나.

## 짧은 답
두 곳에 등록하면 된다.

| 어디 | 무엇을 | 누가 |
|---|---|---|
| 배포 서버 `~/.ssh/authorized_keys` | 팀원 **공개 키** 한 줄씩 | 서버에 들어갈 수 있는 사람(계정 주인 또는 에이전트) |
| AWS 보안 그룹 `iseonjwa-deploy-sg` 인바운드 22 | 팀원 **IP/32** 한 줄씩 | AWS 콘솔을 쓸 수 있는 사람(계정 주인) |

- 둘 다 있어야 들어온다.
  - 보안 그룹은 "어디서 오는 연결을 받을지"를 정한다.
  - `authorized_keys`는 "누구의 키를 받을지"를 정한다.
- 팀원은 자기 개인 키로 `ssh ubuntu@52.63.10.174`에 접속하고, 관리 화면은 SSH 터널로 연다.

## 자세히

### 1. 받은 정보 확인
- **공개 키**
  - `ssh-ed25519 AAAA... 이름` 같은 한 줄이고, 파일로는 `.pub`다.
  - 공개 키는 비밀값이 아니라 메신저로 받아도 된다.
  - 첫 줄에 "OPENSSH PRIVATE KEY"라는 머리말이 있는 여러 줄 파일이면 **개인 키**다. 받으면 안 되는 것이므로 그 팀원에게 키를 새로 만들게 한다.
- **IP**
  - 팀원이 지금 쓰는 공인 IPv4다. `curl https://checkip.amazonaws.com`이나 "내 IP" 검색으로 확인한다.
  - 사설 주소(`192.168.x.x`, `10.x.x.x`)는 쓸 수 없다.
  - 팀원 IP는 저장소 문서에 적지 않는다(개인 정보).

### 2. 서버에 공개 키 등록
- 지금 `authorized_keys`에는 두 줄이 있다(2026-11-05 확인).
  - 계정 주인 키 `iseonjwa-deploy`
  - Jenkins 배포 키 `iseonjwa-jenkins-deploy`
- 이 두 줄을 지우면 계정 주인과 Jenkins가 못 들어오므로 반드시 **덧붙이기**(`>>`)로 넣는다.

```bash
# 계정 주인 맥에서 배포 서버 접속
ssh -i ~/.ssh/iseonjwa-deploy.pem ubuntu@52.63.10.174

# 1) 키 형식 확인. 지문이 나오면 정상이다
echo 'ssh-ed25519 AAAA...(팀원 공개 키) kwonwoo' | ssh-keygen -lf -

# 2) 덧붙이기. 줄 끝 주석에 팀원 이름을 넣어 두면 나중에 한 명만 지우기 쉽다
echo 'ssh-ed25519 AAAA...(팀원 공개 키) kwonwoo' >> ~/.ssh/authorized_keys

# 3) 전체 확인. 기존 두 줄과 새 줄이 모두 보여야 한다
ssh-keygen -lf ~/.ssh/authorized_keys
```

- **세션 유지:** 접속한 세션을 닫기 전에 다른 터미널에서 계정 주인 키로 다시 들어가지는지 확인한다. 실수로 파일을 망가뜨려도 열린 세션에서 고칠 수 있다.
- 권한은 그대로 둔다(`-rw------- ubuntu`). `>>`는 권한을 바꾸지 않는다.
- 비밀번호 로그인은 꺼져 있다(`PasswordAuthentication no`). 키가 없는 사람은 들어오지 못한다.

### 3. 보안 그룹에 IP 등록 (AWS 콘솔)
1. EC2 → 네트워크 및 보안 → 보안 그룹 → `iseonjwa-deploy-sg` → 인바운드 규칙 → **인바운드 규칙 편집**.
2. 규칙 추가를 누르고 다음처럼 채운다.
   - 유형: SSH(포트 22)
   - 소스: 사용자 지정, `팀원IP/32`
   - 설명: `ssh-kwonwoo`처럼 이름을 넣는다
3. 규칙 저장.
- `/32`는 "이 주소 하나만"이라는 뜻이다. `0.0.0.0/0`(전체)으로 열지 않는다.
- 기존 규칙은 지우지 않는다. 계정 주인 IP와 Jenkins 보안 그룹 규칙이 있어야 계정 주인 SSH와 자동 배포가 된다.
- Jenkins 화면까지 볼 팀원이면 `iseonjwa-jenkins-sg`에도 같은 규칙을 더한다.
- **IP는 바뀐다.** 집, 카페, 교육장마다 공인 IP가 다르다. 팀원이 "갑자기 접속이 안 된다"고 하면 대개 IP가 바뀐 것이다. 새 IP를 받아 규칙의 소스만 고친다.

### 4. 팀원이 접속하는 방법 (팀원에게 그대로 전달)
```bash
# 접속 (개인 키 경로는 각자 만든 위치)
ssh -i ~/.ssh/id_ed25519 ubuntu@52.63.10.174

# 매번 치기 번거로우면 ~/.ssh/config에 적는다
Host iseonjwa
  HostName 52.63.10.174
  User ubuntu
  IdentityFile ~/.ssh/id_ed25519
# 그 뒤에는: ssh iseonjwa

# 관리 화면은 SSH 터널로 연다 (접속을 유지한 채 브라우저에서 localhost로 연다)
ssh -N -L 3000:127.0.0.1:3000 -L 8085:127.0.0.1:8085 -L 9090:127.0.0.1:9090 \
  -L 9093:127.0.0.1:9093 -L 9411:127.0.0.1:9411 iseonjwa
# Grafana http://localhost:3000, Kafka-UI :8085, Prometheus :9090, Alertmanager :9093, Zipkin :9411
```
- Windows는 PowerShell의 `ssh`(Windows 10 이상 기본 포함)나 Git Bash를 쓴다. 키 경로는 `C:\Users\<이름>\.ssh\id_ed25519`다.
- 처음 접속하면 서버 지문을 묻는다. `yes`로 받는다.

### 5. 팀원에게 같이 알려 줄 규칙
`ubuntu` 계정은 `sudo`와 `docker` 그룹에 들어 있어 서버의 모든 것을 할 수 있다. 그래서 다음은 하지 않는다.

| 하지 않는다 | 이유 |
|---|---|
| `docker compose down -v`, `docker volume rm` | DB·Kafka 데이터가 지워진다 |
| 서버에서 직접 `deploy.sh`나 `git checkout` | 배포는 main 머지 → Jenkins로만 한다. 손으로 바꾸면 `.deploy/current`와 실제 상태가 어긋난다 |
| `cat .env`, `.env` 복사·공유 | 운영 비밀값이 들어 있다 |
| `~/.ssh/authorized_keys` 수정 | 다른 사람과 Jenkins의 접속이 끊길 수 있다 |

- 해도 되는 것은 읽기 위주다.
  - `docker ps`
  - `docker logs -f iseonjwa-reservation-service-1`
  - `docker stats`
  - SSH 터널로 Grafana·Zipkin 보기
- 컨테이너 재시작이 필요하면 팀 채널에 먼저 알린다.

### 6. 접속을 거둘 때
- `authorized_keys`에서 그 팀원 이름 주석이 달린 줄을 지운다.
- 보안 그룹에서 그 팀원 규칙을 지운다.
- 사람마다 키가 따로라 한 명만 거둘 수 있다. 키 페어 `.pem`을 돌려 쓰지 않는 이유다.

## 면접 포인트
- 서버 접근은 두 층으로 막는다. 네트워크 층(보안 그룹: 어디서)과 인증 층(SSH 키: 누구)이다. 하나가 뚫려도 다른 하나가 막는다.
- 사람마다 키를 따로 등록하면 접속을 한 명씩 거둘 수 있고, 로그(`/var/log/auth.log`)에 어느 키 지문으로 들어왔는지 남는다. 키 하나를 돌려 쓰면 둘 다 안 된다.
- 계정을 사람마다 나누면(리눅스 사용자 분리) 권한도 나눌 수 있다. 이 프로젝트는 팀 규모가 작아 `ubuntu` 하나에 키만 나누고, 하지 말아야 할 일을 규칙으로 정했다.

## 관련 문서
- `docs/design/infra/deployment.md` 3절(보안 그룹 표), 5절 서버 준비 순서 8번(팀원 SSH)
- [배포용 AWS 계정 초기 설정(팀원은 어떻게 접속하나)](2026-11-04-aws-account-initial-setup.md)
- [관리 화면을 IP로 열 수 있나(SSH 터널)](2026-11-04-admin-ui-access-by-ip.md)
