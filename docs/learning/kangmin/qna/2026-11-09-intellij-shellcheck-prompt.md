---
title: IntelliJ가 shellcheck 설치를 물을 때 설치해도 되나
date: 2026-11-09
updated: 2026-11-09
asker: kangmin
level: 입문
tags: [shellcheck, intellij, shell-script, static-analysis]
---

# IntelliJ가 shellcheck 설치를 물을 때 설치해도 되나

## 질문
`run.sh`를 열자 IntelliJ 상단에 "Would you like to install shellcheck to verify your shell scripts?"가 떴다.

## 짧은 답
- ShellCheck는 셸 스크립트 정적 분석기(실행하지 않고 코드만 읽어 실수를 찾는 도구)다. 따옴표 빠진 변수, 잘못된 조건문, 이식성 문제 같은 실수를 편집기에서 바로 표시한다.
- IntelliJ의 Shell Script 플러그인이 ShellCheck 실행 파일을 IDE 설정 폴더에 내려받아 쓴다. 저장소 파일은 바뀌지 않는다.
- 추천: Install. 이 저장소에는 `.sh`가 22개 있다(`git ls-files`로 확인). 측정·배포 순서를 바꾸는 `run.sh`, `measure-mode.sh`, `seed-demo.sh`도 여기에 들어 있다. 반면 CI에는 ShellCheck 검사가 없다(`.github`, `script`에서 확인).

## 자세히
| 선택 | 얻는 것 | 대가 |
|---|---|---|
| Install | 셸 스크립트를 고칠 때 실수를 바로 본다 | 기존 스크립트에도 경고가 뜬다 |
| No | 아무것도 바뀌지 않는다 | 셸 실수는 실행해 봐야 안다 |

- 주의: 경고가 떴다고 팀 스크립트를 한꺼번에 고치지 않는다. 내가 고치는 파일에서만 보고, 다른 담당의 스크립트 경고는 그 담당에게 알린다.
- 바꿀 조건: 셸 스크립트 실수로 측정이나 배포가 실패하는 일이 반복되면, CI에 ShellCheck를 넣자고 팀에 제안한다.
