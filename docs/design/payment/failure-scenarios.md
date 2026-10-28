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
| N7 | `RefundRequested`가 DB 장애로 3회 실패해 DLQ 도착 | DLQ 처리기가 환불 `REQUESTED`로 영속화(`dead_letter` `RESOLVED(AUTO_PERSISTED)`), 환불 워커가 환불, `PaymentRefunded`. 같은 DLQ 메시지 재전달에도 환불 행·PG 환불 1건 | 2, 4 | 0020 | I |
| N8 | DLQ 처리기의 DB 기록 실패 | 오프셋 미커밋, 백오프 재시도, DB 복구 뒤 영속화와 환불 완료, 메시지 유실 없음 | 4 | 0020 | I |
| N9 | 다른 사용자 결제 조회 | 404 `NOT_FOUND`(존재하지 않는 ID와 같은 본문), 내부 WARN 로그 | 9 | 0021 | I |
| N10 | Kafka 장애 중 결제 승인 | 승인은 성공하고 Outbox가 쌓이며 복구 뒤 자동 발행. 영구 오류만 `FAILED` | 5 | 0022 | I |
| N11 | `processed_event` 삭제 뒤 이벤트 재전달 | 조건부 UPDATE·UNIQUE·`INSERT … SELECT … ON CONFLICT`·재호출 전 조회로 중복 결제·환불 없음 | 1, 2 | 0022 | I |
| N12 | `PAYMENT_TOKEN_SECRET` 없음·형식(64자 소문자 16진수) 오류 | 서비스 기동 실패(키 값은 메시지에 없음) | 9 | 0025 | U |
| N13 | 같은 예매에 다른 키 2건 동시 요청 | TX1 `reservation_id` 유니크 충돌, 결제 1건. 진 쪽은 재조회해 202 + ALIAS(500 아님) | 1 | 0026, 0017 | I |
| N14 | 같은 키 2건 동시 요청 | 결제 1건. 진 쪽은 멱등 기록 재조회로 202 | 1 | 0017 | I |
| N15 | 토큰이 검증 뒤 PG 호출 전에 만료 | PG 호출 0회, `DECLINED(TOKEN_EXPIRED)` 200, `PaymentDeclined` | 8 | 0018, 0025 | I |
| N16 | PG 승인 금액·통화 ≠ 결제 | `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, `PaymentApproved` 없음, 즉시 알림. 금액이 다른 행을 `APPROVED`로 바꾸는 UPDATE는 CHECK 위반 | 3 | 0023 | U, I |
| N17 | TX2와 재확인 스케줄러가 같은 결제를 동시에 확정 | 한쪽만 1행, 이력·Outbox 1건, 진 쪽은 현재 상태 응답 | 5 | 0022 | I |
| N18 | `next_check_at` 없이 `REQUESTED` 저장 시도 | CHECK 위반. 정상 저장은 10초 뒤 스케줄러가 집음 | 4 | data-model.md | I |
| N19 | 환불 응답 유실 뒤 PG에 환불 기록 없음(`NotFound`) | 같은 `pg_refund_id`로 재호출, PG 환불 1회 | 2, 4 | 0020, 0023 | I |
| N20 | PG가 환불을 명확히 거절 | 환불 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`(`FAILED` 아님), 알림 | 4 | 0020 | I |
| N21 | Ops `payment resolve`로 NMC 결제 확인 | PG 승인·금액 일치면 `APPROVED` + `PaymentApproved` + 이력(actor OPS) + `manual_action_log`. PG가 `Unknown`이면 거부·상태 유지. `manual_action_log`·이력의 UPDATE·DELETE는 트리거가 거부 | 7 | 0028 | I |
| N22 | 예매 계약 | `CONFIRMED` + 같은 paymentId 재전달은 변화 없음. 다른 paymentId는 `RefundRequested(DUPLICATE_APPROVAL)`. `CONFIRMED` + `PaymentRefunded`는 좌석 유지·이상 격리·알림. 사용자·금액·통화 불일치 승인은 확정하지 않음 | 2, 3 | 0027 | I, S(반효승) |
| N23 | `RefundRequested`가 발행되지 않음(Reservation 소비 실패 모의) | 허용 지연 뒤 대사 RC-2 기록·알림. 자동 수정 없음 | 4, 6 | 0029 | I, S |
| N24 | 새 키 ALIAS | 같은 새 키 재요청은 PG 호출 0회로 현재 상태. 만료 토큰 + 새 키는 기존 결제가 있으면 조회만, 없으면 403 | 1 | 0019 | I |
| N25 | 그룹 분리 | `pg-approve` OPEN 중에도 재확인·환불 진행, `pg-recovery` OPEN 중에도 신규 승인 진행. 복구 CB OPEN 동안 시도 횟수 불변 | 4 | 0018 | I |
| N26 | 재확인 중 PG 지연 | Outbox 릴레이·Gauge 갱신이 늦어지지 않음(실행기 분리) | 5 | 0022 | I |
| N27 | Kafka-UI로 메시지 발행 시도 | READONLY로 거부 | 9 | 0020, S-24 | S |
| N28 | `RefundRequested` 교차 검증 실패(결제 없음, 예매·사용자·금액 불일치, 미지정 사유) | 재시도 없이 DLQ, `dead_letter` `OPEN`, PG 호출 0회, 즉시 알림 | 3, 4, 9 | 0020 | I |
| N29 | Gateway 504 뒤 같은 키 재시도 | 서버 처리 계속, PG 승인 1회, 재시도는 결과 응답 | 1 | 0018 | S |
| N30 | 결제 상태를 직접 SQL로 변경(모의) | 대사 RC-7(이력 불일치) 기록·알림 | 6, 7 | 0028, 0029 | I |
| N31 | 긴급 콘솔 환불 뒤 `refund resolve` | PG API로 환불 확인되면 `REFUNDED` + `PaymentRefunded` + `external_ref`. 확인 불가면 거부·NMC 유지 | 2, 7 | 0028 | I |
| N32 | Ops 명령 통제 | 승인자 없음·실행자와 같음·허용 목록 밖·계획 해시 불일치는 거부(REJECTED 기록) | 7, 9 | 0028 | U, I |

