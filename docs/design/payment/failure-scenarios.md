# 결제 실패 시나리오와 테스트

SA 2-5의 결제 관련 시나리오(E2·E6·E8~E10·E12~E14), 상세 설계에서 찾은 시나리오(N1~N32), 장애 유형별 통합 테스트(FT-1~FT-5)를 한곳에 모은다. 테스트 계층은 `U`(단위), `I`(통합, Testcontainers·Mock PG), `S`(시스템, 전체 compose)이고 기준은 [테스트 가이드](../../guides/testing.md)를 따른다. 각 시나리오가 지키는 조건은 [invariants.md](invariants.md)의 INV-n이다. 이 문서는 설계 기대값이며 아직 실행하지 않았다.

## 1. SA 시나리오

| # | 상황 | 기대 결과 | INV | 근거 | 테스트 |
|---|---|---|---|---|---|
| E2 | 결제 버튼 연타 | 결제 1건. 같은 키는 저장된 응답, 새 키는 기존 결제로 응답(ALIAS) | 1 | 0017, 0019, 0026 | I |
| E6 | 결제 거절 | `DECLINED` 200, Reservation이 예매 `RELEASED`와 좌석 반환 | 5 | api.md, 0027 | I, S |
| E8 | 선점 만료 뒤 승인 도착 | 예매는 그대로 두고 `RefundRequested(EXPIRED_AFTER_APPROVAL)`, 환불 후 `PaymentRefunded` | 4 | 0020, 0027 | S |
| E9 | PG 응답 없음 | 결제 `UNKNOWN` 202, 재조회로 확정한 뒤에만 이벤트 발행 | 8 | 0018, 0023 | I |
| E10 | PG 장애 지속 | `pg-approve` CB OPEN, 신규 승인 503 `PG_UNAVAILABLE`, 행·멱등 기록 없음 | 1 | 0018 | I |
| E12 | PG 호출 중 서버 중단 | 10초 넘은 `REQUESTED`를 스케줄러가 재조회해 확정 | 4, 8 | events-and-reliability.md | I |
| E13 | 같은 결제에 환불 요청 중복 | PG 환불 1회, 환불 행 1건 | 2 | 0020, data-model.md | I |
| E14 | 입장 토큰으로 결제 API 호출 | 403 `PAYMENT_TOKEN_INVALID`(`typ`·`aud` 불일치) | 9 | 0025 | U, I |

## 2. 상세 설계 시나리오

