# 0020. 환불 요청은 DB에 영속화해 자동 환불 워커가 처리하고, DLQ에 온 요청도 같은 경로로 복구한다

- 상태: 제안 (SA 변경 요청 필요. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0016](0016-payment-declined-token-reuse.md), [0022](0022-outbox-retry-and-retention.md), [0023](0023-payment-pg-client-port.md), [0027](0027-reservation-payment-event-contract.md), [0028](0028-payment-ops-commands.md), [0029](0029-payment-reservation-reconciliation.md)
- 불변 조건: INV-2, INV-4, INV-9([invariants.md](../design/payment/invariants.md))

## 맥락

SA 10-1·10-5는 Kafka 소비를 3회 재시도(1·2·4초)한 뒤 `{토픽}.dlq`로 보낸다. DLQ 처리기는 접수형 선점(`seat-hold.request`)에만 정의돼 있다. `payment.command`의 `RefundRequested`가 DLQ로 가면 처리기가 없어서, 좌석은 이미 풀렸는데 결제자는 돈을 돌려받지 못한 채 건이 Kafka에 남는다. 돈이 걸린 경로라 유실을 허용할 수 없다.

SA는 `NEEDS_MANUAL_CHECK`를 "종결 상태"로 쓰고 "운영자가 PG 콘솔에서 확인해 수동으로 닫는다"고 적는다(10-5). 환불 `FAILED`는 8-2에서 `REQUESTED`·`UNKNOWN` 뒤에 오는 상태이면서 10-5·12-4에서는 "PG가 명확히 거절하면 바로 `NEEDS_MANUAL_CHECK`로 보낸다"는 중간 상태로 쓰인다.

2026-10-27 원안은 DLQ에 온 환불 요청을 모두 곧바로 `NEEDS_MANUAL_CHECK`로 만들었다. 대부분의 DLQ 원인은 몇 분짜리 DB·네트워크 장애인데 사람이 매번 처리해야 한다. 또 문서마다 `FAILED`의 뜻이 달랐다(Opus 검토 C2·M8).

## 결정

### 상태의 의미
- 환불 `NEEDS_MANUAL_CHECK`는 실패 확정이 아니라 **운영자 확인이 필요한 상태**다. 자동 경로가 끝내지 못한 건(PG의 명확한 거절, 재시도 소진, 금액 불일치, 상태 충돌)이 여기에 온다.
- 환불 `FAILED`는 **운영자가 PG로는 환불할 수 없음을 확인하고 시스템 밖 조치(예: 계좌 환불)로 넘긴 종결 상태**다. Ops 명령으로만 들어가고 `manual_action_log`에 외부 조치 참조가 필수다([0028](0028-payment-ops-commands.md)). 자동 경로는 `FAILED`를 쓰지 않는다. PG의 명확한 거절(`RefundRejected`)은 바로 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`다.
- 상태 전이 전체는 [domain-and-states.md](../design/payment/domain-and-states.md) 2절이 기준이다.

### 환불 요청은 먼저 영속화한다
- `payment.command` 소비자는 `RefundRequested`를 검증하고 한 트랜잭션에서 `processed_event`, 환불 행(`REQUESTED`, `next_check_at` = 현재), 이력을 쓴다. **PG 환불은 부르지 않는다.**
- 환불 행 생성은 결제 상태를 조건으로 한 `INSERT … SELECT … FROM payment WHERE status = 'APPROVED' … ON CONFLICT (payment_id) DO NOTHING`이다. 환불 금액은 이벤트 값이 아니라 결제의 PG 승인 금액이다(INV-3).
- PG 환불은 **환불 워커** 한 곳에서만 부른다. 워커는 `REQUESTED`·`UNKNOWN` 환불을 `SKIP LOCKED`로 집고, `pg-recovery` 허가를 얻은 뒤 claim(임대 30초, 시도 + 1)하고 트랜잭션 밖에서 PG를 부른다. 첫 호출이 아니면 `findRefund`로 먼저 확인하고 `NotFound`일 때만 같은 `pg_refund_id`로 다시 부른다(0023, 검토 H5·H6).
- 결과: `Refunded`(금액 일치)면 환불·결제 `REFUNDED`와 `PaymentRefunded`, `RefundRejected`면 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`, `Unknown`이면 다음 간격.
- 검토 H6의 "환불 행을 새로 만든 경우에만 PG를 부른다"는 이 구조로 지켜진다. 소비자·DLQ 처리기·Ops 명령은 PG를 부르지 않고, 워커는 claim에 성공한 행만 부른다. 이중 환불 방어는 환불 행 UNIQUE, claim, 재호출 전 조회, PG 멱등키의 네 겹이다.

### 교차 검증
`RefundRequested`의 `paymentId`·`reservationId`·`userId`·`amount`·`currency`·`reason`과 결제 상태를 결제 행과 대조한다([events-and-reliability.md](../design/payment/events-and-reliability.md) 4절). 하나라도 어긋나면 영구 오류로 보고 재시도 없이 DLQ로 보낸다. 이벤트 위조(0008 T5)를 막는 장치이기도 하다. 위조 경로를 줄이려고 Kafka-UI를 READONLY로 둔다(S-24).

