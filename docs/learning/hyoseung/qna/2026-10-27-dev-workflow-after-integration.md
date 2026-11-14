---
title: 초기 환경 통합 뒤에는 어떤 방식으로 개발하고, Linear·Git은 어떻게 연결해 올리는가
date: 2026-10-27
updated: 2026-10-28
asker: hyos1
level: 기초
tags: [git, linear, branch-strategy, pull-request, ci, workspace]
---

# 초기 환경 통합 뒤에는 어떤 방식으로 개발하고, Linear·Git은 어떻게 연결해 올리는가

## 질문
초기 환경이 확정되면서 md 파일과 규칙이 바뀌었다. dev에서 pull 받으면 되는가. 앞으로 어떤 방식으로 개발해야 하고, Linear와 Git이 연결되어 있는데 어떻게 올려야 하는가.

## 답
dev를 pull 받으면 된다. 10/27 통합 회의 결과가 PR #2(코드 통합), #8(팀 규칙), #11(SA v3.2)로 dev에 들어가 있다. 앞으로는 dev에서 이슈 단위 브랜치를 따서 PR로 dev에 합친다.

### 무엇이 바뀌었나
| 영역 | 지금 기준 | 정본 |
|---|---|---|
| 통합 기준 | 준현 브랜치 기준(D1). 각자 만든 초기 환경 브랜치는 비교용으로 끝났다 | `docs/integration/decision-sheet.md` |
| 공통 모듈 | `common` 모듈 하나, 안에서 패키지(`response`·`identity`·`auth`·`web`·`persistence`·`event`·`messaging`)로 나눈다. 웹·JPA·Kafka·JWT는 `compileOnly`라 쓰는 서비스가 직접 선언하고, 설정은 `@Import`로 가져온다 | `docs/guides/common-modules.md`, ADR 0013 |
| 패키지 | `com.iseonjwa.*` | ADR 0014 |
| 버전 | Java 21, Boot 4.1.2, Cloud 2025.1.4, Gradle 9.8.2(Groovy), PostgreSQL 17, Lombok 허용 | SA v3.2 9-1 |
| 블랙리스트 Redis 장애 | closed(503 + `Retry-After`) | SA v3.2 9-5, ADR 0005 |
| 운영자 권한 | Gateway `AccessPolicy`가 `/api/v1/admin/**`를 ADMIN만 통과시키고, 서비스도 `@RequireRole`로 한 번 더 확인 | `docs/guides/workspace.md` 인증 계약 |
| 코드 작성 | 대전제 10번: 읽는 흐름 우선, `Note:` 주석은 이유·주의점·학습 포인트에만 | `docs/guides/code-style.md` |
| 테스트 | JUnit 정본. 기본 검사 `./gradlew verifyWorkspace --no-daemon`(Docker 필요) | `docs/guides/testing.md` |
| 실행 | `./gradlew initLocalEnv` → `docker compose -f compose.yml -f compose.local.yml up -d --build --wait` | `docs/guides/workspace.md` |

### 한 작업의 흐름
1. Linear에서 이슈를 고르거나 만든다. 번호가 `ISE-12`처럼 붙는다.
2. dev를 최신으로 받고 브랜치를 만든다. 이름은 `{type}/ISE-{번호}-{주제}`이고 사람 이름은 넣지 않는다.
   ```bash
   git switch dev
   git pull --ff-only
   git switch -c feat/ISE-12-concert-query
   ```
3. 작업하고 커밋한다. 제목은 `type(scope): 요약`, 한 커밋에 한 가지 변경, 빌드가 깨진 중간 커밋은 만들지 않는다. `git add`는 경로를 지정한다.
4. push 전 `./gradlew verifyWorkspace --no-daemon`을 돌린다. CI도 같은 태스크를 돌린다.
5. `git push -u origin feat/ISE-12-concert-query` 후 dev를 대상으로 PR을 연다. 제목은 커밋 형식, 본문은 PR 템플릿의 STAR, "AI 활용 기록" 칸에 판단 기록·질문 노트 링크를 적는다.
6. CI(`build`, `records-check`)가 통과하면 작성자가 squash로 머지하고 팀에 알린다. 필수 리뷰는 없다. 남의 작업을 도왔을 때만 그 사람에게 리뷰를 요청한다.
7. dev에서 다시 pull 받고 다음 이슈로 간다. 마일스톤이 끝나면 dev → main으로 올린다.

`main`·`dev`에는 직접 push하지 않는다. 학습 노트·판단 기록도 그 작업의 브랜치에 같이 커밋해 PR로 올린다.

### Linear와 GitHub 연결
Linear의 GitHub 연동은 이슈 번호(`ISE-12`)를 보고 연결한다. 그래서 팀 브랜치 규칙이 번호를 이름에 넣는다.

