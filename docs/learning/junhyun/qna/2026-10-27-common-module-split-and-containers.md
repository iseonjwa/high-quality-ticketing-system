---
title: common 모듈은 왜 여러 개로 나뉘었고 Docker에서도 컨테이너가 여러 개인가
date: 2026-10-27
updated: 2026-10-27
asker: Junhyun An
level: 기초
tags: [gradle, multimodule, msa, docker, compose]
---

# common 모듈은 왜 여러 개로 나뉘었고 Docker에서도 컨테이너가 여러 개인가

## 질문
지금 common이 여러개로 나뉘었잖아. 왜 그런거야? 그리고 도커로 띄울 때도 컨테이너가 여러개야?

## 짧은 답
common은 실행되는 서버가 아니라 코드 라이브러리(JAR)라서 컨테이너가 없다. 각 앱을 빌드할 때 그 앱이 필요한 모듈만 앱 JAR 안에 합쳐 들어간다. 여러 개로 나눈 이유는 앱마다 쓰는 기술이 달라서, 한 덩어리로 두면 쓰지 않는 의존성이 따라 들어오기 때문이다. Docker 컨테이너는 앱 7개와 인프라를 합쳐 17개다.

## 자세히
### 모듈이 나뉜 이유: 쓰는 앱의 기술이 다르다
| 모듈 | 담은 것 | 쓰는 앱 | 따로 둔 이유 |
|---|---|---|---|
| common-response | 응답 DTO, 에러 코드, 역할·헤더 상수 | (common-auth를 통해) 전 앱 | Spring에 의존하지 않아 WebFlux인 Gateway도 쓸 수 있다 |
| common-auth | JWT 검증 계약(TokenDecoders) | Gateway, User | Gateway와 User가 같은 검증 규칙을 한 곳에서 공유한다 |
| common-web | MVC 예외 처리기, 사용자 헤더 필터, `@RequireRole` | User, Reservation, Payment | Spring MVC(서블릿) 전용이라 WebFlux인 Gateway가 가져오면 충돌한다 |
| common-messaging | Kafka 연결 기반 | Reservation, Payment | Kafka를 쓰는 앱만 필요하다 |
| common | 감사 엔티티, 이벤트 봉투 | User, Reservation, Payment | JPA를 쓰는 앱만 필요하다 |

핵심 규칙은 두 가지다. 첫째, Gateway는 WebFlux라서 MVC·JPA를 끌어오면 안 된다(SA 11-2도 common-web은 Gateway 제외로 명시). 둘째, Mock PG·Eureka·Config Server는 공통 모듈을 아예 쓰지 않는다. 한 모듈로 합치면 이 구분이 사라져 모든 앱이 모든 의존성을 갖게 된다.

장점은 의존성 격리와 변경 영향의 축소다. 단점은 모듈이 늘수록 이름과 경계를 이해해야 하는 비용이 든다. SA 13장도 `common-response`·`common-web`·`common-messaging`을 나누는 방향이고, M1에서 팀이 각자 구성한 뒤 합치기로 했다. 합치는 기준은 "쓰는 앱의 기술이 같은가"로 잡으면 된다.

### 컨테이너는 몇 개인가
common 모듈은 컨테이너가 되지 않는다. Gradle이 `bootJar`를 만들 때 필요한 common 모듈의 클래스를 앱 JAR 안에 넣는다. Dockerfile은 앱 하나당 이미지 하나를 만들고(`APP_MODULE`로 어떤 앱인지 지정), 컨테이너는 그 이미지를 실행한 것이다.

기본 Compose(`compose.yml`)의 컨테이너는 17개다.
- 앱 7개: config-server, eureka, gateway, user-service, reservation-service, payment-service, mock-pg
- 저장소·메시지: postgres 3개(서비스마다 DB 분리), redis, kafka, kafka-init(볼륨 권한만 맞추고 끝나는 일회성 컨테이너)
- 관측·관리: kafka-ui, prometheus, grafana, zipkin

사용자가 직접 접근하는 것은 Gateway(8080)와 관리자 UI뿐이고 나머지는 내부 네트워크에서만 서로 통신한다.

### 확인한 근거
Gradle 모듈의 의존 관계는 각 `build.gradle`의 `project(':...')` 선언으로, 컨테이너 목록은 `docker compose -f compose.yml config --services`로 확인했다.

### 후속 질문: 그러면 Config Server는 없는가
있다. config-server는 컨테이너 17개 중 앱 7개에 들어가는 독립 서버(포트 8888)이고, common 모듈과는 별개다. "공통 모듈을 쓰지 않는다"는 말은 코드 라이브러리를 가져다 쓰지 않는다는 뜻이지 서버가 없다는 뜻이 아니다. 앱들이 설정을 받아 가는 방식은 다음과 같다.
- 설정 파일은 저장소의 `config-repo/` 폴더에 있다(`application.yml` 공통, `gateway.yml`·`user-service.yml` 등 서비스별, `application-local.yml` 등 프로필별).
- config-server가 이 폴더를 읽기 전용으로 마운트해 제공하고(native 백엔드), 다른 앱은 시작할 때 `spring.config.import=configserver:...`로 가져온다. 못 받으면 제한된 재시도 뒤 기동에 실패한다.
- DB 비밀번호와 JWT 키 같은 비밀값은 config-server에 두지 않고 각 앱의 환경변수로 넣는다.
- 근거: [ADR 0003](../../../decisions/0003-config-server-native.md), SA 9-6.

### 후속 질문: 서비스마다 필요한 모듈을 import하고 컨테이너에 포함되는가
코드는 맞고 설정값은 아니다.
- 코드: 각 앱의 `build.gradle`에 `implementation project(':common-web')`처럼 선언한 모듈이 `app.jar` 안의 `BOOT-INF/lib/`에 들어가고, 그 JAR이 컨테이너 이미지에 담긴다. 실제로 user-service의 JAR에는 common, common-auth, common-web, common-response가 있고 Gateway에는 common-auth, common-response만 있다. Mock PG·Eureka·Config Server에는 없다.
- 설정값: `config-repo`의 값은 이미지에 굽지 않는다. 앱이 시작할 때 config-server에서 받아 온다(앱 JAR에는 앱 이름·프로필·Config 주소 같은 시작용 설정만 있다). 비밀값은 컨테이너 환경변수로 들어온다.
- 그래서 공통 모듈 코드를 고치면 그 모듈을 쓰는 앱을 모두 다시 빌드해 이미지를 새로 만들어야 하고, 설정값을 고치면 이미지는 그대로 두고 앱만 재시작하면 된다.

## 더 보기
- [공통 모듈 가이드](../../../guides/common-modules.md), [워크스페이스 가이드](../../../guides/workspace.md)
