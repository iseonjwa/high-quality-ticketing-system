# 결제 도메인과 상태 머신

결제는 `payment`와 `refund` 두 집합으로 나눈다. 결제 행은 예매당 하나인 논리적 결제이고([0026](../../decisions/0026-payment-one-per-reservation.md)), 환불 행은 결제당 하나다. 모든 상태 전이는 `WHERE status = 이전상태` 조건부 UPDATE로 하고(SA 8-4), 영향 행이 1일 때만 이력과 Outbox를 쓴다(4절). 지켜야 할 조건은 [invariants.md](invariants.md)에 있다.

## 1. 결제 상태

```
REQUESTED ──승인(금액·통화 일치)──▶ APPROVED ──환불 완료──▶ REFUNDED
    │  ├──거절──▶ DECLINED
    │  ├──PG 호출 직전 토큰 만료(미전송)──▶ DECLINED(TOKEN_EXPIRED)
    │  ├──승인 금액·통화 불일치──▶ NEEDS_MANUAL_CHECK
    │  └──PG 불확실──▶ UNKNOWN
REQUESTED(10초 경과) · UNKNOWN ──재조회──▶ APPROVED | DECLINED | NEEDS_MANUAL_CHECK
NEEDS_MANUAL_CHECK ──Ops 명령(PG 근거)──▶ APPROVED | DECLINED
NEEDS_MANUAL_CHECK ──Ops 명령으로 시작한 환불 완료──▶ REFUNDED
```

| 상태 | 뜻 | 종결 |
|---|---|---|
| `REQUESTED` | TX1에서 저장, PG 호출 전·중. `next_check_at` = 저장 시각 + 10초 | 아니오 |
| `UNKNOWN` | PG 응답 불확실 | 아니오 |
| `APPROVED` | PG 승인, 금액·통화가 결제와 같음 | 아니오(환불 가능) |
| `NEEDS_MANUAL_CHECK` | 자동으로 확정하지 못했거나 PG 승인이 결제와 맞지 않음. 운영자 확인 필요 | 아니오 |
| `DECLINED` | PG가 명확히 거절했거나 미전송이 확인됨(`NOT_SENT`, `TOKEN_EXPIRED`) | 예 |
| `REFUNDED` | 환불 완료 | 예 |

- 어느 상태든 같은 예매에 두 번째 결제 행은 없다(`UNIQUE(reservation_id)`, 0026). `DECLINED`·`REFUNDED` 예매에 다시 결제하려면 좌석을 다시 선점해 새 예매로 결제한다(SA 8-3에서 거절된 예매는 `RELEASED`).
- `NEEDS_MANUAL_CHECK`는 실패 확정이 아니다. SA 8-2는 종결로 적었다([SA 변경 요청](sa-change-requests.md) S-5).

### 결제 전이

| 전이 | 주체 | 함께 쓰는 것(한 트랜잭션) | 이벤트 |
|---|---|---|---|
| (생성) → `REQUESTED` | 승인 API TX1 | 멱등 기록(ORIGIN) | 없음 |
| `REQUESTED` → `APPROVED`·`DECLINED` | 승인 API TX2 | 이력, Outbox, 멱등 응답 | `PaymentApproved`·`PaymentDeclined` |
| `REQUESTED` → `DECLINED(TOKEN_EXPIRED)` | 승인 API(PG 호출 직전) | 이력, Outbox, 멱등 응답 | `PaymentDeclined` |
| `REQUESTED` → `UNKNOWN` | 승인 API TX2 | 이력, 멱등 응답(202), `next_check_at` | 없음 |
| `REQUESTED`·`UNKNOWN` → `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)` | TX2, 재확인 스케줄러 | 이력, `pg_tx_id`·`pg_approved_amount`·`approved_at` | 없음(즉시 알림) |
| `REQUESTED`·`UNKNOWN` → `APPROVED`·`DECLINED` | 재확인 스케줄러 | 이력, Outbox | `PaymentApproved`·`PaymentDeclined` |
| `REQUESTED`·`UNKNOWN` → `NEEDS_MANUAL_CHECK(PG_UNRESOLVED)` | 재확인 스케줄러(PG 호출 9회 소진) | 이력 | 없음(알림) |
| `NEEDS_MANUAL_CHECK` → `APPROVED`·`DECLINED` | Ops `payment resolve` | 이력(actor OPS), Outbox, `manual_action_log` | `PaymentApproved`·`PaymentDeclined` |
| `APPROVED` → `REFUNDED` | 환불 워커, Ops `refund resolve` | 환불 전이와 같은 트랜잭션 | `PaymentRefunded` |
| `NEEDS_MANUAL_CHECK` → `REFUNDED` | 환불 워커(Ops `payment resolve`가 시작한 `PG_AMOUNT_MISMATCH` 환불만) | 같음 | `PaymentRefunded` |

## 2. 환불 상태

