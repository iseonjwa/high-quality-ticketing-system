---
title: "공통 모듈: SA 4개 분리 대신 common 하나로 두고 메시징만 나중에 분리하는 AI 추천을 수용"
date: 2026-10-27
updated: 2026-10-27
judge: kwonwoo
ai: Claude Code (SA 13장과 현재 구조 비교, 대안별 트레이드오프 정리)
verdict: 수용
area: 계층형 아키텍처
presentation: 후보
shared: 예
---

# 공통 모듈: SA 4개 분리 대신 common 하나로 두고 메시징만 나중에 분리하는 AI 추천을 수용

## 맥락
- 해결하려던 작업: Gateway를 WebFlux로 바꾸면서 공통 모듈 구조를 정해야 했다.
- AI 활용 방식: 판단은 세 번의 질문을 거쳐 이뤄졌다.
  1. SA 13장이 공통 코드를 왜 나누는지, 하나로 두는 것과 무엇이 다른지 물었다.
  2. "특정 도메인만 쓰는 것은 도메인 build.gradle에 두면 되지 않나"라고 반문했다.
  3. Kafka를 고려해 어느 쪽이 이점이 큰지 신입 수준으로 정리해 달라고 했다.

## AI 제안
- 처음 제안: SA대로 `common-response`·`common-web`·`common`으로 나눈다. 근거는 Gateway가 WebFlux라 MVC 의존을 빌드 구조로 격리해야 한다는 것이었다.
- 사용자 반문 후 수정: 외부 의존성은 각 도메인에 두는 것이 맞다고 인정했다. 공통 코드가 적으면 `common` 하나에 `compileOnly`와 스캔 규칙만으로도 Gateway를 격리할 수 있다.
- Kafka를 검토한 뒤의 최종 추천: 지금은 `common` 하나로 둔다. 메시징(Outbox·processed_event·DLQ)은 만들 때 `common-messaging`으로 분리한다. 메시징까지 `common`에 넣으면 패키지 스캔 때문에 Kafka가 없는 User 서비스가 깨진다.

## 판단
- 결과: 수용 ("최소한의 분리만 진행해줘")
- 판단자: kwonwoo
- 근거: 사용자가 반문에서 밝힌 관점이 근거다. "공통적으로 사용하는 것이 아닌 특정 도메인에서만 사용하는 경우라면 굳이 공통으로 모듈을 쪼개서 가져갈 필요가 없다."
- 검토한 대안: SA 13장 4개 분리, 메시징도 `common`에 넣기, 메시징을 서비스마다 구현

## 반영
- 결정 기록: [ADR 0013](../../decisions/0013-single-common-module.md) (제안, 팀 리뷰 필요)
- 코드
  - `common`: 응답 형식, 에러 코드, `BusinessException`, `GlobalExceptionHandler`, 격리 규칙 주석
  - 서비스 3개: `scanBasePackages`
  - Gateway: `spring-cloud-starter-gateway-server-webflux`로 전환하고 `common`을 의존

## 검증
- `./gradlew clean build --warning-mode all`이 성공했고 테스트 14개가 모두 통과했다.
  - `GlobalExceptionHandlerTest` 6개: 업무 예외, 검증 실패, 깨진 JSON, 404, 405, 500 형식
  - Gateway 3개: 리액티브 기동, `DispatcherServlet` 없음, 예외 처리기 빈 미등록
  - User 2개: 없는 경로가 공통 404 형식으로 나옴
- Eureka와 Gateway jar를 실제로 실행해 확인했다.
  - Gateway가 Netty로 기동하고 Eureka에 등록됐다.
  - 서비스가 없는 경로는 503, 정의하지 않은 경로는 404였다.

## 판단 이력
- 2026-10-27 수용: SA 4개 분리 대신 common 하나 + 메시징만 나중에 분리

## 발표·면접 포인트
- 공통 모듈을 나누는 기준은 그 코드를 쓰는 서비스 수가 아니라 끌고 오는 의존성의 무게다. 가벼운 코드는 하나로 두고 규칙과 테스트로 격리했다. Kafka·JPA 런타임이 필요한 메시징만 분리했다.
- AI의 첫 추천(SA대로 분리)을 그대로 받지 않았다. 반문으로 전제(의존성과 코드 배치의 구분)를 따져 더 단순한 구조로 바꿨다.
