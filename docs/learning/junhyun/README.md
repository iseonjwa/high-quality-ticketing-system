# junhyun 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 하루 정리

| 날짜 | 정리 |
|---|---|
| 2026-10-23 | [프로젝트 첫날: SA 1차 범위, 서비스 경계, 교차 검토, 문서화 체계](daily/2026-10-23.md) |

## 블로그 초안

| 날짜 | 글 | 상태 |
|---|---|---|
| 2026-10-23 | [TIL 15일차 - 티켓팅 서비스를 몇 개로 나눌까, 타임아웃은 실패가 아니다](blog/2026-10-23-til-15.html) | Tistory 임시저장 (카테고리: 백엔드 심화 TIL) |

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-10-29 | 결제 서비스 구현 완료는 M3까지인가 M4까지인가 | [qna/2026-10-29-payment-done-scope.md](qna/2026-10-29-payment-done-scope.md) |
| 2026-10-29 | Redis에서 두 곳을 지울 때 순서가 왜 중요한가 (대기열 만료 정리 사례) | [qna/2026-10-29-redis-write-order-recovery.md](qna/2026-10-29-redis-write-order-recovery.md) |
| 2026-10-29 | 폴링 간격에 ±20% jitter를 섞는다는 것은 무슨 뜻인가 | [qna/2026-10-29-polling-interval-jitter.md](qna/2026-10-29-polling-interval-jitter.md) |
| 2026-10-28 | 도메인은 예외 종류만 알고 상태 코드 매핑은 바깥 계층이 맡는 전략인가 | [qna/2026-10-28-domain-exception-http-mapping.md](qna/2026-10-28-domain-exception-http-mapping.md) |
| 2026-10-28 | Outbox·처리 이력 같은 공통 메시징 코드를 common에 둘지, 별도 모듈로 나눌지 | [qna/2026-10-28-common-messaging-placement.md](qna/2026-10-28-common-messaging-placement.md) |
| 2026-10-27 | common 모듈은 왜 여러 개로 나뉘었고 Docker에서도 컨테이너가 여러 개인가 | [qna/2026-10-27-common-module-split-and-containers.md](qna/2026-10-27-common-module-split-and-containers.md) |
| 2026-10-27 | Spring Boot 프로젝트의 개발·검증 스크립트는 왜 Python으로 작성했는가 | [qna/2026-10-27-spring-boot-python-scripts.md](qna/2026-10-27-spring-boot-python-scripts.md) |
| 2026-10-27 | 현재 구현에서 어떤 부분을 먼저 보강하면 좋은가 | [qna/2026-10-27-implementation-hardening-review.md](qna/2026-10-27-implementation-hardening-review.md) |
| 2026-10-27 | 가독성과 학습을 위해 공통 코드 작성 규칙을 어떻게 보완하면 좋은가 | [qna/2026-10-27-common-coding-rules.md](qna/2026-10-27-common-coding-rules.md) |
| 2026-10-27 | 서비스가 Access JWT를 직접 검증할 때 HS256과 RS256은 무엇이 다른가 | [qna/2026-10-27-jwt-hs256-vs-rs256.md](qna/2026-10-27-jwt-hs256-vs-rs256.md) |
| 2026-10-27 | MSA 사용자 JWT·Gateway 내부 토큰 비교와 이중 검증의 비용·보안 절충 | [qna/2026-10-27-msa-service-identity-verification.md](qna/2026-10-27-msa-service-identity-verification.md) |
| 2026-10-26 | fail open과 fail closed는 무엇이고 어떻게 고르는가 | [qna/2026-10-26-fail-open-fail-closed.md](qna/2026-10-26-fail-open-fail-closed.md) |
| 2026-10-26 | DLQ(Dead Letter Queue)는 무엇이고 언제 쓰는가 | [qna/2026-10-26-dlq-dead-letter-queue.md](qna/2026-10-26-dlq-dead-letter-queue.md) |
| 2026-10-26 | Redis Lua 스크립트는 무엇이고 왜 쓰는가 | [qna/2026-10-26-redis-lua-script.md](qna/2026-10-26-redis-lua-script.md) |
| 2026-10-23 | M1(Kafka 비동기 접수 설계)을 자세히 설명해 줘 | [sa-review/03-seat-hold-kafka-design.md](../../sa-review/03-seat-hold-kafka-design.md) |
| 2026-10-23 | 원래 PG사의 타임아웃은 어떻게 처리돼? | [sa-review/02-pg-timeout-handling.md](../../sa-review/02-pg-timeout-handling.md) |
| 2026-10-23 | 알림을 넣으면 새 서비스가 생기나, 범위는 어떻게 넓히나 | [qna/2026-10-23-notification-service-scope.md](qna/2026-10-23-notification-service-scope.md) |
| 2026-10-23 | 대기열에 RabbitMQ를 쓰면 안 되나, RabbitMQ는 필수인가 | [qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md](qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md) |
| 2026-10-23 | 발제 추천을 무시하면 서비스를 몇 개로? 좌석·예매를 한 서비스에 두는 이유, Queue 분리 이점 | [qna/2026-10-23-ticketing-service-boundaries.md](qna/2026-10-23-ticketing-service-boundaries.md) |

PG 타임아웃과 M1 노트는 이 체계를 만들기 전에 SA 검토 문서와 함께 `docs/sa-review/`에 저장했다. 팀 설계 검토와 이어지는 내용이라 위치는 옮기지 않고 링크만 둔다.
