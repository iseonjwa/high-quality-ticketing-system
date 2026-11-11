---
title: 접수형 선점에 Kafka를 도입할 때 코드를 infrastructure에 넣으면 되는가
date: 2026-11-03
updated: 2026-11-11
asker: kwonwoo
level: 중급
tags: [kafka, outbox, layered-architecture, seathold, msa]
---

# 접수형 선점에 Kafka를 도입할 때 코드를 infrastructure에 넣으면 되는가

## 질문
카프카 도입 시점(M4 접수형 선점, `ISE-15`~`ISE-18`)이 다가오는데 infrastructure에 넣으면 되는가. 문서와 함께 검토해 달라.

## 짧은 답
> 정정(2026-11-11): 같은 날 팀 회의에서 채택한 [ADR 0043](../../../decisions/0043-inbound-adapters-in-presentation.md)으로 Kafka 소비자·스케줄러는 `presentation/messaging`·`presentation/scheduling`에 둔다. 아래의 "소비자는 infrastructure" 부분은 이 ADR로 대체됐다. 생산자와 Outbox 릴레이는 그대로 infrastructure다.

- Kafka 소비자·발행 코드는 `seathold/infrastructure/messaging/`에 둔다. 팀이 확정한 4계층 규칙(결정 기록 #22, SA 13장)이 "Kafka 발행·구독은 인프라 계층, application·domain에는 Kafka 코드를 두지 않는다"이고, `seathold/infrastructure/package-info.java`도 접수형 소비자를 이 계층에 둔다고 적어 두었다.
- 다만 infrastructure에 넣는 것만으로 끝나지 않는다. 유스케이스(접수 저장, 소비 시 선점)는 application에, 접수 요청 상태 전이는 domain에 둔다.
- Kafka 브로커(docker-compose)와 `spring-boot-starter-kafka` 의존은 이미 있다. reservation-service에 아직 없는 것은 Outbox 설정 import, `outbox_events`·`processed_events` 테이블, `seat-hold.request` 토픽과 DLQ 설정이다.
- SA 13장 트리는 `SeatHoldConsumer`를 `reservation/infrastructure/messaging`에 그려 두었지만, ADR 0034가 선점 소비자를 `seathold` 모듈로 옮겼으므로 `seathold` 쪽이 맞다.

## 자세히

### 계층별 배치
| 계층 | 둘 것 | 근거 |
|---|---|---|
| presentation | `POST /api/v1/schedules/{scheduleId}/reservations`가 `seat-hold.mode=async`이면 202와 `Location` 반환, `GET /api/v1/reservations/requests/{requestId}` | SA 11장, ADR 0032 |
| application | 접수 서비스(`seat_hold_requests(REQUESTED)` 저장 + `OutboxWriter.write` 한 트랜잭션), 소비 처리 서비스(매수 한도 → 좌석 조건부 UPDATE → 예매 저장 → 요청 `HELD`/`REJECTED`), 30초 타임아웃 처리 | SA 10-1 D4, 12-3 |
| domain | `SeatHoldRequest` 상태 전이(REQUESTED → HELD/REJECTED), 저장소 인터페이스 | SA 8-2 |
| infrastructure | `messaging/SeatHoldConsumer`(파싱 후 application 호출), 리스너 컨테이너·에러 핸들러·DLQ 설정, 토픽 빈, `scheduling/`의 타임아웃 스케줄러 | SA 13장, 결제의 `refund/infrastructure/messaging` |

### application이 `OutboxWriter`를 불러도 되는가
`OutboxWriter`는 Kafka를 직접 부르지 않고 같은 트랜잭션으로 `outbox_events` 행을 쓰는 JDBC 코드다. 실제 발행은 `OutboxRelay`가 한다. 결제 서비스도 응용 계층의 발행기가 `OutboxWriter`를 호출한다. 엄격하게 떼려면 application에 `SeatHoldRequestPublisher` 같은 인터페이스를 두고 infrastructure에서 `OutboxWriter`로 구현하면 된다. 어느 쪽이든 `KafkaTemplate`이 application에 들어오지 않으면 규칙은 지킨다.

### 소비자를 얇게 두는 이유
소비자에 업무 로직을 넣으면 Kafka 없이 테스트할 수 없고, 동기형과 접수형이 같은 선점 규칙(D1·D2)을 두 벌로 갖게 된다. 소비자는 메시지 파싱, `ProcessedEventService.processOnce`로 중복 차단, application 호출만 한다. 그러면 동기형 `SyncSeatHoldService`와 접수형이 같은 선점 코드를 공유하고, 재측정에서 차이는 접수 방식에서만 나온다.

### 도입 전 확인할 것
1. **reservation-service 공유 설정**: `CommonOutboxConfiguration`을 `global/ServiceConfiguration`에 import하면 같은 서비스의 결제 결과 소비(reservation 모듈), 응모(lottery 모듈)에도 영향이 간다. 공유 설정이므로 reservation-service 다른 담당과 먼저 맞춘다.
2. **Flyway**: `outbox_events`·`processed_events` 표준 DDL을 reservation-service 마이그레이션으로 추가한다(`docs/guides/common-modules.md`). 다른 담당이 같은 테이블을 먼저 만들 수 있으니 누가 만들지 정한다.
3. **파티션 키**: `{scheduleId}:{section}`(SA 10-1, AI 검증 #5). 같은 구역 요청을 한 파티션에서 순서대로 처리해 락 경합을 줄이는 목적이다. 정합성은 여전히 조건부 UPDATE가 지킨다.
4. **실패 처리**: 재시도 3회 뒤 DLQ, DLQ 처리기가 `REJECTED(PROCESSING_FAILED)`로 닫는다. 30초 넘게 남은 REQUESTED는 `REJECTED(TIMEOUT)`, 늦게 온 메시지는 조건부 갱신 0행으로 건너뛴다(SA 10-5, E15).
5. **측정 비교**: SA 10-1은 동기형, 직접 발행, outbox 세 경우를 비교하라고 한다. outbox 폴링(500ms)이 접수 → 처리 시간에 더해지기 때문이다. 직접 발행 경로도 측정용으로 둘지 `ISE-18` 전에 정한다.
6. **서비스 간 접점**: `seat-hold.request`는 Reservation이 발행하고 Reservation이 소비하므로 다른 서비스 계약은 아니다. 다만 공통 `messaging` 코드를 고쳐야 하면 결제 담당과 `contract` 이슈로 맞춘다.

## 이 프로젝트에서는
- 이미 있는 것: `compose.yml`의 `kafka`(apache/kafka 4.1.1, KRaft 단일 노드)와 `KAFKA_BOOTSTRAP_SERVERS`, `reservation-service/build.gradle`의 `spring-boot-starter-kafka`, `ServiceConfiguration`의 `CommonMessagingConfiguration` import, `seat_hold_requests` 테이블(`V20261030_3__seat_hold_tables.sql`).
- 참고 구현: `payment-service/.../refund/infrastructure/messaging/`(`PaymentCommandListener`, `PaymentCommandKafkaConfiguration`, `PaymentCommandErrorHandlers`)가 리스너·에러 핸들러·메시지 파서를 infrastructure에 두고 application 서비스를 호출하는 같은 구조다.
- 스케줄링: `SeatHoldSchedulingConfiguration`의 `@EnableScheduling`은 Outbox 릴레이를 켜도 중복 등록되지 않는다.

## 면접 포인트
- Kafka 코드를 왜 인프라 계층에 두었나: 선점 규칙을 메시징 기술과 떼어 동기형·접수형이 같은 코드를 쓰게 하고, Kafka 없이 단위 테스트하기 위해서다.
- 저장과 발행을 어떻게 원자적으로 했나: 접수 요청 행과 outbox 행을 한 트랜잭션으로 쓰고 릴레이가 발행한다. 대가는 폴링 지연이며, 그래서 직접 발행과 비교 측정한다.
- 파티션 순서로 정합성을 지키나: 아니다. 순서는 락 경합을 줄이는 용도이고 중복 판매 방지는 DB 조건부 UPDATE가 맡는다.

## 참고
- `docs/core/SA MASTER.md` 10-1(토픽 표), 10-5, 12-3, 13장
- `docs/decisions/0034-seathold-unidirectional-module-boundary.md`, `0035-defer-seathold-concurrency-and-reconciliation-to-m4.md`, `0032-seat-hold-result-and-idempotency-contract.md`
- `docs/ai-judgments/kangmin/2026-10-26-layered-architecture-four-layers.md`
- `docs/guides/common-modules.md` Outbox와 처리 이력
- `docs/design/reservation/mvp-and-troubleshooting-roadmap.md` 고도화 2단계
