---
title: Spring 설정 파일은 application.properties와 application.yml 중 무엇을 쓰고, 차이는 무엇인가
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 기초
tags: [spring-boot, configuration, yaml, config-server]
---

# Spring 설정 파일은 application.properties와 application.yml 중 무엇을 쓰고, 차이는 무엇인가

## 질문
properties vs yaml

## 짧은 답
- 기능은 거의 같다. Spring은 둘 다 읽어 같은 평평한 키(`spring.datasource.url`)로 바꾼 뒤 쓴다.
- 차이는 표현 방식이다. properties는 한 줄에 키 하나, YAML은 들여쓰기로 계층을 표현한다.
- 이 프로젝트는 YAML로 통일했다. 설정이 깊고 길며(Gateway 라우트, Config 서버 프로필 파일), compose·Prometheus도 YAML이라 한 형식으로 맞췄다.
- 대가는 YAML의 함정이다: 들여쓰기 실수, `on`·`no` 같은 값이 불리언으로 바뀌는 문제, `@PropertySource` 미지원.

## 자세히

### 같은 설정, 두 형식

```properties
spring.datasource.url=jdbc:postgresql://postgres-user:5432/user_db
spring.datasource.username=user_app
spring.datasource.password=${USER_DB_PASSWORD}
spring.datasource.hikari.maximum-pool-size=20
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres-user:5432/user_db
    username: user_app
    password: ${USER_DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
```

### 비교

| 항목 | properties | YAML |
|---|---|---|
| 구조 | 한 줄에 전체 키. 접두사를 매번 반복 | 들여쓰기로 계층. 접두사 한 번 |
| 목록 | `routes[0].id=...`, `routes[1].id=...` 또는 쉼표 | `- id: ...` |
| 여러 프로필을 한 파일에 | `#---` 구분선 (Boot 2.4+) | `---` 구분선 |
| 검색·복사 | 전체 키가 한 줄이라 `grep`, 문서 복사에 유리 | 키가 여러 줄에 흩어져 `grep spring.datasource.url`로 안 잡힌다 |
| 실수 유형 | 오타 정도 | 들여쓰기 한 칸, 탭 문자, 타입 자동 변환 |
| `@PropertySource` | 지원 | 기본 미지원(별도 팩토리 필요) |
| 같은 위치에 둘 다 있으면 | **properties가 이긴다** | 진다 |
| 파서 | Java 기본 | SnakeYAML(스타터에 포함) |

공통점: 프로필 파일(`application-local.yml`), 환경 변수로 덮어쓰기(`SPRING_DATASOURCE_URL`), `${...}` 자리표시자, `@ConfigurationProperties` 바인딩은 형식과 상관없이 똑같이 동작한다.

### YAML에서 자주 틀리는 것

| 함정 | 예 | 해결 |
|---|---|---|
| 불리언으로 바뀌는 값 (SnakeYAML은 YAML 1.1) | `country: NO` → `false`, `mode: on` → `true` | 문자열은 따옴표: `"NO"` |
| 0으로 시작하는 숫자 | `code: 0123` → 8진수로 해석될 수 있다 | `"0123"` |
| 특수 문자로 시작하는 값 | `*`, `@`, `%`, `` ` `` 로 시작 | 따옴표 |
| 탭 문자 | 들여쓰기에 탭 → 파싱 실패 | 공백만 (`.gitattributes`·에디터 설정) |
| 들여쓰기 한 칸 차이 | `hikari`가 `datasource` 밖으로 나가면 다른 키가 된다(오류 없이 무시될 수 있다) | 설정 테스트, IDE 자동 완성 |
| 맵 키에 점·대괄호 | `"[/api/**]": ...` | 키를 `"[...]"`로 감싼다 |

### 두 형식 모두 같은 함정: 목록은 합쳐지지 않고 통째로 바뀐다
우선순위가 높은 파일에 같은 목록 키가 있으면 낮은 쪽 목록을 **통째로 대체**한다(Spring Boot 문서의 목록 병합 규칙). 예를 들어 `config-repo/application-local.yml`에 Gateway 라우트 하나를 더 적으면, `gateway.yml`의 라우트 3개가 사라지고 그 하나만 남는다. 맵(키-값)은 키 단위로 합쳐진다.

## 이 프로젝트에서는
- 서비스 모듈의 `application.yml`, `config-repo/*.yml`, `compose.yaml`, `infra/prometheus/prometheus.yml` 모두 YAML이다. `gradle.properties`는 Spring 설정이 아니라 Gradle 설정이라 properties다.
- Config 서버는 YAML을 읽어 평평한 키로 바꿔 내려 준다. 그래서 `ConfigRepoTest`가 `firstValue(env, "spring.datasource.url")`처럼 점으로 이은 키로 값을 찾는다. `curl localhost:8888/user-service/local`로 보면 확인할 수 있다.
- 형식을 섞지 않는다. 같은 폴더에 `application.properties`가 생기면 YAML보다 우선해 헷갈린다.
- 설정을 잘못 고치면 오류 없이 무시될 수 있으므로, 중요한 값은 `ConfigRepoTest`·`GatewayRoutesTest`처럼 테스트로 지킨다(변이 테스트로 실패하는 것을 확인함).

## 면접 포인트
- "properties와 YAML 중 무엇을?": 기능은 같고 Spring이 같은 키로 바꿔 쓴다. 계층이 깊고 목록이 많은 설정(라우트, 프로필별 파일)이 많아 YAML을 골랐고, 들여쓰기·타입 변환 함정은 따옴표 규칙과 설정 테스트로 막았다.
- "둘 다 있으면?": 같은 위치면 properties가 우선한다. 그래서 한 프로젝트에서 섞지 않는다.
- "프로필 파일에서 목록 하나만 추가하면?": 합쳐지지 않고 목록 전체가 바뀐다.

## 참고
- Spring Boot 문서 "Externalized Configuration"(YAML 사용, 목록 병합, `.properties` 우선 규칙)
- 이 프로젝트 `config-server/src/test/java/com/iseonjwa/configserver/ConfigRepoTest.java`
