---
title: GitHub Actions 워크플로를 특정 폴더가 바뀔 때만 돌리면 필수 검사와 어떻게 부딪히나
date: 2026-11-02
updated: 2026-11-02
asker: Junhyun An
level: 중급
tags: [github-actions, ci, required-status-checks, branch-protection, paths-filter, monorepo]
---

# GitHub Actions 워크플로를 특정 폴더가 바뀔 때만 돌리면 필수 검사와 어떻게 부딪히나

## 질문
프론트 CI를 프론트 변경사항이 있을 때만 돌도록 할 수 있나.

## 짧은 답
워크플로의 `on.pull_request.paths`에 `frontend/**`를 적으면 된다. 다만 그 검사는 브랜치 보호의 필수 검사로 지정할 수 없다. 필수 검사는 "그 이름의 검사가 성공했다"는 보고가 와야 머지를 허락하는데, 워크플로가 아예 시작하지 않으면 보고가 오지 않아 GitHub이 계속 기다린다. 필수 검사인데 일부 PR에서만 무거운 단계를 돌리고 싶으면, 워크플로는 항상 시작하고 단계나 작업을 `if`로 건너뛴다. 건너뛴 작업은 성공으로 보고된다.

## 자세히

### 1. 두 가지 걸러내기
| 방식 | 어디서 거르나 | 바뀌지 않은 PR에서 | 필수 검사 |
|---|---|---|---|
| 워크플로 `paths` 필터 | 워크플로가 시작하기 전 | 워크플로가 생기지 않는다. 검사 목록에도 없다 | 지정하면 "Expected — Waiting for status to be reported"로 머지가 막힌다 |
| 단계·작업 `if` 조건 | 워크플로 안에서 | 작업은 시작하고 조건에 맞지 않는 단계·작업을 건너뛴다 | 건너뛴 작업은 성공으로 보고되어 막히지 않는다 |

### 2. `if`로 건너뛰려면 무엇이 바뀌었는지 직접 알아야 한다
`paths` 필터는 GitHub이 대신 계산하지만, `if` 조건을 쓰면 바뀐 파일 목록을 스스로 구해야 한다. PR에서는 기준 브랜치 커밋과 PR 마지막 커밋을 세 점으로 비교한다(`git diff --name-only base...head`). 두 점 비교를 쓰면 PR을 연 뒤 기준 브랜치에 들어온 남의 변경까지 섞인다. 비교하려면 두 커밋이 있어야 하므로 `actions/checkout`에 `fetch-depth: 0`을 준다. 새 브랜치의 첫 push처럼 비교 기준이 없을 때는 모두 돌리는 쪽이 안전하다.

### 3. 어느 쪽을 고르나
- 필수 검사로 머지를 막아야 하면 `if` 조건 방식. 설정이 조금 복잡하고, 무관한 PR에도 몇 초짜리 작업이 붙는다.
- 필수 검사가 아니어도 되면 `paths` 필터 방식. 단순하고 무관한 PR에 체크가 붙지 않는다. 대신 실패해도 머지는 막히지 않으므로 사람이 보고 머지한다.

## 이 프로젝트에서는
- `build`는 dev·main 필수 검사라 `if` 조건 방식이다. `script/ci-changed-areas.sh`가 `frontend/` 밖의 파일이 바뀌었는지 보고, 프론트만 바뀐 PR에서는 Java 검사(약 3분)를 건너뛴다.
- `frontend`는 `paths` 필터 방식이다(`.github/workflows/frontend.yml`). 처음에는 필수로 만들 수 있게 `if` 조건 방식으로 만들었으나, 프론트가 바뀔 때만 돌기를 원해 바꿨다. 프론트 PR은 담당자가 CI를 보고 머지한다.
- 규칙: `docs/guides/git-workflow.md` 5절 보호 규칙, ADR 0040 후속 작업(ISE-108).

## 면접 포인트
- 모노레포에서 CI 시간을 어떻게 줄였나: 바뀐 경로로 검사를 나눴다. 필수 검사는 워크플로를 항상 띄우고 단계만 건너뛰어, 경로 필터 때문에 머지가 막히는 문제를 피했다.
- `paths` 필터와 필수 검사를 같이 쓰면 왜 문제인가: 워크플로가 생기지 않으면 성공도 실패도 보고되지 않아, 보호 규칙이 결과를 무한히 기다린다.

## 참고
- GitHub Docs: "Workflow syntax for GitHub Actions"의 `on.<push|pull_request>.paths`
- GitHub Docs: "Troubleshooting required status checks"(건너뛴 작업은 성공으로 보고된다)
- 확인: PR #176 첫 CI 실행에서 두 워크플로가 판별 결과(`java=true`)대로 돌았다