```
(생성) ──▶ REQUESTED ──PG 환불 완료(금액 일치)──▶ REFUNDED
              │  ├──PG가 명확히 거절──▶ NEEDS_MANUAL_CHECK(REFUND_REJECTED)
              │  └──PG 불확실──▶ UNKNOWN ──재조회·재호출──▶ REFUNDED | NEEDS_MANUAL_CHECK
NEEDS_MANUAL_CHECK ──Ops refund retry──▶ REQUESTED
NEEDS_MANUAL_CHECK ──Ops refund resolve(PG 근거)──▶ REFUNDED | FAILED
```

| 상태 | 뜻 | 종결 |
|---|---|---|
| `REQUESTED` | 환불 의무 등록. 환불 워커가 PG 호출 전이거나 호출 중 | 아니오 |
| `UNKNOWN` | PG 응답 불확실 | 아니오 |
| `REFUNDED` | PG 환불 완료 확인 | 예 |
| `NEEDS_MANUAL_CHECK` | 자동 경로로 끝내지 못함(PG 거절, 재시도 소진, 금액 불일치, 상태 충돌). 운영자 확인 필요 | 아니오 |
| `FAILED` | PG로는 환불할 수 없음을 운영자가 확인하고 시스템 밖 조치로 넘김. Ops 명령으로만 들어가고 `manual_action_log`에 외부 조치 참조가 필수 | 예 |

