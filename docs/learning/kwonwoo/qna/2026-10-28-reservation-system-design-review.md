---
title: 예매 시스템 설계 정리와 잠재적 허점·보완 사항
date: 2026-10-28
updated: 2026-10-28
asker: kwonwoo
level: 중급
tags: [reservation, seathold, concurrency, saga, kafka, idempotency]
---

# 예매 시스템 설계 정리와 잠재적 허점·보완 사항

## 질문
예매 시스템에 대한 내용을 정리해 달라. 시스템적인 허점은 없는지, 설계 단계에서 고려되지 못한 것이 있는지, 수정되어야 할 부분이 무엇인지 짚어 달라.

## 짧은 답
- 예매 서비스(`reservation-service`)는 공연·좌석 조회(`concert`), 대기열(`queue`), 좌석 선점(`seathold`), 예매·발권(`reservation`), 추첨(`lottery`)을 한 DB 트랜잭션 경계 안에 둔 모듈러 모놀리스다.
- 핵심 정합성은 `user_schedule_quota` 조건부 UPDATE(회차당 매수 제한) → `schedule_seat` 오름차순 잠금 조건부 UPDATE(1차 판정) → `reservation_seat` 부분 유니크 인덱스(최종 방어)의 3단계로 지킨다.
- 현재 설계에서 구현 전에 바로잡아야 할 주요 허점은 ① 동기형 선점 실패(409) 시 트랜잭션 롤백으로 `seat_hold_request` 거절 기록이 함께 사라지는 문제, ② Gateway가 `POST /api/v1/reservations`의 본문 안에 있는 `scheduleId`를 입장 토큰과 대조하기 어려운 문제, ③ 접수형 선점의 다중 구역 선택 시 파티션 키 모호성, ④ 타임아웃 스케줄러와 소비자의 경합 순서, ⑤ 결제 이벤트 수신 시 원장 대조·중복 전달 방어 누락(ADR 0027)이다.

## 자세히

### 1. 예매 시스템 전체 구조와 핵심 흐름
| 모듈 | 역할 | 핵심 저장소·장비 |
|---|---|---|
| `queue` | 진입 순번(`seq`) 발급, 일정 속도 입장(`seq <= admitted`), 서명 입장 토큰 발급 | Redis (`seq`, `waiting`, `admitted`, 제안: `lastTick`, `issued`) |
| `concert` | 공연·회차·좌석 등록 및 조회(오픈 시각 판정, `available` 여부 반환) | PostgreSQL (`concert`, `schedule`, `schedule_seat`), Redis 캐시(H2) |
| `seathold` | 동기형·접수형 좌석 일괄 선점, 회차당 매수 제한, 선점 멱등 처리 | PostgreSQL (`seat_hold_requests`, `user_schedule_quotas`), Kafka (`seat-hold.request`) |
| `reservation` | 예매 상태 머신, 만료 스케줄러, 결제 결과 반영(확정·발권·보상·자동 환불 요청), 취소 | PostgreSQL (`reservations`, `reservation_seats`, `tickets`, `outbox`), Kafka (`payment.result`, `payment.command`) |
| `lottery` | 추첨 응모 접수, 시드 기반 추첨, 자동 배정·빌링키 자동결제 요청, 예비자 승계 | PostgreSQL (`lottery`, `lottery_entry`), Kafka (`lottery.entry`) |

### 2. 설계상 허점과 미고려 엣지 케이스

#### (1) 동기형 선점 롤백 시 `seat_hold_request` 유실과 멱등성 허점
- **문제**: SA 12-3은 동기형도 `seat_hold_request`를 남겨 조회·멱등 경로를 하나로 통일하고 거절 사유(`REJECTED`)를 기록한다고 정했다. 그러나 SA 12-7의 동기형 쿼리는 한 트랜잭션 안에서 매수 초과·좌석 경합 시 전체 롤백한다. 롤백하면 `seat_hold_request` 행도 함께 사라져 `REJECTED` 기록이 남지 않는다. 또한 `idempotency_record`와 달리 `request_hash`가 없어 같은 멱등키에 다른 좌석 본문을 보냈을 때(`409 IDEMPOTENCY_KEY_REUSED`)를 판별하는 절차가 빠져 있다.
- **수정**: `seat_hold_request(REQUESTED)` 생성(또는 기존 행 조회 후 `userId`·`scheduleId`·`seatIds` 일치 검증)을 별도 트랜잭션으로 분리하거나, 선점 본 트랜잭션이 409로 롤백된 뒤 별도 트랜잭션에서 `seat_hold_request`를 `REJECTED`로 갱신해야 한다.

