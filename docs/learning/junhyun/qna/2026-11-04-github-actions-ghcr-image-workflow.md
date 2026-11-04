---
title: GitHub Actions로 배포 이미지를 만들어 GHCR에 올리는 워크플로는 어떻게 읽나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [github-actions, ghcr, docker, buildx, bake, buildkit, cache, ci-cd]
---

# GitHub Actions로 배포 이미지를 만들어 GHCR에 올리는 워크플로는 어떻게 읽나

## 질문
ISE-173에서 main 머지 때 서비스 이미지를 GHCR에 올리는 워크플로를 만들면서, 워크플로에 대한 설명을 저장소 안에 남겨 달라고 했다. 이 워크플로가 무엇을 왜 하는지 읽을 수 있게 정리한다.

## 짧은 답
- 이 워크플로는 **CI가 만든 결과물(이미지)을 창고(GHCR)에 넣는 일**까지만 한다. 서버에 띄우는 일(CD)은 Jenkins가 한다.
- 이미지 이름은 `ghcr.io/iseonjwa/<서비스>:<커밋 SHA>`다. 태그가 커밋이라 "서버에 지금 무엇이 떠 있나"를 커밋 하나로 말할 수 있고, 되돌릴 때도 직전 SHA를 다시 띄우면 된다.
- 8개 이미지를 `docker buildx bake` 한 번으로 빌드한다. 앱 7개가 같은 Gradle 빌드 단계를 공유해서, 따로 빌드하면 7번 할 일을 한 번만 한다.
- main이면 올리고, PR이면 빌드만 해서 Dockerfile이 깨졌는지 미리 본다.

## 자세히

### 1. CI와 CD를 나누는 이유
- **CI(지속적 통합)**: 코드가 합쳐질 때마다 빌드·테스트해서 "합쳐도 되는지"를 본다. 이 저장소에서는 `build.yml`(테스트), `frontend.yml`, `records-check.yml`이 한다.
- **산출물 만들기**: 합쳐진 코드를 실행 가능한 형태(이미지)로 굳혀 저장소(레지스트리)에 넣는다. `images.yml`이 한다.
- **CD(지속적 배포)**: 산출물을 서버에 올리고 확인하고, 실패하면 되돌린다. Jenkins가 한다(ADR 0044).
- 나누면 각 단계를 따로 다시 돌릴 수 있다. 예를 들어 이미지가 이미 있으면 배포만 다시 할 수 있고, 서버 접속 정보는 GitHub이 아니라 Jenkins에만 둔다.

### 2. GitHub Actions의 기본 단위
- **워크플로**: `.github/workflows/*.yml` 파일 하나. `on:`에 적은 사건(push, pull_request, 수동 실행)이 일어나면 돈다.
- **잡(job)**: 새 가상 머신 한 대에서 도는 작업 묶음. 잡이 끝나면 머신이 사라진다. 그래서 다음 실행에 남기고 싶은 것(의존성 다운로드)은 캐시로 따로 저장해야 한다.
- **스텝(step)**: 잡 안의 한 단계. `uses:`는 남이 만든 액션을 불러 쓰고(`actions/checkout` 등), `run:`은 셸 명령을 돈다.
- **조건과 표현식**: `${{ ... }}`는 GitHub이 실행 전에 계산하는 값이다. `github.ref`(어느 브랜치에서 돌았나), `github.sha`(커밋), `github.event_name`(무슨 사건) 등을 쓴다.
- **동시 실행 제어(concurrency)**: 같은 그룹의 실행이 겹치면 이전 것을 취소할지 정한다. PR은 마지막 커밋만 보면 돼서 취소한다. 주의할 점은 `cancel-in-progress`를 꺼도 한 그룹에는 대기 실행이 하나만 남고 먼저 기다리던 실행은 취소된다는 것이다. main은 커밋마다 이미지가 있어야 해서 커밋(SHA)마다 그룹을 따로 둔다(2026-11-04 Opus 리뷰에서 찾았다).

### 3. GITHUB_TOKEN과 권한
- 워크플로가 돌 때마다 GitHub이 그 실행에만 쓰는 토큰(`GITHUB_TOKEN`)을 만든다. 실행이 끝나면 쓸 수 없다.
- 토큰이 할 수 있는 일은 `permissions:`로 정한다. 기본은 읽기만 주고, 이미지를 올리는 잡에만 `packages: write`를 준다(최소 권한).
- 이 토큰으로 GHCR에 로그인하므로 개인 접근 토큰(PAT)을 비밀값으로 저장하지 않아도 된다. 비밀값이 적을수록 샐 곳이 적다.

### 4. 컨테이너 레지스트리와 태그
- **레지스트리**는 이미지 창고다. Docker Hub, GHCR(GitHub), ECR(AWS) 등이 있다. GHCR은 저장소와 같은 곳에서 권한을 관리하고, 공개 패키지는 무료다.
- 이미지 주소는 `레지스트리/소유자/이름:태그`다. 같은 이름에 태그를 여러 개 둘 수 있다.
- `latest` 같은 **움직이는 태그**는 편하지만 "지금 latest가 어느 코드인가"를 따로 알아야 한다. **커밋 SHA 태그**는 바뀌지 않아 배포·되돌림·장애 분석에서 코드와 바로 이어진다.
- 라벨 `org.opencontainers.image.source`는 이미지가 어느 저장소에서 왔는지 적는 표준 라벨이다. GHCR이 이것으로 패키지를 저장소에 연결한다.

