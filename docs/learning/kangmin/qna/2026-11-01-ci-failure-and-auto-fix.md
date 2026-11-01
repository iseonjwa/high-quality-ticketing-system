---
title: CI 실패는 무엇이고, PR의 CI 자동 수정은 무엇을 하나
date: 2026-11-01
updated: 2026-11-01
asker: GGangMY
level: 초급
tags: [ci, github-actions, pull-request, branch-protection]
---

# CI 실패는 무엇이고, PR의 CI 자동 수정은 무엇을 하나

## 질문
PR을 연 뒤 "CI 실패를 자동으로 고치게 켤까"라는 질문을 받았다. CI 실패가 무슨 말인가.

## 짧은 답
CI(지속적 통합, Continuous Integration)는 PR이 올라올 때마다 GitHub 서버가 저장소에 정해 둔 검사를 자동으로 돌리는 것이다. 검사 중 하나라도 통과하지 못하면 "CI 실패"다. 이 저장소는 dev 브랜치 보호 규칙으로 CI 통과를 머지 조건으로 두었으므로, 실패하면 머지할 수 없다.

## 이 저장소의 CI
| 검사 | 워크플로 | 하는 일 |
|---|---|---|
| `build` | `.github/workflows/build.yml` | 테스트 인프라(Docker Compose)를 띄우고 `./gradlew verifyWorkspace`로 전체 빌드와 모든 모듈의 JUnit 테스트, 포트 정책·기록 검사를 돌린다 |
| `records-check` | `.github/workflows/records-check.yml` | `script/records-check.sh`로 기록 구조와 비밀값 흔적을 검사한다 |

- 필수 승인은 0명이고, 리뷰 대신 이 두 검사를 자동 관문으로 둔다(`docs/guides/git-workflow.md` 5절).
- 로컬에서 일부 테스트만 돌렸거나 Docker가 없어 전체를 못 돌렸을 때, CI가 처음으로 전체를 확인하는 자리가 된다.

## 실패하는 흔한 이유
- 컴파일 오류, 테스트 실패(내 변경이 다른 모듈 테스트를 깬 경우 포함)
- 로컬과 CI 환경 차이(OS, 줄바꿈, 시간대, 포트)
- 기록 형식 위반(머리말 누락 등)
- 드물게 외부 요인(네트워크, 이미지 다운로드)으로 인한 일시 실패. 다시 실행하면 통과한다

## 자동 수정(Auto-fix)
Claude Code 데스크톱 앱이 PR의 CI를 지켜보다가 실패하면 에이전트를 깨워 로그를 읽고 고쳐 커밋·푸시하게 하는 기능이다.

| 선택 | 장점 | 단점 |
|---|---|---|
| 켠다 | 실패를 기다리며 확인할 필요가 없다 | 내가 보기 전에 커밋이 PR에 추가된다 |
| 끈다 | 고치기 전에 원인을 직접 보고 판단한다 | 결과를 직접 확인해야 한다 |

## 관련
- `docs/guides/testing.md`(기본 검사), `docs/guides/git-workflow.md` 5절(보호 규칙)