#### (2) Gateway의 입장 토큰 회차(`scheduleId`) 검증 위치
- **문제**: SA 7-3은 Gateway가 입장 토큰의 회차 ID와 요청 회차를 대조한다고 정의한다. 좌석 조회(`GET /api/v1/schedules/{scheduleId}/seats`)는 경로 변수에 회차가 있지만, 선점(`POST /api/v1/reservations`)은 JSON 본문(`{scheduleId, seatIds}`)에 회차가 있다. Gateway(WebFlux)에서 POST 본문을 열어 파싱하면 메모리·성능 비용과 구현 복잡도가 커진다.
- **수정**: Gateway는 입장 토큰의 서명·`typ`·`aud`·`exp`·`sub == X-User-Id`까지만 검증하고, 토큰의 회차 ID와 요청 `scheduleId`의 일치 여부는 Reservation 서비스(또는 경로/헤더에 회차 ID 명시)에서 검증하도록 역할을 나눈다.

#### (3) 접수형(Async) 선점의 구역(`section`) 결정과 다중 구역 선택
- **문제**: 접수형 토픽 `seat-hold.request`의 파티션 키는 `{scheduleId}:{section}`인데, 선점 요청 본문에는 `seatIds`만 있고 `section`이 없다. 또한 사용자가 한 번에 서로 다른 구역 좌석(예: A구역 1석 + B구역 1석)을 고르면 단일 파티션 키로 묶을 수 없어 구역별 순차 처리 전제가 깨진다.
- **수정**: 1회 선점 요청은 같은 구역 안에서만 선택 가능하게 제한하거나, 다중 구역을 허용한다면 파티션 키 규칙과 API 단계의 구역 조회 방법을 명시해야 한다. 아울러 오픈 전 호출 차단(`SALE_NOT_OPEN`, E4)과 좌석 수 검증(`1~4매`)은 접수형에서도 202 반환 전에 수행해야 한다.

#### (4) 접수형 30초 타임아웃 스케줄러와 소비자의 경합 순서
- **문제**: SA 12-3은 소비자가 선점(quota → 좌석 → 예매)을 수행한 뒤 `seat_hold_request`를 `WHERE status = 'REQUESTED'`로 갱신한다고 적었다. 만약 타임아웃 스케줄러가 먼저 `REJECTED(TIMEOUT)`으로 바꾼 직후 소비자가 돌면, 앞 단계에서 잡은 좌석·예매를 반드시 롤백해야 한다.
- **수정**: 소비자 트랜잭션 시작 시점에 `seat_hold_request` 상태를 먼저 잠그거나 확인해 이미 닫혔으면 즉시 건너뛰고, 마지막 조건부 갱신이 0행이면 트랜잭션 전체를 롤백하도록 순서를 고정한다.

#### (5) 해제(만료·거절·취소) 시 `user_schedule_quota` 이중 차감 방어
- **문제**: 잠금 순서(SA 8-4)가 `매수 한도 → 회차 좌석 → 예매`이므로, 만료 스케줄러와 `PaymentDeclined` 소비자가 동시에 같은 예매를 처리할 때 매수 한도를 먼저 깎고 마지막에 `reservation` 조건부 UPDATE가 0행이 될 수 있다.
- **수정**: `reservation`의 `WHERE status = 'HELD'` 조건부 UPDATE가 0행이면 앞선 `user_schedule_quota` 차감과 `schedule_seat` 해제를 반드시 롤백해야 하며, 감소 쿼리에 `AND held_count >= :n` 조건을 추가한다.

#### (6) 결제 결과 이벤트 처리 계약 보강 (ADR 0016·0027·0029)
- **문제**: SA 8-3 원안은 `EXPIRED`에 `PaymentApproved`가 올 때만 자동 환불을 명시했다.
  1. `CONFIRMED` 예매에 같은 `paymentId`의 `PaymentApproved`가 재전달될 때 환불로 빠지면 확정 티켓의 결제가 환불되는 사고가 난다(멱등 무시 필요).
  2. `RELEASED`·`CANCELED` 상태나 금액·사용자 불일치 승인에 대한 환불 처리 규칙이 SA 본문에 없다.
  3. 결제 토큰 만료(`hold_expires_at - 10초`)가 지난 뒤 남은 10초 동안 `GET /reservations/{id}`를 부르면 이미 만료된 결제 토큰이 내려간다.
- **수정**: ADR 0027의 상태별 표와 원장 대조(`userId`, `amount`, `paymentId`)를 소비자에 반영하고, `now >= hold_expires_at - 10초`인 `HELD` 예매 조회 시에는 결제 토큰을 발급하지 않는다.

