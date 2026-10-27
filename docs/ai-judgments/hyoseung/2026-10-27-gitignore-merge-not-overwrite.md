---
title: 멀티모듈 전환: AI의 ".gitignore를 루트로 옮기라"는 안내를 덮어쓰기 위험으로 거부
date: 2026-10-27
updated: 2026-10-27
judge: hyoseung
ai: Claude Code (멀티모듈 전환 단계 안내)
verdict: 거부
area: 기타
presentation: 아님
shared: 아니오
---

# 멀티모듈 전환: AI의 ".gitignore를 루트로 옮기라"는 안내를 덮어쓰기 위험으로 거부

## 맥락
- 해결하려던 작업: Initializr로 만든 독립 프로젝트 `user-service`를 멀티모듈 구조로 바꾸는 일(M1 0단계)
- AI 활용 방식: user-service에 의존성만 넣은 상태에서 다음에 할 일을 물었다

## AI 제안
`user-service/` 안의 `gradlew`, `gradlew.bat`, `gradle/`, `.gitattributes`, `.gitignore`를 루트로 옮기라고 안내했다. 루트에 이미 팀 `.gitignore`가 있다는 점을 짚지 않았다.

## 판단
- 결과: 거부
- 판단자: hyoseung
- 근거: IntelliJ가 겹치는 파일을 덮어쓸지 묻자 "하면 안 되지 않냐"고 보고 `.gitignore`만 옮기지 않았다.
- 검토한 대안: 루트 `.gitignore`에 Gradle·IDE 항목을 합치고 모듈의 `.gitignore`를 지우는 방식(AI 후속 안내)

## 반영
`.gitignore`는 옮기지 않았다. 나머지(`gradlew`, `gradlew.bat`, `gradle/`, `.gitattributes`)는 루트로 옮겼다.

## 검증
덮어쓰기를 했다면 루트 `.gitignore`의 `.env`, `CLAUDE.local.md`, `.claude/settings.local.json` 제외 규칙이 사라져 비밀값과 개인 설정이 커밋될 수 있었다.

## 판단 이력
- 2026-10-27 거부: 루트 `.gitignore`를 덮어쓰지 않고 내용을 합치기로 했다

## 발표·면접 포인트
- AI의 파일 이동 안내를 그대로 따르지 않고, 기존 파일과 충돌하는지 먼저 확인했다.