| # | 상황 | 기대 결과 | INV | 근거 | 테스트 |
|---|---|---|---|---|---|
| N1 | `DECLINED` 뒤 같은 토큰으로 재승인 | 409 `PAYMENT_ALREADY_CLOSED`, PG 호출 없음 | 1 | 0016, 0026 | I |
| N2 | 승인이 `RELEASED` 예매에 도착 | 확정하지 않음, `RefundRequested(NOT_CONFIRMABLE)`, Reservation 즉시 알림 | 4 | 0027 | S(반효승) |
| N3 | PG 호출 중 같은 키로 재시도, 서버 중단 뒤 재시도 | 202와 기존 `paymentId`. 만료된 토큰으로도 같은 응답 | 1 | 0017 | I |
| N4 | `pg-approve` CB OPEN 중 같은 키·새 키 재요청 | 기존 상태로 응답(허가 없이, PG 호출 없음) | 1 | 0018, 0019 | I |
| N5 | 허가 획득 뒤 TX1 실패(DB 오류) | 허가 반환, 결제 행 없음, 503 `SERVICE_UNAVAILABLE` | 5 | 0018 | U, I |
| N6 | `UNKNOWN` 중 새 키 재요청 | 202 + 기존 `paymentId`, ALIAS 저장. 금액·수단·통화가 다르면 409(`Location` 없음). `NEEDS_MANUAL_CHECK`는 409 + `Location` | 1 | 0019 | I |
| N7 | `RefundRequested`가 DB 장애로 3회 실패해 DLQ 도착 | DLQ 처리기가 환불 `REQUESTED`로 영속화(`dead_letters` `RESOLVED(AUTO_PERSISTED)`), 환불 워커가 환불, `PaymentRefunded`. 같은 DLQ 메시지 재전달에도 환불 행·PG 환불 1건 | 2, 4 | 0020 | I |
| N8 | DLQ 처리기의 DB 기록 실패 | 오프셋 미커밋, 백오프 재시도, DB 복구 뒤 영속화와 환불 완료, 메시지 유실 없음 | 4 | 0020 | I |
| N9 | 다른 사용자 결제 조회 | 404 `NOT_FOUND`(존재하지 않는 ID와 같은 본문), 내부 WARN 로그 | 9 | 0021 | I |
| N10 | Kafka 장애 중 결제 승인 | 승인은 성공하고 Outbox가 쌓이며 복구 뒤 자동 발행. 영구 오류만 `FAILED` | 5 | 0022 | I |
| N11 | `processed_events` 삭제 뒤 이벤트 재전달 | 조건부 UPDATE·UNIQUE·`INSERT … SELECT … ON CONFLICT`·재호출 전 조회로 중복 결제·환불 없음 | 1, 2 | 0022 | I |
| N12 | `PAYMENT_TOKEN_SECRET` 없음·형식(64자 소문자 16진수) 오류 | 서비스 기동 실패(키 값은 메시지에 없음) | 9 | 0025 | U |
| N13 | 같은 예매에 다른 키 2건 동시 요청 | TX1 `reservation_id` 유니크 충돌, 결제 1건. 진 쪽은 재조회해 202 + ALIAS(500 아님) | 1 | 0026, 0017 | I |
| N14 | 같은 키 2건 동시 요청 | 결제 1건. 진 쪽은 멱등 기록 재조회로 202 | 1 | 0017 | I |
| N15 | 토큰이 검증 뒤 PG 호출 전에 만료 | PG 호출 0회, `DECLINED(TOKEN_EXPIRED)` 200, `PaymentDeclined` | 8 | 0018, 0025 | I |
| N16 | PG 승인 금액·통화 ≠ 결제 | `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, `PaymentApproved` 없음, 즉시 알림. 금액이 다른 행을 `APPROVED`로 바꾸는 UPDATE는 CHECK 위반 | 3 | 0023 | U, I |
| N17 | TX2와 재확인 스케줄러가 같은 결제를 동시에 확정 | 한쪽만 1행, 이력·Outbox 1건, 진 쪽은 현재 상태 응답 | 5 | 0022 | I |
| N18 | `next_check_at` 없이 `REQUESTED` 저장 시도 | CHECK 위반. 정상 저장은 10초 뒤 스케줄러가 집음 | 4 | data-model.md | I |
| N19 | 환불 응답 유실 뒤 PG에 환불 기록 없음(`NotFound`) | 같은 `pg_refund_id`로 재호출, PG 환불 1회 | 2, 4 | 0020, 0023 | I |
| N20 | PG가 환불을 명확히 거절 | 환불 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`(`FAILED` 아님), 알림 | 4 | 0020 | I |
| N21 | Ops `payment resolve`로 NMC 결제 확인 | PG 승인·금액 일치면 `APPROVED` + `PaymentApproved` + 이력(actor OPS) + `manual_action_logs`(APPLIED + `pg_evidence`). PG 승인·금액 불일치면 환불 `REQUESTED`(금액 = PG 승인 금액)를 만들고 결제는 NMC 유지. PG가 `Unknown`이면 거부·상태 유지. `manual_action_logs`·이력의 UPDATE·DELETE는 트리거가 거부. 구현: `PaymentResolveCommand`, 테스트: `PaymentResolveTest`(실제 PostgreSQL + 가짜 `PgClient`. 트리거 거부는 `PaymentMigrationTest`) | 7 | 0028 | I |
| N22 | 예매 계약 | `CONFIRMED` + 같은 paymentId 재전달은 변화 없음. 다른 paymentId는 `RefundRequested(DUPLICATE_APPROVAL)`. `CONFIRMED` + `PaymentRefunded`는 좌석 유지·이상 격리·알림. 사용자·금액·통화 불일치 승인은 확정하지 않음 | 2, 3 | 0027 | I, S(반효승) |
| N23 | `RefundRequested`가 발행되지 않음(Reservation 소비 실패 모의) | 허용 지연 뒤 대사 RC-2 기록·알림. 자동 수정 없음 | 4, 6 | 0029 | I, S |
| N24 | 새 키 ALIAS | 같은 새 키 재요청은 PG 호출 0회로 현재 상태. 만료 토큰 + 새 키는 기존 결제가 있으면 조회만, 없으면 403 | 1 | 0019 | I |
| N25 | 그룹 분리 | `pg-approve` OPEN 중에도 재확인·환불 진행, `pg-recovery` OPEN 중에도 신규 승인 진행. 복구 CB OPEN 동안 시도 횟수 불변 | 4 | 0018 | I |
| N26 | 재확인 중 PG 지연 | Outbox 릴레이·Gauge 갱신이 늦어지지 않음(실행기 분리) | 5 | 0022 | I |
| N27 | Kafka-UI로 메시지 발행 시도 | READONLY로 거부 | 9 | 0020, S-24 | S |
| N28 | `RefundRequested` 교차 검증 실패(결제 없음, 예매·사용자·금액 불일치, 미지정 사유) | 재시도 없이 DLQ, `dead_letters` `OPEN`, PG 호출 0회, 즉시 알림 | 3, 4, 9 | 0020 | I |
| N29 | Gateway 504 뒤 같은 키 재시도 | 서버 처리 계속, PG 승인 1회, 재시도는 결과 응답 | 1 | 0018 | S |
| N30 | 결제 상태를 직접 SQL로 변경(모의) | 대사 RC-7(이력 불일치) 기록·알림. 테스트: `ReconciliationScenarioTest`(직접 SQL → L 뒤 RC-7 `OPEN`·Gauge 1 → 이력과 맞추면 `RESOLVED`·Gauge 0), `ReconciliationServiceTest`(환불 쪽 어긋남, RC-8) | 6, 7 | 0028, 0029 | I |
| N31 | 긴급 콘솔 환불 뒤 `refund resolve` | PG API로 환불 확인되면 `REFUNDED` + `PaymentRefunded` + `external_ref`. 확인 불가면 거부·NMC 유지. 한계: 콘솔 환불은 우리 `pg_refund_id` 없이 일어나 지금의 `findRefund`로는 확인되지 않으므로 `NotFound`로 거부되고 NMC로 남는다(토스 어댑터 M4 때 콘솔 환불 확인용 조회를 다시 본다). 구현: `RefundResolveCommand`, 테스트: `RefundResolveTest`(PG가 환불을 확인하는 경우와 `NotFound` 거부) | 2, 7 | 0028 | I |
| N32 | Ops 명령 통제 | 승인자 없음·실행자와 같음·허용 목록 밖·계획 해시 불일치는 거부(REJECTED 기록) | 7, 9 | 0028 | U, I |

