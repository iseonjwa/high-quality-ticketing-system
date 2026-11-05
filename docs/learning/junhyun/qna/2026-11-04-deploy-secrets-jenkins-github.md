---
title: 배포 비밀값을 Jenkins Credentials나 GitHub Secrets에 두고 관리하는 방법
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 중급
tags: [secrets, jenkins-credentials, github-secrets, deploy-keys, deployment]
---

# 배포 비밀값을 Jenkins Credentials나 GitHub Secrets에 두고 관리하는 방법

## 질문
Jenkins에 키 값을 담아 두거나, GitHub에 배포용 키를 담아 두고 관리하는 방법이 있지 않았나.

## 짧은 답
있다. 이 프로젝트는 이미 일부를 그렇게 한다. Jenkins Credentials에는 배포 서버 SSH 키와 배포 알림 슬랙 웹훅이 들어 있다. GitHub은 실행마다 자동으로 나오는 토큰(`GITHUB_TOKEN`)만 쓰고 저장한 비밀값이 없다. 앱 비밀값 15개(DB 비밀번호, JWT 서명 키 등)는 배포 서버의 `.env`에 있다. 이 `.env`는 서버에서 `init-env.sh`가 무작위로 만든다. 리허설 전에는 이 구조를 유지한다. `.env`까지 Jenkins로 옮기는 것은 서버를 새로 만들 일이 생길 때 검토한다.

## 자세히

### 1. 지금 비밀값이 있는 곳
| 비밀값 | 있는 곳 | 쓰는 곳 |
|---|---|---|
| GHCR에 이미지를 올리는 권한 | GitHub Actions가 실행마다 주는 `GITHUB_TOKEN`(저장하지 않음, 실행이 끝나면 만료) | `images` 워크플로 |
| 배포 서버 SSH 개인 키 | Jenkins Credentials `deploy-ssh`(원본 `/opt/jenkins/deploy_ssh_key`) | Jenkinsfile `sshagent` |
| 배포 알림 슬랙 웹훅 | Jenkins Credentials `slack-webhook-deploy`(원본 `/opt/jenkins/jenkins.env`) | Jenkinsfile `withCredentials` |
| Jenkins 관리자 비밀번호 | `/opt/jenkins/jenkins.env` | JCasC 로컬 사용자 |
| 앱 비밀값 15개(DB, JWT·토큰 서명 키, 관리자·샘플 계정, Grafana, 모니터링 웹훅) | 배포 서버 `~/iseonjwa/.env`(0600) | `docker compose`가 컨테이너 환경 변수로 넣는다 |

이미지에는 비밀값이 없다. 그래서 GHCR 패키지를 공개로 둘 수 있었다.

### 2. Jenkins Credentials
- Jenkins 안의 비밀값 보관함이다. 종류로는 Secret text(문자열), Secret file(파일), SSH Username with private key, Username with password 등이 있다.
- 파이프라인에서는 블록 안에서만 꺼내 쓴다.
  ```groovy
  withCredentials([string(credentialsId: 'slack-webhook-deploy', variable: 'SLACK_URL')]) { sh 'curl ... "$SLACK_URL"' }
  withCredentials([file(credentialsId: 'deploy-env', variable: 'ENV_FILE')]) { sh 'scp "$ENV_FILE" ...' }  // 파일은 임시 경로로 받는다
  sshagent(['deploy-ssh']) { sh 'ssh ubuntu@...' }
  ```
- 콘솔 로그에 값이 그대로 찍히면 `****`로 가린다. 다만 값을 바꿔 찍으면(예: base64) 가리지 못하므로 출력하지 않는 것이 원칙이다.
- 값은 `JENKINS_HOME`에 암호화돼 저장된다. 키도 같은 `JENKINS_HOME`에 있다. 그래서 Jenkins 관리자나 서버 접근 권한이 있는 사람은 값을 꺼낼 수 있다. Jenkins UI를 공개하지 않는 이유 중 하나다.
- **이 프로젝트는 화면이 아니라 코드(JCasC)로 넣는다.** `infra/jenkins/casc.yaml`이 `${SLACK_WEBHOOK_DEPLOY}`, `${readFile:/run/secrets/deploy_ssh_key}`처럼 서버 파일에서 값을 읽어 자격 증명을 만든다. Jenkins를 다시 띄우면 casc.yaml 기준으로 다시 정해지므로, 화면에서 바꾼 값이 남는다고 기대하지 않는다. 값을 바꾸거나 더하려면 다음 순서로 한다.
  1. `casc.yaml`에 자격 증명 항목을 더한다(저장소, PR).
  2. Jenkins 서버 `/opt/jenkins/jenkins.env`에 값을 넣는다(저장소에 올리지 않음).
  3. `docker compose -f infra/jenkins/compose.yml up -d`로 다시 띄운다.

