---
title: 브랜치 이름에 슬래시(/)를 여러 번 넣는 것은 흔한가, 주의할 점은 무엇인가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [git, branch, naming, ci]
---

# 브랜치 이름에 슬래시(/)를 여러 번 넣는 것은 흔한가, 주의할 점은 무엇인가

## 질문
`ggangmy/chore/init-env`로 가 볼까. 근데 브랜치명에 `/`를 여러 번 넣는 것도 흔한지?

## 짧은 답
- 흔하다. Git 브랜치는 내부적으로 파일 경로(`refs/heads/ggangmy/chore/init-env`)라서 `/`는 폴더 구분일 뿐이고 개수 제한이 없다.
- 가장 흔한 것은 한 번(`feature/login`, `fix/ISE-12-...`)이고, 사람이나 팀을 앞에 붙이는 두 번(`users/<이름>/<주제>`, `<이름>/feat/<주제>`)도 큰 조직과 개인 실험 브랜치에서 자주 쓴다.
- 주의할 점은 세 가지다: 상위 이름 충돌, CI의 `*` 패턴, 브랜치 이름을 그대로 쓰는 곳(Docker 태그 등).

## 자세히

### 흔한 형식

| 형식 | 예 | 주로 쓰는 곳 |
|---|---|---|
| `{type}/{주제}` | `feature/login`, `hotfix/1.2.1` | Git Flow, 대부분의 팀 |
| `{type}/{이슈}-{주제}` | `feat/ISE-12-user-signup` | 이슈 트래커 연동(Linear·Jira) |
| `{이름}/{주제}` | `ggangmy/init-env` | 개인 실험, Linear 기본 복사 형식 |
| `{이름}/{type}/{주제}` | `kwonwoo/feat/common`, `ggangmy/chore/init-env` | 사람별로 묶으면서 종류도 보이게 할 때 |
| `users/{이름}/{주제}` | `users/kangmin/spike` | Azure DevOps 권장 예시 등 큰 조직 |

IntelliJ와 GitHub Desktop, SourceTree는 `/` 기준으로 브랜치를 폴더처럼 묶어 보여 준다. 그래서 사람별로 브랜치를 모아 보기 좋다.

### 주의할 점

**1. 상위 이름 충돌(디렉터리·파일 충돌)**
브랜치는 파일 경로라서 같은 이름이 파일이면서 폴더일 수 없다. 이 저장소에서 확인했다.
- `ggangmy/chore/init-env`가 있으면 `ggangmy/chore`는 만들 수 없다(`cannot lock ref ... exists`).
- 반대로 `ggangmy` 브랜치가 원격에 있으면 `ggangmy/...`를 push할 수 없다(`refname conflict`).
- 그래서 규칙을 정할 때 "끝이 되는 이름"과 "폴더가 되는 이름"을 섞지 않는다.

**2. CI 브랜치 필터의 `*`**
GitHub Actions의 `branches: ['feature/*']`에서 `*`는 `/`를 넘지 않는다. `feature/a/b`는 걸리지 않으므로 `feature/**`로 써야 한다. 이 저장소의 `records-check` 워크플로처럼 브랜치 필터를 쓰는 곳이 생기면 확인한다.

**3. 브랜치 이름을 그대로 쓰는 곳**
Docker 이미지 태그에는 `/`를 쓸 수 없다. CI에서 브랜치 이름으로 태그를 만들면 `/`를 `-`로 바꿔야 한다. 폴더 이름이나 URL 경로로 쓸 때도 같은 처리가 필요하다.

### 길이와 읽기
`/`가 많을수록 이름이 길어지고 터미널·PR 제목에서 잘린다. 세 단계까지가 보통이며, 그 이상은 드물다.

## 이 프로젝트에서는
- 초기 환경 실험 브랜치는 임시로 `{이름}/{type}/{주제}` 형식을 쓴다: `ggangmy/chore/init-env`(권우님 `kwonwoo/feat/common`과 같은 형식).
- 병렬 작업부터의 정식 규칙(`{type}/ISE-{번호}-{주제}` 또는 Linear 기본 형식)은 회의에서 정한다.
- 지금 원격에 `ggangmy` 브랜치가 있어 먼저 지워야 `ggangmy/chore/init-env`를 올릴 수 있다.

## 면접 포인트
- "브랜치 이름 규칙은 어떻게 정했나?": 이슈 번호를 넣어 트래커와 자동 연결되게 하고, `/`로 종류를 묶었다. Git 참조가 파일 경로라 상위 이름과 하위 이름이 충돌하는 점, CI 패턴의 `*`가 `/`를 넘지 않는 점을 함께 고려했다.

## 참고
- Git 문서 `git check-ref-format` (참조 이름 규칙)
- GitHub Actions 문서 "Filter pattern cheat sheet" (`*`와 `**`)
- 이 저장소에서 확인한 오류: `cannot lock ref 'refs/heads/ggangmy/chore': 'refs/heads/ggangmy/chore/init-env' exists`