## 3. 시나리오별 확인 규칙
- 장애 주입 시나리오는 DB 상태, Kafka 토픽, 지표(NMC 건수, Outbox 최고령, `dead_letters` OPEN, 대사 불일치)를 함께 확인한다.
- 응답 동일성(N9)은 `traceId`를 제외한 본문이 같은지 비교한다.
- PG 어댑터의 공통 계약 테스트 목록은 [pg-integration.md](pg-integration.md) 6절에 있다.
- 구현 후 실제로 실행한 시나리오와 결과는 PR 본문에 기록한다.

## 4. 장애 유형별 통합 테스트

장애 유형마다 주입 지점을 바꿔 가며 같은 세 가지를 반드시 검증한다. 테스트 프로필은 대사 허용 지연을 5초`[가정]`로 줄이고, 장애가 끝난 뒤 대사를 한 번 강제 실행한다.

| 검증 | 확인 방법 | 기대 |
|---|---|---|
| V1 결제 승인 중복 없음 | 결제 DB `reservation_id`별 행 수, Mock PG 원장의 주문별 승인 호출 수(`approveCalls`), PG 승인 주문 집합과 `pg_tx_id`가 있는 결제 집합 | 예매당 결제 ≤ 1, 주문당 승인 호출 ≤ 1, PG에만 있는 승인 0 |
| V2 환불 중복 없음 | `payment_id`별 환불 행 수, Mock PG 원장의 결제별 환불 수·금액 | 결제당 환불 ≤ 1, PG 환불 합계 ≤ 승인 금액 |
| V3 결제↔예매 최종 정합 | 강제 대사 결과, 결제·예매 상태 쌍 | `reconciliation_mismatches` OPEN 0. 모든 결제가 정상 쌍(`APPROVED`↔`CONFIRMED`, `DECLINED`↔`RELEASED`, `REFUNDED`↔`EXPIRED`·`RELEASED`·`CANCELED`)이거나 명시적 미해결 상태(`UNKNOWN`·NMC·환불 대기·`dead_letters` OPEN)이고 그 지표가 0보다 크다 |

Mock PG 원장 조회는 `test`·`loadtest` 프로필 전용 기능이 필요하다(결제 담당이 Mock PG에 구현, S-28, [mock-pg.md](mock-pg.md) 9절). 배경 작업(재확인 스케줄러, 환불 워커)이 PG를 함께 부르므로 장애 주입은 `orderId`·`refundId`로 대상을 지정한다.