### 3. GitHub Secrets (Actions 비밀값)
- 저장소 Settings → Secrets and variables → Actions에 넣는다. 범위는 저장소, 조직, Environment(예: `production`) 셋이다.
- 넣은 뒤에는 화면에서 다시 볼 수 없고 덮어쓰기만 된다. 워크플로에서 `${{ secrets.이름 }}`으로 쓰고, 로그에서는 가려진다.
- Environment 비밀값에는 보호 규칙을 걸 수 있다. 정해진 사람이 승인해야 그 Environment를 쓰는 잡이 돈다(required reviewers). "운영 배포는 승인 뒤"를 만들 때 쓴다.
- **GitHub Actions 안에서만 쓸 수 있다.** Jenkins는 이 값을 읽지 못한다. 그래서 GitHub Secrets로 배포 키를 관리한다는 것은 배포를 Actions가 하게 바꾼다는 뜻이다.
  - Actions의 GitHub 호스트 러너는 IP가 넓은 범위에서 바뀐다. 거기서 배포 서버로 SSH를 하려면 22번을 사실상 전체에 열어야 한다.
  - 아니면 AWS 안에 자체 러너를 두거나, AWS SSM(OIDC 역할)으로 명령을 보낸다.
  - 어느 쪽이든 "배포는 Jenkins"로 정한 ADR 0044를 바꾸는 일이다.

### 4. GitHub Deploy keys (헷갈리기 쉬운 것)
- 저장소 Settings → Deploy keys에 **서버의 SSH 공개 키**를 등록하는 기능이다. 그 서버가 이 저장소 하나만 clone·pull할 수 있게 한다(기본은 읽기 전용).
- 비공개 저장소를 서버가 받을 때 쓴다. 이 저장소는 공개라서 배포 서버와 Jenkins가 HTTPS로 키 없이 받는다. 지금은 필요 없다.

### 5. 운영에서 많이 쓰는 방식: 클라우드 비밀값 저장소
- AWS Systems Manager Parameter Store(표준 파라미터는 무료)나 Secrets Manager에 값을 두고, EC2에 붙인 IAM 역할로 기동 때 읽는다.
- 서버 디스크에 `.env`가 남지 않고, 값 교체(rotation)와 접근 기록(CloudTrail)이 된다. 대신 서버에 AWS 권한을 줘야 한다. 지금은 "서버에 AWS 자격 증명을 주지 않는다"로 두었다.

## 이 프로젝트에서는
- 결정: ADR 0044 5번이다. "서버 `.env`는 서버에서 만든다. GHCR 읽기 토큰, 배포 SSH 키, 슬랙 웹훅은 Jenkins Credentials에만 둔다." GHCR 패키지를 공개로 바꿔 읽기 토큰은 필요 없어졌다.
- 앱 `.env`를 Jenkins Secret file(`deploy-env`)로 옮기면 좋아지는 것:
  - 서버를 새로 만들어도 `.env`를 손으로 만들지 않는다. Jenkins가 배포 전에 서버로 복사한다.
  - 값이 한 곳에 모인다.
- 그때 주의할 것:
  - PostgreSQL 비밀번호는 DB 볼륨을 처음 만들 때만 적용된다. 나중에 `.env`의 `DB_PASSWORD`만 바꾸면 앱이 DB에 붙지 못한다. 바꿀 때는 DB 안에서도 비밀번호를 바꾼다.
  - Jenkins가 앱 비밀값까지 모두 갖게 된다. Jenkins 보호(터널 전용, 관리자 비밀번호)가 더 중요해진다.
  - `init-env.sh`에 새 키를 더하는 흐름과 맞춰야 한다.
- 그래서 리허설(11/6) 전에는 바꾸지 않는다. 11/7~11/12 매니지드 분리 검토(ADR 0047) 때 Parameter Store와 함께 본다.

## 면접 포인트
- 비밀값은 이미지·저장소에 넣지 않고 실행 시점에 넣는다. 보관 위치는 그 값을 쓰는 주체에 맞춘다: 빌드는 CI의 짧게 사는 토큰, 배포 접속 키는 배포 도구(Jenkins Credentials), 앱 설정은 실행 환경(서버 `.env` 또는 클라우드 비밀값 저장소).
- GitHub Secrets는 Actions 안에서만 쓰인다. 배포 도구가 Jenkins면 Jenkins Credentials가 맞는 자리다.

## 참고
- [Using credentials (Jenkins)](https://www.jenkins.io/doc/book/using/using-credentials/)
- [Credentials Binding: withCredentials (Jenkins)](https://www.jenkins.io/doc/pipeline/steps/credentials-binding/)
- [Using secrets in GitHub Actions (GitHub)](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)
- [Managing environments for deployment (GitHub)](https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/managing-environments-for-deployment)
- [Managing deploy keys (GitHub)](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys)
- [postgres 이미지의 환경 변수 (Docker Hub)](https://hub.docker.com/_/postgres)