### 5. buildx·bake·BuildKit
- **BuildKit**은 요즘 docker 빌드 엔진이다. Dockerfile을 단계 그래프로 풀어서, 입력이 같은 단계는 한 번만 실행하고 결과를 재사용한다.
- **buildx**는 BuildKit을 다루는 docker 플러그인이다. 다른 CPU용 이미지(맥 arm64에서 EC2 amd64용)를 만들 수 있다.
- **bake**는 여러 이미지를 파일(`docker-bake.hcl`) 하나에 정의해 한 번에 빌드한다. 한 번의 실행이라 BuildKit이 이미지 사이에서 같은 단계를 알아보고 공유한다.
- 이 저장소의 루트 `Dockerfile`은 builder 단계에서 모든 모듈을 `gradlew bootJar`로 빌드하고, 다음 단계에서 `APP_MODULE`의 jar 하나만 꺼낸다. builder 단계는 `APP_MODULE`을 쓰지 않으니 7개 앱에 똑같다. 그래서 bake 한 번이면 Gradle 빌드가 한 번만 돈다. 워크플로 매트릭스(앱마다 잡 하나)로 나누면 머신 7대가 같은 빌드를 7번 한다.

### 6. 캐시 두 가지
- **캐시 마운트**(`RUN --mount=type=cache,target=/root/.gradle`): 빌드 중에 내려받은 의존성을 빌드 사이에 남기는 BuildKit 기능이다. 이미지 안에는 들어가지 않는다. 다만 빌드 머신 안에만 남는다.
- **actions/cache**: 잡 머신의 폴더를 GitHub에 저장했다가 다음 실행에서 복원한다. 키는 의존성 목록 파일(`*.gradle`, `package-lock.json`)의 해시다. 파일이 바뀌면 키도 바뀌어 새 캐시가 생긴다.
- GitHub 실행기는 매번 새 머신이라 캐시 마운트가 사라진다. 그래서 `buildkit-cache-dance`가 둘을 잇는다. 복원한 폴더를 빌드 전에 캐시 마운트에 넣고, 빌드 뒤 다시 꺼내 actions/cache가 저장하게 한다.

### 7. PR에서 빌드만 하는 이유
- 이미지 정의(Dockerfile, nginx 설정, bake 파일)가 깨지면 main에 들어간 뒤에야 알게 된다. 그때는 이미 릴리스가 실패한 상태다.
- 그래서 그 파일들이 바뀐 PR에서는 같은 빌드를 돌리되 올리지 않는다. paths 필터가 있는 워크플로라 필수 검사로 지정하지 않는다. 필수로 두면 해당 파일을 안 바꾼 PR에서 검사가 보고되지 않아 머지가 막힌다.

## 이 프로젝트에서는
- 파일: `.github/workflows/images.yml`(언제·어떻게), `docker-bake.hcl`(무엇을). 단계마다 주석을 달았다.
- 이미지 8개: 앱 7개(`config-server`, `eureka`, `gateway`, `user-service`, `reservation-service`, `payment-service`, `mock-pg`)와 `frontend`(nginx, ISE-109).
- 흐름: dev → main 머지 → `images.yml`이 8개를 SHA 태그로 GHCR에 올림 → Jenkins가 main 새 SHA를 보고 배포 EC2에서 그 태그를 pull·up(ISE-175).
- 2026-11-04 로컬에서 `config-server`와 `gateway`를 bake로 함께 빌드해, Gradle 단계(`gradlew bootJar`)가 한 번만 도는 것을 확인했다(캐시가 있는 상태에서 11초).
- `docker buildx bake`는 `-f docker-bake.hcl`을 꼭 붙인다. 붙이지 않으면 `compose.yml`도 읽어 같은 이름의 서비스를 타깃에 합친다(같은 날 `--print`로 확인).
- 남은 일: 첫 main 실행 뒤 패키지 공개 여부를 정한다(공개면 배포 서버가 토큰 없이 받는다, ISE-174). 설계 문서는 `docs/design/infra/deployment.md` "이미지 워크플로" 절이다.

## 면접 포인트
- 산출물을 커밋 SHA 태그로 레지스트리에 두고 배포 도구가 그 태그를 띄우면, 빌드와 배포를 따로 다시 돌릴 수 있고 되돌림이 "직전 태그로 다시 띄우기"가 된다.
- CI 토큰은 실행마다 새로 만들고 잡 단위로 최소 권한만 준다. 장기 토큰을 저장하지 않는다.
- 여러 이미지가 같은 빌드 단계를 공유하면 한 번의 BuildKit 실행으로 묶어 중복 빌드를 없앤다.

## 참고
- [Workflow syntax for GitHub Actions (GitHub)](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions)
- [Automatic token authentication, GITHUB_TOKEN (GitHub)](https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication)
- [Working with the Container registry (GitHub)](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Bake (Docker)](https://docs.docker.com/build/bake/)
- [docker/bake-action v7 README](https://github.com/docker/bake-action)
- [reproducible-containers/buildkit-cache-dance](https://github.com/reproducible-containers/buildkit-cache-dance)
- 위 액션 버전(checkout v7, setup-buildx v4, cache v6, login v4, bake v7, cache-dance v3)은 2026-11-04 각 저장소의 최신 릴리스로 확인했다.