| # | 장애 유형 | 주입 지점과 기대 | 관련 |
|---|---|---|---|
| FT-1 | 메시지 중복 | (a) `PaymentApproved`를 같은 eventId·새 eventId로 재발행: 확정 1회, 환불 없음. (b) `RefundRequested` 3중 발행·DLQ 재전달: 환불 1건. (c) Outbox 전송 확인 전 릴레이 중단으로 중복 전송: 소비 1회. (d) `processed_events` 정리 뒤 재전달 | N7, N11, N22 |
| FT-2 | PG 응답 유실 | (a) 승인 `DROP_RESPONSE`: `UNKNOWN` → 조회로 `APPROVED`, 승인 1회. (b) 환불 응답 유실: `UNKNOWN` → `findRefund`로 `REFUNDED`. (c) 환불이 PG에 도달하지 않음: `NotFound` → 같은 키 재호출. (d) PG 미도달 승인: `NotFound` → 늦은 승인 방지 → `DECLINED(NOT_SENT)` | E9, N19 |
| FT-3 | 서버 재시작 | `payment-service` 강제 종료 시점: (a) TX1 커밋 뒤 PG 호출 전, (b) PG 호출 중, (c) PG 승인 뒤 TX2 전, (d) 환불 워커의 claim 뒤 PG 호출 중, (e) Outbox 전송 중, (f) DLQ 처리기 트랜잭션 중. 재기동 뒤 모두 확정되거나 명시적 미해결 | E12, N3, N17 |
| FT-4 | DB 일시 장애 | `postgres-payment` 일시 정지 시점: (a) TX1: 503, 행 없음, 같은 요청 재시도 성공. (b) TX2: 202, DB 복구 뒤 재확인으로 확정. (c) `payment.command` 소비 중: 재시도 뒤 DLQ, 처리기가 DB 복구까지 대기 후 영속화. (d) 환불 결과 기록 중: 임대 만료 뒤 조회로 확정, PG 환불 1회 | N5, N7, N8 |
| FT-5 | 환불 중단 | (a) 환불 중 `pg-recovery` CB OPEN: 시도 횟수 불변, 복구 뒤 완료. (b) 장기 장애: 9회 뒤 NMC, 알림, `refund retry`로 완료. (c) PG 거절: NMC, `refund resolve --close-failed`는 외부 조치 참조가 있어야만 적용. (d) 긴급 콘솔 환불 예외 절차 | N20, N25, N31 |

각 FT는 끝에 V1~V3를 모두 실행하고, 하나라도 어긋나면 실패로 본다. 실제 실행 결과(명령, 주입 방법, V1~V3 출력)는 구현 PR 본문에 남긴다.

### 구현(ISE-161, ISE-44 분할 1/2)

검증 기반(V1~V3)과 FT-1·FT-2를 구현했다. FT-3~5와 금액 불일치(FT-6)는 이 절 뒤의 ISE-162 구간에 있다. 테스트는 `payment-service/src/test/java/com/iseonjwa/payment/scenario/`에 있다.

| 클래스 | 역할 |
|---|---|
| `ScenarioTestBase` | 공통 기반. 실제 Mock PG 프로세스(test 프로필)와 테스트 PostgreSQL을 쓰고 Kafka 리스너·배경 실행기는 끈다. 시나리오마다 Mock PG `reset`과 시나리오 데이터 정리로 시작한다. 복구는 `recoverUntilIdle()`이 재확인·환불 워커·Outbox 릴레이를 할 일이 없을 때까지(상한 40바퀴) 직접 부른다 |
| `ScenarioInvariants` | 검증기 V1~V3. 위반 항목을 모아 한 번에 실패시킨다 |
| `ScenarioInvariantsTest` | 검증기 자체 테스트. 일부러 만든 위반 데이터로 V1·V2·V3가 각각 실패하는지 본다 |
| `Ft1DuplicateScenarioTest`, `Ft2PgResponseLossScenarioTest` | FT-1, FT-2 |

- 기다림은 시계를 앞으로 당겨 없앤다. 시나리오의 재확인·환불·Outbox 다음 시도 시각과 임대 만료를 과거로 당긴다(기존 통합 테스트와 같은 방식이고 운영 시계 빈은 바꾸지 않는다). `sleep`을 쓰지 않는다.
- 장애는 `orderId`·`refundId`로 한정해 건다. 승인의 주문 ID는 TX1이 정하므로 `PgClient` 스파이로 승인 호출 직전에 그 주문의 장애를 건다. 환불은 환불 행의 `pg_refund_id`를 읽어 건다.
- 결제 쪽 V3의 범위: 모든 결제가 정상 종결(`APPROVED`·`DECLINED`·`REFUNDED`)이거나 명시적 미해결(`UNKNOWN`·NMC 결제, `REQUESTED`·`UNKNOWN`·NMC 환불, `OPEN` `dead_letters`)이고, 미해결이면 `DbAggregateGauges.refresh()` 뒤 해당 Gauge가 0보다 크다. `REQUESTED`로 남은 결제는 위반이다. 결제↔예매 상태 대조(RC-1~RC-6)는 ISE-168이 맡는다. 결제 DB만으로 보는 RC-7·RC-8은 이 검증기에 붙이지 않고 `ReconciliationScenarioTest`가 같은 시나리오 기반 위에서 승인·환불·응답 유실 복구·재시작 뒤 거절 흐름 끝에 불일치가 0인지 확인한다(검증기 단독 테스트 `ScenarioInvariantsTest`가 결제를 이력 없이 직접 넣어 붙이면 RC-7로 실패한다). Gauge는 DB 전체를 세므로 "0보다 크다"는 하한 확인이다.
- V1의 "PG에만 있는 승인 0"은 PG가 승인한 모든 주문에 같은 `pg_tx_id`의 결제가 있어야 한다. 결제가 아직 결과를 모르는 미해결(`UNKNOWN`·NMC이고 `pg_tx_id` 없음)이면 예외로 둔다.