### DLQ 처리기
- `payment.command.dlq`를 소비하는 DLQ 처리기(그룹 `payment.command-dlq`)는 한 트랜잭션에서 `dead_letter` 저장(`event_key` UNIQUE로 멱등), 같은 검증, 아래 처리, `processed_event` 기록을 한다.

  | 메시지 | 처리 | `dead_letter` |
  |---|---|---|
  | 검증 통과, 환불 행 없음 | 환불 `REQUESTED` 생성 → 환불 워커가 자동 복구 | `RESOLVED(AUTO_PERSISTED)` |
  | 검증 통과, 환불 행 있음 | 변경 없음 | `RESOLVED(DUPLICATE)` |
  | 검증 실패, 역직렬화 실패 | 환불 행을 만들 근거가 없음 | `OPEN` + 즉시 알림 |

- `event_key`는 `eventId`가 있으면 그 값, 없으면(역직렬화 실패) `topic-partition-offset`이다.
- **영속화 실패는 처리 완료가 아니다**: DB 기록이 실패하면 오프셋을 커밋하지 않고 지수 백오프(최대 60초)로 횟수 제한 없이 다시 처리한다. 메시지 내용 문제로는 예외를 던지지 않으므로 메시지 하나가 파티션을 영구히 막지 않는다. `.dlq` 토픽 보관은 7일 이상으로 한다`[가정]`.
- 결과적으로 일시적 DB·네트워크 장애는 사람 없이 복구되고, `NEEDS_MANUAL_CHECK`·`dead_letter` `OPEN`은 명확한 영구 오류와 장기 미확정에만 남는다.

### 자동 복구의 한계
- 환불 워커는 SA 9-4의 간격(10초×6회 → 1분 → 5분 → 15분)으로 PG를 부르고, 실제로 부른 9번째 호출도 확정하지 못하면 `NEEDS_MANUAL_CHECK(REFUND_UNRESOLVED)`다(약 21분).
- `pg-recovery` CB OPEN으로 부르지 못한 주기는 세지 않는다. PG 전체 장애 동안 환불은 `REQUESTED`·`UNKNOWN`으로 남고 환불 대기 최고령 알림(경고 30분, 심각 2시간`[가정]`)이 본다.
- 운영자는 원인이 풀리면 Ops `refund retry`로 `NEEDS_MANUAL_CHECK`를 `REQUESTED`로 되돌려 자동 경로에 다시 맡긴다.

### 수동 복구와 감사
- 상태를 바꾸는 직접 SQL은 쓰지 않고 Ops 명령으로만 복구한다(0028). MVP에 관리자 재처리 HTTP API는 만들지 않는다.
- 감사 기록은 append-only `manual_action_log`에 남긴다: 명령, 대상, 전이, 결과, PG 근거, 외부 조치 참조, 실행자·승인자, 시각, 메모.

### 알림
- DLQ 처리기 실패(재시도 중인 건, DLQ 그룹 랙), `dead_letter` `OPEN` 건수와 최고령.
- `NEEDS_MANUAL_CHECK` 건수와 가장 오래된 건의 경과 시간. 장기 미해결 기준은 경고 30분, 심각 24시간`[가정]`이다.
- 환불 대기(`REQUESTED`·`UNKNOWN`) 최고령. 값은 측정·운영 경험 뒤 조정한다([observability.md](../design/payment/observability.md)).

## 환불 요청이 DB에 남지 못하고 유실될 수 있는 경로 점검

| 경로 | 유실 가능성 | 대응 |
|---|---|---|
| Reservation의 RefundRequested 발행 | 상태 변경과 outbox를 한 트랜잭션에 저장하므로 발행 유실은 없다. 발행 자체를 하지 못하는 경우(Reservation 소비 실패)는 남는다 | Outbox는 영구 오류만 `FAILED`로 두고 알린다(0022). 발행되지 않은 의무는 대사 RC-2가 찾는다(0029) |
| 릴레이 → Kafka | `acks=all`, idempotence. 전송 뒤 `SENT` 표시 전에 죽으면 중복 | 소비자가 멱등이라 중복은 무해하다 |
| Payment 소비 중 오류 | 오프셋을 영속화 뒤에 커밋(`ack-mode=record`)하므로 없다. 3회 재시도 뒤 DLQ | 위 DLQ 처리기 |
| DLQ 토픽으로 보내기 실패(Kafka 장애) | 보내지 못하면 원 메시지도 커밋하지 않고 다시 처리한다 | 복구 설정이 실패해도 오프셋을 올리지 않는지 통합 테스트로 확인한다 |
| 역직렬화 실패(poison message) | `ErrorHandlingDeserializer` 없이는 컨슈머가 같은 레코드에서 멈춘다 | `ErrorHandlingDeserializer`로 DLQ에 보내고 원문을 `dead_letter`에 `OPEN`으로 저장한다 |
| DLQ 처리기의 DB 장애 | 오프셋 미커밋으로 메시지가 남는다 | 백오프 무한 재시도, 처리기 실패 알림 |
| 토픽 보관 기간 초과 | 컨슈머가 7일 넘게 멈추면 메시지가 사라질 수 있다 | 대사 RC-2가 결제 `APPROVED` + 예매 비확정 + 환불 행 없음으로 찾는다(0029) |
| 환불 행 생성 뒤 PG 호출 전 중단 | 환불 행이 `REQUESTED`로 남는다 | 환불 워커가 이어받는다 |

