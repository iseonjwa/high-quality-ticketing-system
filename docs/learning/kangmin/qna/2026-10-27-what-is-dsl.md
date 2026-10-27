---
title: DSL(도메인 특화 언어)은 무엇이고 Gradle의 Kotlin·Groovy DSL은 어떤 의미인가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [dsl, gradle, kotlin, groovy]
---

# DSL(도메인 특화 언어)은 무엇이고 Gradle의 Kotlin·Groovy DSL은 어떤 의미인가

## 질문
DSL이 정확히 뭐임

## 짧은 답
- **DSL(Domain-Specific Language, 도메인 특화 언어)**은 한 가지 분야의 일만 표현하도록 만든 언어다. 반대말은 Java·Kotlin 같은 **범용 언어(GPL, General-Purpose Language)**다.
- SQL(데이터 조회), 정규식(문자열 패턴), HTML(문서 구조)이 대표적인 DSL이다.
- Gradle의 "Kotlin DSL"은 **Kotlin 문법 위에 만든 빌드 설정 전용 표현 방식**이다. `dependencies { implementation(...) }`는 설정 파일처럼 보이지만 실제로는 Kotlin 함수 호출이다. "Groovy DSL"은 같은 것을 Groovy 위에 만든 것이다.

## 자세히

### 두 종류

| 종류 | 뜻 | 예 |
|---|---|---|
| 외부 DSL | 자기만의 문법과 파서를 가진 독립 언어 | SQL, 정규식, HTML, Mermaid, JPQL |
| 내부 DSL(임베디드 DSL) | 범용 언어의 문법을 빌려 그 분야에 맞게 읽히도록 만든 API | Gradle Kotlin·Groovy DSL, Spring Security 설정의 람다 DSL, QueryDSL, AssertJ의 `assertThat(x).isEqualTo(y)` |

Gradle DSL은 내부 DSL이다. 새 언어가 아니라 Kotlin(또는 Groovy) 코드를 "빌드 설정처럼 읽히게" 쓰는 방식이다.

### 설정처럼 보이지만 코드다

이 저장소 `user-service/build.gradle.kts`의 모양:

```kotlin
plugins {
    id("iseonjwa.jpa-postgres")
}
```

Kotlin 문법으로 풀면:

```kotlin
plugins({            // plugins라는 함수에 람다(코드 블록)를 넘긴다
    this.id("iseonjwa.jpa-postgres")   // 블록 안의 this는 플러그인 설정 객체
})
```

- 마지막 인자가 람다면 괄호 밖으로 뺄 수 있고, 인자가 람다 하나면 괄호를 생략할 수 있다(Kotlin 문법).
- 블록 안에서 `this`가 특정 객체로 바뀌는 "수신 객체 지정 람다(lambda with receiver)" 덕분에 `id(...)`를 그냥 부를 수 있다.
- 그래서 설정 파일인데도 변수, 함수, 조건문을 쓸 수 있다. 이 저장소 컨벤션 플러그인의 `fun lib(alias: String) = libs.findLibrary(alias).get()`가 그 예다.

Groovy DSL은 같은 일을 더 느슨한 문법으로 한다: `plugins { id 'iseonjwa.jpa-postgres' }` (괄호·따옴표 종류 자유, 타입 검사 없음).

### 왜 DSL로 만드나

| 장점 | 대가 |
|---|---|
| 그 분야 사람이 읽기 쉽다(빌드 = 플러그인·의존성·작업) | 겉모양이 설정 같아서 "코드"라는 사실을 잊기 쉽다 |
| 내부 DSL은 범용 언어의 기능(함수, 조건, IDE 지원)을 그대로 쓴다 | 오류 메시지는 범용 언어 기준이라 낯설 수 있다 |
| 외부 DSL(SQL)은 목적에 꼭 맞는 문법을 가진다 | 외부 DSL은 별도 파서·도구가 필요하다 |

### YAML은 DSL인가
YAML·JSON은 **데이터 형식**이다. 값을 적을 뿐 함수 호출이나 조건이 없다. Spring 설정(`application.yml`)은 YAML 형식에 Spring이 정한 키 규칙을 얹은 것이라 "설정 형식"으로 부르는 편이 정확하다. 반면 Gradle 스크립트는 실행되는 코드다. 이 차이 때문에 Gradle 파일에는 `if`를 쓸 수 있고 YAML에는 쓸 수 없다.

## 이 프로젝트에서는
- Kotlin DSL: `settings.gradle.kts`, 모듈별 `build.gradle.kts`, `build-logic/src/main/kotlin/*.gradle.kts`.
- 외부 DSL: Flyway 마이그레이션의 SQL, Mermaid(SA 다이어그램), Redis Lua(대기열 seq, 예정).
- 내부 DSL: AssertJ(`assertThat(routes).containsEntry(...)`), Gateway 라우트를 코드로 정의할 때의 `RouteLocatorBuilder`(이 저장소는 YAML로 정의).

## 면접 포인트
- "Gradle Kotlin DSL이 뭔가?": Kotlin 문법 위에 만든 빌드 설정용 내부 DSL이다. 설정처럼 보이지만 수신 객체 지정 람다로 된 Kotlin 코드라서 타입 검사와 IDE 지원을 받는다.

## 참고
- Martin Fowler, "Domain-Specific Languages" (내부·외부 DSL 구분)
- Kotlin 문서 "Type-safe builders"(수신 객체 지정 람다)