FT-1 (a)~(c)와 FT-2 결과(모두 V1~V3 통과):

| 시나리오 | 주입 | 최종 상태 |
|---|---|---|
| FT-1 (a) 같은 키 3회·새 키 2회 | 없음 | 결제 1건 `APPROVED`, `approveCalls` 1, 모든 응답이 같은 `paymentId`(200), `PaymentApproved` 1건 |
| FT-1 (a) 같은 예매로 6건 동시(같은 키·새 키 섞음) | 없음 | 응답 200·202만(500 없음), 결제 1건, `approveCalls` 1 |
| FT-1 (b) `RefundRequested` 3중 발행 + 같은 DLQ 메시지 2회 | 없음 | 환불 행 1건, `dead_letters` `DUPLICATE`, PG `refundCount` 1·`refundCalls` 1, `REFUNDED` |
| FT-1 (b) DLQ가 먼저 도착한 뒤 원본 2회 재전달 | 없음 | `dead_letters` `AUTO_PERSISTED`, 환불 행 1건, PG `refundCount` 1 |
| FT-1 (c) Outbox 전송 확인 전 중단 | 첫 전송 뒤 릴레이 중단(기록용 sender) | 임대 중에는 다시 집히지 않고, 임대 만료 뒤 같은 `eventId`로 다시 전송, `SENT` 1행 |
| FT-2 (a) 승인 응답 유실 | APPROVE `DROP_RESPONSE`, `orderId` 한정 | 202 `UNKNOWN` → 재확인 → `APPROVED`, `approveCalls` 1, `PaymentApproved` 1건 |
| FT-2 (b) 환불 응답 유실 | REFUND `DROP_RESPONSE`, `refundId` 한정 | 환불 `UNKNOWN` → `findRefund` → `REFUNDED`, `refundCount` 1, `refundCalls` 1 |
| FT-2 (c) 환불이 PG에 닿지 않음 | REFUND `SERVER_ERROR`, `refundId` 한정 | `UNKNOWN` → `NotFound` → 같은 `pg_refund_id`로 재호출 → `REFUNDED`, `refundCount` 1, 원장 환불 ID가 처음 것과 같음, `refundCalls` 2 |
| FT-2 (d) 승인이 PG에 닿지 않음 | APPROVE `SERVER_ERROR`, `orderId` 한정 | `UNKNOWN` → `NotFound` → 늦은 승인 방지 → `DECLINED(NOT_SENT)`, `PaymentDeclined` 1건. 같은 주문의 늦은 승인은 PG가 받지 않고 원장 `CANCELED`, 승인 0건 |

- FT-2 (d)의 V1~V3는 승인 호출이 1회인 상태에서 실행한다. 이어서 거는 늦은 승인 시도는 `approveCalls`를 늘리므로 그 뒤에는 검증기를 돌리지 않는다.
- FT-1 (d) `processed_events` 정리 뒤 재전달은 `RedeliveryAfterRetentionCleanupTest`(ISE-146)가 이미 다룬다. 중복으로 만들지 않았다.
- FT-1 (a)의 `PaymentApproved` 같은 `eventId` 재수신을 거르는 것은 소비자(예매)의 `processed_events`다. 결제 쪽은 같은 `eventId`로 다시 나가는 것까지만 확인한다(c).

### 구현(ISE-162, ISE-44 분할 2/2)

FT-3 재시작, FT-4 DB 일시 장애, FT-5 환불 중단을 구현했다. ISE-161 변이 확인에서 시나리오가 금액 불일치 경로(N16)를 지나지 않는 것이 드러나 FT-6을 더했다. FT-6은 4절 표에 없는 추가 시나리오이고 `payment resolve`로 환불하는 데까지 본다. 테스트는 같은 `scenario/` 패키지에 있다.

| 클래스 | 역할 |
|---|---|
| `Ft3RestartScenarioTest` | FT-3 (a)~(f) |
| `Ft4DbOutageScenarioTest` | FT-4 (a)~(d). 테스트 설정에서만 `DataSource`를 감싸는 스위치를 둔다 |
| `Ft4DbFreezeScenarioTest` | FT-4 (e)~(h). 테스트 DB 앞의 `FreezableTcpProxy`가 연결을 닫지 않고 전달만 멈춘다. 연결 대기·생존 검사·소켓·문장 시한은 `config-repo/payment-service.yml`에서 읽어 이 테스트에만 적용한다 |
| `Ft5RefundInterruptionScenarioTest` | FT-5 (a)~(d). Ops 명령은 4-eyes 절차대로 부른다 |
| `Ft6AmountMismatchScenarioTest` | FT-6 금액 불일치 → NMC → Ops `payment resolve` → 환불 |
| `ScenarioOps` | `refund retry`·`refund resolve`·`payment resolve`를 `OpsRunner`로 부르는 도우미. dry-run 출력에서 계획 해시를 읽어 적용한다 |