#### (7) 선점 중(`HELD`) 자발적 해제 경로와 취소 기한 규칙
- **문제**: 사용자가 좌석을 잘못 잡아 다시 고르고 싶을 때 `HELD` 예매를 스스로 해제하는 API가 없다(`cancel`은 `CONFIRMED → CANCELED`만 정의). 회차당 4매 제한(`user_schedule_quota`) 때문에 5분 만료 전까지 새 좌석을 고르지 못한다. 또한 `RESERVATION_NOT_CANCELABLE`의 기한 기준(예: 공연 시작 N시간 전)이 스키마·규칙에 정의되지 않았다.
- **수정**: `cancel` API가 `HELD` 상태도 즉시 `RELEASED`(환불 없이 좌석·쿼터만 반환)할 수 있게 허용하거나 별도 해제 경로를 두고, 취소 마감 기준 시각을 명시한다.

#### (8) 추첨(Lottery) 좌석과 일반 예매 좌석의 격리
- **문제**: `schedule_seat`의 상태가 `AVAILABLE · HELD · SOLD` 세 가지뿐이라 추첨용으로 할당된 좌석이 일반 예매에서 보이는지, 예비자 승계 중 반환된 좌석이 일반 예매로 새어 나가지 않는지 구분할 컬럼이나 상태가 없다.
- **수정**: 추첨 시작 전(M4)에 `schedule_seat`의 추첨 할당 표시 또는 추첨 종료 후 일반 예매 오픈 순서를 강제하는 불변 조건을 확정해야 한다(U9).

### 3. 현재 코드·설정과 SA 문서의 불일치
| 항목 | SA MASTER v3.2 | 현재 저장소 코드·설정 | 조치 |
|---|---|---|---|
| 테이블 이름 | 단수 (`reservation`, `reservation_seat`, `seat_hold_request`, `user_schedule_quota`) | 복수 (`reservations`, `reservation_seats`, `seat_hold_requests`, `user_schedule_quotas`) | Flyway·쿼리 작성 전 복수형으로 통일 |
| DB 커넥션 풀 | 서비스당 20 (SA 9-4) | `config-repo/reservation-service.yml`에 `maximum-pool-size: 5` | 측정 왜곡 방지를 위해 20으로 수정 |
| 모듈 구조 | `reservation` 하나에 선점·예매 포함 (SA 13장) | `seathold` 모듈과 `shared/domain`(`Money`) 분리 (ADR 0030) | 반효승 협의 후 SA 7-1·13장 반영 |
| 대기열 안전장치 | 12-8에 🆕 제안으로만 표기 (ADR 0015) | 미구현 | `sub` 검증·`lastTick`·`issued` 팀 확정 후 구현 |

## 이 프로젝트에서는
- 엔티티: `reservation-service/src/main/java/com/iseonjwa/reservation/reservation/domain/entity/Reservation.java`, `ReservationSeat.java`, `ReservationStatusHistory.java`
- 선점 모듈: `reservation-service/src/main/java/com/iseonjwa/reservation/seathold/domain/entity/SeatHoldRequest.java`, `UserScheduleQuota.java`
- 관련 결정: `docs/decisions/0015-queue-admission-guards.md`, `0016-payment-declined-token-reuse.md`, `0027-reservation-payment-event-contract.md`, `0029-payment-reservation-reconciliation.md`, `0030-seathold-module-and-shared-money.md`

## 면접 포인트
- **왜 분산락(Redis) 대신 DB 조건부 UPDATE + 부분 유니크 인덱스를 썼는가?**: 최종 판매 기록은 DB에 남으므로 분산락을 써도 DB 제약이 어차피 필요하다. 락 만료나 Redis 지연이 선점 경로를 막지 않게 DB의 오름차순 행 잠금(`lock_timeout 500ms`)과 부분 유니크 인덱스로 정합성을 지켰다.
- **동시 요청에서 회차당 매수 제한(4매) 우회를 어떻게 막았는가?**: 예매 테이블만 세면 동시 트랜잭션이 서로의 미커밋 행을 보지 못해 한도를 넘긴다. `(user_id, schedule_id)`를 PK로 하는 `user_schedule_quotas` 행을 먼저 보장하고 `held_count + n <= max_per_user` 조건부 UPDATE로 같은 사용자의 요청을 한 행에서 직렬화했다.
- **결제 완료 이벤트가 중복 전달되거나 만료 뒤에 도착하면 어떻게 처리하는가?**: 예매 원장의 상태·`payment_id`·금액·사용자와 대조해, 이미 같은 `paymentId`로 확정된 예매는 멱등 무시하고, 만료(`EXPIRED`)·해제(`RELEASED`)된 예매나 다른 `paymentId`의 중복 승인은 확정하지 않고 자동 환불(`RefundRequested`)을 발행한다.

## 참고
- `docs/core/SA MASTER.md` (v3.2)
- `docs/sa-review/03-seat-hold-kafka-design.md`
