# 결제(payment) 상세 설계

결제 서비스의 상세 설계를 둔다. 기준은 [SA MASTER v3.2](../../core/SA%20MASTER.md)이고, 설계 중 SA와 달라지는 부분은 [SA 변경 요청](sa-change-requests.md)으로 모아 별도 PR로 올린다. 각 결정의 고려 사항과 트레이드오프는 [결정 기록](../../decisions/README.md)에 있고 이 문서들은 결정 결과를 구현 수준으로 푼다.

상태: 제안(2026-10-27 작성, 2026-10-28 검토 지적과 사용자 결정 반영). 팀 확인이 필요한 항목은 각 문서와 SA 변경 요청에 표시했다. 구현 전이며 코드는 아직 없다.

설계 원칙: **금전적 의무가 추적 없이 사라지지 않는다.** 불변 조건을 먼저 정하고, 장치마다 서로 다른 실패를 맡게 했다([invariants.md](invariants.md)).

## 읽는 순서

| 문서 | 내용 |
|---|---|
| [invariants.md](invariants.md) | 불변 조건 INV-1~INV-9, 조건별 방어 수단과 깨지는 경우, 단일 방어 점검 |
| [domain-and-states.md](domain-and-states.md) | 결제·환불 상태 머신, 전이별 이벤트, 사유 코드, 전이 공통 규칙, 계층 구조 |
| [api.md](api.md) | 승인·조회 API, 처리 순서, 상태별 응답, 토큰 검증, 에러 코드, 접근 제어 |
| [data-model.md](data-model.md) | 테이블, UNIQUE·CHECK 제약, append-only 트리거, 인덱스, 마이그레이션 순서 |
| [events-and-reliability.md](events-and-reliability.md) | 이벤트, Outbox, 환불 요청 영속화, DLQ, 재확인·환불 워커, 실행기 분리, 정리, 대사 |
| [pg-integration.md](pg-integration.md) | `PgClient` 포트, 결과 타입과 금액 검증, 호출 규칙, 호출 자원 그룹, 토스 확인 항목 |
| [mock-pg.md](mock-pg.md) | Mock PG 서버 설계: 원장, 멱등, 승인·망취소 원자성, 장애 주입, 프로필 제한, PR 분할 |
| [failure-scenarios.md](failure-scenarios.md) | 실패 시나리오, 장애 유형별 통합 테스트와 필수 검증(V1~V3) |
| [observability.md](observability.md) | 지표, 알림, 대시보드 |
| [manual-recovery.md](manual-recovery.md) | Ops 명령, 알림별 원인 확인과 복구, 긴급 콘솔 환불, 키 유출 대응 |
| [contracts.md](contracts.md) | 결제 토큰 claim(ISE-22)과 `payment.result` 이벤트(ISE-23) 계약, Reservation 확인 요청 |
| [sa-change-requests.md](sa-change-requests.md) | SA 변경 요청 목록(S-1~S-30) |

## 결정 기록

