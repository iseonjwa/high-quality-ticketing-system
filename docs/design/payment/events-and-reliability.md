# 이벤트와 신뢰성

결제 결과는 Outbox로 발행하고, 소비는 처리 이력과 상태 전이로 멱등하게 하며, 환불 요청은 PG를 부르기 전에 DB에 영속화한다. 끝내 확정하지 못한 건은 DB 상태로 남겨 재확인 스케줄러·환불 워커·운영자가 이어받고, 서비스 사이 어긋남은 대사가 찾는다. 토픽·봉투는 SA 10장을 따르고 여기에는 구현 규칙만 둔다. 지키는 조건은 [invariants.md](invariants.md)의 INV-2·INV-4·INV-5·INV-6이다.

## 1. 발행과 구독

| 토픽 | 이벤트 | 키 | 이 서비스의 역할 |
|---|---|---|---|
| `payment.result` | `PaymentApproved`·`PaymentDeclined`·`PaymentRefunded` | reservationId | 발행(Outbox). Reservation 그룹 `reservation.payment-result`의 처리 규칙은 [0027](../../decisions/0027-reservation-payment-event-contract.md) |
| `payment.command` | `RefundRequested`(MVP)·`BillingPaymentRequested`(M4) | reservationId | 구독, 그룹 `payment.command` |
| `payment.command.dlq` | 소비 실패 | 원본 키 | 구독, 그룹 `payment.command-dlq`(DLQ 처리기) |

페이로드 변경([SA 변경 요청](sa-change-requests.md) S-18, 0027): `PaymentApproved`·`PaymentRefunded`에 `currency`(`"KRW"`)를 더하고, `RefundRequested`에 `userId`·`amount`·`currency`를 더하며 `reason`에 `DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE`을 추가한다. SA 10-4는 필드 추가만 허용하므로 `version`은 그대로 1이다.

구현 위치(ISE-24): 일반 부품(Outbox 쓰기·릴레이·Kafka 전송, `processed_event` 처리 이력)은 `common`의 `com.iseonjwa.common.messaging.outbox`·`inbox` 패키지에 있고 서비스가 `CommonOutboxConfiguration`을 `@Import`한다([common-modules.md](../../guides/common-modules.md)). 결제 이벤트 페이로드와 발행기(`PaymentResultPublisher`)는 `Payment`에 의존하므로 `payment-service`에 둔다. 저장소는 JPA 엔티티 없이 `JdbcTemplate`으로 쓰며 JPA 트랜잭션의 같은 연결을 쓴다. ISE-24에는 임대·키별 순서·SENT 기록까지 넣었고, 지수 백오프·`FAILED`·정리·실행기 분리는 ISE-40이다. 발행 실패 시 지금은 임대(30초)가 끝나면 다시 시도한다. 한 번의 릴레이 실행은 전송 시한(12초) 안에 끝나고 마감을 넘긴 행은 임대를 바로 푼다.

## 2. 공통 규칙: 실제로 바뀐 전이만 후속 쓰기를 한다

조건부 UPDATE의 영향 행이 1일 때만 이력·Outbox·멱등 응답·감사 기록(APPLIED)을 같은 트랜잭션에 쓴다(INV-5, [0022](../../decisions/0022-outbox-retry-and-retention.md)).

| 경로 | 0행일 때 |
|---|---|
| 승인 API TX2 | 후속 쓰기 없음. 현재 상태를 다시 읽어 응답 |
| 재확인 스케줄러·환불 워커 | 후속 쓰기 없음. 건너뜀 |
| `payment.command` 소비자·DLQ 처리기 | 업무 쓰기 없음. `processed_event`·`dead_letter`만 기록 |
| Ops 명령 | `manual_action_log`에 REJECTED만 남기고 실패로 끝남 |

모든 경로에서 `payment_transition_conflict_total{operation}`을 올린다. 0행은 대개 다른 경로가 먼저 전이한 정상 경합이고, 지표가 갑자기 늘면 중복 처리 경로를 의심한다.

## 3. Outbox 릴레이 ([0022](../../decisions/0022-outbox-retry-and-retention.md))

