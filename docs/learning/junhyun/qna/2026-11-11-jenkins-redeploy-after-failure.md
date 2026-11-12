---
title: 배포가 실패하면 팀원은 Jenkins에 어떻게 들어가서 다시 배포하나
date: 2026-11-11
updated: 2026-11-11
asker: Junhyun An
level: 기초
tags: [jenkins, deploy, rollback, ssh-tunnel]
---

# 배포가 실패하면 팀원은 Jenkins에 어떻게 들어가서 다시 배포하나

## 질문
배포가 실패했을 때 다시 배포하려면 팀원들은 Jenkins에 어떻게 접속해야 하나? 로그인용 ID·비밀번호는 `.env`에 있나?

## 짧은 답
- Jenkins 화면은 인터넷에 열려 있지 않다. SSH 터널(`ssh -N -L 8081:127.0.0.1:8080 ubuntu@<Jenkins 공인 IP>`)을 켜 두고 브라우저에서 `http://localhost:8081`로 연다. 계정은 `admin` 하나다.
- 잡 `deploy-main`에서 "Build with Parameters"를 누른다. `TAG`를 비우면 main 최신 커밋을, 40자 커밋 SHA를 넣으면 그 커밋을 배포한다.
- 먼저 슬랙 알림의 종류를 본다. "직전 버전으로 되돌렸습니다"면 서비스는 이미 살아 있다. 같은 커밋을 다시 돌리기보다 고친 코드를 main에 머지하는 것이 맞다. Jenkins가 2분마다 main을 확인해 알아서 배포한다.

## 자세히

### 왜 터널로 들어가나
Jenkins는 배포 서버에 SSH로 들어갈 수 있는 키를 갖고 있다. 화면을 인터넷에 열면 로그인 화면 하나가 배포 서버의 문이 된다. 그래서 Jenkins의 8080은 보안 그룹에서 열지 않고 22만 연다(ADR 0044). SSH 터널은 내 컴퓨터의 8081 포트로 들어온 요청을 SSH 연결 안에 실어 Jenkins 서버의 `127.0.0.1:8080`으로 넘긴다. SSH에 성공한 사람, 즉 등록된 IP와 등록된 키를 가진 사람만 화면을 볼 수 있다.

### 접속 순서
1. 준비: 내 IP가 `iseonjwa-jenkins-sg`의 22번 규칙에 있어야 하고, 내 공개 키가 Jenkins 서버에 등록돼 있어야 한다(2026-11-05에 강민·권우·효승·영은 등록). Jenkins 비밀번호는 담당에게 개인 메시지로 받는다.
2. Jenkins 공인 IP를 확인한다. 탄력적 IP가 없어 서버를 껐다 켤 때마다 바뀐다. 최신 값은 `docs/guides/deploy-server-access.md` 1절 표나 팀 공지에 있다.
3. 터널을 연다. `-N`은 원격 명령 없이 터널만 연다는 뜻이라 터미널이 멈춘 것처럼 보이는 것이 정상이다. 창을 닫으면 터널도 끊긴다.
   ```bash
   # 팀원: 자기 개인 키
   ssh -i ~/.ssh/id_ed25519 -N -L 8081:127.0.0.1:8080 ubuntu@<Jenkins 공인 IP>
   # 계정 주인: EC2를 만들 때 고른 키 페어의 개인 키(.pem)
   ssh -i ~/.ssh/iseonjwa-deploy.pem -N -L 8081:127.0.0.1:8080 ubuntu@<Jenkins 공인 IP>
   ```
   `-i`에는 서버에 등록된 공개 키와 짝인 개인 키를 넣는다. 계정 주인의 공개 키는 AWS가 인스턴스를 만들 때 키 페어(`iseonjwa-deploy`)로 넣었고, 팀원의 공개 키는 2026-11-05에 손으로 더했다. 둘 다 같은 `ubuntu` 계정의 `authorized_keys`에 있어서 키만 다르고 명령은 같다.
4. 브라우저에서 `http://localhost:8081` → `admin`으로 로그인한다.
5. `deploy-main` → "Build with Parameters"에서 `TAG`를 정하고 빌드한다.
6. 빌드 화면의 "Console Output"과 슬랙 `#이선좌-배포상태알림`에서 결과를 본다.

### 로그인 ID와 비밀번호는 어디에 있나
- ID는 `admin` 하나로 고정이다. `infra/jenkins/casc.yaml`의 `securityRealm.local.users`에 적혀 있고, 가입은 막혀 있다.
- 비밀번호는 저장소 `.env`에 없다. Jenkins 서버의 `/opt/jenkins/jenkins.env`(권한 600)에 `JENKINS_ADMIN_PASSWORD`로 있다. 이 파일에는 `SLACK_WEBHOOK_DEPLOY`, `DEPLOY_HOST`, `POLL_SCHEDULE`, `PUBLIC_URL`도 같이 들어 있다(2026-11-11 키 이름만 확인).
- Jenkins는 시작할 때 이 값을 환경 변수로 받아 JCasC가 `${JENKINS_ADMIN_PASSWORD}` 자리에 넣는다. 비밀번호를 바꾸려면 이 파일을 고치고 `docker compose -f infra/jenkins/compose.yml up -d`로 컨테이너를 다시 만든다. `restart`만으로는 env 파일을 다시 읽지 않는다.
- 계정 주인이 값을 볼 때는 Claude Code 대화창이 아닌 별도 터미널에서 본다. 대화창에서 `!`로 실행하면 출력이 대화 기록에 남는다.
  ```bash
  ssh -i ~/.ssh/iseonjwa-deploy.pem ubuntu@<Jenkins 공인 IP> 'grep ^JENKINS_ADMIN_PASSWORD= /opt/jenkins/jenkins.env | cut -d= -f2-'
  ```
