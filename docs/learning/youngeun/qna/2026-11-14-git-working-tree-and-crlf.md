---
title: Git의 작업 트리는 무엇이고, 변경이 없는데 git status에 수정으로 나오는 이유는 무엇인가
date: 2026-11-14
updated: 2026-11-14
asker: kye
level: 기초
tags: [git, working-tree, line-ending, crlf, autocrlf]
---

# Git의 작업 트리는 무엇이고, 변경이 없는데 git status에 수정으로 나오는 이유는 무엇인가

## 질문
작업 트리는 무슨 말인가. AI가 "작업 트리에 커밋 안 된 변경이 있다"고 했는데, 내가 고친 파일이 아니라 나와 관련이 없지 않은가.

## 짧은 답
작업 트리(working tree)는 지금 디스크에 풀려 있는 파일들이다. Git은 이것을 마지막 커밋(HEAD)과 비교해 다르면 "수정됨"으로 보여 준다. 이번에 나온 15개 파일은 내용이 아니라 줄바꿈 문자만 달랐다. Windows에서 체크아웃한 파일(CRLF)을 줄바꿈 변환 설정이 없는 리눅스 Git으로 읽어서 생긴 착시였고, 실제 변경은 없었다.

## 자세히
Git이 파일을 보는 곳은 세 군데다.

| 이름 | 위치 | 내용 |
|---|---|---|
| 작업 트리 | 프로젝트 폴더의 실제 파일 | 편집기로 보고 고치는 파일 |
| 스테이징 영역(index) | `.git/index` | `git add`로 다음 커밋에 넣기로 한 상태 |
| 저장소(HEAD) | `.git/objects` | 마지막 커밋의 상태 |

`git status`의 "Changes not staged for commit"은 작업 트리가 index와 다르다는 뜻이다. 누가 고쳤는지는 따지지 않는다. 그래서 내가 손대지 않은 파일도 나올 수 있다.

줄바꿈 착시는 이렇게 생긴다.
- 저장소에는 LF(`\n`)로 저장되어 있다.
- Windows Git은 `core.autocrlf=true`일 때 체크아웃하면서 CRLF(`\r\n`)로 바꾸고, 비교할 때 다시 LF로 보고 같다고 판단한다.
- 같은 폴더를 `autocrlf`가 꺼진 다른 Git(리눅스 VM, WSL 등)으로 보면 모든 줄의 끝이 달라 보인다. 그래서 추가 줄 수와 삭제 줄 수가 똑같이 나온다.

진짜 변경인지 확인하는 방법:

```bash
git diff --ignore-cr-at-eol --stat   # 출력이 비면 줄바꿈 차이뿐이다
file <파일>                           # with CRLF line terminators 여부
git config core.autocrlf              # 이 Git의 변환 설정
```

## 이 프로젝트에서는
- 11/14 `fix/ISE-282-queue-turn-ttl` 브랜치에서 리눅스 Git으로 본 `git status`에 `docs/` 아래 15개 파일(kangmin·hyoseung 기록, SA MASTER 등)이 수정으로 나왔다. 추가와 삭제가 각각 3,676줄로 같았고, `git diff --ignore-cr-at-eol`은 비어 있었다. 리눅스 쪽 `core.autocrlf`는 설정되어 있지 않았다.
- 루트 `.gitattributes`는 `*.sh`, `gradlew`만 LF로, `*.bat`만 CRLF로 고정하고 나머지는 각자의 `autocrlf` 설정을 따른다.
- 커밋은 평소처럼 Windows Git(IntelliJ, Git Bash)에서 하면 이 파일들은 수정으로 잡히지 않는다. 다른 환경의 Git으로 `git add .`를 하면 줄바꿈만 바뀐 파일이 커밋에 섞일 수 있으니 파일을 지정해 `add`한다.

## 면접 포인트
- 작업 트리, 스테이징 영역, HEAD의 차이: 고친 파일 → `add`로 고른 변경 → 커밋된 기록.
- `core.autocrlf`와 `.gitattributes`의 `eol`: 팀에 OS가 섞이면 `.gitattributes`로 줄바꿈을 저장소에서 고정하는 편이 개인 설정에 맡기는 것보다 안전하다.

## 참고
- Git 문서 `git-config` 의 `core.autocrlf`, `gitattributes` 의 `text`·`eol`
- 확인한 실행 결과: `git diff --stat` 15 files, 3676 insertions(+), 3676 deletions(-) / `git diff --ignore-cr-at-eol --stat` 출력 없음
