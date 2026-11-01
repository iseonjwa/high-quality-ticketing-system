# kangmin 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-10-31 | 앞 PR을 스쿼시 머지한 뒤 그 위에 쌓은 브랜치는 왜 강제 푸시가 필요하고, 다른 방법은 무엇인가 | [qna/2026-10-31-stacked-branch-after-squash.md](qna/2026-10-31-stacked-branch-after-squash.md) |
| 2026-10-31 | 세션을 Redis에 공유하는 구조에서 Redis가 죽으면 전체가 멈추는데, 어떻게 막을 수 있나 | [qna/2026-10-31-session-redis-single-point-of-failure.md](qna/2026-10-31-session-redis-single-point-of-failure.md) |
| 2026-10-31 | 서비스 간 인증에서 mTLS와 서비스 메시는 무엇이고, 이 프로젝트에 왜 과한가 | [qna/2026-10-31-mtls-service-mesh.md](qna/2026-10-31-mtls-service-mesh.md) |
| 2026-10-30 | NOT_SENT는 언제 생기고, 추첨 자동결제의 NOT_SENT와 결과 미수신 만료는 어떤 선택지로 처리할 수 있나 | [qna/2026-10-30-lottery-payment-failure-options.md](qna/2026-10-30-lottery-payment-failure-options.md) |
| 2026-10-30 | 추첨을 별도 서비스로 분리하면 무엇이 문제가 되고, 지금 무엇을 해 두면 되는가 | [qna/2026-10-30-lottery-service-split-readiness.md](qna/2026-10-30-lottery-service-split-readiness.md) |
| 2026-10-30 | 추첨 자동결제 전용 Bulkhead의 동시 3, 전용 토픽, 백프레셔, 그룹별 Circuit Breaker는 무엇인가 | [qna/2026-10-30-lottery-billing-bulkhead.md](qna/2026-10-30-lottery-billing-bulkhead.md) |
| 2026-10-30 | 추첨을 한 트랜잭션으로 실행하면 무엇이 보장되고 무엇이 위험하며, 청크·중간 상태는 무엇이고 긴 트랜잭션은 어떻게 미리 막는가 | [qna/2026-10-30-lottery-draw-transaction-unit.md](qna/2026-10-30-lottery-draw-transaction-unit.md) |
| 2026-10-30 | 추첨 응모에서 1매·2매를 다른 서비스는 어떻게 처리하고, 1인 1매만 받으면 무엇이 달라지는가 | [qna/2026-10-30-lottery-entry-quantity.md](qna/2026-10-30-lottery-entry-quantity.md) |
| 2026-10-30 | 실제 운영 중인 추첨 서비스는 공정성을 어떻게 보여 주는가 | [qna/2026-10-30-lottery-fairness-in-real-services.md](qna/2026-10-30-lottery-fairness-in-real-services.md) |
| 2026-10-30 | 응모 취소 뒤 재응모를 새 행으로 만들면 한 사용자에게 행이 여러 개 생기는데, 무엇이 불리한가 | [qna/2026-10-30-lottery-cancel-multiple-rows.md](qna/2026-10-30-lottery-cancel-multiple-rows.md) |
| 2026-10-29 | 추첨석을 등급으로 구분하는 것과 추첨이 좌석을 먼저 선점해 두는 것은 무엇이 다른가 | [qna/2026-10-29-lottery-seat-allocation-vs-hold.md](qna/2026-10-29-lottery-seat-allocation-vs-hold.md) |
| 2026-10-29 | 추첨 응모 때 빌링키 유무는 누가 확인하는 것이 가장 좋은가 | [qna/2026-10-29-lottery-billing-key-check-location.md](qna/2026-10-29-lottery-billing-key-check-location.md) |
| 2026-10-29 | 스케줄러와 워커란 무엇이고, 추첨 설계에서 왜 5개나 필요한가 | [qna/2026-10-29-what-is-scheduler.md](qna/2026-10-29-what-is-scheduler.md) |
| 2026-10-29 | 입장 토큰을 Gateway, Reservation, 양쪽 중 어디서 검증할까 | [qna/2026-10-29-entry-token-verification-location.md](qna/2026-10-29-entry-token-verification-location.md) |
| 2026-10-29 | ISE-20 입장 토큰 계약은 무엇을 기준으로 범위를 정했고, 누가 함께 정해야 하나 | [qna/2026-10-29-ise20-entry-token-contract-scope.md](qna/2026-10-29-ise20-entry-token-contract-scope.md) |
| 2026-10-29 | 서버 문제로 실패한 추첨 자동결제를 끝까지 보장하면 어떤 비용이 드나 | [qna/2026-10-29-lottery-retry-guarantee-cost.md](qna/2026-10-29-lottery-retry-guarantee-cost.md) |
| 2026-10-29 | 추첨 응모 상태의 WON → WON은 어떤 상황인가 | [qna/2026-10-29-lottery-won-to-won-retry.md](qna/2026-10-29-lottery-won-to-won-retry.md) |
| 2026-10-29 | 대기열 폴링 간격의 jitter는 무엇이고, 서버와 프론트 중 어디서 넣어야 하나 | [qna/2026-10-29-queue-polling-jitter.md](qna/2026-10-29-queue-polling-jitter.md) |
| 2026-10-29 | 대기열 응답 상태에 READY를 더하는 제안(ISE-68)을 어떻게 볼까 | [qna/2026-10-29-queue-ready-status-review.md](qna/2026-10-29-queue-ready-status-review.md) |
| 2026-10-29 | 선점(주문 완료) 응답에 좌석 ID를 넣어야 하나 | [qna/2026-10-29-seat-hold-response-seat-ids.md](qna/2026-10-29-seat-hold-response-seat-ids.md) |
| 2026-10-29 | 우리 입장 토큰은 어떻게 구성되어 있나 | [qna/2026-10-29-entry-token-structure.md](qna/2026-10-29-entry-token-structure.md) |
| 2026-10-29 | 빌링키란 무엇이고 추첨 자동결제에 왜 쓰나 | [qna/2026-10-29-what-is-billing-key.md](qna/2026-10-29-what-is-billing-key.md) |
| 2026-10-29 | 추첨 자동결제를 위해 결제 담당과 무엇을 합의해야 하나 | [qna/2026-10-29-lottery-payment-agreement-items.md](qna/2026-10-29-lottery-payment-agreement-items.md) |
| 2026-10-28 | 회차당 매수를 4매에서 1인 1매로 바꾸면 무엇을 얻고 무엇을 치르나 | [qna/2026-10-28-one-ticket-per-user-cost.md](qna/2026-10-28-one-ticket-per-user-cost.md) |
| 2026-10-28 | 대기열 진입부터 입장 토큰 발급까지 요청·Redis·상태 전이를 단계별로 보면 | [qna/2026-10-28-queue-entry-to-admission-sequence.md](qna/2026-10-28-queue-entry-to-admission-sequence.md) |
| 2026-10-28 | 이미 입장 토큰을 받은 사용자가 대기열 진입(POST)을 다시 부르면 토큰을 누가 돌려줘야 하나 | [qna/2026-10-28-queue-entry-vs-status-responsibility.md](qna/2026-10-28-queue-entry-vs-status-responsibility.md) |
| 2026-10-28 | ISE-47 user-service 계층 정리는 무엇을 왜 바꿨고 어떻게 검증했나 | [qna/2026-10-28-ise47-user-layer-refactoring.md](qna/2026-10-28-ise47-user-layer-refactoring.md) |
| 2026-10-28 | 우리 4계층 분리는 DDD인가, 계층형 아키텍처와 DDD는 무엇이 다른가 | [qna/2026-10-28-layered-architecture-vs-ddd.md](qna/2026-10-28-layered-architecture-vs-ddd.md) |
| 2026-10-28 | AI 검증 #7의 "응답 레코드 분리 거부"는 무엇을 거부한 것인가 | [qna/2026-10-28-response-record-separation.md](qna/2026-10-28-response-record-separation.md) |
| 2026-10-27 | 추첨 배분 설계로 이 프로젝트에서 무엇을 얻어 갈 수 있나 | [qna/2026-10-27-lottery-design-learning-points.md](qna/2026-10-27-lottery-design-learning-points.md) |
| 2026-10-27 | 마일스톤을 더 잘게 나눠야 하나, 공통 모듈은 필요할 때 늘려도 되나 | [qna/2026-10-27-milestone-granularity-and-common-module-timing.md](qna/2026-10-27-milestone-granularity-and-common-module-timing.md) |
| 2026-10-27 | .env 파일과 initLocalEnv는 무엇이고, 통합 뒤 왜 다시 만들어야 하나 | [qna/2026-10-27-env-file-and-init-local-env.md](qna/2026-10-27-env-file-and-init-local-env.md) |
| 2026-10-27 | 공통 모듈 4개와 config-repo 파일 14개는 너무 잘게 나눈 것인가 | [qna/2026-10-27-common-module-config-repo-granularity.md](qna/2026-10-27-common-module-config-repo-granularity.md) |
| 2026-10-27 | Config 서버는 무슨 역할을 하고, 설정을 따로 분리하면 무엇을 얻고 잃나 | [qna/2026-10-27-config-server-role-tradeoffs.md](qna/2026-10-27-config-server-role-tradeoffs.md) |
| 2026-10-27 | 브랜치 이름에 슬래시(/)를 여러 번 넣는 것은 흔한가, 주의할 점은 무엇인가 | [qna/2026-10-27-branch-name-slashes.md](qna/2026-10-27-branch-name-slashes.md) |
| 2026-10-27 | DSL(도메인 특화 언어)은 무엇이고 Gradle의 Kotlin·Groovy DSL은 어떤 의미인가 | [qna/2026-10-27-what-is-dsl.md](qna/2026-10-27-what-is-dsl.md) |
| 2026-10-27 | Gradle 빌드 스크립트를 Kotlin DSL에서 Groovy로 바꾸면 무엇이 달라지나 | [qna/2026-10-27-gradle-groovy-vs-kotlin-dsl.md](qna/2026-10-27-gradle-groovy-vs-kotlin-dsl.md) |
| 2026-10-27 | Spring Initializr로 만들었으면 Gradle 연결과 초기 구성이 알아서 됐을까 | [qna/2026-10-27-spring-initializr-vs-manual-multimodule.md](qna/2026-10-27-spring-initializr-vs-manual-multimodule.md) |
| 2026-10-27 | IntelliJ에서 "Gradle 프로젝트로 불러온다"는 것은 무엇이고 왜 필요한가 | [qna/2026-10-27-intellij-gradle-project.md](qna/2026-10-27-intellij-gradle-project.md) |
| 2026-10-27 | Spring 설정 파일은 application.properties와 application.yml 중 무엇을 쓰고, 차이는 무엇인가 | [qna/2026-10-27-properties-vs-yaml.md](qna/2026-10-27-properties-vs-yaml.md) |
| 2026-10-27 | 이 프로젝트의 4계층(표현·응용·도메인·인프라)은 각각 무엇을 맡고 어떻게 의존하나 | [qna/2026-10-27-four-layer-architecture.md](qna/2026-10-27-four-layer-architecture.md) |
| 2026-10-27 | 초기 개발 환경(M1 뼈대)은 어떻게 구성했고 각 선택의 이유는 무엇인가 | [qna/2026-10-27-initial-environment-setup.md](qna/2026-10-27-initial-environment-setup.md) |
| 2026-10-26 | 좌석 선점 API의 v1·v2는 무엇이고, 응답을 가공 없이 바로 쓰게 하려면 어떻게 하나 | [qna/2026-10-26-seat-hold-v1-v2-response.md](qna/2026-10-26-seat-hold-v1-v2-response.md) |
