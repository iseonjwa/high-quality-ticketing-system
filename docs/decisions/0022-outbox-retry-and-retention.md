# 0022. Outbox는 지속 재시도하고, 실제로 바뀐 전이만 이벤트를 만들며, 정리는 미처리 건을 지우지 않는 배치로 한다

- 상태: 제안 (SA 변경 요청 필요. 2026-10-28 개정)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0017](0017-payment-idempotency-in-progress.md), [0018](0018-payment-pg-permit-before-tx1.md), [0020](0020-payment-refund-dlq-manual-check.md), [0026](0026-payment-one-per-reservation.md), [0029](0029-payment-reservation-reconciliation.md)
- 불변 조건: INV-2, INV-5([invariants.md](../design/payment/invariants.md))

## 맥락

SA 12-5는 `outbox_events`(`PENDING·SENT·FAILED`, `attempts`), `processed_events`, `idempotency_records`를 정의하지만 정리 규칙이 없다. 릴레이의 재시도 한계와 `FAILED` 처리도 없다. 부하 테스트에서는 세 테이블이 빠르게 커지고, 반대로 실패한 이벤트를 버리면 결제 결과가 예매에 전달되지 않아 "돈은 냈는데 예매는 확정되지 않는" 상태가 된다.

2026-10-28 검토에서 세 가지가 더 나왔다. 조건부 UPDATE가 0행인데 이력·Outbox를 쓰면 경합에서 진 쪽이 중복 이벤트를 만든다(H3). Spring 기본 스케줄러는 스레드가 1개라 PG 재조회가 느려지면 릴레이도 멈춘다(M4). 릴레이 임대(30초)가 프로듀서 전송 시한(기본 `delivery.timeout.ms` 120초)보다 짧으면 아직 재시도 중인 전송과 새 전송이 겹친다(L5).

## 결정

### 1. 실제로 바뀐 전이만 후속 쓰기를 한다
- 상태 전이는 조건부 UPDATE이고, **영향 행이 1일 때만** 같은 트랜잭션에서 이력·Outbox·멱등 응답·감사 기록(APPLIED)을 쓴다. 0행이면 후속 쓰기를 하지 않는다.
- 적용 범위는 승인 API TX2, 재확인 스케줄러, 환불 워커, `payment.command` 소비자, DLQ 처리기, Ops 명령 전부다. 경로별 0행 처리는 [events-and-reliability.md](../design/payment/events-and-reliability.md) 2절이 기준이다.
- 상태는 엔티티 변경 감지로 바꾸지 않고, 영향 행 수를 돌려주는 저장소 메서드로만 바꾼다. 0행은 `payment_transition_conflict_total`로 센다.
- 이 규칙이 빠져도 대사 RC-7(상태·이력 불일치)·RC-8(확정 전이 이벤트 누락)이 찾는다([0029](0029-payment-reservation-reconciliation.md)).

### 2. Outbox 릴레이
- **발행 성공은 Kafka 전송 확인으로만 판단한다.** `acks=all`로 보내고 브로커 확인을 기다린 뒤에 `SENT`로 표시한다.
- **발행 중 서버가 중단돼도 다시 처리한다.** 릴레이는 행을 `claimed_until`(임대 만료 시각, 30초`[가정]`)로 잡은 뒤 DB 트랜잭션 밖에서 전송하고, 확인을 받으면 `SENT`로 바꾼다. 전송 뒤 표시 전에 죽으면 임대가 끝난 뒤 다시 집어 중복 전송된다. 소비자는 `processed_events`와 상태 전이로 중복을 견딘다(SA 10-4).
- **임대는 전송 시한의 2배 이상**이다. 릴레이용 프로듀서는 `delivery.timeout.ms=10000`, `request.timeout.ms=5000`, `linger.ms=0`(값은 `[가정]`)로 두고 전송 대기는 `delivery.timeout.ms` + 2초까지다. 시한을 넘기면 일시 오류로 처리한다.
- 일시적 오류(타임아웃, 브로커 불가, 리더 변경)는 **지수 백오프로 계속 재시도**한다. 간격은 500ms에서 시작해 두 배씩 늘리고 최대 60초`[가정]`로 제한하며 Jitter를 적용한다(`next_attempt_at`에 기록). 횟수 한계로 포기하지 않는다. `attempts`는 모니터링용이다.
- **복구 불가능한 영구 오류**(직렬화 실패, 레코드 크기 초과, 잘못된 토픽 등)만 `FAILED`로 바꾸고 `last_error`를 남기며 알림을 낸다. 복구는 Ops `outbox requeue`다.
- 같은 `message_key`(reservationId)의 이벤트는 순서를 지킨다. 같은 키에 앞선 미발행 행이 있으면 뒤 행은 집지 않는다(키 단위 대기). 소비자의 조건부 UPDATE가 순서 뒤바뀜을 견디므로 이중 방어다.

