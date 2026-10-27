# 결제 데이터 모델

payment DB(PostgreSQL, 포트 5435)의 테이블과 제약이다. 컬럼 규칙은 SA 12-1(UTC, 금액 NUMERIC(15,0), 서비스 간 FK 없음)을 따르고, 아래는 SA 12-4·12-5에서 바뀌거나 새로 생기는 부분을 포함한 구현 기준이다. Flyway 마이그레이션 위치는 `payment-service/src/main/resources/db/migration/`이다(현재 비어 있음). 제약마다 지키는 불변 조건(INV-n, [invariants.md](invariants.md))을 함께 적는다.

## 1. 마이그레이션 순서

| 버전 | 내용 | 마일스톤 |
|---|---|---|
| `V1__payment_core.sql` | `payment`, `payment_status_history`(append-only 트리거), `idempotency_record`, `outbox`, `processed_event` | M2 |
| `V2__refund_and_recovery.sql` | `refund`, `dead_letter`, `manual_action_log`(append-only 트리거), `reconciliation_mismatch` | M3 |
| `V3__billing_key.sql` | `billing_key` | M4 |

마이그레이션 테스트는 아래 UNIQUE·CHECK·트리거가 실제로 생겼는지 카탈로그(`pg_indexes`, `pg_constraint`, `pg_trigger`)로 확인한다(INV-1, INV-7).

## 2. payment

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| payment_no | VARCHAR(20) | NOT NULL, UNIQUE | 고객용 번호 |
| reservation_id | BIGINT | NOT NULL, **UNIQUE** | Reservation 서비스 ID(FK 없음). 예매당 결제 하나([0025](../../decisions/0025-payment-one-per-reservation.md)) |
| user_id | BIGINT | NOT NULL | |
| amount | NUMERIC(15,0) | NOT NULL, CHECK (amount > 0) | 토큰 금액과 검증한 값 |
| currency | CHAR(3) | NOT NULL, DEFAULT 'KRW', CHECK (currency = 'KRW') | **신규.** 원화만(SA 4-5). PG 결과·이벤트와 대조 |
| method | VARCHAR(10) | NOT NULL, CHECK (CARD·BILLING) | 승인 API는 CARD만 받는다 |
| status | VARCHAR(20) | NOT NULL, CHECK (REQUESTED·APPROVED·DECLINED·UNKNOWN·REFUNDED·NEEDS_MANUAL_CHECK) | |
| pg_order_id | UUID | NOT NULL, UNIQUE | PG 주문 식별자. PG 호출 전에 저장 |
| pg_tx_id | VARCHAR(200) | UNIQUE(NULL 허용) | PG 발급 거래 식별자(`PgPaymentRef`). 한 PG 거래가 두 결제에 연결되지 않게 한다 |
| pg_approved_amount | NUMERIC(15,0) | | **신규.** PG가 응답한 승인 금액. 승인·금액 불일치 때 저장 |
| decline_code | VARCHAR(30) | | 응답의 `reasonCode`(우리 `DeclineReason`). `NOT_SENT`, `TOKEN_EXPIRED` 포함 |
| unknown_retry_count | SMALLINT | NOT NULL, DEFAULT 0 | 실제 PG 재조회 횟수 |
| next_check_at | TIMESTAMPTZ | | 다음 재조회 시각. TX1에서 저장 시각 + 10초로 채운다 |
| approved_at · refunded_at | TIMESTAMPTZ | | |
| created_at 등 | | | 감사 컬럼(`BaseEntity`) |

CHECK 제약:

| 이름 | 조건 | 막는 것 |
|---|---|---|
| `chk_payment_check_schedule` | `status NOT IN ('REQUESTED','UNKNOWN') OR next_check_at IS NOT NULL` | 재확인 스케줄러가 집지 못하는 미확정 결제(INV-4·INV-8) |
| `chk_payment_approved` | `status <> 'APPROVED' OR (pg_tx_id IS NOT NULL AND approved_at IS NOT NULL AND pg_approved_amount = amount)` | PG 거래 식별자 없는 승인, 금액이 다른 승인(INV-3) |
| `chk_payment_refunded` | `status <> 'REFUNDED' OR (pg_tx_id IS NOT NULL AND refunded_at IS NOT NULL)` | 근거 없는 환불 완료 |
| `chk_payment_declined` | `status <> 'DECLINED' OR decline_code IS NOT NULL` | 사유 없는 거절 |

