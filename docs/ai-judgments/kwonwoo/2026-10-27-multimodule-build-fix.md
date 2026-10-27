---
title: "멀티모듈 빌드 실패: AI의 원인 진단과 수정안(common 라이브러리화, 공통 의존성 루트 집약)을 수용"
date: 2026-10-27
updated: 2026-10-27
judge: kwonwoo
ai: Claude Code (빌드 실행·의존성 해석으로 원인 진단 후 수정안 제시)
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 멀티모듈 빌드 실패: AI의 원인 진단과 수정안(common 라이브러리화, 공통 의존성 루트 집약)을 수용

## 맥락
- 해결하려던 작업: `common`에 공통 설정을 두고 Eureka와 Spring Cloud Gateway로 User·Reservation·Payment 서비스를 연결하는 멀티모듈 뼈대를 만들던 중 루트 빌드가 실패했다.
- AI 활용 방식: "빌드가 안 되는 원인을 찾아 달라"고 요청했다. AI는 `./gradlew build --continue`와 모듈별 `dependencies` 해석으로 오류를 모두 드러냈다.

## AI 제안
- 빌드 차단 원인 3개:
  - `common`에 Boot 플러그인이 있어 `bootJar` 실패
  - `java-test-fixtures` 누락
  - Eureka client group 오기(`org.springframework.boot` → `org.springframework.cloud`)
- 이어서 깨질 곳:
  - `common`의 `@SpringBootTest`
  - `common`의 `application.yaml`이 서비스 설정과 이름 충돌
- 구성 보완:
  - `@EnableEurekaServer` 누락
  - Gateway에 Eureka client와 라우트 없음
  - 모듈 안에 Initializr 단독 빌드 파일이 남음
- 수정 방향: `common`은 `java-library` + `java-test-fixtures`로 바꾸고, Feign·Eureka 의존성은 쓰는 서비스가 직접 선언한다.

## 판단
- 결과: 수용
- 판단자: kwonwoo
- 근거: 미기재. 수정과 빌드 확인을 요청하면서, 모듈 공통 의존성을 루트 `build.gradle`에 모으고 빌드를 검토하는 작업을 추가로 지시했다.
- 검토한 대안: 없음

## 반영
- 루트 `build.gradle`: 공통 의존성을 세 범위로 모았다.
  - 모든 모듈: Java 21, BOM, Lombok, 테스트
  - 실행 모듈: Boot 플러그인, Actuator, Prometheus
  - 서비스: common, Eureka client, webmvc, JPA, validation, PostgreSQL
- 테스트용 Eureka 비활성화 설정을 추가하고, `springCloudVersion`을 스크립트 변수로 바꿨다.
- `common/build.gradle`: `java-library` + `java-test-fixtures`로 바꿨다. Feign과 Eureka 의존성은 뺐다.
- `common/src/main/resources/application.yaml` → `application-common.yaml`: 이름을 바꾸고 `..env` 오타를 고쳤다. 서비스와 연결하는 작업은 아직 하지 않았다.
- Eureka에 `@EnableEurekaServer`를 넣었다.
- Gateway: webmvc gateway와 Eureka client를 쓰고, SA 7-1·API 목록 기준 라우트를 넣었다. 포트는 SA 값(8080·8761·8081~8083)을 따랐다.
- 모듈 안 `settings.gradle`, `gradlew`, `gradle/wrapper`, `.gitattributes`를 삭제했다.
- 문제 해결 기록: [TS-1](../../troubleshooting.md)

## 검증
- `./gradlew clean build --warning-mode all`: 성공했고 deprecation 경고는 0건, 테스트 5개(Eureka·Gateway·서비스 3개 contextLoads)는 모두 통과했다.
- Eureka와 Gateway jar를 실제로 띄워 확인했다.
  - Gateway가 Eureka에 등록됐다.
  - `/api/v1/users/**`, `/api/v1/payments/**`, `/api/v1/concerts`는 각각 `user-service`, `payment-service`, `reservation-service` 이름으로 로드밸런서 조회까지 갔다.
  - 인스턴스가 없어 500이 났다.
  - 정의하지 않은 경로는 404였다.
- 도메인 서비스는 DB 연결 설정 전이라 실제 기동과 Gateway 경유 호출은 확인하지 못했다.

## 판단 이력
- 2026-10-27 수용: AI 진단과 수정안을 받아들이고 공통 의존성 루트 집약을 추가 지시

## 발표·면접 포인트
- 멀티모듈에서 공통 모듈은 실행 jar가 아닌 라이브러리(`java-library`)로 두고, 공통 의존성은 루트에서 모듈 성격(전체·실행·서비스)별로 나눠 관리했다.