- 팀원에게는 개인 메시지로 준다. 팀 채널이나 저장소에 적지 않는다.

### 실패 알림별로 할 일
`deploy.sh`는 새 버전이 헬스·스모크를 통과하지 못하면 `.deploy/current`에 남긴 직전 성공 커밋으로 스스로 되돌린다. 그래서 실패해도 대개 서비스는 이전 버전으로 살아 있다.

| 슬랙 알림 | 뜻 | 할 일 |
|---|---|---|
| 주황 `[배포 실패] … 직전 버전(abc1234)으로 되돌렸습니다` | 새 버전이 확인을 통과하지 못했고, 서버는 직전 버전으로 돌아왔다 | 원인을 고쳐 main에 머지한다(자동 배포). 일시적인 원인(서비스 발견 지연 등)이 의심될 때만 `TAG`를 비우고 다시 돌린다 |
| 회색 `[배포 중단] …` | 그 커밋의 GHCR 이미지가 20분 안에 생기지 않았다 | GitHub Actions의 이미지 워크플로가 실패했는지 본다. 이미지가 생긴 뒤 `TAG`를 비우고 다시 돌린다 |
| 빨강 `[배포 오류] …` | 배포 단계까지 가지 못했다(예: Jenkins가 배포 서버에 SSH 접속 실패) | 다시 돌려도 같다. 담당에게 알린다 |
| 빨강 `[배포 실패] 배포와 되돌림이 모두 실패했습니다` | 서버 상태가 어긋났다 | 바로 담당에게 알린다. 알려진 정상 커밋을 `TAG`로 배포하는 것은 담당과 같이 정한다 |

### `TAG`에 넣을 커밋 SHA 찾기
- 40자 전체를 넣는다. 7자 짧은 SHA는 거부된다(`TAG는 40자 16진 커밋 SHA여야 합니다`).
- 짧은 SHA를 40자로: `git rev-parse abc1234`
- 최근 main 커밋: `git fetch origin && git log origin/main --format='%H %s' -5`
- 슬랙 알림의 "직전 버전" 링크가 GitHub 커밋 페이지라, 주소 끝의 40자가 그 SHA다.

### 주의
- 동시 실행이 막혀 있다. 돌고 있는 빌드가 있으면 끝날 때까지 기다린다.
- 부하 측정 중에는 배포하지 않는다. 배포가 돌면 측정 모드가 풀린다.
- 예전 커밋으로 되돌려도 DB 스키마(Flyway) 변경은 되돌려지지 않는다. 스키마를 바꾼 릴리스를 되돌릴 때는 담당과 먼저 상의한다.
- 서버에서 `deploy.sh`를 직접 돌리지 않는다. 배포는 Jenkins로만 한다(배포 서버 접속 안내 4절).

## 이 프로젝트에서는
- 잡 정의와 로그인: `infra/jenkins/casc.yaml`(로컬 사용자 `admin` 하나, 가입 없음, 잡 `deploy-main`)
- 매개변수 `TAG`와 실패 처리: 루트 `Jenkinsfile`(`커밋 정하기` → `이미지 기다리기` → `배포`)
- 되돌림: `script/deploy/deploy.sh`(종료 코드 0 성공, 1 실패·되돌림 성공, 3 되돌림도 실패)
- 팀원용 안내: `docs/guides/deploy-server-access.md` 3절(접속), 3-1절(실패 뒤 다시 배포). 설계: `docs/design/infra/deployment.md` 2절·Jenkins(ISE-175)

## 면접 포인트
- **배포가 실패하면 어떻게 되나?** 새 버전이 헬스와 스모크를 통과하지 못하면 배포 스크립트가 직전 성공 커밋으로 되돌리고 슬랙에 알린다. 사람은 원인을 고쳐 다시 머지하거나, Jenkins에서 원하는 커밋 SHA를 지정해 재배포한다.
- **CI 서버 화면을 왜 공개하지 않았나?** Jenkins는 배포 서버 접속 키를 가진 서버라, 화면을 공개하면 공격 면이 커진다. 웹훅 대신 폴링을 쓰고 화면은 SSH 터널로만 연다.

## 참고
- `Jenkinsfile`, `infra/jenkins/casc.yaml`, `script/deploy/deploy.sh`(2026-11-11 dev 기준)
- `docs/design/infra/deployment.md` 2절, Jenkins(ISE-175) 절의 알림 종류