1. 전용 실행기(7절)에서 500ms마다 `status='PENDING' AND next_attempt_at <= now()`이고 임대가 끝났으며 같은 `message_key`에 앞선 미발행 행이 없는 행을 `FOR UPDATE SKIP LOCKED`로 집어 `claimed_until = now() + 30초`로 갱신한다(트랜잭션은 이 갱신까지).
2. 트랜잭션 밖에서 `acks=all`로 전송하고 브로커 확인을 기다린다. 프로듀서는 `delivery.timeout.ms=10000`, `request.timeout.ms=5000`, `linger.ms=0`(값은 `[가정]`)이고, 전송 대기는 `delivery.timeout.ms` + 2초까지다. 임대(30초)는 전송 시한의 2배 이상이어야 한다. 짧으면 전송이 아직 재시도 중인데 다른 실행이 같은 행을 다시 보내 순서가 뒤섞인 중복이 생긴다.
3. 확인되면 `status='SENT'`, `sent_at`을 기록한다. 확인 전에 죽으면 임대가 끝난 뒤 다시 집힌다(중복 전송, 소비자가 멱등).
4. 일시 오류는 `attempts + 1`, `next_attempt_at = now() + min(500ms × 2^attempts, 60s)`에 Jitter를 적용한다. 횟수로 포기하지 않는다.
5. 영구 오류(직렬화 실패, 레코드 크기 초과, 잘못된 토픽)만 `FAILED` + `last_error`로 바꾸고 알림을 낸다.
6. `PENDING`·재시도 대기·`FAILED`는 자동 삭제하지 않는다.

`SENT`는 Kafka 발행 성공만 뜻한다. Reservation 소비 실패와 `payment.result` DLQ 복구는 Reservation이 관리한다(반효승 통보 항목). 발행은 됐는데 Reservation이 반영하지 못한 건은 대사(10절)가 찾는다.

## 4. 소비 `payment.command` ([0020](../../decisions/0020-payment-refund-dlq-manual-check.md))

소비자는 환불 요청을 검증하고 DB에 영속화만 한다. PG 환불은 부르지 않는다. PG 호출은 환불 워커(6절) 한 곳에서만 한다.

1. `RefundRequested`를 아래 표로 교차 검증한다.
2. 통과하면 한 트랜잭션에서 `processed_event(event_id, 'payment.command')`, 환불 `INSERT … SELECT … ON CONFLICT (payment_id) DO NOTHING`(`REQUESTED`, `next_check_at` = 현재, `source=EVENT`), 이력을 쓴다([data-model.md](data-model.md) 4절). 0행이고 환불 행이 이미 있으면 중복이라 `processed_event`만 남긴다.
3. 검증 실패와 역직렬화 실패는 영구 오류다. 재시도하지 않고 바로 DLQ로 보낸다(재시도 제외 예외로 등록).
4. 일시 오류(DB 연결, 잠금 시간 초과)는 1·2·4초 간격 3회 재시도 뒤 DLQ로 보낸다.
5. 오프셋은 처리 성공 뒤에 커밋한다(`ack-mode=record`). DLQ로 보내는 것이 실패하면 원 메시지의 오프셋을 올리지 않는다. 역직렬화 실패는 `ErrorHandlingDeserializer`로 DLQ에 보내 원문을 보존한다.

교차 검증(INV-3·INV-9):

| 항목 | 기준 |
|---|---|
| `paymentId` | 결제가 있다 |
| `reservationId`·`userId` | 결제의 `reservation_id`·`user_id`와 같다 |
| `amount`·`currency` | 결제의 `amount`·`currency`와 같다 |
| `reason` | `EXPIRED_AFTER_APPROVAL`·`CANCELED`·`DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE` 중 하나 |
| 결제 상태 | `APPROVED`면 새 환불. 환불 행이 이미 있으면 중복(정상). 그 밖(`REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`·`DECLINED`)은 영구 오류 |
| 봉투 `producer` | `reservation-service`. 위조를 막지는 못하지만 잘못 보낸 메시지를 거른다 |

Kafka에는 브로커 인증이 없다(0008 T5). 메시지 위조 경로를 줄이기 위해 Kafka-UI를 READONLY로 둔다(S-24, 0020).

## 5. DLQ 처리기 ([0020](../../decisions/0020-payment-refund-dlq-manual-check.md))

