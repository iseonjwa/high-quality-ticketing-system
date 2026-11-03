---
title: 예매(reservation-service)는 outbox를 언제 어떻게 쓰는가
date: 2026-11-02
updated: 2026-11-02
asker: GGangMY
level: 중급
tags: [outbox, kafka, transaction, reservation, payment, lottery]
---

# 예매(reservation-service)는 outbox를 언제 어떻게 쓰는가

## 질문
예매에서 outbox를 언제, 어떻게 활용하는가. reservation outbox 테이블은 어디에 있는가.

## 답

### 1. outbox가 푸는 문제
업무 변경(DB)과 이벤트 발행(Kafka)은 서로 다른 시스템이라 하나의 트랜잭션으로 묶을 수 없다.

| 순서 | 실패 시나리오 |
|---|---|
| DB 커밋 → Kafka 발행 | 커밋 직후 서버가 죽으면 이벤트가 사라진다(예매는 바뀌었는데 결제는 모른다) |
| Kafka 발행 → DB 커밋 | 발행 뒤 커밋이 실패하면 없는 변경을 알린 셈이 된다 |

outbox는 이벤트를 **업무 변경과 같은 DB 트랜잭션에서 `outbox_events` 테이블에 행으로 저장**하고, 별도 릴레이가 그 행을 읽어 Kafka로 보낸다. 커밋되면 이벤트도 반드시 남고, 롤백되면 이벤트도 없다. 릴레이가 보낸 뒤 `SENT` 표시 전에 죽으면 다시 보내므로 **최소 한 번(at-least-once)** 전달이 되고, 중복은 받는 쪽이 멱등 처리(`processed_events`, 조건부 UPDATE)로 막는다.

감수한 점: 릴레이가 폴링(500ms)하므로 발행이 그만큼 늦다(SA 13장 "결제 → 예매 확정 반영 2초 이내"에 포함).

### 2. 예매 쪽에서 outbox를 쓰는 곳(SA 10-1 토픽 표, 6-7)
SA 원칙: "Outbox는 업무 변경과 이벤트가 함께 저장돼야 하는 곳에 쓴다."

| 언제 | 같은 트랜잭션의 업무 변경 | 발행 이벤트(토픽) | 단계·이슈 |
|---|---|---|---|
| 만료(EXPIRED)된 예매에 결제 승인(`PaymentApproved`)이 늦게 도착 | 예매는 그대로, 처리 이력 기록 | `RefundRequested`(`payment.command`) | MVP, E8. `payment.result` 소비자(ISE-73·74) |
| 확정(CONFIRMED) 예매 취소 | 좌석 반환, 티켓 VOID, 매수 −n, CANCELED | `RefundRequested`(`payment.command`) | 취소 API |
| 추첨 당첨자 배정 | 예매 HELD 생성 + 응모 연결 | `BillingPaymentRequested`(`payment.command`) | 추첨 ISE-92 |
| 접수형 선점 | `seat_hold_requests(REQUESTED)` 저장 | `SeatHoldRequested`(`seat-hold.request`) | 접수형(M4, ISE-16) |

반대 방향(결제 → 예매)인 `payment.result`는 payment-service의 outbox가 보내고, 예매는 소비만 한다.

### 3. 어떻게 쓰나(코드 구조)
- 라이브러리: `common/src/main/java/com/iseonjwa/common/messaging/outbox/`
  - `OutboxWriter`: 업무 트랜잭션 안에서 이벤트를 `outbox_events`에 INSERT
  - `OutboxRelay`·`OutboxRelayScheduler`: PENDING 행을 임대(`claimed_until`)로 잡아 Kafka로 보내고 SENT 표시. 같은 `message_key`(예: `reservationId`)의 앞선 미발행 행이 있으면 기다려 키 순서를 지킨다. 실패하면 `next_attempt_at`으로 백오프
  - `OutboxRepository`: 엔티티 없이 JDBC로 테이블 이름 `outbox_events`를 고정해서 쓴다
- 테이블 정의: SA 12-5 "공통 테이블(reservation·payment DB 각각)". 지금은 payment-service에만 있다(`payment-service/src/main/resources/db/migration/V1__payment_core.sql`, `V2__outbox_claim_index.sql`).
- 사용 예(추첨 배정, ISE-92 예정):

```java
@Transactional
public Optional<Long> assignNext() {
    ... systemHoldPort.hold(...)                    // 예매 HELD
    lotteryEntryRepository.linkReservation(...)     // 응모 연결
    outboxWriter.write("payment.command", String.valueOf(reservationId), "BillingPaymentRequested", 1, payload); // 같은 커밋
}
```
`OutboxWriter.write(topic, messageKey, eventType, version, payload)`는 10-2 봉투를 만들어 저장하고 `event_id`(UUID)를 돌려준다. 업무 트랜잭션 밖에서 부르면 원자성이 깨지므로 반드시 `@Transactional` 안에서 부른다.

### 4. reservation-service의 현재 상태
- `reservation-service/src/main/resources/db/migration/`에 `outbox_events`가 없다. 릴레이 설정(`config-repo/reservation-service.yml`)도 없다.
- 예매 흐름의 `RefundRequested`와 추첨의 `BillingPaymentRequested`가 같은 테이블·릴레이를 쓰므로, 먼저 만드는 쪽이 다른 담당에게 알려야 한다(`contract`).
- 서비스 하나에 테이블 하나다. 이벤트 종류마다 테이블을 나누지 않고 `topic`·`event_type` 컬럼으로 구분한다.

