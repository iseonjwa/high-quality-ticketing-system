---
title: Gradle 빌드 스크립트를 Kotlin DSL에서 Groovy로 바꾸면 무엇이 달라지나
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 중급
tags: [gradle, kotlin-dsl, groovy, build-logic]
---

# Gradle 빌드 스크립트를 Kotlin DSL에서 Groovy로 바꾸면 무엇이 달라지나

## 질문
Groovy로 변경하면? (팀원 브랜치가 Initializr 기본값인 Groovy `build.gradle`이고, 내 브랜치는 Kotlin DSL `build.gradle.kts`라 합칠 때 하나로 맞춰야 하는 상황)

## 짧은 답
- 빌드 결과(모듈, 의존성, 버전, Java 21)는 **바뀌지 않는다.** 같은 Gradle 모델을 다른 문법으로 적는 것뿐이다.
- 바뀌는 것은 파일 19개의 문법과, 에디터 지원(자동 완성, 오류를 미리 잡는 정도)이다.
- 정말 중요한 결정은 DSL이 아니라 **구조**(멀티모듈, 컨벤션 플러그인, 버전 카탈로그)다. Groovy로 가도 이 구조는 그대로 옮길 수 있다.
- 그래서 DSL은 팀이 익숙한 쪽으로 정해도 되는, 되돌리기 쉬운 결정이다. 단, 섞어 쓰지는 않는다.

## 자세히

### 비교

| 항목 | Kotlin DSL (`.gradle.kts`) | Groovy DSL (`.gradle`) |
|---|---|---|
| 타입 검사 | 컴파일 시점에 오타·타입 오류를 잡는다 | 실행해 봐야 안다(동적 타입) |
| IDE 자동 완성 | 강하다. Ctrl+클릭으로 Gradle API까지 이동 | 약하다. 추측 기반 |
| 첫 동기화 속도 | 스크립트를 컴파일해서 느리다(이후 캐시) | 빠르다 |
| 문법 | 엄격(`id("x")`, `"implementation"(...)`) | 느슨(`id 'x'`, 괄호·따옴표 생략) |
| 예제·자료 | Gradle 8.2부터 새 빌드의 기본값. 최신 문서는 Kotlin 우선 | 오래된 블로그·강의 대부분. Initializr에서 "Gradle - Groovy" 선택지 |
| 팀 익숙함 | 낮을 수 있다 | 부트캠프·Initializr에서 익숙한 경우가 많다 |

### 바꾸면 이 저장소에서 달라지는 것 (19개 파일)

| 파일 | 지금 (Kotlin) | Groovy로 |
|---|---|---|
| `settings.gradle.kts`, `build-logic/settings.gradle.kts` | `include("common", ...)` | `settings.gradle`: `include 'common', ...` |
| `build-logic/build.gradle.kts` | ``plugins { `kotlin-dsl` }`` | `plugins { id 'groovy-gradle-plugin' }` |
| 컨벤션 플러그인 5개 `src/main/kotlin/*.gradle.kts` | `tasks.withType<Test>().configureEach { }` | `src/main/groovy/*.gradle`: `tasks.withType(Test).configureEach { }` |
| 모듈 11개 `build.gradle.kts` | `implementation(libs.spring.cloud.config.server)` | `implementation libs.spring.cloud.config.server` |

바뀌지 않는 것: `gradle/libs.versions.toml`(버전 카탈로그는 DSL과 무관), `gradle.properties`, wrapper, 소스 코드, Dockerfile, compose.

### 주의할 점
- **섞어 쓰지 않는다.** Gradle은 파일마다 DSL을 고를 수 있지만, 같은 폴더에 `settings.gradle`과 `settings.gradle.kts`가 함께 있으면 `settings.gradle`을 읽는다. 오늘 임시 폴더에서 두 브랜치를 합쳐 보니 모듈 11개가 사라졌는데 `./gradlew build`는 성공(exit 0)했다. 바꿀 때는 `.kts` 파일을 모두 지워야 한다.
- 컨벤션 플러그인 폴더도 `src/main/kotlin` → `src/main/groovy`로 옮긴다. 플러그인 id(`iseonjwa.mvc-service`)는 파일 이름에서 나오므로 그대로다.
- 바꾼 뒤 확인: 전체 빌드와 테스트 수(지금 11개)가 같아야 하고, `docker compose build`가 성공해야 한다.

### 결정 기준
- 팀 다수가 Groovy에 익숙하고 빌드 파일을 각자 고쳐야 한다면 → Groovy. 리뷰할 때 읽기 쉬운 것이 중요하다.
- 빌드 로직(컨벤션 플러그인)을 소수가 관리하고 IDE 지원을 중시한다면 → Kotlin.
- 어느 쪽이든 구조(멀티모듈 + 컨벤션 플러그인 + 카탈로그)는 유지한다.

## 이 프로젝트에서는
- 현재 Kotlin DSL 파일 19개(`git ls-files "*.gradle.kts"`로 확인). 1단계에서 Kotlin DSL을 고른 이유는 타입 검사와 IDE 자동 완성, Initializr·Gradle의 현재 기본값이었다(`docs/ai-judgments/kangmin/daily/2026-10-26.md`).
- 팀원 브랜치 `kwonwoo/feat/common`은 Groovy `build.gradle` + `settings.gradle`이다. 통합할 때 DSL을 하나로 정해야 한다.

## 면접 포인트
- "Kotlin DSL과 Groovy DSL의 차이는?": 같은 Gradle 모델을 다른 문법으로 적는다. Kotlin은 정적 타입이라 오류를 일찍 잡고 IDE 지원이 좋으며, Groovy는 문법이 느슨하고 첫 평가가 빠르다. 팀 익숙함으로 고를 수 있는 결정이고, 구조 결정과는 분리해서 봤다.

## 참고
- Gradle 문서 "Gradle Kotlin DSL Primer", "Migrating build logic from Groovy to Kotlin"
- 같은 날 노트: `2026-10-27-spring-initializr-vs-manual-multimodule.md`