- 브랜치 이름에 `ISE-12`가 있으면 그 브랜치의 PR이 이슈에 자동으로 붙는다. PR 제목이나 본문에 `ISE-12`를 적어도 붙는다.
- 연동 기본 동작은 PR을 열면 이슈가 진행 중 계열 상태로, 머지하면 완료로 바뀌는 것이다. 이 팀의 자동 상태 전환 설정은 Linear 팀 설정(Settings → 팀 → Workflow의 GitHub 항목)에서 확인한다(미확인).
- Linear의 "브랜치 이름 복사" 기본값은 `사용자명/ise-12-제목` 형식이라 팀 규칙과 다르다. 브랜치 이름은 규칙대로 직접 쓴다.
- 이슈 하나를 PR 여러 개로 나누면, 완료 처리를 원하지 않는 PR에는 본문에 `Refs ISE-12`처럼 참조로만 적는다. 닫는 PR에는 `Fixes ISE-12`나 `Closes ISE-12`를 쓴다. 팀에서 정한 규칙은 아니고 Linear의 기본 문법이다.

### 브랜치 구조 (2026-10-28 다시 물음)
브랜치는 세 층이다.

```
main  ── 마일스톤이 끝날 때만 dev에서 올린다. 발표·배포 기준점
  └─ dev  ── 팀의 통합 브랜치. 모든 작업 PR이 여기로 들어간다
       ├─ feat/ISE-63-venue-seed      ── 이슈 하나 = 브랜치 하나 = PR 하나
       ├─ feat/ISE-64-admin-concert-schedule
       └─ docs/ISE-31-payment-detailed-design (다른 팀원)
```

- 작업 브랜치는 항상 dev에서 만들고 dev로 돌아간다. 작업 브랜치끼리 서로 합치지 않는다.
- 다음 이슈가 앞 이슈에 의존하면(예: ISE-64가 ISE-63의 테이블을 씀) 앞 PR이 dev에 머지된 뒤 dev에서 새로 만드는 것이 기본이다. 기다릴 수 없으면 앞 브랜치에서 따서 작업하고, 앞 PR이 머지된 뒤 dev로 rebase한다.
- squash 머지라 작업 브랜치의 작은 커밋 여러 개가 dev에는 커밋 하나로 들어간다. 머지가 끝난 브랜치는 지운다.

### 처음 한 번 할 일 (pull 직후)
- `script/setup-agents.sh`를 다시 실행한다(스킬이 바뀌었을 수 있다).
- 옛 `.env`는 변수 이름이 달라 쓸 수 없다. `JWT_SECRET` 하나가 `JWT_ACCESS_SECRET`·`JWT_REFRESH_SECRET`로 나뉘었고 DB 계정·운영자 시드 계정이 생겼다. 옛 파일을 옮기고 `./gradlew initLocalEnv`로 새로 만든다. 생성기는 기존 `.env`를 덮어쓰지 않는다.
- 옛 모듈 폴더(`common-response/`, `common-web/`)에 빌드 결과물만 남아 있으면 지워도 된다. Git이 추적하지 않는 폴더다.
- IntelliJ는 Gradle을 다시 불러온다(모듈 구성이 바뀌었다). IntelliJ가 `.env`를 자동으로 읽지 않으므로 앱은 `./gradlew runLocal -Papp=<앱>`으로 띄우는 것이 간단하다.

## 핵심 정리
- 출발은 항상 dev, 브랜치 이름에 Linear 이슈 번호, 도착은 dev로 가는 PR이다.
- 리뷰 대신 CI(`verifyWorkspace`, `records-check`)가 관문이라 push 전에 같은 검사를 로컬에서 돌린다.
- 이슈 번호가 브랜치·PR에 있어야 Linear가 진행 상황을 따라간다.

## 면접 포인트
- 필수 리뷰를 없애고 CI를 필수 관문으로 둔 이유: 5명이 2주 안에 병렬로 움직이며 리뷰 대기로 막히지 않게 하되, 빌드·테스트·기록 구조는 자동으로 막는다. 대가는 설계 실수를 사람이 못 볼 수 있다는 점이고, AI 검증과 도움받은 작업의 리뷰 요청으로 보완한다.
- squash 머지를 기본으로 하는 이유: 작업 브랜치의 작은 커밋을 PR 하나로 묶어 dev 이력이 이슈 단위로 읽힌다.

## 참고
- `docs/guides/git-workflow.md` 5절, `docs/integration/decision-sheet.md` 회의 결과 D16·D17
- Linear GitHub 연동 문서: https://linear.app/docs/github (팀 설정 값은 미확인)