기반(`ScenarioTestBase`)에는 프로세스 중단 흉내(`armApproveCrash`, `ProcessCrash`)와 환불 REQUESTED 준비(`approvedPaymentWithRefundRequest`)를 더했고, 정리 때 `manual_action_logs`도 지운다.

- FT-3은 "재시작 직후의 DB·PG 상태"를 만들고 복구 경로를 돌린다. 상태는 운영 코드를 실제로 실행하다 `ProcessCrash`(`Error`)로 끊어서 만든다. 운영 코드는 `RuntimeException`만 PG 결과 모름으로 다루고 `Error`는 통과시키므로 끊긴 뒤 단계(TX2, 결과 반영, 임대 해제)는 실행되지 않는다. 컨트롤러 예외 처리기가 이 `Error`를 500 응답으로 바꾸지만, 실제로 죽었다면 클라이언트는 응답을 받지 못하므로 테스트는 그 응답을 쓰지 않는다.
- 실제 프로세스를 강제 종료하는 확인은 테스트로 하지 않는다. compose에서 `payment-service`를 `kill -9`로 죽였다가 다시 띄워 메인이 수동으로 확인한다. 테스트가 보장하는 것은 "그 순간의 DB·PG 상태에서 복구 경로가 맞다"까지다.
- compose 확인(2026-11-03): `docker pause`로 결제 Postgres를 얼린 채 승인을 보냈다. 테스트의 DB 스위치(새 연결을 거절)와 달리 연결이 거절되지 않고 쿼리가 멈췄다. 그래서 N5의 503이 아니라 Gateway 시한(3초)을 넘긴 504가 났다. DB가 풀린 뒤 처리가 이어져 결제 1건이 `APPROVED`가 됐고, 같은 키 재시도는 200으로 같은 결과를 받았다(N29 경로, 결제·이력·Outbox 각 1건). 커넥션 풀 대기 시한은 빈 연결이 없을 때만 걸리고 빌린 연결의 쿼리에는 시한이 없어서다. 이 문제는 ISE-163에서 소켓 시한 2초와 문장 시한 1.5초로 막았다([events-and-reliability.md](events-and-reliability.md) 7절). ISE-163 뒤 같은 방법으로 다시 확인했다(2026-11-03, 세 번). 세 번 모두 Gateway를 거쳐 2.0초 만에 503 `SERVICE_UNAVAILABLE`이 왔고 로그의 원인은 `SocketTimeoutException`이었다. 그사이 결제 행은 생기지 않았고, 풀린 뒤 같은 키 재시도는 200 `APPROVED`, 결제·Outbox 각 1건이었다. compose에서는 릴레이 같은 배경 작업이 연결을 0.5초 안에 계속 써서 Hikari의 생존 검사를 건너뛰었을 가능성이 크다. 검사를 거치는 경로(기본 5초)는 테스트 (h)가 맡는다. 컨테이너를 `kill -9`로 죽이는 확인은 compose Mock PG의 장애 주입이 닫혀 있어(local 프로필) PG 호출 도중을 만들 수 없어 하지 않았다.
- FT-4 (a)~(d)의 스위치는 `getConnection` 시점만 막는다. Hikari 풀에서 이미 빌려 간 연결은 영향을 받지 않으므로, 트랜잭션이 시작되기 전의 장애만 만든다. 진행 중이던 쿼리가 멈추는 경우와 쉬던 연결의 생존 검사가 멈추는 경우는 (e)~(h)의 멈춤 프록시가 맡는다. 프록시는 연결을 닫지 않고 양방향 전달만 멈추므로 `docker pause`와 같은 모양이다. 실제 컨테이너로 확인하는 일은 compose 수동 확인이 맡는다.
- FT-4의 장애 시작 지점은 타이밍에 기대지 않고 호출 경계의 훅으로 정했다. (a)는 승인 허가를 얻은 직후, (b)·(d)는 PG 승인·환불이 끝난 직후, (c)는 호출 전이다. (b)를 Mock PG 지연과 별도 스레드로 만드는 방식은 쓰지 않았다.
- FT-5 (c)는 거절 뒤 조회 결과를 `PgClient` 스파이로 정한다. Mock PG는 거절한 환불을 저장하지 않아 그 환불 ID를 조회하면 항상 `NotFound`이므로, 실제 Mock PG만으로는 `refund resolve --close-failed`의 종결 분기에 닿지 못한다. 환불 거절 자체는 실제 Mock PG가 낸다. 실제 Mock PG 그대로의 결과는 (d)가 확인한다.
- V3는 환불 `FAILED`를 정상 종결로 본다. `FAILED`는 운영자가 PG로는 환불할 수 없음을 확인하고 외부 조치로 넘긴 종결이고, 결제는 `APPROVED`로 남으며 환불 행이 있어 대사 RC-2의 대상도 아니다([domain-and-states.md](domain-and-states.md) 2절). 검증기 규칙은 바꾸지 않았다.
- FT-6은 변조형 장애(`WRONG_AMOUNT`, ISE-130)가 없어서 승인 호출 경계에서 PG로 보내는 금액만 1,000원 줄인다. 주문 ID와 멱등키는 결제 서비스가 만든 그대로다. 같은 주문을 다른 금액으로 먼저 승인해 두는 방식은 승인 호출이 주문당 2회가 되어 V1을 어기므로 쓰지 않았다.

