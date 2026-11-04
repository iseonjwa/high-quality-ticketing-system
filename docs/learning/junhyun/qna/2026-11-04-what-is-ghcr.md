---
title: GHCR(GitHub Container Registry)은 무엇이고 왜 쓰나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [ghcr, container-registry, docker, github-packages, deployment]
---

# GHCR(GitHub Container Registry)은 무엇이고 왜 쓰나

## 질문
ISE-173 워크플로가 이미지를 GHCR에 올린다고 했다. GHCR이 무엇인가.

## 짧은 답
GHCR은 GitHub이 운영하는 도커 이미지 창고(컨테이너 레지스트리)다. 주소는 `ghcr.io`다. GitHub 저장소가 코드를 보관하듯 GHCR은 빌드한 이미지를 보관한다. 이 프로젝트에서는 GitHub Actions가 이미지를 GHCR에 올리고, 배포 서버가 거기서 받아 띄운다.

## 자세히

### 1. 컨테이너 레지스트리
- 도커 이미지는 앱과 실행 환경(JRE, nginx 등)을 묶은 실행 꾸러미다. 한 곳에서 만들고 다른 곳에서 실행하려면 둘 다 접근할 수 있는 보관소가 필요하다. 이것이 레지스트리다.
- `docker push`로 올리고 `docker pull`로 받는다. 레지스트리마다 주소가 다르다.
  - Docker Hub: `docker.io`. `docker pull nginx`처럼 주소를 생략하면 여기서 받는다.
  - GHCR: `ghcr.io`(GitHub)
  - Amazon ECR: `<계정ID>.dkr.ecr.<리전>.amazonaws.com`(AWS)

### 2. 이미지 주소 읽는 법
```
ghcr.io / iseonjwa / gateway : 6aeaae2f...(커밋 SHA)
레지스트리  소유자(조직)  이미지 이름   태그(버전 표시)
```
- 같은 이름에 태그를 여러 개 둘 수 있다. 이 프로젝트는 태그로 커밋 SHA를 써서, 어떤 이미지가 어느 코드에서 나왔는지 바로 알 수 있게 한다.

### 3. GHCR의 특징
- GitHub 조직·저장소와 같은 곳에 있다. 이미지(패키지)를 저장소에 연결하면 그 저장소의 권한을 따르고, GitHub 화면의 Packages에서 보인다.
- GitHub Actions가 실행마다 받는 토큰(`GITHUB_TOKEN`)으로 바로 올릴 수 있다. 다른 레지스트리처럼 계정 비밀번호나 키를 비밀값으로 따로 둘 필요가 없다.
- 새로 만든 패키지는 비공개다. 비공개 패키지를 받으려면 `read:packages` 권한이 있는 토큰으로 로그인해야 한다. 공개로 바꾸면 누구나 로그인 없이 받는다. 공개 패키지는 저장·전송 요금이 없다.

### 4. 다른 레지스트리와 비교
| | GHCR | Docker Hub | Amazon ECR |
|---|---|---|---|
| 올리는 쪽 인증 | Actions의 `GITHUB_TOKEN` | Docker Hub 계정 토큰을 비밀값으로 둔다 | AWS 자격 증명(역할·키)이 필요하다 |
| 받는 쪽 인증 | 공개면 없음, 비공개면 GitHub 토큰 | 공개면 없음(로그인하지 않으면 받는 횟수 제한이 있다) | AWS 자격 증명 |
| 코드와의 연결 | 같은 GitHub 조직·저장소 | 따로 | 따로(AWS 계정) |

## 이 프로젝트에서는
- ADR 0044: GitHub Actions가 main 머지 때 이미지를 GHCR에 올리고, Jenkins가 배포 EC2에서 `docker compose pull`로 받아 띄운다.
- 이미지 이름은 `ghcr.io/iseonjwa/<서비스>:<40자 커밋 SHA>`이고 8개다(ISE-173, `docs/design/infra/deployment.md` "이미지 워크플로").
- GHCR을 쓰면 AWS 쪽에 이미지 저장소를 두지 않아도 된다. 그래서 배포 EC2와 Jenkins에 AWS 자격 증명을 줄 필요가 없다(AWS 계정 노트 2절의 "액세스 키 필요 없음"과 이어진다).
- 첫 main 릴리스 뒤 패키지 공개 여부를 정한다(ISE-174). 저장소가 공개이고 이미지에 비밀값이 없으니(비밀값은 서버 `.env`가 실행할 때 넣는다) 공개로 두면 배포 서버가 토큰 없이 받는다. 비공개로 두면 읽기 토큰을 Jenkins Credentials에 둔다.

## 면접 포인트
- 이미지 레지스트리를 CI와 같은 플랫폼에 두면 CI가 짧게 사는 토큰으로 올릴 수 있어, 장기 비밀값을 하나 줄인다.
- 이미지에 비밀값을 넣지 않고 실행할 때 환경 변수로 넣기 때문에, 이미지 자체는 공개해도 된다.

## 참고
- [Working with the Container registry (GitHub)](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Configuring a package's access control and visibility (GitHub)](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)
- [About billing for GitHub Packages (GitHub)](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-packages/about-billing-for-github-packages)
- [Docker Hub usage and rate limits (Docker)](https://docs.docker.com/docker-hub/usage/)
