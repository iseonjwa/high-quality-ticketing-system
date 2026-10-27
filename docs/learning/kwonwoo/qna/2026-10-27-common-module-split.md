---
title: 공통 모듈을 왜 여러 개로 나누나, 하나로 두는 것과 무엇이 다르고 어떻게 나누나
date: 2026-10-27
updated: 2026-10-27
asker: kwonwoo
level: 중급
tags: [gradle, multi-module, msa, webflux, webmvc, dependency]
---

# 공통 모듈을 왜 여러 개로 나누나, 하나로 두는 것과 무엇이 다르고 어떻게 나누나

## 질문
SA 13장은 공통 코드를 `common` / `common-response` / `common-web` / `common-messaging`으로 나눈다. 왜 나누나. `common` 하나로 두는 방식과 비교하면 어느 쪽이 유리한가. 나눈다면 모듈을 여러 개로 만드는 것인가, 한 모듈 안에서 더 작게 나누는 것인가.

## 짧은 답
- 나누는 이유는 의존성 격리다. Gradle은 모듈 단위로 jar와 의존성을 넘기므로, 일부 패키지만 골라 가져올 수 없다.
- Gateway가 WebFlux, 서비스가 MVC면 나누는 쪽이 유리하다. Spring Boot는 클래스패스에 `spring-webmvc`가 있으면 서블릿으로 뜨므로, MVC 예외 처리기가 든 공통 모듈을 Gateway가 쓰면 Gateway가 리액티브로 뜨지 않는다.
- 나누는 방식은 별도 Gradle 하위 프로젝트 여러 개다. 한 모듈 안 패키지 분리로는 의존성이 격리되지 않는다.

## 자세히

### 무엇이 어디에 필요한가
| 내용 | 필요한 의존성 | Gateway | User | Reservation·Payment |
|---|---|---|---|---|
| 응답 DTO·ErrorCode | 없음 | 씀 | 씀 | 씀 |
| MVC 예외 처리기 | spring-webmvc | 쓰면 안 됨 | 씀 | 씀 |
| BaseEntity·AuditorAware | JPA | 안 씀 | 씀 | 씀 |
| Outbox·processed_event·DLQ | Kafka, JPA | 안 씀 | 안 씀 | 씀 |

### 하나로 둘 때와 나눌 때
`common` 하나로도 MVC·JPA·Kafka를 `compileOnly`로 두면 소비자에게 의존성이 넘어가지 않는다. 하지만 격리를 사람이 지켜야 한다. 누가 `implementation`으로 하나만 추가해도 Gateway가 깨진다. 또 MVC를 참조하는 클래스가 Gateway jar에 들어가 있어서, 자동 구성이나 스캔으로 로드되면 `NoClassDefFoundError`가 난다. 이를 막으려면 `@ConditionalOnWebApplication(type = SERVLET)` 같은 조건을 빠짐없이 달아야 한다.

나누면 Gateway는 `common-response`만 의존하므로 MVC가 들어올 길이 구조적으로 없다. 변경 영향도 줄어든다. Outbox를 고쳐도 Gateway는 다시 빌드되지 않는다. 모듈별 담당도 둘 수 있다. 대가는 `build.gradle`과 `settings.gradle` 항목이 늘어나는 것, 그리고 과하게 쪼개면 몇 줄짜리 모듈이 생긴다는 것이다.

Gateway가 WebMVC라면 하나로 둬도 비용이 거의 없다. 웹 스택이 갈리는 순간부터 나누는 편이 안전하다.

### 나누는 방식
| 방식 | 구조 | 의존성 격리 | 평가 |
|---|---|---|---|
| 루트에 나란히 | `common/`, `common-response/`, `common-web/` | 됨 | SA 13장 방식, 가장 흔함 |
| 중첩 | `common/response/` + `include 'common:response'` | 됨 | 효과는 같고 경로만 길어짐 |
| 패키지 분리 | 한 모듈 안 `response`, `web` 패키지 | 안 됨 | jar가 하나라 정리 효과만 있음 |
| feature variant | `registerFeature('web')` | 됨 | 소비자가 capability를 지정해야 하는 고급 기능 |

```
gateway-edge  ──▶ common-response
*-service     ──▶ common-response, common-web, common
common-web    ──▶ common-response (+ spring-webmvc)
```

### 이 프로젝트에서는
지금 필요한 `common-response`, `common-web`, `common`만 만든다. `common-messaging`은 Outbox 코드가 생길 때 뗀다. 공통 모듈 경계는 10/27 안건("각자 구현 후 비교·병합")이므로, 개인 구현은 비교 대상으로 둔다.

### 이어서 나온 질문: 특정 도메인만 쓰는 의존성은 각 도메인에 두면 되지 않나
외부 라이브러리 의존성(Kafka, Redis)은 그것을 쓰는 서비스의 `build.gradle`에 선언하는 것이 맞다. 모듈 분리는 의존성이 아니라 여러 서비스가 함께 쓰는 코드를 어디에 두느냐의 문제다. 한 서비스만 쓰는 코드(JWT 검증은 Gateway, JWT 발급은 User)는 그 서비스에 둔다. 그렇게 거르면 공통 코드는 응답 형식, MVC 예외 처리기, BaseEntity 정도로 적다.

이 정도 양이면 `common` 하나로도 된다. 조건은 두 가지다. 첫째, `common`은 웹 스택·Kafka를 `compileOnly`로만 둔다. 둘째, 자동 구성 없이 서비스가 `scanBasePackages`나 `@Import`로 직접 가져온다. 그러면 Gateway jar에 MVC 클래스가 들어 있어도 스캔되지 않아 로드되지 않는다. 차이는 격리를 팀 규칙으로 지키느냐 빌드 구조로 보장하느냐다. 공통 코드에 일부 서비스만 쓰는 무거운 의존성(Outbox의 Kafka)이 생기면 그 부분만 뗀다.

## 면접 포인트
- MSA 공통 모듈을 나눈 이유: Gateway(WebFlux)와 서비스(MVC)의 웹 스택이 다르고, Boot가 클래스패스로 웹 스택을 고르기 때문에 의존성 격리를 모듈 구조로 보장했다.
- 패키지와 모듈의 차이: 패키지는 코드 정리 단위이고, 모듈은 의존성과 빌드 결과물(jar)의 단위다.
- 공통 모듈을 하나로 둘지 나눌지: 공통 코드 양과 의존성 무게로 정한다. 적을 때는 compileOnly와 스캔 규칙으로 격리하고, 무거운 의존성이 생기면 그 부분만 뗀다.