### 3. 실행기 분리
릴레이, 재확인·환불 워커, 정리·Gauge 갱신·대사를 서로 다른 실행기에서 돌린다(릴레이 1, 복구 2`[가정]`, 정리 1 스레드). PG 재조회가 느려져도 릴레이가 늦어지지 않아 승인 결과가 선점 만료 전에 예매에 닿는다. 작업마다 마지막 성공 시각과 실패 수를 지표로 내고 정체를 알린다. 배경 작업이 동시에 잡는 DB 연결은 최대 6개이고 풀은 SA 9-4의 20이다([events-and-reliability.md](../design/payment/events-and-reliability.md) 7절).

### 4. 자동 삭제하지 않는 것
`PENDING`, 재시도 대기 중(`next_attempt_at`이 미래), `FAILED` outbox는 자동으로 지우지 않는다. 재시도 가능한 이벤트가 오래 남는 것도 모니터링한다.

### 5. 정리 보관 기간

| 테이블 | 삭제 조건 | 초기값 |
|---|---|---|
| `outbox_events` | `SENT`이고 `sent_at` 경과 | 7일 |
| `processed_events` | `processed_at` 경과 | 14일 |
| `idempotency_records` | `expires_at` 경과 + 아래 조건 | 24시간(SA 9-4) |