## 3. 시나리오별 확인 규칙
- 장애 주입 시나리오는 DB 상태, Kafka 토픽, 지표(NMC 건수, Outbox 최고령, `dead_letter` OPEN, 대사 불일치)를 함께 확인한다.
- 응답 동일성(N9)은 `traceId`를 제외한 본문이 같은지 비교한다.
- PG 어댑터의 공통 계약 테스트 목록은 [pg-integration.md](pg-integration.md) 6절에 있다.
- 구현 후 실제로 실행한 시나리오와 결과는 PR 본문에 기록한다.

## 4. 장애 유형별 통합 테스트

장애 유형마다 주입 지점을 바꿔 가며 같은 세 가지를 반드시 검증한다. 테스트 프로필은 대사 허용 지연을 5초`[가정]`로 줄이고, 장애가 끝난 뒤 대사를 한 번 강제 실행한다.

| 검증 | 확인 방법 | 기대 |
|---|---|---|
| V1 결제 승인 중복 없음 | 결제 DB `reservation_id`별 행 수, Mock PG 원장의 주문별 승인 수, PG 승인 주문 집합과 `pg_tx_id`가 있는 결제 집합 | 예매당 결제 ≤ 1, 주문당 승인 ≤ 1, PG에만 있는 승인 0 |
| V2 환불 중복 없음 | `payment_id`별 환불 행 수, Mock PG 원장의 결제별 환불 수·금액 | 결제당 환불 ≤ 1, PG 환불 합계 ≤ 승인 금액 |
| V3 결제↔예매 최종 정합 | 강제 대사 결과, 결제·예매 상태 쌍 | `reconciliation_mismatch` OPEN 0. 모든 결제가 정상 쌍(`APPROVED`↔`CONFIRMED`, `DECLINED`↔`RELEASED`, `REFUNDED`↔`EXPIRED`·`RELEASED`·`CANCELED`)이거나 명시적 미해결 상태(`UNKNOWN`·NMC·환불 대기·`dead_letter` OPEN)이고 그 지표가 0보다 크다 |

Mock PG 원장 조회는 테스트 프로필 전용 기능이 필요하다(결제 담당이 Mock PG에 구현, S-28).

| # | 장애 유형 | 주입 지점과 기대 | 관련 |
|---|---|---|---|
| FT-1 | 메시지 중복 | (a) `PaymentApproved`를 같은 eventId·새 eventId로 재발행: 확정 1회, 환불 없음. (b) `RefundRequested` 3중 발행·DLQ 재전달: 환불 1건. (c) Outbox 전송 확인 전 릴레이 중단으로 중복 전송: 소비 1회. (d) `processed_event` 정리 뒤 재전달 | N7, N11, N22 |
| FT-2 | PG 응답 유실 | (a) 승인 `DROP_RESPONSE`: `UNKNOWN` → 조회로 `APPROVED`, 승인 1회. (b) 환불 응답 유실: `UNKNOWN` → `findRefund`로 `REFUNDED`. (c) 환불이 PG에 도달하지 않음: `NotFound` → 같은 키 재호출. (d) PG 미도달 승인: `NotFound` → 늦은 승인 방지 → `DECLINED(NOT_SENT)` | E9, N19 |
| FT-3 | 서버 재시작 | `payment-service` 강제 종료 시점: (a) TX1 커밋 뒤 PG 호출 전, (b) PG 호출 중, (c) PG 승인 뒤 TX2 전, (d) 환불 워커의 claim 뒤 PG 호출 중, (e) Outbox 전송 중, (f) DLQ 처리기 트랜잭션 중. 재기동 뒤 모두 확정되거나 명시적 미해결 | E12, N3, N17 |
| FT-4 | DB 일시 장애 | `postgres-payment` 일시 정지 시점: (a) TX1: 503, 행 없음, 같은 요청 재시도 성공. (b) TX2: 202, DB 복구 뒤 재확인으로 확정. (c) `payment.command` 소비 중: 재시도 뒤 DLQ, 처리기가 DB 복구까지 대기 후 영속화. (d) 환불 결과 기록 중: 임대 만료 뒤 조회로 확정, PG 환불 1회 | N5, N7, N8 |
| FT-5 | 환불 중단 | (a) 환불 중 `pg-recovery` CB OPEN: 시도 횟수 불변, 복구 뒤 완료. (b) 장기 장애: 9회 뒤 NMC, 알림, `refund retry`로 완료. (c) PG 거절: NMC, `refund resolve --close-failed`는 외부 조치 참조가 있어야만 적용. (d) 긴급 콘솔 환불 예외 절차 | N20, N25, N31 |

각 FT는 끝에 V1~V3를 모두 실행하고, 하나라도 어긋나면 실패로 본다. 실제 실행 결과(명령, 주입 방법, V1~V3 출력)는 구현 PR 본문에 남긴다.