- `FAILED`의 뜻은 위 정의 하나다. 자동 경로는 `FAILED`를 쓰지 않는다. SA 8-2·10-5·12-4는 `FAILED`를 "PG가 명확히 거절"로 쓰고 곧바로 `NEEDS_MANUAL_CHECK`로 보내는데, 이 설계는 PG 거절을 바로 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`로 기록한다(S-5).
- 환불이 `FAILED`여도 결제는 `APPROVED`로 남는다. 돈이 PG로 돌아가지 않았기 때문이다. 환불 행이 있으므로 대사 RC-2의 대상이 아니다([0029](../../decisions/0029-payment-reservation-reconciliation.md)).
- 환불은 결제가 `APPROVED`일 때만 시작한다. 예외는 Ops `payment resolve`가 PG 승인을 확인한 `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)` 결제다. 결제당 환불 1건(`UNIQUE(payment_id)`), 전액 환불만 한다(SA #16). 환불 금액은 PG 승인 금액(`pg_approved_amount`)이다.

### 환불 전이

| 전이 | 주체 | 함께 쓰는 것(한 트랜잭션) | 이벤트 |
|---|---|---|---|
| (생성) → `REQUESTED` | `payment.command` 소비자, DLQ 처리기, Ops `payment resolve`(금액 불일치), Ops `dead-letter resolve --replay` | 이력, `processed_event`(소비 경로), `dead_letter`(DLQ 경로), `manual_action_log`(Ops) | 없음 |
| `REQUESTED`·`UNKNOWN` → `REFUNDED` | 환불 워커 | 결제 → `REFUNDED`, 이력 2건, Outbox | `PaymentRefunded` |
| `REQUESTED` → `UNKNOWN` | 환불 워커 | 이력, `next_check_at` | 없음 |
| `REQUESTED`·`UNKNOWN` → `NEEDS_MANUAL_CHECK` | 환불 워커 | 이력(reason) | 없음(알림) |
| `NEEDS_MANUAL_CHECK` → `REQUESTED` | Ops `refund retry` | 이력(actor OPS), `manual_action_log`, `attempt` 0, `next_check_at` 현재 | 없음 |
| `NEEDS_MANUAL_CHECK` → `REFUNDED` | Ops `refund resolve`(PG 환불 완료 확인) | 결제 → `REFUNDED`, 이력, Outbox, `manual_action_log` | `PaymentRefunded` |
| `NEEDS_MANUAL_CHECK` → `FAILED` | Ops `refund resolve --close-failed`(PG 거절 확인 + 외부 조치 참조) | 이력, `manual_action_log` | 없음 |

## 3. 사유 코드

| 대상 | 저장 위치 | 값 |
|---|---|---|
| 결제 거절 | `payment.decline_code` | 우리 `DeclineReason`(PG 사유 매핑, 못 하면 `OTHER`), `NOT_SENT`(망취소 뒤 미전송), `TOKEN_EXPIRED`(PG 호출 전 만료) |
| 결제 확인 필요 | 이력 `reason` | `PG_UNRESOLVED`(재조회 소진), `PG_AMOUNT_MISMATCH` |
| 환불을 시작한 이유 | `refund.reason` | `EXPIRED_AFTER_APPROVAL`, `CANCELED`, `DUPLICATE_APPROVAL`, `NOT_CONFIRMABLE`([0027](../../decisions/0027-reservation-payment-event-contract.md)), `PG_AMOUNT_MISMATCH`(Ops) |
| 환불 확인 필요 | 이력 `reason` | `REFUND_REJECTED`, `REFUND_UNRESOLVED`, `REFUND_AMOUNT_MISMATCH`, `STATE_CONFLICT` |

## 4. 전이 공통 규칙

1. 조건부 UPDATE의 영향 행이 1일 때만 이력, Outbox, 멱등 응답, `manual_action_log`(APPLIED)를 쓴다(INV-5). 0행이면 같은 트랜잭션에서 후속 쓰기를 하지 않는다. 승인 API는 현재 상태를 다시 읽어 응답하고, 스케줄러·워커·소비자는 건너뛰고, Ops 명령은 `manual_action_log`에 REJECTED로 남기고 끝낸다. 모든 경로에서 `payment_transition_conflict_total`을 올린다.
2. 상태는 엔티티 변경 감지(dirty checking)로 바꾸지 않는다. 저장소의 조건부 UPDATE 메서드로만 바꾸고 영향 행 수를 반환받는다.
3. 이력에는 `target_type`(PAYMENT·REFUND), `reason`, `actor`(SYSTEM 또는 `OPS:<operator>`)를 남긴다.
4. 결제와 환불이 함께 바뀌는 전이(환불 완료)는 두 UPDATE가 모두 1행일 때만 커밋한다. 하나라도 0행이면 롤백하고, 새 트랜잭션에서 환불을 `NEEDS_MANUAL_CHECK(STATE_CONFLICT)`로 바꾸고 알린다.
5. 운영자는 목표 상태를 직접 고르지 않는다. Ops 명령이 실행 시점의 PG 조회 결과로 목표 상태를 정한다([0028](../../decisions/0028-payment-ops-commands.md)).

## 5. 같은 예매의 중복·재사용 방어

| 상황 | 방어 | 근거 |
|---|---|---|
| 같은 예매에 두 번째 결제 행 | `UNIQUE(reservation_id)` 전체. TX1 위반은 재조회 응답(500 아님) | 0026 |
| `DECLINED`·`REFUNDED` 예매의 토큰 재사용 | 조회 단계에서 409 `PAYMENT_ALREADY_CLOSED`. 조회와 쓰기 사이 경합은 UNIQUE가 막는다 | [0016](../../decisions/0016-payment-declined-token-reuse.md), 0026 |
| 같은 키·새 키 재요청 | 기존 결제로 응답, 새 키는 ALIAS로 기록, PG 호출 없음 | [0017](../../decisions/0017-payment-idempotency-in-progress.md), [0019](../../decisions/0019-payment-new-key-existing-payment.md) |
| 위 방어를 통과한 승인이 확정할 수 없는 예매에 도착 | Reservation 상태별 규칙. 같은 paymentId로 이미 확정된 예매는 멱등 처리 | 0027 |

## 6. 계층 구조

SA 13장의 4계층을 따른다. 패키지는 `com.iseonjwa.payment.payment`(결제), `com.iseonjwa.payment.refund`(환불), `com.iseonjwa.payment.reconciliation`(대사), 공통 `global`로 나눈다.

| 계층 | 결제 서비스에서 담는 것 |
|---|---|
| presentation | `PaymentController`(승인·조회), 요청·응답 DTO, 입력 검증, Ops 명령 진입점(`ops` 프로필 전용) |
| application | 승인·조회·환불·Ops·대사 유스케이스, 트랜잭션 경계(TX1·TX2), **`PgClient` 포트**, 결제 토큰 검증 포트, 예매 조회 포트 |
| domain | `Payment`·`Refund` 엔티티와 상태 전이, `DeclineReason`, 저장소 인터페이스 |
| infrastructure | JPA 저장소, `MockPgAdapter`·`TossPgAdapter`, Resilience4j 래퍼, Outbox 릴레이, Kafka 소비자(`payment.command`, DLQ), 재확인 스케줄러·환불 워커·정리·대사 작업, 예매 내부 API 클라이언트 |

- Kafka 코드와 PG·예매 API 클라이언트 구현은 infrastructure에만 둔다. domain·application에는 포트(인터페이스)만 있다.
- 엔티티를 응답으로 바로 내보내지 않는다(DTO 분리).
- Ops 명령은 HTTP 컨트롤러가 아니라 일회성 실행의 명령 진입점이고, 승인 API와 같은 application 유스케이스(전이·이력·Outbox)를 부른다([0028](../../decisions/0028-payment-ops-commands.md)).
- 재사용: `ErrorCode`·`BusinessException`·`SuccessResponse`(`common.response`), `GatewayIdentityFilter`·`@RequireRole`(`common.web`), `BaseEntity`(`common.persistence`, 상태 이력·outbox 같은 기록 테이블은 상속하지 않음), `EventEnvelope`(`common.event`), `TokenDecoders.signingKey`(`common.auth`, 결제 토큰 키 바이트, [0025](../../decisions/0025-payment-token-secret-injection.md)).
- 새로 만드는 공통 코드: Outbox 릴레이, `processed_event`, 재시도·DLQ 설정(SA 13장이 결제 담당에게 맡김). 서비스 코드를 `common`에 의존시키지 않는다는 규칙과의 경계는 구현 때 확인한다.