## 검토한 대안

- **DLQ 도착 시 곧바로 `NEEDS_MANUAL_CHECK`(2026-10-27 원안)**: 처리기가 단순하고 모든 건을 사람이 본다. 일시 장애로 DLQ에 온 정상 요청까지 사람이 처리해야 하고 복구가 느리다. 사용자가 2026-10-28에 자동 복구로 바꿨다.
- **소비자가 PG 환불까지 호출(원안)**: 환불이 빠르다. PG 지연 동안 Kafka 파티션을 잡고, 소비 재시도·DLQ 재처리·스케줄러가 서로 다른 경로로 PG를 불러 중복 호출 경로가 늘어난다.
- **관리자 재처리 API**: 원인을 고친 뒤 DLQ 메시지를 원 토픽으로 다시 보낸다. API·권한·감사 범위가 늘어 MVP에서 제외한다. 같은 일은 Ops `dead-letter resolve --replay`가 한다(0028).
- **알림만 두고 Kafka-UI로 수동 재전송**: 구현이 가장 작다. DB에 상태가 없어 환불 누락을 찾을 기준이 없고 감사 기록도 남지 않는다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 먼저 영속화, PG는 워커 한 곳 | 환불 의무가 DB에 먼저 남고, PG 호출 경로가 하나라 중복 호출을 통제하기 쉽다 | 소비부터 환불까지 워커 폴링 간격만큼 늦다 |
| DLQ 자동 복구 | 일시 장애는 사람 없이 끝난다. 사람은 영구 오류만 본다 | 처리기가 검증 로직을 소비자와 공유해야 한다 |
| 무한 재시도(DB 장애) | 영속화 전에는 메시지를 버리지 않는다 | 장기 DB 장애 동안 DLQ 랙이 쌓인다(알림) |
| `FAILED`를 운영자 종결로 한정 | 문서마다 다르던 뜻이 하나가 되고, 외부 조치 근거 없이 닫히지 않는다 | SA 8-2·10-5·12-4의 서술을 바꿔야 한다(S-5) |
| `dead_letter`·`manual_action_log` 추가 | 원문 보존과 감사가 가능하다 | 테이블 둘과 보관 정책이 늘어난다 |

## 결과

- SA 변경 요청: S-5(상태 의미와 전이), S-8(DLQ 처리기와 테이블), S-19(영속화와 워커), S-24(Kafka-UI READONLY).
- `data-model.md`의 `refund`(`source`, `pg_requested_at` 등)·`dead_letter`·`manual_action_log`, `events-and-reliability.md` 4~6절, `manual-recovery.md` 2·3절.
- 반효승: `RefundRequested`에 `userId`·`amount`·`currency`를 넣는다(0027, S-18).
- 테스트: N7, N8, N19, N20, N28, E13, FT-1·FT-4·FT-5.

## 개정 이력

- 2026-10-28: 사용자 결정 2와 즉시 수정 지시를 반영해 다시 썼다. 이전 제목은 "환불 요청이 DLQ로 가면 DLQ 처리기가 운영자 확인 상태로 전환한다"였다. (1) DLQ에 온 요청을 곧바로 `NEEDS_MANUAL_CHECK`로 보내지 않고 환불 행으로 영속화해 자동 환불 워커가 복구하게 했다(M8). (2) 소비자는 PG를 부르지 않고 영속화만 한다. (3) 환불 `FAILED`를 운영자 종결로만 정의하고 PG 거절은 바로 `NEEDS_MANUAL_CHECK`로 했다(C2). (4) 원안 본문의 "`dead_letter.event_id`가 UNIQUE"를 `event_key`로 바로잡았다(L6). (5) `RefundRequested` 교차 검증과 Kafka-UI READONLY를 더했다(M7). (6) 수동 복구를 SQL 템플릿에서 Ops 명령으로 바꿨다(0028). (7) "토픽 보관 기간 초과" 잔여 위험을 대사(0029)가 맡는다.

## 면접에서 설명할 한 줄

환불 요청은 PG를 부르기 전에 DB에 먼저 남기고 PG 호출은 워커 한 곳에서만 하게 해서, 메시지 큐 장애가 환불 의무를 지우지 못하게 했고, 일시 장애는 자동으로 복구하되 PG가 명확히 거절하거나 오래 확정하지 못한 건만 사람이 보게 했다.