FT-3 결과(모두 V1~V3 통과, 승인 중단은 (a)~(c)):

| 시나리오 | 만든 상태 | 최종 상태 |
|---|---|---|
| (a) TX1 뒤 PG 호출 전 | 결제 `REQUESTED`, PG에 주문 없음 | 재확인 `NotFound` → 늦은 승인 방지 → `DECLINED(NOT_SENT)`, `PaymentDeclined` 1건, 승인 호출 0, PG 원장 `CANCELED`. 같은 키 재시도는 PG를 부르지 않고 기존 결제로 응답(N3) |
| (b) PG 승인 뒤 응답 처리 전 | 결제 `REQUESTED`, PG `APPROVED` | 재확인 → `APPROVED`, `approveCalls` 1, `PaymentApproved` 1건 |
| (c) (b)에서 TX1이 두 시간 전 | 같음. PG 승인이 TX1보다 한참 뒤 | 같은 결과, `approved_at - created_at` > 1시간 |
| (d) 환불 워커 claim·PG 환불 뒤 | 환불 `REQUESTED`, 임대 중, `pg_requested_at` 있음, PG `refundCount` 1 | 임대 중에는 워커가 집지 않음. 임대 만료 뒤 `findRefund` → `REFUNDED`, `refundCalls` 1, `refundLookupCalls` 1 |
| (e) Outbox 릴레이가 임대 후 전송 전 | `PENDING`, `claimed_until` 미래 | 임대 중에는 다시 집히지 않음. 만료 뒤 한 번 전송, `SENT` 1행 |
| (f) DLQ 처리기 트랜잭션 중간 | `dead_letters`·`processed_events`·환불 모두 롤백 | 같은 메시지를 다시 처리하면 환불 1건, `dead_letters` `AUTO_PERSISTED` 1건, PG `refundCount` 1 |

FT-4 결과(모두 V1~V3 통과):

| 시나리오 | 주입 | 최종 상태 |
|---|---|---|
| (a) TX1 | 허가 획득 직후 스위치 켬 | 503 `SERVICE_UNAVAILABLE`, 결제 행·멱등 기록 없음, 허가 반환(Bulkhead 여유 복구, CB 집계 0), PG 호출 0. 스위치를 끄고 같은 키·같은 토큰으로 다시 호출하면 200 `APPROVED`, `approveCalls` 1(N5) |
| (b) TX2 | PG 승인 직후 스위치 켬 | 202 `REQUESTED`, 결제 `REQUESTED`·`pg_tx_id` 없음, PG `APPROVED`. 스위치를 끄고 재확인 → `APPROVED`, `approveCalls` 1, `PaymentApproved` 1건 |
| (c) `payment.command` 소비 중 | 스위치 켠 채 리스너 1회, DLQ 처리기 2회 | 세 호출 모두 DB 오류 예외(내용 문제가 아님), 아무것도 남지 않음. 끄고 DLQ 처리기 → 환불 `REQUESTED` 1건, `dead_letters` `AUTO_PERSISTED` 1건 → 워커 → `REFUNDED`(N7·N8) |
| (d) 환불 결과 기록 중 | PG 환불 직후 스위치 켬 | 워커가 DB 오류로 중단, 환불 `REQUESTED`·임대 중, PG `refundCount` 1. 임대 만료 뒤 `findRefund` → `REFUNDED`, `refundCalls` 1 |

FT-4 (e)~(h) 결과(모두 V1~V3 통과, 경과 시간은 한 번 실행한 값이다):