DLQ에 온 환불 요청도 자동 복구 경로로 되돌린다. 한 트랜잭션에서 `dead_letter`를 `INSERT … ON CONFLICT (event_key) DO NOTHING`으로 저장하고, 4절과 같은 검증을 다시 해 아래처럼 처리하고, `processed_event(event_id, 'payment.command-dlq')`를 쓴다.

| 메시지 | 처리 | `dead_letter` |
|---|---|---|
| 검증 통과, 환불 행 없음 | 환불 `REQUESTED` 생성(`source=DLQ`), 워커가 이어받음 | `RESOLVED(AUTO_PERSISTED)`, `refund_id` |
| 검증 통과, 환불 행 있음 | 변경 없음 | `RESOLVED(DUPLICATE)`, `refund_id` |
| 검증 실패, 역직렬화 실패 | 환불 행을 만들 근거가 없음 | `OPEN` + 즉시 알림 |

- 영속화가 실패하면(DB 장애) 처리 완료로 인정하지 않는다. 오프셋을 커밋하지 않고 지수 백오프(최대 60초)로 횟수 제한 없이 다시 처리한다.
- 메시지 내용 문제로는 예외를 던지지 않는다. 내용 문제는 `OPEN`으로 기록하고 커밋한다. 예외는 DB·인프라 오류뿐이라 메시지 하나가 파티션을 영구히 막지 않는다.
- `.dlq` 토픽 보관은 7일`[가정]`이다. `OPEN` 건의 해결은 Ops `dead-letter resolve`다([manual-recovery.md](manual-recovery.md) 3절).

## 6. 재확인 스케줄러와 환불 워커 (SA 10-5)

두 작업은 복구 실행기(7절)에서 돌고 PG 호출은 `pg-recovery` 그룹을 쓴다([pg-integration.md](pg-integration.md) 5절). 한 건의 처리 순서는 같다.

1. 후보를 `next_check_at <= now()` 순으로 `FOR UPDATE SKIP LOCKED`, 배치 20건`[가정]`으로 고른다.
2. `pg-recovery` 허가를 먼저 얻는다. 못 얻으면(CB OPEN·Bulkhead 초과) claim하지 않고 다음 주기로 넘긴다. 이때는 시도 횟수를 세지 않는다.
3. claim: 조건부 UPDATE로 `next_check_at = now() + 30초`(임대), 시도 횟수 + 1. 1행일 때만 계속한다.
4. 트랜잭션 밖에서 PG를 부르고, 결과를 조건부 UPDATE로 반영한다(2절).

결제 재확인(`REQUESTED`는 TX1이 `next_check_at`을 저장 + 10초로 채워 10초 뒤 대상이 된다):

| `findPayment` 결과 | 결제 |
|---|---|
| `Approved`, 금액·통화 일치 | `APPROVED` + Outbox `PaymentApproved` |
| `Approved`, 금액·통화 불일치 | `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, 즉시 알림 |
| `Declined` | `DECLINED` + Outbox `PaymentDeclined` |
| `NotFound` | `preventLateApproval`: `Prevented`면 `DECLINED(NOT_SENT)` + `PaymentDeclined`, `AlreadyApproved`면 `Approved`와 같게 처리, `Unknown`이면 다음 간격 |
| `Unknown` | `REQUESTED`면 `UNKNOWN`으로, 이미 `UNKNOWN`이면 `next_check_at`만 갱신 |

환불 워커(`REQUESTED`·`UNKNOWN` 환불):

| 단계 | 규칙 |
|---|---|
| 호출 선택 | `pg_requested_at`이 NULL이면 `refund`를 부른다(claim 때 `pg_requested_at`을 채운다). 값이 있으면 `findRefund`로 먼저 확인하고 `NotFound`일 때만 같은 `pg_refund_id`로 `refund`를 다시 부른다 |
| `Refunded`, 금액·통화 일치 | 환불 `REFUNDED`, 결제 `APPROVED`(금액 불일치 환불이면 `NEEDS_MANUAL_CHECK`) → `REFUNDED`, 이력 2건, Outbox `PaymentRefunded`. 결제 UPDATE가 0행이면 롤백 후 환불 `NEEDS_MANUAL_CHECK(STATE_CONFLICT)` |
| `Refunded`, 금액·통화 불일치 | 환불 `NEEDS_MANUAL_CHECK(REFUND_AMOUNT_MISMATCH)`, 즉시 알림 |
| `RefundRejected` | 환불 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`, 알림. `FAILED`는 쓰지 않는다 |
| `Unknown` | `REQUESTED`면 `UNKNOWN`으로, 이미 `UNKNOWN`이면 `next_check_at`만 갱신 |