- `processed_events` 보존 기간은 Kafka 토픽 보관(7일`[가정]`), DLQ 보관(7일`[가정]`), 수동 재처리 기간보다 충분히 길어야 한다. 시작할 때 설정값(토픽·DLQ 보관, `processed_events` 보존)을 비교해 보존 기간이 모자라면 기동을 실패시킨다.
- `idempotency_records`는 만료 뒤에도 **연결된 결제(`resource_id`, 0017)가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`이면 지우지 않는다.** ORIGIN·ALIAS 모두 같다.

### 6. 정리 작업 방식
- PostgreSQL 기반 작업 하나가 작은 배치(예: 1,000건) 단위로 `DELETE`한다. 배치마다 별도 트랜잭션이고 배치 사이에 잠시 쉬어 부하를 줄인다.
- **중복 실행 방지**: 정리 시작 때 `pg_try_advisory_lock`을 잡는다. 못 잡으면 건너뛴다(서비스를 여러 대로 늘려도 안전).
- **실패 재시도**: 배치가 실패하면 로그·지표를 남기고 다음 주기(예: 10분`[가정]`)에 다시 한다. 삭제는 조건이 같으면 반복해도 같은 결과(멱등)다.
- 파티셔닝은 MVP에 쓰지 않는다. 데이터량과 성능 지표를 보고 나중에 검토한다.

### 7. `SENT`의 의미와 소비 쪽 복구
`SENT`는 **Kafka 발행 성공만 의미한다.** Reservation 소비자의 처리 실패와 DLQ 복구는 Reservation이 따로 관리한다. 특히 `payment.result`의 `PaymentApproved`가 `reservation.payment-result` DLQ로 가면 돈은 받았는데 예매를 확정하지 못하므로 Reservation에 DLQ 처리기(예: 예매 상태 확인 후 `RefundRequested` 발행 또는 운영자 확인)가 필요하다. 반효승에게 통보하는 계약 항목이다. 처리기가 없거나 실패해도 대사 RC-1·RC-2가 찾는다.

### 8. 오래된 이벤트 재전달에도 중복이 없게 유지
`processed_events`가 지워진 뒤에 오래된 이벤트가 다시 와도 업무 규칙이 막는다.
- 상태 전이는 `WHERE status = 이전상태` 조건부 UPDATE이고 영향 행 1일 때만 후속 쓰기를 한다(1절).
- 같은 예매의 중복 결제는 `UNIQUE(reservation_id)` 전체가 막는다([0026](0026-payment-one-per-reservation.md)).
- 환불은 `UNIQUE(payment_id)`와 결제 상태 조건이 있는 `INSERT … SELECT … ON CONFLICT DO NOTHING`, 재호출 전 조회, PG 환불 멱등키 `pg_refund_id`가 막는다(0020).
- 결제 승인 호출은 결제 행당 한 번이고 PG 멱등키 `pg_order_id`가 한 번 더 막는다.
- 예매 쪽은 paymentId 대조가 확정 티켓의 환불을 막는다([0027](0027-reservation-payment-event-contract.md)).

### 9. 알림과 지표
- `FAILED` outbox 건수
- 가장 오래된 `PENDING`의 경과 시간: 경고 5분, 심각 30분`[가정]`
- 재시도 중인 건수와 최대 `attempts`
- 작업별 마지막 성공 시각과 실패 수(릴레이, 재확인, 환불 워커, 정리, Gauge 갱신, 대사)
- 조건부 UPDATE 0행 수
- 테이블별 행 수

## 검토한 대안

- **파티셔닝 후 파티션 DROP**: 대량 삭제 비용과 블로트가 없다. `UNIQUE(event_id)` 같은 제약이 파티션 키와 맞아야 하고 관리가 복잡해 MVP에는 과하다. 데이터량이 문제로 확인되면 다시 검토한다.
- **MVP에서는 정리 없이 지표만**: 가장 단순하다. 부하 테스트마다 수동 정리가 필요하고 시연 중 성능에 영향을 줄 수 있다.
- **횟수 한계 뒤 `FAILED`**: 일시 장애가 길어지면 정상 이벤트가 `FAILED`가 돼 수동 복구가 늘어난다. 일시 오류와 영구 오류를 나누는 쪽이 운영 부담이 작다.
- **스케줄러 스레드 수만 늘리기**: 설정 한 줄이다. 작업끼리 스레드를 나눠 쓰므로 느린 작업이 여러 스레드를 차지하면 여전히 릴레이가 밀린다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 영향 행 1일 때만 후속 쓰기 | 경합에서 진 쪽이 중복 이벤트·이력을 만들지 않는다 | 모든 전이 코드가 행 수를 확인해야 한다 |
| 지속 재시도 | Kafka 장애가 길어도 결과가 유실되지 않고 복구되면 자동 발행된다 | 장기 장애에서 `PENDING`이 쌓인다(알림으로 감지) |
| 임대 기반 릴레이, 임대 ≥ 2 × 전송 시한 | 발행 중 중단돼도 재처리되고, 재시도 중인 전송과 겹치지 않는다 | 중복 전송이 생길 수 있어 소비 쪽 멱등이 필수다. 전송 시한을 줄여 일시 오류 판정이 빨라진다 |
| 실행기 분리 | 느린 PG가 이벤트 발행을 막지 않는다 | 스레드·설정·지표가 늘어난다 |
| 키 단위 순서 보존 | 같은 예매의 이벤트 순서가 유지된다 | 한 행이 막히면 같은 키의 뒤 행이 기다린다 |
| 배치 삭제 | 구현이 단순하고 PostgreSQL 기능만 쓴다 | 대량 삭제 때 블로트와 vacuum 부담이 있다 |
| 14일 보존 | 재전달 방어 창이 길다 | 테이블이 더 커진다. 14일을 넘긴 재전달은 업무 규칙에 의존한다 |

## 결과

- SA 변경 요청: S-10(outbox 컬럼, 재시도·보관 값, 임대와 전송 시한, `SENT` 의미, Reservation DLQ 처리기), S-21(실행기 분리와 연결 풀).
- `events-and-reliability.md`에 공통 규칙·릴레이·실행기·정리를, `data-model.md`에 컬럼·인덱스를, `manual-recovery.md`에 `FAILED` outbox 복구(Ops `outbox requeue`)를 쓴다.
- 반효승에게 통보: `payment.result` 소비 DLQ 처리기와 `SENT` 의미.
- 테스트: 전송 확인 전 중단 뒤 재처리, 중복 전송 시 소비 멱등, 일시 오류 백오프와 최대 간격·Jitter, 영구 오류 `FAILED`, 같은 키 순서 보존, 정리 배치(조건·크기·중복 실행 방지), 멱등 기록 삭제 제외 조건, 14일 뒤 재전달 시 업무 규칙 방어(N11), 0행 경합(N17), 실행기 분리(N26).

## 개정 이력

- 2026-10-28: (1) 조건부 UPDATE 영향 행이 1일 때만 후속 쓰기를 하는 규칙을 1절로 넣었다(검토 H3, 사용자 지정 수정). (2) 실행기 분리(M4)와 작업별 마지막 성공 지표를 넣었다. (3) 임대와 프로듀서 전송 시한의 관계를 정했다(L5). (4) 재전달 방어를 전체 UNIQUE(0026), 환불 `INSERT … SELECT`, 재호출 전 조회, 예매 paymentId 대조로 갱신했다. 제목을 바꿨다(이전: "Outbox는 지속 재시도하고, 정리는 배치 삭제로 하되 미처리 건은 지우지 않는다").

## 면접에서 설명할 한 줄

Outbox를 "브로커 확인을 받아야 발행 성공"으로 정의하고 일시 오류는 끝까지 재시도하되 영구 오류만 사람에게 넘겼으며, 조건부 UPDATE가 실제로 한 행을 바꿨을 때만 이벤트를 만들게 해 경합에서 진 쪽의 중복 이벤트를 막았고, 느린 PG 재조회가 이벤트 발행을 막지 않게 실행기를 나눴다.