| 시나리오 | 주입 | 최종 상태 |
|---|---|---|
| (e) TX1 멈춤 | 허가 획득 직후 프록시 `freeze()` | 503 `SERVICE_UNAVAILABLE`, 경과 2.3초(Gateway 시한 3초 안). `thaw()` 뒤 결제 행·멱등 기록 없음, 허가 반환, PG 승인 호출 0. 같은 키·같은 토큰으로 다시 호출하면 200 `APPROVED`, `approveCalls` 1 |
| (f) TX2 멈춤 | PG 승인 직후 프록시 `freeze()` | 202 `REQUESTED`, 경과 2.0초(멈추지 않고 돌아옴). `thaw()` 뒤 재확인 → `APPROVED`, `approveCalls` 1, `PaymentApproved` 1건 |
| (g) 문장 시한 | 같은 `DataSource`로 `SELECT pg_sleep(3)` | SQLState `57014`로 실패, 경과 1.5초(문장 시한 근처). 이어서 `SELECT 1`이 같은 풀에서 성공 |
| (h) 생존 검사 중 멈춤 | 허가 획득 뒤 0.6초 쉬고(연결이 검사 대상이 됨) 프록시 `freeze()` | 503 `SERVICE_UNAVAILABLE`, Gateway 시한 3초 안. 결제 행 없음, 허가 반환, PG 호출 0. 같은 키 재시도 200 `APPROVED`. 변이 확인: `validation-timeout`을 기본값 5000으로 두면 5.9초로 실패한다 |

(b)의 응답은 `UNKNOWN`이 아니라 `REQUESTED`다. TX2가 롤백돼 상태를 `UNKNOWN`으로 바꾸지 못하고, 202 응답은 메모리의 `REQUESTED` 결제로 만든다(`PaymentApprovalService.settle`).

FT-5 결과(모두 V1~V3 통과):

| 시나리오 | 주입 | 최종 상태 |
|---|---|---|
| (a) `pg-recovery` CB OPEN | CB를 OPEN으로 전환, 워커 5회 | 워커는 `false`, PG 호출 0, 환불 `attempt` 0·`pg_requested_at` 없음. 이 동안 새 예매 승인은 200(N25). CB를 닫은 뒤 `REFUNDED`, `attempt` 1, `refundCalls` 1 |
| (b) 장기 장애 | REFUND·REFUND_LOOKUP `SERVER_ERROR` 계속 | 9회 뒤 환불 NMC(`REFUND_UNRESOLVED`, `attempt` 9), 결제 `APPROVED`, PG `refundCount` 0. 장애를 풀고 `refund retry` dry-run → apply(4-eyes) → `REQUESTED`(`OPS_RETRY`) → 워커 → `REFUNDED`, 같은 `pg_refund_id`, PG `refundCount` 1 |
| (c) PG 거절 | REFUND `REJECT`, 조회는 `RefundRejected`로 대체 | 환불 NMC(`REFUND_REJECTED`, `FAILED` 아님). `--close-failed`를 `--external-ref` 없이 dry-run·apply하면 둘 다 거부(`REJECTED` 기록 1건). 참조를 함께 주면 `FAILED`(`OPS_CLOSED`), 결제 `APPROVED` 유지, 이벤트 없음, 감사 기록에 `external_ref`(N20) |
| (d) 콘솔 환불 | NMC 환불(거절로 만듦), 실제 Mock PG 조회 | `refund resolve`가 `NotFound`로 거부(manual-recovery.md 8절 한계), NMC 유지, `REJECTED` 기록 1건(N31) |

FT-6 결과(V1~V3 통과):

| 시나리오 | 주입 | 최종 상태 |
|---|---|---|
| PG 승인 금액 ≠ 결제 금액 | 승인 호출 금액을 1,000원 줄임 | 승인 API 409 + `Location`, 결제 NMC(`PG_AMOUNT_MISMATCH`)·`pg_approved_amount` 49,000, `PaymentApproved` 없음, `payment.pg.amount.mismatch{operation=approve}` 1 증가, 재확인이 집지 않음. `payment resolve` dry-run → apply(4-eyes) → 환불 `REQUESTED`(금액 49,000, `PG_AMOUNT_MISMATCH`) → 워커 → 환불·결제 `REFUNDED`, PG 환불 합계 49,000, `PaymentRefunded`(금액 49,000) 1건 |

변이 확인(운영 코드를 일시적으로 바꿔 시나리오가 실패하는지 본 결과, 코드는 되돌렸고 커밋하지 않았다):

| 변이 | 실패한 시나리오 |
|---|---|
| 환불 워커가 `findRefund` 없이 바로 `refund`를 부름 | FT-3 (d), FT-4 (d), FT-2 (b)·(c). PG 멱등이 막아 `refundCount`는 1로 같으므로 `refundCalls`(2)와 `refundLookupCalls`(0) 단정이 차이를 본다 |
| 복구 CB가 OPEN일 때도 시도 횟수를 올림 | FT-5 (a) (`attempt` 0 단정, 실제 5) |
| `--close-failed`의 `external_ref` 검사를 뺌 | FT-5 (c) (참조 없는 dry-run이 거부되지 않음) |
| TX2의 금액 대조를 뺌 | FT-6. `chk_payments_approved`가 금액이 다른 `APPROVED`를 막아 TX2가 롤백되고 202 `REQUESTED`가 되어 409 단정이 실패 |