| ADR | 결정 | 출처 |
|---|---|---|
| [0016](../../decisions/0016-payment-declined-token-reuse.md) | 거절·환불된 예매의 결제 토큰 재사용을 Payment와 Reservation이 함께 막는다(10/28 개정: 경합은 0026, 예매 규칙은 0027) | 빈틈 #1, C1·H1 |
| [0017](../../decisions/0017-payment-idempotency-in-progress.md) | 같은 멱등키가 처리 중이면 202와 기존 paymentId, TX1 유니크 위반은 재조회 응답 | 빈틈 #3, M1·L6 |
| [0018](../../decisions/0018-payment-pg-permit-before-tx1.md) | 신규 승인 시도만 허가를 TX1 앞에서 얻고, PG 호출 자원을 승인·복구 두 그룹으로 나눈다 | 빈틈 #4, M2·M3·L9 |
| [0019](../../decisions/0019-payment-new-key-existing-payment.md) | 다른 멱등키로 같은 예매를 재요청하면 기존 결제로 응답하고 새 키는 ALIAS로 기록 | 빈틈 #5, L3 |
| [0020](../../decisions/0020-payment-refund-dlq-manual-check.md) | 환불 요청은 DB에 영속화해 자동 환불 워커가 처리하고, DLQ도 같은 경로로 복구 | 빈틈 #6, C2·H5·M7·M8·L6 |
| [0021](../../decisions/0021-payment-object-level-authorization.md) | 결제 조회는 소유자 또는 ADMIN, 타인은 404 | 빈틈 #7 |
| [0022](../../decisions/0022-outbox-retry-and-retention.md) | Outbox 지속 재시도와 배치 정리, 영향 행 1일 때만 후속 쓰기, 실행기 분리 | 빈틈 #8, H3·M4·L5 |
| [0023](../../decisions/0023-payment-pg-client-port.md) | `PgClient` 포트와 계약 테스트, 결과의 금액·통화 검증 | 빈틈 #9, H2·H5·H6·M5·M9 |
| [0024](../../decisions/0024-payment-observability-metrics.md) | 핵심 지표·대시보드 1장·알림 | 빈틈 #10, H4·L6 |
| [0025](../../decisions/0025-payment-token-secret-injection.md) | 결제 토큰 키 주입과 시작 검증, 키 바이트 해석 통일 | 빈틈 #11, L4 |
| [0026](../../decisions/0026-payment-one-per-reservation.md) | 결제 행은 예매당 하나인 논리적 결제, `reservation_id` 전체 UNIQUE | 사용자 결정 1, H1 |
| [0027](../../decisions/0027-reservation-payment-event-contract.md) | Reservation은 결제 이벤트를 예매 원장과 대조해 상태별 규칙으로 처리 | 사용자 결정 4, C1·H2 |
| [0028](../../decisions/0028-payment-ops-commands.md) | 운영자 복구는 감사되는 Ops 명령으로만, 직접 SQL 금지 | 사용자 결정 5, H6·H7 |
| [0029](../../decisions/0029-payment-reservation-reconciliation.md) | 결제·예매 대사는 Payment가 예매 내부 조회 API로, MVP는 기록과 알림만 | 사용자 결정 6, H8 |

빈틈 #2(D3 부분 유니크 인덱스에 `NEEDS_MANUAL_CHECK` 누락)는 0026의 전체 UNIQUE로 대체됐다. 출처 칸의 C·H·M·L 번호는 2026-10-27 설계에 대한 Opus 검토 지적이다([AI 판단 기록](../../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)).

## 이번 설계가 의존하는 다른 담당의 일

| 담당 | 필요한 일 | 근거 |
|---|---|---|
| 반효승 | 결제 토큰 claim 이름 통보(U1, 구현은 [api.md](api.md) 구현 메모의 가정 값으로 먼저 만들었다). 결제 이벤트 처리 계약(원장 대조, 상태별 규칙, `CONFIRMED` + `PaymentRefunded` 격리). `RefundRequested`의 `userId`·`amount`·`currency`와 새 사유. `payment.result` 소비의 DLQ 처리기. 대사용 읽기 전용 내부 조회 API. 결제 토큰 발급기는 `TokenDecoders.signingKey`로 키 바이트를 만든다 | 0022, 0025, 0027, 0029 |
| 이강민 | U9(추첨 결제 실패 유형별 재시도)가 같은 예매 재결제를 요구하는지 확인 | 0026 |
| 팀 전체 | SA 변경 요청 리뷰, U22(환불 이력 위치), Kafka-UI READONLY, `payment-service` 연결 풀 설정 | [sa-change-requests.md](sa-change-requests.md) |

Mock PG 환불 조회·환불 멱등·망취소와 경로 확정(U16), 장애 주입 모드, 응답의 `amount`·`currency`, 테스트 전용 원장 조회는 10/28 역할 변경으로 결제 담당이 함께 구현한다(0023).