인덱스:
- `UNIQUE(reservation_id)` — 예매당 결제 하나(INV-1). SA D3의 부분 유니크와 `INDEX(reservation_id, status)`를 대체한다. 예매별 기존 결제 조회도 이 인덱스를 쓴다.
- `UNIQUE(pg_order_id)`, `UNIQUE(pg_tx_id)`
- `INDEX(next_check_at) WHERE status IN ('REQUESTED','UNKNOWN')` — 재확인 스케줄러
- `INDEX(status, created_at) WHERE status IN ('REQUESTED','UNKNOWN','NEEDS_MANUAL_CHECK')` — 최고령 Gauge([0023](../../decisions/0023-payment-observability-metrics.md))

`pg_tx_id` 길이(SA 12-4는 VARCHAR(64))는 토스 `paymentKey`를 담도록 넓혔다. 실제 최대 길이는 M4에서 확인한다. 종결 상태로 갈 때 `next_check_at`은 NULL로 비운다.

## 3. payment_status_history (append-only)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| payment_id | BIGINT NOT NULL, FK | 환불 전이도 결제 ID로 묶는다(환불은 결제당 1건) |
| target_type | VARCHAR(10) NOT NULL, CHECK (PAYMENT·REFUND) | **신규.** 결제 전이인지 환불 전이인지 |
| from_status · to_status | VARCHAR(20) | to NOT NULL |
| reason | VARCHAR(50) | 사유 코드([domain-and-states.md](domain-and-states.md) 3절) |
| actor | VARCHAR(40) NOT NULL | **신규.** `SYSTEM` 또는 `OPS:<operator>` |
| created_at | TIMESTAMPTZ NOT NULL | |

- `INDEX(payment_id, id)`. 대사 RC-7이 대상별 마지막 이력을 읽는다.
- 환불 이력 위치(U22)는 M3 전 결정이다. 이 설계는 `target_type`을 둔 이 테이블을 기본안으로 쓴다(SA 12-4의 `(팀 확인)`).
- append-only: `BEFORE UPDATE OR DELETE` 행 트리거와 `BEFORE TRUNCATE` 문장 트리거가 예외를 던진다. 함수 하나(`reject_modification()`)를 `manual_action_log`와 함께 쓴다.

```sql
CREATE FUNCTION reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER payment_status_history_no_update BEFORE UPDATE OR DELETE ON payment_status_history
  FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER payment_status_history_no_truncate BEFORE TRUNCATE ON payment_status_history
  FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
```

