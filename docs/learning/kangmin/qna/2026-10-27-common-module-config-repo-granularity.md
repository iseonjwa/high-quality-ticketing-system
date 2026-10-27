---
title: 공통 모듈 4개와 config-repo 파일 14개는 너무 잘게 나눈 것인가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 중급
tags: [multi-module, gradle, spring-cloud-config, module-boundary]
---

# 공통 모듈 4개와 config-repo 파일 14개는 너무 잘게 나눈 것인가

## 질문
현재 보기에는 common도 잘게 쪼개져 있고 config도 잘게 쪼개져 있는 것처럼 보이는데 패키지 구성을 달리할 필요는 없는지?

## 짧은 답
- 두 곳 모두 "크기"가 아니라 **다른 기준**으로 나뉘어 있다. 공통 모듈은 **의존성(누가 무엇을 끌어오는가)**, config-repo는 **Config 서버의 파일 이름 규칙**이 기준이다.
- 공통 모듈 4개는 SA 13장(#22)의 팀 구조다. 지금은 4개 모두 빌드 파일 한 줄뿐인 빈 모듈이라 쪼갠 비용이 거의 없다. 합칠지는 내용이 생긴 뒤 의존성을 보고 정하는 편이 낫다.
- config-repo는 파일 이름이 곧 조회 키(`{서비스}-{프로필}.yml`)라서, 파일 수는 "서비스 수 × 프로필 차이"를 그대로 반영한다. 14개(181줄)는 아직 평면 구조로 충분하다.

## 자세히

### 1. 공통 모듈: 왜 4개인가

| 모듈 | 담을 것 (SA 13장) | 쓰는 곳 | 끌어오는 의존성 |
|---|---|---|---|
| `common` | 이벤트 봉투, BaseEntity·AuditorAware | 서비스 3개 | JPA |
| `common-response` | 성공·실패 응답 DTO, ErrorCode | **Gateway 포함** 전체 | 거의 없음 |
| `common-web` | MVC 예외 처리기 | Gateway **제외** 서비스 | Spring MVC |
| `common-messaging` | Outbox 릴레이, processed_event, 재시도·DLQ | Kafka를 쓰는 서비스 | Kafka, JPA |

### 왜 이렇게 나누나

공통 모듈을 나누는 기준은 크기가 아니라 **"이 모듈을 받으면 무엇이 함께 딸려 오는가"**다. 모듈에 의존하면 그 모듈의 클래스만 오는 것이 아니라, 그 모듈이 쓰는 라이브러리까지 실행 클래스패스에 함께 들어온다. 이를 전이 의존성(transitive dependency)이라고 한다. 예를 들어 Gateway가 응답 DTO 하나 때문에 `common`을 받으면, `common`이 쓰는 JPA까지 Gateway에 들어온다.

들어온 라이브러리는 쓰지 않아도 동작을 바꾼다. Spring Boot는 코드가 무엇을 호출하는지가 아니라 "클래스패스에 무엇이 있는가"를 보고 자동 설정을 켜기 때문이다. JPA가 있으면 DB 연결을 만들려 하고, Spring MVC가 있으면 서블릿 웹 서버를 준비하려 한다.

이 저장소에서 Gateway에 두 라이브러리를 임시로 붙여 실제로 확인했다(2026-10-27, `@SpringBootTest` 컨텍스트 기동).

| Gateway 클래스패스 | 결과 |
|---|---|
| 기본 | 성공. `GenericReactiveWebApplicationContext`(WebFlux), `dispatcherServlet` 빈 없음 |
| + `spring-boot-starter-webmvc` (`common-web`을 받은 경우) | 기동 실패. `MvcFoundOnClasspathException`: Spring Cloud Gateway가 MVC를 발견하면 일부러 멈춘다 |
| + `spring-boot-starter-data-jpa` (`common`을 받은 경우) | 기동 실패. `Failed to configure a DataSource: 'url' attribute is not specified` |

Gateway는 WebFlux로 동작하고 DB가 없다. 그래서 모든 서비스가 쓰는 응답 형식(`common-response`)은 의존성이 거의 없게 따로 두고, MVC 예외 처리기(`common-web`)와 JPA를 쓰는 엔티티 기반(`common`)은 MVC 서비스만 받게 나눴다. Kafka와 Outbox(`common-messaging`)도 같은 이유로, 이벤트를 쓰는 서비스만 받게 했다.

막는 방법은 두 가지다. 하나로 합치고 쓰는 쪽에서 자동 설정을 하나씩 끄는 방법(`exclude`)과, 애초에 쓰는 곳에 맞게 나누는 방법이다. 앞의 방법은 새 라이브러리가 공통 모듈에 들어올 때마다 모든 서비스의 제외 목록을 다시 확인해야 한다. 그래서 이 구조는 뒤의 방법을 골랐다. 부수 효과로, `common-messaging`을 고치면 Kafka를 쓰는 서비스만 다시 빌드되고, 모듈마다 담당이 분명해진다.

대가는 모듈 수다. 처음 보는 사람은 새 클래스를 어디에 둘지 헷갈린다. 판단 기준은 "이 클래스가 MVC·JPA·Kafka를 쓰는가, 그리고 Gateway도 이 클래스가 필요한가"다.

### 선택지 비교

| 안 | 구성 | 장점 | 단점 |
|---|---|---|---|
| A. 하나로 | `common` 1개 | 찾기 쉽고 빌드 파일이 하나 | Gateway에 MVC·JPA가 딸려 감. "아무거나 넣는 곳"이 되기 쉬움 |
| B. 둘로 | 프레임워크 없는 것(응답 DTO, ErrorCode, 이벤트 봉투) / MVC·JPA 쓰는 것(예외 처리기, BaseEntity) | 모듈 수가 적고, Gateway 문제도 피함 | 메시징(Outbox)이 들어오면 Kafka가 모든 서비스에 딸려 감 |
| C. 넷으로 (현재 SA) | 의존성 묶음마다 하나 | 각 서비스가 필요한 것만 가져감. 담당이 분명함(messaging은 결제 담당) | 모듈 수가 많아 보이고, 처음 보는 사람이 어디에 넣을지 헷갈림 |

### 하나로 시작하고 필요해지면 나누는 안
그대로 하나로 합치면 Gateway가 MVC·JPA를 받아 뜨지 않는다(위 실험). Gateway는 M1 JWT 필터에서 401을 공통 형식으로 내야 하므로 "필요해지는 시점"이 바로 온다. 그래서 하나로 시작하려면 Gateway 처리 방식을 함께 정한다.

| 안 | 방법 | 감수할 점 |
|---|---|---|
| A. 하나로 + Gateway는 의존하지 않음 | `common`은 서비스 3개만 받는다. Gateway는 에러 JSON(401·429·503)을 같은 모양으로 직접 쓴다 | 응답 형식이 Gateway에 한 번 복제된다(같은 모양인지 테스트로 묶는다). Kafka를 쓰지 않는 user-service에도 Kafka가 딸려 간다(기동 시 영향은 확인 못 함). DB 없는 mock-pg는 받지 않는다 |
| B. 하나로 + 라이브러리는 `compileOnly` | 쓰는 서비스가 실제 라이브러리를 넣는다 | 라이브러리가 없는 서비스에 그 라이브러리를 쓰는 클래스가 들어가, 스캔되면 기동 오류. 규칙을 모르면 사고 나기 쉽다 |

A안을 쓴다면 모듈 안 패키지를 미래 경계대로 미리 나눈다(`common.response`, `common.web`, `common.jpa`, `common.messaging`). 그러면 나중에 나눌 때 폴더를 옮기고 의존성 한 줄만 바꾸면 되고 import는 그대로다. 나눌 조건은 ① Gateway가 응답 형식 외의 공통 코드를 필요로 할 때, ② Kafka 때문에 Kafka를 안 쓰는 서비스에 문제가 생길 때, ③ 결제 담당이 messaging을 따로 관리하려 할 때다. SA 13장(#22)을 바꾸는 일이므로 팀 합의 뒤 ADR로 남긴다.

### 지금 판단
- **당장 바꿀 이유는 약하다.** 4개 모두 `plugins { id("iseonjwa.java-library") }` 한 줄뿐이고, 아무도 의존하지 않는다. 빈 모듈의 비용은 빌드 파일 4개와 Gradle 설정 시간 정도다.
- **바꿀 조건**: 내용을 채운 뒤 두 모듈의 의존성과 사용처가 같아지면 합친다. 예를 들어 `common`에 JPA가 필요 없게 되면 `common-response`와 합칠 수 있다.
- `common-messaging`은 결제 담당이 실제로 만들 때 생겨도 된다(YAGNI). 다만 SA 구조도에 있는 이름이라 미리 자리를 잡아 둔 것이다.
- 구조 변경은 팀 결정(#22, SA 13장)을 바꾸는 일이라 통합 회의에서 다룬다.

### 2. config-repo: 왜 14개인가

```
application.yml                ← 모든 서비스 공통
application-{local,loadtest,prod}.yml  ← 모든 서비스, 프로필별
{서비스}.yml                    ← eureka, gateway, mock-pg, user, reservation, payment
{서비스}-local.yml              ← DB를 쓰는 3개 서비스의 localhost 주소
```

Config 서버는 `{application}-{profile}` 이름으로 파일을 찾는다. 그래서 파일 수는 "공통 4 + 서비스 6 + 서비스별 local 3 + README"로 정해진다. 임의로 줄이면 조회 규칙이 바뀐다.

| 안 | 방법 | 장점 | 단점 |
|---|---|---|---|
| 평면 파일 (현재) | 파일 하나 = 서비스 하나 × 프로필 하나 | 파일 이름만 보고 어떤 값이 이기는지 안다. 우선순위를 테스트로 확인함 | 서비스·프로필이 늘면 파일이 곱으로 늘어남 |
| 한 파일 여러 문서 | `user-service.yml` 안에 `---` + `spring.config.activate.on-profile: local` | 서비스당 파일 1개 | 같은 파일 안의 프로필 문서와 `application-local.yml` 사이 우선순위를 다시 확인해야 함(확인 못 함) |
| 서비스별 폴더 | `config-repo/user-service/...` + `search-locations`에 `{application}` 자리표시자 | 폴더로 묶여 보기 좋음 | native 백엔드에서의 동작과 공통 파일 위치를 다시 확인해야 함(확인 못 함) |

- 지금은 181줄, 14개라서 평면 구조로 충분하다. 바꿀 조건은 프로필이 더 생기거나(예: test·staging) 파일이 25개 정도를 넘어 찾기 어려워질 때다.
- 바꾸면 `ConfigRepoTest`의 우선순위 테스트를 먼저 고쳐 같은 결과가 나오는지 확인한다.

## 이 프로젝트에서는
- 공통 모듈: `common*/build.gradle.kts`(빈 모듈), 구조 정본은 `docs/core/SA MASTER.md` 13장.
- 설정: `config-repo/`, 우선순위 검증은 `config-server/src/test/.../ConfigRepoTest.java`.
- 비교: `kwonwoo/feat/common` 브랜치는 Initializr 단일 모듈이라 공통 모듈 경계가 아직 없다. 통합 회의에서 SA 13장 구조를 그대로 쓸지 확인한다.

## 면접 포인트
- "공통 모듈을 왜 여러 개로 나눴나?": 크기가 아니라 의존성으로 나눴다. WebFlux 기반 Gateway가 MVC 예외 처리기를 끌어오지 않게 응답 형식과 예외 처리기를 분리했고, Kafka·Outbox는 쓰는 서비스만 가져가게 했다.
- "공통 모듈의 위험은?": 모든 서비스가 의존해서, 하나를 바꾸면 전 서비스가 다시 빌드·배포된다. 그래서 형식과 유틸만 두고 업무 로직은 넣지 않는다.

## 참고
- SA MASTER 13장, 결정 기록 #22
- Spring Boot 문서 `SpringApplication` 웹 애플리케이션 타입 추론
- Spring Cloud Config 문서 (native 백엔드 search-locations)
