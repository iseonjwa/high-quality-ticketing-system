# SA 변경 요청 (결제)

결제 상세 설계가 SA MASTER v3.2와 달라지거나 SA에 없던 규칙을 더하는 항목이다. SA는 팀 합의 문서라 이 저장소에서 개인 판단으로 고치지 않고, 이 목록을 근거로 SA 변경 요청 PR을 올려 팀이 검토한다. 상태는 모두 "제안"이다. 2026-10-28 검토 반영으로 개정한 항목은 종류에 "(개정)"을 붙였다.

| # | SA 위치 | 변경 | 종류 | 근거 | 협의 대상 |
|---|---|---|---|---|---|
| S-1 | 8-3 전이 표 | 예매의 결제 이벤트 처리를 상태별 규칙으로 바꾼다. `CONFIRMED`·`CANCELED` + 같은 paymentId는 멱등, 다른 paymentId는 중복 승인 환불, `EXPIRED`·`RELEASED`·대조 실패 승인은 확정 금지 + `RefundRequested`. 이전 안 "`HELD`가 아닌 모든 상태에 `RefundRequested`"는 `CONFIRMED`를 포함해 재전달 때 확정 티켓을 환불할 수 있어 철회한다. 세부는 S-17 | 변경(개정) | [0016](../../decisions/0016-payment-declined-token-reuse.md), [0027](../../decisions/0027-reservation-payment-event-contract.md) | 반효승 |
| S-2 | 11-3 에러 코드 | `PAYMENT_ALREADY_CLOSED`(409) 추가. 같은 예매에 `DECLINED`·`REFUNDED` 결제 | 추가 | 0016 | 팀 |
| S-3 | 12-4 payment 제약 | D3 부분 유니크를 `UNIQUE(reservation_id)` 전체로 바꾼다(S-16). `currency`·`pg_approved_amount` 컬럼, `UNIQUE(pg_tx_id)`, CHECK 4개(재조회 시각, 승인 식별자·금액, 환불 완료, 거절 사유) 추가 | 변경(개정) | [0026](../../decisions/0026-payment-one-per-reservation.md), [data-model.md](data-model.md) | 팀 |
| S-4 | 11-1, 12-5 | `idempotency_records`에 `resource_id`(NOT NULL)와 `link_type`(ORIGIN·ALIAS) 추가. 같은 키 처리 중이면 연결 결제의 현재 상태로 응답. 응답 저장은 ORIGIN의 200·202뿐 | 추가(개정) | [0017](../../decisions/0017-payment-idempotency-in-progress.md), [0019](../../decisions/0019-payment-new-key-existing-payment.md) | 팀 |
| S-5 | 8-2, 10-5, 12-4 | `NEEDS_MANUAL_CHECK`를 "종결"이 아니라 "운영자 확인 필요"로 정의. 환불 `FAILED`는 운영자가 PG 환불 불가를 확인하고 외부 조치로 넘긴 종결 상태로만 쓰고, PG의 명확한 거절은 바로 `NEEDS_MANUAL_CHECK`. 닫는 전이(결제 NMC → `APPROVED`·`DECLINED`, 환불 NMC → `REQUESTED`·`REFUNDED`·`FAILED`)와 결제 `REQUESTED` → `DECLINED(TOKEN_EXPIRED)`, 금액 불일치 환불의 결제 NMC → `REFUNDED` 추가 | 변경(개정) | [0020](../../decisions/0020-payment-refund-dlq-manual-check.md), [0028](../../decisions/0028-payment-ops-commands.md), [domain-and-states.md](domain-and-states.md) | 팀 |
| S-6 | 6-6, 8-1 | 승인 처리 순서: 인증 → 형식 검증 → 같은 키 조회 → 토큰·금액 검증 → 같은 예매 결제 조회 → 허가 획득 → TX1 → 토큰 만료 재검사 → PG → TX2. TX1 유니크 위반은 재조회 응답 | 추가(개정) | [0018](../../decisions/0018-payment-pg-permit-before-tx1.md), [api.md](api.md) | 팀 |
| S-7 | 11-3, 11-5 | `PAYMENT_ALREADY_EXISTS` 의미를 `NEEDS_MANUAL_CHECK` 결제가 있거나 요청 정보가 다를 때로 좁히고, 같은 예매의 진행·승인 결제는 기존 결제로 응답(202·200)하며 새 키는 ALIAS로 기록 | 변경(개정) | 0019 | 팀 |
| S-8 | 10-1, 10-5, 12-5 | `payment.command` DLQ 처리기(그룹 `payment.command-dlq`)가 환불 요청을 DB에 영속화해 자동 환불로 되돌린다. `dead_letters`·`manual_action_logs`·`reconciliation_mismatches` 테이블 추가 | 추가(개정) | 0020 | 팀 |
| S-9 | 11-4, 11-3 | `GET /payments/{paymentId}` 권한을 "인증 · 소유자 또는 ADMIN"으로, 타인·미존재는 404 `NOT_FOUND` | 변경 | [0021](../../decisions/0021-payment-object-level-authorization.md) | 팀 |
| S-10 | 12-5, 9-4, 10-1 | `outbox_events`에 `next_attempt_at`·`claimed_until`·`last_error` 추가, 재시도(백오프)·보관(7일·14일) 값, 임대 ≥ 2 × 프로듀서 `delivery.timeout.ms`, `SENT`는 Kafka 발행 성공만 의미, Reservation의 `payment.result` DLQ 처리기 필요 | 추가(개정) | [0022](../../decisions/0022-outbox-retry-and-retention.md) | 반효승 |
| S-11 | 12-4 payment | `decline_code`는 우리 `DeclineReason`(PG 원본 코드 아님, `NOT_SENT`·`TOKEN_EXPIRED` 포함), `pg_tx_id` 길이 VARCHAR(64) → VARCHAR(200) | 변경 | [0023](../../decisions/0023-payment-pg-client-port.md) | 팀 |
| S-12 | 9장, 10-5 | 결제 지표·알림 목록(대시보드 1장). `REQUESTED`·환불 대기·NMC 최고령, 대사 불일치, 작업 마지막 성공 시각 포함 | 추가(개정) | [0024](../../decisions/0024-payment-observability-metrics.md) | 팀 |
| S-13 | 7-3 | 결제 토큰 키: 64자 소문자 16진수 문자열이고 HMAC 키 바이트는 문자열의 UTF-8 바이트(기존 `TokenDecoders`와 같은 해석, 16진수 디코딩 안 함). 시작 시 형식 검증, 두 서비스에만 주입, `exp` 허용 오차 0초, 토큰 길이 상한, 키 유출 절차 | 추가(개정) | [0025](../../decisions/0025-payment-token-secret-injection.md) | 반효승, 팀 |
| S-14 | 11-5 결제 예시 | (M4) 구매자가 PG 결제창에서 받은 식별자(`clientPaymentRef`)를 승인 요청이 받고, 토스 `orderId`를 결제창 전에 정함 | 추가 | 0023 | 팀 |
| S-15 | 6-9, 17-1, 17-2 | 토큰 claim 이름 통보(U1), U22(환불 이력 위치)의 담당·시점 확인. U9(추첨 결제 실패 유형별 재시도)가 같은 예매 재결제를 요구하면 S-16을 다시 연다 | 확인(개정) | api.md, data-model.md, 0026 | 반효승, 이강민, 안준현 |
| S-16 | 12-4, 15장 #58, 부록 A M6 | 결제 행을 예매당 하나인 논리적 결제로 정의하고 `UNIQUE(reservation_id)` 전체로 강제. #58의 "RELEASED·DECLINED는 제외해야 좌석 재선택·재결제가 가능하다"를 "재선택은 새 예매라 영향이 없고, 같은 예매의 재결제는 8-3에 따라 없다"로 정정(Notion 결정 기록 #58 포함) | 변경 | 0026 | 팀, 이강민(U9) |
| S-17 | 8-3, 10-4 | Reservation의 `PaymentApproved`·`PaymentDeclined`·`PaymentRefunded` 처리 계약: 예매 원장과 reservationId·paymentId·userId·amount·currency 대조, 상태별 규칙, `CONFIRMED` + `PaymentRefunded`는 자동 좌석 해제 금지와 이상 격리·알림, 상태 변경과 Outbox는 한 트랜잭션이고 영향 행 1일 때만 | 추가 | 0027 | 반효승 |
| S-18 | 10-3 페이로드 | `PaymentApproved`·`PaymentRefunded`에 `currency`. `RefundRequested`에 `userId`·`amount`·`currency`, `reason`에 `DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE` 추가. 필드 추가라 `version` 유지 | 추가 | 0027 | 반효승 |
| S-19 | 6-6, 10-5, 12-4 | `RefundRequested` 소비자는 환불 행만 영속화하고 PG를 부르지 않는다. 교차 검증 실패는 재시도 없이 DLQ. PG 환불은 환불 워커만 부르고, 재호출 전에 환불 조회를 한다 | 추가 | 0020, 0023 | 팀 |
| S-20 | 6-6, 9-4 | PG 호출 자원을 `pg-approve`(신규 승인)와 `pg-recovery`(재조회·늦은 승인 방지·환불·환불 조회) 두 그룹으로 나누고 값을 9-4에 추가. 복구 CB OPEN 동안 재조회 횟수를 세지 않음 | 추가 | 0018 | 팀 |
| S-21 | 9-4, 설정 | 결제 서비스 실행기 분리(Outbox 릴레이·복구·정리 작업). `config-repo/payment-service.yml`의 `maximum-pool-size: 5`를 SA 9-4의 20으로 맞추고 `connection-timeout` 1초`[가정]` 추가 | 추가 | 0018, 0022 | 팀 |
| S-22 | 10-5, 12-5 | `NEEDS_MANUAL_CHECK` 처리를 "운영자가 PG 콘솔에서 확인해 수동으로 닫는다"에서 "감사되는 Ops 명령으로만 닫는다(직접 SQL 금지, PG 근거 강제, 4-eyes)"로 바꾼다. PG 콘솔 직접 환불은 예외 절차 | 변경 | 0028 | 팀 |
| S-23 | 3장 메시지 신뢰성, 10-5, 11-4 | 결제·예매 대사(RC-1~RC-8)와 Reservation의 읽기 전용 내부 조회 API(`/internal/reconciliation/reservations`) 추가. MVP는 기록과 알림만 | 추가 | [0029](../../decisions/0029-payment-reservation-reconciliation.md) | 반효승, 팀 |
| S-24 | 9-2, compose | Kafka-UI를 READONLY로 둔다(`KAFKA_CLUSTERS_0_READONLY: "true"`, 설정 이름은 `[가정]`이라 kafbat 문서와 실제 동작으로 확인). 관리 UI의 메시지 발행으로 이벤트를 위조하는 경로를 닫는다 | 추가 | 0020 | 팀(인프라) |
| S-25 | 12-1, 11-5 | 12-1의 "순번 PK를 밖에 노출하지 않는다"와 11-5의 `paymentId`(BIGINT) 노출이 어긋난다. 0021의 소유자 조건으로 정보 노출은 막고, 외부 식별자 분리는 후속으로 적는다 | 정정 | 0021 | 팀 |
| S-26 | 9-4 | Gateway 3초 안에 TX1·PG·TX2가 끝나지 않을 수 있음을 적고, 504 뒤에도 Payment 처리는 계속되며 같은 키 재시도로 결과를 받는다고 명시. Gateway에 POST 재시도 필터를 두지 않음 | 추가 | 0018, api.md | 팀 |
| S-27 | 11-5 결제 | `GET /payments/{id}`의 `APPROVED`는 예매 확정이 아니며 확정·티켓은 예매 조회로 확인한다고 명시 | 추가 | api.md | 팀 |
| S-28 | 11-5 Mock PG | Mock PG 승인·조회·환불 응답에 `amount`·`currency` 추가, 테스트 프로필 전용 원장 조회(주문별 승인 수, 결제별 환불 수·금액) | 추가 | 0023, [failure-scenarios.md](failure-scenarios.md) | 안준현 (10/28 역할 변경) |

## PR 방식
- SA 변경 요청 PR은 팀 규칙·SA 변경을 별도 PR로 리뷰하는 합의(통합 회의 D9)를 따른다.
- 영향이 큰 순서로 묶는 것을 제안한다: (1) 돈과 계약이 걸린 S-1·S-16·S-17·S-18·S-5·S-19, (2) API 동작 S-4·S-6·S-7·S-26·S-27, (3) 운영 S-8·S-22·S-23·S-24, (4) 나머지. 반효승이 소유한 계약(S-1·S-10·S-13·S-17·S-18·S-23)과 U9에 걸린 S-16은 해당 담당(S-16은 이강민) 리뷰를 받는다. S-28(Mock PG)은 10/28 역할 변경으로 결제 담당 안에서 처리한다.
- Notion 정본(SA MASTER)과 `docs/core/SA MASTER.md` 사본은 팀이 합의한 뒤 같은 방식으로 함께 고친다.