트리거는 테이블 소유 계정이 끌 수 있다. 현재 compose는 DB 계정 하나(`DB_USER`)를 쓰므로 마이그레이션 계정과 앱 계정을 나누는 것은 후속 과제다([invariants.md](invariants.md) 3절 #4).

## 4. refund (M3)

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | |
| payment_id | BIGINT | NOT NULL, UNIQUE, FK | 결제 1건당 환불 1건, 전액 환불 |
| pg_refund_id | UUID | NOT NULL, UNIQUE | 생성 때 만든다. PG 환불 멱등키의 원천 |
| amount | NUMERIC(15,0) | NOT NULL, CHECK (amount > 0) | 결제의 `pg_approved_amount`를 `INSERT … SELECT`로 복사. 이벤트 값을 쓰지 않는다 |
| reason | VARCHAR(30) | NOT NULL, CHECK (EXPIRED_AFTER_APPROVAL·CANCELED·DUPLICATE_APPROVAL·NOT_CONFIRMABLE·PG_AMOUNT_MISMATCH) | 환불을 시작한 이유 |
| source | VARCHAR(10) | NOT NULL, CHECK (EVENT·DLQ·OPS) | **신규.** 생성 경로 |
| source_event_id | UUID | | **신규.** 생성한 `RefundRequested`의 eventId(Ops는 NULL) |
| status | VARCHAR(20) | NOT NULL, CHECK (REQUESTED·UNKNOWN·REFUNDED·NEEDS_MANUAL_CHECK·FAILED) | |
| attempt | SMALLINT | NOT NULL, DEFAULT 0 | 실제 PG 호출 횟수 |
| next_check_at | TIMESTAMPTZ | | 생성 때 현재 시각 |
| pg_requested_at | TIMESTAMPTZ | | **신규.** 첫 PG 환불 호출 시각. NULL이면 미호출, 값이 있으면 재호출 전에 조회부터 한다 |
| pg_refund_ref | VARCHAR(200) | | **신규.** PG가 준 환불 거래 식별자 |
| refunded_at | TIMESTAMPTZ | | |

- CHECK: `status NOT IN ('REQUESTED','UNKNOWN') OR next_check_at IS NOT NULL`, `status <> 'REFUNDED' OR refunded_at IS NOT NULL`.
- 인덱스: `INDEX(next_check_at) WHERE status IN ('REQUESTED','UNKNOWN')`, `INDEX(status, created_at) WHERE status IN ('REQUESTED','UNKNOWN','NEEDS_MANUAL_CHECK')`.
- 생성은 결제 상태를 조건으로 한 원자적 쓰기다(E13, INV-2·INV-3).

```sql
INSERT INTO refund (payment_id, pg_refund_id, amount, reason, source, source_event_id,
                    status, next_check_at, created_at, ...)
SELECT p.id, :pgRefundId, p.pg_approved_amount, :reason, :source, :eventId,
       'REQUESTED', now(), now(), ...
  FROM payment p
 WHERE p.id = :paymentId AND p.reservation_id = :reservationId AND p.status = 'APPROVED'
ON CONFLICT (payment_id) DO NOTHING;
-- 0행이면 다시 읽어 "이미 환불 행 있음"(중복, 정상)과 "결제 조건 불일치"(영구 오류)를 구분한다
```

Ops `payment resolve`가 시작하는 금액 불일치 환불만 `p.status = 'NEEDS_MANUAL_CHECK' AND p.pg_tx_id IS NOT NULL AND p.pg_approved_amount IS NOT NULL` 조건을 쓴다.

## 5. idempotency_record

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| user_id | BIGINT | PK(user_id, idem_key) | |
| idem_key | UUID | | |
| request_hash | CHAR(64) | NOT NULL | 본문 SHA-256 |
| resource_id | BIGINT | NOT NULL | 연결된 결제 ID([0016](../../decisions/0016-payment-idempotency-in-progress.md)) |
| link_type | VARCHAR(10) | NOT NULL, CHECK (ORIGIN·ALIAS) | **신규.** ORIGIN은 TX1에서 결제를 만든 키, ALIAS는 기존 결제에 연결된 새 키([0018](../../decisions/0018-payment-new-key-existing-payment.md)) |
| response_status | SMALLINT | | ORIGIN의 저장 응답(200·202). 처리 중과 ALIAS는 NULL |
| response_body | JSONB | | |
| expires_at | TIMESTAMPTZ | NOT NULL | 24시간 |
| created_at | TIMESTAMPTZ | NOT NULL | |

- `INDEX(resource_id)`: 결제별 연결 키 조회(운영 확인용).
- 삭제는 `expires_at` 경과 + 연결 결제가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`가 아닐 때만 한다([0021](../../decisions/0021-outbox-retry-and-retention.md)). ORIGIN·ALIAS 모두 같다.

## 6. outbox

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK | 릴레이 순서 |
| event_id | UUID | NOT NULL, UNIQUE | |
| topic · message_key · event_type | VARCHAR(100) | NOT NULL | |
| payload | JSONB | NOT NULL | `EventEnvelope` 전체 |
| status | VARCHAR(10) | NOT NULL, CHECK (PENDING·SENT·FAILED) | |
| attempts | SMALLINT | NOT NULL, DEFAULT 0 | 모니터링용, 재시도 한계 아님 |
| next_attempt_at | TIMESTAMPTZ | NOT NULL | **신규.** 백오프 다음 시각 |
| claimed_until | TIMESTAMPTZ | | **신규.** 릴레이 임대 만료(30초`[가정]`, 프로듀서 `delivery.timeout.ms`의 2배 이상) |
| last_error | VARCHAR(500) | | **신규** |
| created_at · sent_at | TIMESTAMPTZ | | |

인덱스: `INDEX(next_attempt_at, id) WHERE status = 'PENDING'`(릴레이 폴링), `INDEX(message_key, id) WHERE status = 'PENDING'`(같은 키 순서 확인), `INDEX(sent_at) WHERE status = 'SENT'`(정리), `INDEX(created_at)`(대사 RC-8의 최근 24시간 범위).

## 7. processed_event
`PK(event_id, consumer_group)`, `processed_at NOT NULL`. 보존 14일, 정리 인덱스 `INDEX(processed_at)`.

## 8. dead_letter (M3)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| source_topic · source_partition · source_offset | VARCHAR(100) · INT · BIGINT | 원본 위치 |
| message_key | VARCHAR(100) | |
| event_key | VARCHAR(200) NOT NULL, UNIQUE | 멱등 키. `eventId`가 있으면 그 값, 없으면(역직렬화 실패) `topic-partition-offset` |
| event_id · event_type | UUID · VARCHAR(100) | 해석했을 때만 |
| payload_raw | TEXT NOT NULL | 원문(JSON이 아닐 수 있다) |
| reason | VARCHAR(500) NOT NULL | 실패·검증 사유 |
| status | VARCHAR(10) NOT NULL, CHECK (OPEN·RESOLVED) | OPEN은 환불 의무를 확인하지 못한 미해결 상태(INV-4) |
| resolution | VARCHAR(20) | CHECK (AUTO_PERSISTED·DUPLICATE·REPLAYED·NO_OBLIGATION). RESOLVED일 때 필수 |
| refund_id | BIGINT | 연결된 환불 |
| created_at · resolved_at | TIMESTAMPTZ | |

- CHECK: `status <> 'RESOLVED' OR resolution IS NOT NULL`.
- `INDEX(status, created_at) WHERE status = 'OPEN'`(최고령 Gauge).

## 9. manual_action_log (M3, append-only)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| command | VARCHAR(40) NOT NULL | Ops 명령 이름([0027](../../decisions/0027-payment-ops-commands.md)) |
| target_type · target_id | VARCHAR(20) · BIGINT | PAYMENT·REFUND·OUTBOX·DEAD_LETTER |
| from_status · to_status | VARCHAR(20) | 적용했으면 전이, 거부면 NULL 가능 |
| result | VARCHAR(10) NOT NULL, CHECK (APPLIED·REJECTED) | 0행·근거 불일치·검증 실패는 REJECTED |
| plan_hash | CHAR(64) NOT NULL | dry-run 계획 해시 |
| pg_evidence | JSONB | 실행 시점의 PG 조회 결과 요약(상태, 금액, 통화, PG 식별자). 카드 정보·비밀값은 넣지 않는다 |
| external_ref | VARCHAR(200) | 환불 `FAILED`·콘솔 환불의 외부 조치 참조. 해당 명령에서 필수 |
| operator · approver | VARCHAR(40) | 실행자, 승인자(4-eyes 명령에서 필수, 실행자와 달라야 함) |
| reason | VARCHAR(500) NOT NULL | 메모 |
| performed_at | TIMESTAMPTZ NOT NULL | |

append-only 트리거는 3절과 같다. 삭제 기간은 없다.

## 10. reconciliation_mismatch (M3)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| rule | VARCHAR(10) NOT NULL | RC-1~RC-8([0028](../../decisions/0028-payment-reservation-reconciliation.md)) |
| reservation_id · payment_id | BIGINT | 대상(없으면 NULL) |
| detail | JSONB NOT NULL | 양쪽 상태·금액·사용자·시각 |
| status | VARCHAR(10) NOT NULL, CHECK (OPEN·RESOLVED) | 다음 대사에서 일치하면 RESOLVED. 데이터를 고치지 않는다 |
| first_detected_at · last_seen_at · resolved_at | TIMESTAMPTZ | |

- `UNIQUE(rule, reservation_id, payment_id) WHERE status = 'OPEN'`(NULL은 0으로 바꾼 식 인덱스): 같은 불일치는 행 하나로 이어 본다.
- `INDEX(status, rule) WHERE status = 'OPEN'`(Gauge).

## 11. billing_key (M4)
SA 12-4 그대로(`billing_key_enc` 암호화 저장, 사용자당 ACTIVE 1개). 암호화 키 관리는 M4에서 설계한다.

## 12. 보관
`payment`·`refund`·`payment_status_history`·`manual_action_log`·`dead_letter`·`reconciliation_mismatch`는 자동 삭제하지 않는다(결제 기록 5년 보관, SA 12-1). 정리 대상은 `outbox`·`processed_event`·`idempotency_record`뿐이다([events-and-reliability.md](events-and-reliability.md) 8절).
