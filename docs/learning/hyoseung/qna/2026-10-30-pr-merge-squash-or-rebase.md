---
title: PR 머지는 squash인가 rebase인가, 우리 팀은 실제로 어떻게 하나
date: 2026-10-30
asker: hyos1
level: 기초
tags: [git, github, pull-request, merge, squash, rebase]
---

# PR 머지는 squash인가 rebase인가, 우리 팀은 실제로 어떻게 하나

## 질문
우리 팀 PR 머지 방식이 squash가 맞나? rebase는 아닌가?

## 답
### 세 가지 방식
GitHub 머지 버튼에는 세 가지가 있다. 브랜치에 커밋 A, B 두 개가 있다고 하자.

| 방식 | dev에 남는 것 | 특징 |
|---|---|---|
| Create a merge commit | A, B + "Merge pull request #n" 커밋 | 브랜치 커밋이 그대로 남고, 합친 지점이 커밋으로 보인다 |
| Squash and merge | A+B를 합친 커밋 1개 | PR 하나가 커밋 하나가 된다. 이력이 짧다 |
| Rebase and merge | A', B' (dev 끝에 다시 붙인 복사본) | merge 커밋 없이 일직선. 커밋 해시가 바뀐다 |

### 규칙과 실제
- 규칙: `docs/guides/git-workflow.md`의 병합 방식은 "squash가 기본이다. 커밋 단위 이력을 남겨야 할 때만 merge commit을 쓴다"이다. rebase는 규칙에 없다.
- 실제(10/30 dev 이력): 최근 PR 15개 중 14개가 "Merge pull request"(merge commit)이고 squash는 #86 하나다. 팀원 대부분이 GitHub 기본 버튼(merge commit)을 눌렀다. 저장소 설정은 세 방식을 모두 허용한다.

### 이번 PR #97은
커밋 두 개(열 번호 정수 변경, 공연장 시드)가 각각 의미가 있다. 규칙의 예외("커밋 단위 이력을 남겨야 할 때")에 해당한다고 볼 수 있어 merge commit도 규칙 위반이 아니다. squash로 하나로 합쳐도 PR 본문에 두 작업이 다 적혀 있어 문제없다.

### 함께 겪은 일: 브랜치를 옮기면 테스트 DB가 안 맞을 수 있다
ISE-65 브랜치에서 테스트를 돌려 테스트 DB에 `V20261030_1`(row_no INT)이 적용됐다. 그 뒤 dev(아직 #97 머지 전)에서 새 브랜치를 만들어 테스트하자 엔티티는 `String rowNo`(VARCHAR), DB는 INT라 JPA validate가 실패했다. 테스트 DB는 tmpfs라 컨테이너를 재시작해 비우면 지금 브랜치의 파일로 다시 적용된다.
```bash
docker restart iseonjwa-test-postgres-reservation-1
```

### 후속 질문(11/01): squash 머지한 브랜치를 `git branch -d`로 지우면 "not fully merged"가 뜬다
squash는 브랜치 커밋(A, B)을 dev에 그대로 넣지 않고 내용을 합친 **새 커밋 C**를 만든다. git의 `-d`는 "이 브랜치의 커밋이 다른 브랜치에 들어 있는가"를 커밋 단위로 확인하는데, A·B는 dev에 없으니 안 합쳐졌다고 판단한다. 내용은 C에 다 들어 있으므로 PR이 머지된 것을 확인했다면 `git branch -D`로 지워도 된다.

로컬 반영도 같은 원리다. GitHub에서 머지하면 원격 dev에 C가 생기고, 로컬은 `git checkout dev` → `git pull`로 받아 오면 끝이다.

## 핵심 정리
- 문서 규칙은 squash 기본, 필요하면 merge commit. rebase는 쓰지 않는다.
- 실제 팀 이력은 대부분 merge commit이다. 둘 다 규칙 안이다.

## 참고
- `docs/guides/git-workflow.md` 5절 병합 방식