간격은 SA 9-4대로 10초×6회 → 1분 → 5분 → 15분이다. 실제로 PG를 부른 9번째 호출도 확정하지 못하면 `NEEDS_MANUAL_CHECK`(`PG_UNRESOLVED`·`REFUND_UNRESOLVED`)다(약 21분). CB OPEN으로 부르지 못한 주기는 세지 않으므로 PG 전체 장애 중에는 횟수가 줄지 않고, 그동안의 체류는 최고령 지표와 알림이 본다([observability.md](observability.md)). 확정 때만 이벤트를 발행한다.

## 7. 실행기와 DB 연결

Spring `@Scheduled`의 기본 스케줄러는 스레드 1개라 PG 재조회가 느려지면 Outbox 릴레이가 같이 멈추고, 승인 결과가 예매에 늦게 닿아 선점이 만료될 수 있다. 작업 성격별로 실행기를 나눈다.

| 실행기 | 작업 | 스레드 | DB 연결 최대 |
|---|---|---|---|
| `outbox-relay` | Outbox 릴레이(500ms) | 1 | 1 |
| `recovery` | 결제 재확인, 환불 워커 | 2`[가정]` | 2 |
| `housekeeping` | 정리(10분`[가정]`), Gauge 갱신(15초), 대사(5분`[가정]`) | 1 | 1 |
| Kafka 리스너 | `payment.command`, `payment.command.dlq` | 각 1 | 2 |

- 각 작업은 끝날 때 `payment_job_last_success_timestamp{job}`을 갱신하고, 실패는 `payment_job_failures_total{job}`으로 센다. 정체 알림이 이 값을 본다.
- 배경 작업은 PG 호출 동안 DB 연결을 잡지 않는다. 동시에 잡을 수 있는 연결은 최대 6개다.
- 연결 풀은 SA 9-4의 서비스당 20으로 둔다. 현재 `config-repo/payment-service.yml`은 `maximum-pool-size: 5`라 배경 작업만으로 풀이 찰 수 있어 고친다(S-21). `connection-timeout`은 1초`[가정]`로 둔다. 기본값 30초면 요청 스레드가 연결을 기다리다 Gateway 3초를 넘기고, TX2가 늦어져 재확인 스케줄러와 겹친다.

## 8. 정리 작업 ([0022](../../decisions/0022-outbox-retry-and-retention.md))

| 대상 | 삭제 조건 | 기간 |
|---|---|---|
| `outbox` | `SENT`이고 `sent_at` 경과 | 7일 |
| `processed_event` | `processed_at` 경과 | 14일(Kafka·DLQ 보관 + 수동 재처리 기간보다 길게. 시작 때 설정값 비교) |
| `idempotency_record` | `expires_at` 경과, 연결 결제가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`가 아님 | 24시간 |

배치 1,000건 단위, `pg_try_advisory_lock`으로 중복 실행 방지, 실패는 다음 주기(10분`[가정]`)에 재시도한다. 그 밖의 결제 테이블은 지우지 않는다([data-model.md](data-model.md) 12절).

## 9. 오래된 이벤트 재전달 방어

`processed_event`가 지워진 뒤 재전달돼도 다음이 중복 결제·환불을 막는다(INV-1·INV-2).
- 조건부 UPDATE와 영향 행 확인(2절)
- `payment.reservation_id` 전체 UNIQUE([0026](../../decisions/0026-payment-one-per-reservation.md))
- `refund.payment_id` UNIQUE와 결제 상태 조건이 있는 `INSERT … SELECT … ON CONFLICT`
- 환불 재호출 전 `findRefund`, PG 멱등키(`pg_order_id`·`pg_refund_id`)
- Reservation의 paymentId 대조(0027)

## 10. 대사 ([0029](../../decisions/0029-payment-reservation-reconciliation.md))

`housekeeping` 실행기가 5분`[가정]`마다 결제 DB와 예매 내부 조회 API를 대조해 RC-1~RC-8 불일치를 `reconciliation_mismatch`에 기록하고 Gauge로 알린다. 데이터는 고치지 않는다. 허용 지연은 10분`[가정]`이다.
