---
title: 멀티모듈로 옮길 때 .gitignore는 어떻게 하고, gradle/ 폴더는 무엇인가
date: 2026-10-27
updated: 2026-10-27
asker: hyos1
level: 기초
tags: [git, gitignore, gradle, gradle-wrapper, multimodule]
---

# 멀티모듈로 옮길 때 .gitignore는 어떻게 하고, gradle/ 폴더는 무엇인가

## 질문
Initializr로 만든 user-service를 멀티모듈로 바꾸며 파일을 루트로 옮기는데, `.gitignore`가 겹친다고 덮어쓸지 묻는다. 덮어쓰면 안 되지 않나. 그리고 `gradle/`은 그 폴더 자체를 말하는가.

## 짧은 답
덮어쓰면 안 된다. 루트 `.gitignore`에는 팀 규칙(`.env`, 개인 설정 제외)이 있어서 덮어쓰면 비밀값이 커밋될 수 있다. 모듈 `.gitignore`에서 필요한 줄(Gradle 빌드 결과, IDE 파일)만 루트에 합치고 모듈 쪽은 지운다. `gradle/`은 폴더 자체가 맞다. 안의 `gradle/wrapper/`에 Gradle Wrapper 파일 두 개가 있고, 이 폴더는 커밋해야 한다.

## 자세히
### .gitignore는 덮어쓰지 않고 합친다
- `.gitignore`는 폴더마다 둘 수 있고 각자 자기 폴더 아래에 적용된다. 그래서 모듈 안에 남겨도 동작은 한다.
- 그래도 루트 하나로 합치는 편이 낫다. gradlew가 루트로 오면 루트에도 `.gradle/`, `build/`가 생기는데 모듈 `.gitignore`는 루트를 덮지 못한다. 모듈이 늘 때마다 같은 파일이 복사되는 것도 피할 수 있다.
- 합칠 때 쓸모없어지는 줄도 있다. Initializr의 `!gradle/wrapper/gradle-wrapper.jar`는 `*.jar`를 무시하는 규칙의 예외인데, 루트 `.gitignore`에 `*.jar` 규칙이 없으면 필요 없다.

루트에 더할 줄 예시:
```gitignore
# Gradle 빌드 결과
.gradle/
build/
!**/src/main/**/build/
!**/src/test/**/build/
out/
!**/src/main/**/out/
!**/src/test/**/out/

# IDE (IntelliJ는 .idea/로 이미 제외)
*.iml
*.ipr
*.iws
.vscode/
bin/
!**/src/main/**/bin/
!**/src/test/**/bin/
```
- `!**/src/main/**/build/`는 소스 안에 우연히 `build`라는 패키지가 있을 때 제외되지 않게 하는 예외다.

### gradle/ 폴더는 Gradle Wrapper다
```
gradlew, gradlew.bat              실행 스크립트 (Linux·macOS / Windows)
gradle/wrapper/gradle-wrapper.jar         Gradle을 내려받아 실행하는 작은 프로그램
gradle/wrapper/gradle-wrapper.properties  쓸 Gradle 버전(distributionUrl)
```
- `./gradlew build`를 실행하면 properties에 적힌 버전(이 프로젝트는 9.8.2)을 내려받아 쓴다. 각자 PC에 Gradle을 설치하지 않아도 팀 전체가 같은 버전으로 빌드한다.
- 그래서 이 네 개는 커밋한다. 빌드 결과(`build/`, `.gradle/`)와 다르다.
- 멀티모듈에서는 wrapper가 루트에 하나만 있다. 모듈마다 두지 않는다.

### IntelliJ에서 지울 때 "Conflicts Detected"가 뜨는 이유 (추가 질문)
IntelliJ의 Delete는 Safe Delete다. 지우기 전에 다른 파일이 그 파일을 쓰는지 찾고, "Search for text occurrences"가 켜져 있으면 파일 이름이 글자로 적힌 곳까지 찾는다. `.gitignore`를 지우려 하면 `AGENTS.md`나 학습 노트처럼 문서에 `.gitignore`라는 글자가 적힌 곳이 모두 "충돌"로 잡힌다. 이름만 같을 뿐 루트 `.gitignore`를 가리키는 문장이라 모듈 파일을 지워도 영향이 없다.

- 지우려는 파일이 `user-service/.gitignore`인지(루트 것이 아닌지) 확인한 뒤 `Refactor Anyway`를 누른다. 파일만 지워지고 문서 내용은 바뀌지 않는다.
- 이런 경고가 번거로우면 삭제 대화상자에서 "Search for text occurrences"를 끈다.
- 코드에서 실제로 참조하는 클래스를 지울 때 뜨는 충돌은 다르다. 그때는 목록을 보고 참조를 먼저 고친다.

## 이 프로젝트에서는
- 루트 `.gitignore`의 기존 항목: `.env`, `CLAUDE.local.md`, `.claude/settings.local.json`, `.claude/skills/`, `.idea/`, `.claude/worktrees/`. `docs/guides/git-workflow.md` 3절의 "올리면 안 되는 것"과 이어진다.
- 함께 옮긴 `.gitattributes`의 `/gradlew text eol=lf`는 Windows에서 받아도 gradlew를 LF로 유지해 Linux 컨테이너에서 `bad interpreter` 오류가 나지 않게 한다.
- Initializr가 만든 `HELP.md`는 안내용이라 지워도 된다.

## 면접 포인트
- Gradle Wrapper를 커밋하는 이유: 빌드 도구 버전을 저장소가 고정해 "내 PC에서는 된다" 문제를 줄인다.
- `.gitignore`를 병합할 때 주의할 점: 기존 규칙(특히 비밀값 제외)을 잃지 않는다.