## 관련
- SA MASTER 10-1(토픽 표), 6-7(예매 기능), 12-5(`outbox_events`), 13장(지연 상한)
- `docs/design/payment/events-and-reliability.md`
- 보고서 7장(멱등 소비자): `2026-11-02-lottery-mvp-code-review-report.md`

## 추가 질문: ISE-92는 무엇이고 왜 reservation outbox가 필요한가

### ISE-92가 하는 일
배정 워커가 당첨자 예매(HELD, `source = LOTTERY`)를 만들 때 같은 트랜잭션에서 `BillingPaymentRequested`(`reservationId`, `userId`, `amount`, `lotteryEntryId`, 키 `reservationId`, 토픽 `payment.command`)를 outbox에 남긴다. payment-service(ISE-89)가 이 요청을 받아 빌링키(ISE-96)로 결제하고 `payment.result`로 결과를 돌려주면, 결과 처리기(ISE-90)가 응모를 PAID·FAILED로 바꾼다. 지금 코드에는 `LotteryAssignmentService.assignNext`의 `TODO(ISE-92)` 자리만 있어 예매는 생기지만 결제 요청이 나가지 않는다.

### outbox 없이 하면 생기는 일
| 방식 | 실패 | 결과 |
|---|---|---|
| 커밋 뒤 `KafkaTemplate.send` | 커밋 직후 서버 종료, Kafka 장애 | 예매는 HELD인데 요청이 없다. 30분 뒤 만료 → 당첨자가 우리 잘못으로 FAILED(D12 "우리 서버 책임") |
| 트랜잭션 안에서 `send` | 발행 뒤 롤백(선점 실패 등) | 없는 예매로 결제 요청. Payment는 금액 대조에 실패하거나 엉뚱한 결제를 시도 |
| 배정 워커가 별도로 "요청 안 나간 예매" 재발행 | 재발행 조건·중복 처리를 직접 구현 | outbox 릴레이가 이미 하는 일(임대, 재시도, 키 순서)을 다시 만든다 |

outbox면 예매 HELD와 요청 행이 한 커밋이라 둘 다 있거나 둘 다 없다. 릴레이가 결국 보내고, 늦게 가거나 두 번 가도 `reservationId`가 결제 멱등 키(`UNIQUE(reservation_id)`, `processed_events`)라 결제는 한 번이다. D12의 "결과를 못 받은 채 선점 만료면 선점을 1회 연장" 규칙도 "outbox가 요청을 결국 보낸다"는 전제 위에 있다.

### 왜 reservation DB에 있어야 하나
outbox의 핵심은 업무 변경과 같은 DB 트랜잭션이다. 예매·응모 행은 reservation DB에 있으므로 outbox 행도 reservation DB에 있어야 한다. payment DB의 outbox는 다른 DB라 같은 트랜잭션에 넣을 수 없다(서비스별 DB 원칙). 공통 라이브러리가 테이블 이름 `outbox_events`를 고정해 쓰므로 SA 12-5 구조 그대로 reservation 마이그레이션에 만든다.

## 추가 질문: outbox 테이블을 ISE-92 안에서 같이 처리하는 것과 별도 이슈(ISE-147)로 나누는 것의 비교
| 기준 | A. ISE-92 한 이슈·한 PR | B. ISE-147 먼저, ISE-92는 그 위에(선택) |
|---|---|---|
| 리뷰 | 공통 인프라(마이그레이션·설정)와 추첨 로직이 한 PR에 섞여 예매 흐름 담당이 볼 부분을 골라야 한다 | 예매 흐름 담당은 ISE-147만 보면 된다. PR이 작다 |
| 알림·추적 | 추첨 이슈 안에 묻혀 다른 담당이 테이블이 생긴 줄 모를 수 있다(contract 가이드가 막으려는 상황) | `contract` 라벨·멘션으로 알림이 가고, ISE-73이 이 이슈를 보고 시작할 수 있다 |
| 다른 담당 일정 | ISE-92(빌링키·결제 쪽 결정에 걸림)가 끝나야 테이블이 dev에 들어간다 | 테이블만 먼저 머지되면 ISE-73도 바로 쓸 수 있다 |
| 되돌리기 | ISE-92를 되돌리면 테이블까지 빠져 다른 사용처가 깨진다 | 인프라와 기능을 따로 되돌릴 수 있다 |
| 비용 | 이슈·PR·리뷰가 1번 | 이슈·PR 2개, 쌓은 PR이면 squash 머지 충돌(보고서 10-9) 가능 |
| 완료 판단 | 이슈 하나로 끝 | ISE-92가 ISE-147 머지를 기다린다 |

감수한 점: PR이 하나 늘고 순서 의존이 생긴다. 쌓은 PR 충돌을 피하려면 ISE-147을 먼저 머지한 뒤 dev에서 ISE-92 브랜치를 딴다.
바꿀 조건: 공유 접점이 아니라 내 서비스 안에서만 쓰는 테이블이면 한 이슈로 처리한다.
