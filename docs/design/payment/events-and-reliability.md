# 이벤트와 신뢰성

결제 결과는 Outbox로 발행하고, 소비는 처리 이력과 상태 전이로 멱등하게 하며, 환불 요청은 PG를 부르기 전에 DB에 영속화한다. 끝내 확정하지 못한 건은 DB 상태로 남겨 재확인 스케줄러·환불 워커·운영자가 이어받고, 서비스 사이 어긋남은 대사가 찾는다. 토픽·봉투는 SA 10장을 따르고 여기에는 구현 규칙만 둔다. 지키는 조건은 [invariants.md](invariants.md)의 INV-2·INV-4·INV-5·INV-6이다.

## 1. 발행과 구독

| 토픽 | 이벤트 | 키 | 이 서비스의 역할 |
|---|---|---|---|
| `payment.result` | `PaymentApproved`·`PaymentDeclined`·`PaymentRefunded` | reservationId | 발행(Outbox). Reservation 그룹 `reservation.payment-result`의 처리 규칙은 [0027](../../decisions/0027-reservation-payment-event-contract.md) |
| `payment.command` | `RefundRequested`(MVP)·`BillingPaymentRequested`(M4) | reservationId | 구독, 그룹 `payment.command` |
| `payment.command.dlq` | 소비 실패 | 원본 키 | 구독, 그룹 `payment.command-dlq`(DLQ 처리기) |

페이로드 변경([SA 변경 요청](sa-change-requests.md) S-18, 0027): `PaymentApproved`·`PaymentRefunded`에 `currency`(`"KRW"`)를 더하고, `RefundRequested`에 `userId`·`amount`·`currency`를 더하며 `reason`에 `DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE`을 추가한다. SA 10-4는 필드 추가만 허용하므로 `version`은 그대로 1이다.

구현 위치(ISE-24): 일반 부품(Outbox 쓰기·릴레이·Kafka 전송, `processed_events` 처리 이력)은 `common`의 `com.iseonjwa.common.messaging.outbox`·`inbox` 패키지에 있고 서비스가 `CommonOutboxConfiguration`을 `@Import`한다([common-modules.md](../../guides/common-modules.md)). 결제 이벤트 페이로드와 발행기(`PaymentResultPublisher`)는 `Payment`에 의존하므로 `payment-service`에 둔다. 저장소는 JPA 엔티티 없이 `JdbcTemplate`으로 쓰며 JPA 트랜잭션의 같은 연결을 쓴다. ISE-24에는 임대·키별 순서·SENT 기록까지 넣었고, 지수 백오프·`FAILED`·정리·실행기 분리는 ISE-40이다. 발행 실패 시 지금은 임대(30초)가 끝나면 다시 시도한다. 한 번의 릴레이 실행은 전송 확인을 최대 전송 시한(12초)까지 기다리고(프로듀서의 `max.block.ms`·`delivery.timeout.ms`가 이 안에 들어오는지 기동 때 검증), 마감을 넘긴 행은 임대를 바로 푼다. 실제 소요는 시한에 `send` 호출이 막히는 시간(`max.block.ms`)과 후처리 쓰기가 더해진다.

## 2. 공통 규칙: 실제로 바뀐 전이만 후속 쓰기를 한다

조건부 UPDATE의 영향 행이 1일 때만 이력·Outbox·멱등 응답·감사 기록(APPLIED)을 같은 트랜잭션에 쓴다(INV-5, [0022](../../decisions/0022-outbox-retry-and-retention.md)).

| 경로 | 0행일 때 |
|---|---|
| 승인 API TX2 | 후속 쓰기 없음. 현재 상태를 다시 읽어 응답 |
| 재확인 스케줄러·환불 워커 | 후속 쓰기 없음. 건너뜀 |
| `payment.command` 소비자·DLQ 처리기 | 업무 쓰기 없음. `processed_events`·`dead_letters`만 기록 |
| Ops 명령 | `manual_action_logs`에 REJECTED만 남기고 실패로 끝남 |

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
2. 통과하면 한 트랜잭션에서 `processed_events(event_id, 'payment.command')`, 환불 `INSERT … SELECT … ON CONFLICT (payment_id) DO NOTHING`(`REQUESTED`, `next_check_at` = 현재, `source=EVENT`), 이력을 쓴다([data-model.md](data-model.md) 4절). 0행이고 환불 행이 이미 있으면 중복이라 `processed_events`만 남긴다.
3. 검증 실패와 역직렬화 실패는 영구 오류다. 재시도하지 않고 바로 DLQ로 보낸다(재시도 제외 예외로 등록).
4. 일시 오류(DB 연결, 잠금 시간 초과)는 1·2·4초 간격 3회 재시도 뒤 DLQ로 보낸다.
5. 오프셋은 처리 성공 뒤에 커밋한다(`ack-mode=record`). DLQ로 보내는 것이 실패하면 원 메시지의 오프셋을 올리지 않는다. 깨진 메시지는 리스너가 읽다가 영구 오류로 DLQ에 보내 원문을 보존한다(아래 구현 메모).

### 구현 메모 (ISE-136)

- 위치: 환불은 결제와 다른 집합이라 `com.iseonjwa.payment.refund`(domain·application·infrastructure)에 둔다. 소비자는 `refund.infrastructure.messaging`, 교차 검증과 영속화는 `refund.application.RefundRequestService`다. 환불 상태 이력은 새 테이블 없이 `payment_status_histories`에 `target_type=REFUND`로 쓴다. 이 테이블은 환불에만 있는 `FAILED`를 담도록 상태 컬럼을 열거형이 아닌 문자열로 매핑한다.
- 리스너가 봉투를 읽는다: 값 역직렬화기가 `StringDeserializer`(`config-repo/application.yml`)라 깨진 JSON도 역직렬화 단계에서 실패하지 않는다. 리스너가 문자열에서 봉투를 읽다 실패하면 영구 오류(`NonRetryableMessageException`)가 되어 원문 문자열 그대로 DLQ로 간다. 그래서 `ErrorHandlingDeserializer`를 쓰지 않는다. 5번 문장과 [0020](../../decisions/0020-payment-refund-dlq-manual-check.md)의 해당 행을 이 구현 결정이 대체한다. 봉투는 트리에서 필요한 필드만 읽으므로 모르는 필드는 무시한다(SA 10-4의 필드 추가 허용). `amount`는 정수 숫자만 받고 `50000.0`과 문자열은 영구 오류다.
- 전용 컨테이너 팩토리: 오류 처리 부품은 공통 모듈(`common.messaging.consumer`, ISE-27)을 쓴다. 결제는 `paymentCommandListenerFactory`에서 `ConsumerContainerFactories.recordAck`로 Boot 공통 설정을 먼저 적용하고 `ConsumerErrorHandlers.retryThenDeadLetter`를 붙인다. `ack-mode`는 공통 설정에도 `record`로 있지만 팩토리에서 `RECORD`로 다시 고정한다. 오프셋 보존이 이 값에 달려 있어 공유 설정 파일이 바뀌어도 흔들리지 않게 하려는 것이다. 전역 `CommonErrorHandler` 빈은 만들지 않는다. 다른 리스너와 DLQ 리스너(ISE-138, 5절)는 오류 규칙이 다르기 때문이다. 동시성은 1이다.
- 오류 처리기(공통 `ConsumerErrorHandlers.retryThenDeadLetter`): `DefaultErrorHandler(DeadLetterPublishingRecoverer, BackOff)`. DLQ 토픽 이름은 `원본토픽.dlq`이고 DLQ로 보낸 뒤 `event=MESSAGE_DEAD_LETTERED` ERROR 로그를 남긴다. BackOff는 1초에서 시작해 2배, 3회 재시도(1·2·4초)이고 `NonRetryableMessageException`은 재시도 제외로 등록한다. DLQ 전송 결과를 기다리고 실패하면 복구가 실패해 원 메시지가 다시 전달되며 오프셋은 올라가지 않는다.
- DLQ 파티션은 지정하지 않는다(음수 파티션, 키 기반 배정). DLQ 토픽의 파티션 수가 원본과 같다는 보장이 없고, 원본 파티션 번호를 그대로 쓰면 파티션이 더 적은 DLQ 토픽에서 전송이 실패한다. 키는 원본 그대로 가서 같은 예매는 같은 파티션에 모인다.
- 0행 뒤에는 환불 행을 먼저 다시 읽는다: 원자적 생성이 0행이면 결제 상태를 보기 전에 `refunds`를 `payment_id`로 읽는다. 행이 있으면 중복(정상)이다. 이미 환불돼 결제가 `REFUNDED`여도 환불 행이 있으면 중복이어야 하므로, 결제 상태로 먼저 거르면 정상 중복을 영구 오류로 오판한다. 행이 없을 때만 결제 상태가 `APPROVED`가 아닌 것이라 영구 오류다.
- 테스트 범위: 오류 처리기 단위 테스트는 공통 모듈(`ConsumerErrorHandlersTest`)로 옮겼고, 결제에는 두 컨테이너가 각자 맞는 처리기·RECORD로 등록되는지 확인하는 연결 테스트(`PaymentCommandContainerWiringTest`)가 남았다. 오류 처리기는 브로커 없이 `KafkaOperations` 목으로 단위 확인한다(영구 오류 즉시 DLQ, 일시 오류는 재시도 소진 뒤 DLQ, DLQ 전송 실패 시 재처리). 테스트 프로필은 `listener.auto-startup=false`라 컨테이너를 시작하지 않는다. 실제 브로커에서 DLQ 토픽 도착과 오프셋 보존은 compose 스택에서 확인한다(미확인).

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

DLQ에 온 환불 요청도 자동 복구 경로로 되돌린다. 한 트랜잭션에서 `dead_letters`를 `INSERT … ON CONFLICT (event_key) DO NOTHING`으로 저장하고, 4절과 같은 검증을 다시 해 아래처럼 처리하고, `processed_events(event_id, 'payment.command-dlq')`를 쓴다.

| 메시지 | 처리 | `dead_letters` |
|---|---|---|
| 검증 통과, 환불 행 없음 | 환불 `REQUESTED` 생성(`source=DLQ`), 워커가 이어받음 | `RESOLVED(AUTO_PERSISTED)`, `refund_id` |
| 검증 통과, 환불 행 있음 | 변경 없음 | `RESOLVED(DUPLICATE)`, `refund_id` |
| 검증 실패, 역직렬화 실패 | 환불 행을 만들 근거가 없음 | `OPEN` + 즉시 알림 |

- 영속화가 실패하면(DB 장애) 처리 완료로 인정하지 않는다. 오프셋을 커밋하지 않고 지수 백오프(최대 60초)로 횟수 제한 없이 다시 처리한다. 이 처리기도 공통 부품(`ConsumerErrorHandlers.retryForever`)이다.
- 메시지 내용 문제로는 예외를 던지지 않는다. 내용 문제는 `OPEN`으로 기록하고 커밋한다. 예외는 DB·인프라 오류뿐이라 메시지 하나가 파티션을 영구히 막지 않는다.
- `.dlq` 토픽 보관은 7일`[가정]`이다. `OPEN` 건의 해결은 Ops `dead-letter resolve`다([manual-recovery.md](manual-recovery.md) 3절).

### 구현 메모 (ISE-138)

- 위치: 리스너는 `refund.infrastructure.messaging.PaymentCommandDlqListener`, 처리는 `refund.application.DeadLetterService`, 저장은 `dead_letters`(`V4__dead_letters.sql`, [data-model.md](data-model.md) 8절)다. 리스너는 `ConsumerRecord<String, String>`을 받아 봉투를 4절의 파서로 읽고(실패는 영구 오류를 잡아 "역직렬화·봉투 오류" 사유로 다룬다. 파싱은 트랜잭션 밖이라 rollback-only 문제가 없다) 서비스에 넘긴다.
- `event_key`에 원본 위치를 쓰는 이유: 봉투를 읽지 못한 메시지의 키는 DLQ 레코드가 아니라 **원본** `topic-partition-offset`이다. 원본 위치는 Spring Kafka가 DLQ 레코드에 붙이는 `kafka_dlt-original-topic`(UTF-8)·`-partition`(4바이트 int)·`-offset`(8바이트 long) 헤더에서 읽는다. DLQ 전송은 성공했는데 원본 오프셋 커밋이 실패하면 원본이 다시 처리되어 같은 메시지가 DLQ에 두 번 들어오는데, 두 DLQ 레코드의 위치는 달라도 원본 위치는 같아서 한 건으로 묶인다. 헤더가 없거나 길이가 맞지 않으면(직접 넣은 메시지) DLQ 레코드 자신의 위치를 쓰고, `source_topic`·`source_partition`·`source_offset` 컬럼에도 같은 값을 넣는다. 봉투를 읽은 메시지는 `eventId`가 키라 다른 DLQ 레코드로 와도 한 건이다. 봉투 검사에서 걸린 메시지는 `eventId` 필드가 있어도 읽은 것으로 보지 않고 위치 키를 쓴다(`event_id`도 NULL).
- 검증 재사용은 결과 값으로 한다: 4절의 교차 검증과 환불 생성을 `RefundRequestService`에서 `verify`(읽기만, `Valid(reason)`·`Invalid(message)`)와 `create`(원자적 생성, `Created`·`Duplicate`·`NotApproved`)로 나눴다. 소비자 경로(`handle`)는 `Invalid`·`NotApproved`를 `NonRetryableMessageException`으로 바꿔 던져 ISE-136 동작을 그대로 지킨다. DLQ 처리기는 내용 문제를 예외로 던지면 안 되는데, 바깥 트랜잭션에 참여한 코드가 예외를 던지면 그 트랜잭션이 rollback-only가 되어 예외를 삼켜도 커밋 때 `UnexpectedRollbackException`이 나기 때문이다(`ProcessedEventService.processOnce`의 Note). 결과 값이면 `OPEN` 기록으로 닫고 정상 커밋한다.
- `processed_events`는 `processOnce`를 쓰지 않고 `ProcessedEventRepository.markProcessed`로 같은 트랜잭션에서 직접 쓴다. 중복 판단은 `dead_letters.event_key` UNIQUE가 먼저 하고(삽입이 0행이면 이미 처리한 메시지라 아무것도 하지 않고 끝난다), `processOnce`는 업무가 예외를 던지면 바깥 트랜잭션을 rollback-only로 만들기 때문이다. `processed_events`는 `eventId`를 읽었을 때만 쓴다.
- 순서는 한 트랜잭션(`TransactionTemplate`)에서 `dead_letters` OPEN 삽입(`ON CONFLICT (event_key) DO NOTHING`, 사유는 우선 "처리 중") → `processed_events` → 파싱 실패면 `OPEN` 유지 → 검증 → 생성(`source=DLQ`) → 결과 반영이다. `Created`는 `RESOLVED(AUTO_PERSISTED)`와 `refund_id`, `Duplicate`는 `RESOLVED(DUPLICATE)`와 `refund_id`, `Invalid`·`NotApproved`는 `OPEN`과 사유다. 사유는 `VARCHAR(500)`이라 길면 자른다. 원문의 NUL 문자는 PostgreSQL 텍스트가 저장하지 못해 U+FFFD로 바꿔 저장한다(내용 때문에 예외가 나면 같은 메시지를 영원히 다시 처리하게 된다). DB·인프라 오류만 예외로 나가고 트랜잭션이 롤백되어 `dead_letters`·환불·`processed_events`가 모두 남지 않는다.
- 오류 처리기와 컨테이너: `paymentCommandDlqListenerFactory`는 ISE-136 팩토리처럼 Boot configurer 적용 뒤 ack-mode `RECORD`, 동시성 1로 고정하고 전역 `CommonErrorHandler` 빈은 만들지 않는다. 오류 처리기는 복구기 없는 `DefaultErrorHandler`에 무제한 백오프(1초 시작, 2배, 최대 간격 60초, 횟수·경과 시간 제한 없음)를 쓴다. 다른 토픽으로 보내지 않고 건너뛰지도 않아서, DB 장애 동안 같은 레코드를 계속 다시 처리하며 오프셋을 커밋하지 않는다. 모든 예외를 재시도 대상으로 분류한다. `DefaultErrorHandler`는 기본으로 변환·역직렬화 예외를 재시도 없이 복구하고 기본 복구기는 로그만 남기고 건너뛰는데, 이 리스너에서 건너뛰면 환불 의무가 지워질 수 있기 때문이다.
- 최대 간격 60초는 `max.poll.interval.ms`(기본 5분)보다 작다. 재시도 대기 중에는 컨슈머가 poll하지 못하므로 한 번의 대기가 이 시간을 넘으면 그룹에서 쫓겨나 리밸런스가 반복된다. 이 값을 키우려면 `max.poll.interval.ms`도 함께 본다.
- `OPEN`으로 끝난 건은 커밋된 뒤 ERROR 로그(`event=DEAD_LETTER_OPEN`, `eventKey`, 사유)만 남긴다. 즉시 알림과 `OPEN` 건수·최고령 지표는 ISE-41에서 붙인다(TODO). `OPEN` 건의 해결(`REPLAYED`·`NO_OBLIGATION`)은 Ops `dead-letter resolve`라 이 이슈 범위 밖이다.
- 테스트 범위: DLQ 레코드를 직접 만들어 리스너를 부르는 통합 테스트(실제 테스트 DB)로 표의 세 경우, 재전달 멱등, 원본 위치 키, DB 오류 시 롤백 뒤 재처리(N8)를 확인했다. 오류 처리기는 브로커 없이 단위로 확인한다(백오프가 60초에서 멈추고 끝나지 않는다, 복구기 없음). 실제 브로커에서 DLQ 토픽 소비와 오프셋 보존은 compose 스택에서 확인한다(미확인).

## 6. 재확인 스케줄러와 환불 워커 (SA 10-5)

두 작업은 복구 실행기(7절)에서 돌고 PG 호출은 `pg-recovery` 그룹을 쓴다([pg-integration.md](pg-integration.md) 5절). 한 건의 처리 순서는 같다.

1. 한 주기(기본 1초 간격)에 최대 20건`[가정]`을 한 건씩 처리한다. 한 건은 한 트랜잭션에서 후보 하나를 `next_check_at <= now()` 순으로 `FOR UPDATE SKIP LOCKED`로 잠근다. 처리할 건이 없거나 허가를 못 얻으면 그 주기를 끝낸다.
2. 잠근 직후 `pg-recovery` 허가를 얻는다(`PgRecoveryGate.tryAcquire`). 못 얻으면(CB OPEN·Bulkhead 초과) 아무것도 바꾸지 않고 트랜잭션을 끝내 claim하지 않고 다음 주기로 넘긴다. 이때는 시도 횟수를 세지 않는다.
3. claim: 같은 트랜잭션에서 조건부 UPDATE로 `next_check_at = now() + 30초`(임대), 시도 횟수 + 1. 1행일 때만 계속한다(0행이어도 커밋하고 주기를 끝낸다).
4. 트랜잭션 밖에서 PG를 부르고, 결과를 조건부 UPDATE로 반영한다(2절).

결제 재확인(`REQUESTED`는 TX1이 `next_check_at`을 저장 + 10초로 채워 10초 뒤 대상이 된다):

| `findPayment` 결과 | 결제 |
|---|---|
| `Approved`, 금액·통화 일치 | `APPROVED` + Outbox `PaymentApproved` |
| `Approved`, 금액·통화 불일치 | `NEEDS_MANUAL_CHECK(PG_AMOUNT_MISMATCH)`, 즉시 알림 |
| `Declined` | `DECLINED` + Outbox `PaymentDeclined` |
| `NotFound` | `preventLateApproval`: `Prevented`면 `DECLINED(NOT_SENT)` + `PaymentDeclined`, `AlreadyApproved`면 `Approved`와 같게 처리, `Unknown`이면 다음 간격. 늦은 승인 방지 허가를 못 얻으면 `Unknown`과 같게 다음 간격(시도 횟수는 센다. 9번째면 운영자 확인) |
| `Unknown` | `REQUESTED`면 `UNKNOWN`으로, 이미 `UNKNOWN`이면 `next_check_at`만 갱신 |

환불 워커(`REQUESTED`·`UNKNOWN` 환불):

| 단계 | 규칙 |
|---|---|
| 호출 선택 | `pg_requested_at`이 NULL이면 `refund`를 부른다(claim 때 `pg_requested_at`을 채운다). 값이 있으면 `findRefund`로 먼저 확인하고 `NotFound`일 때만 같은 `pg_refund_id`로 `refund`를 다시 부른다 |
| `Refunded`, 금액·통화 일치 | 환불 `REFUNDED`, 결제 `APPROVED`(금액 불일치 환불이면 `NEEDS_MANUAL_CHECK`) → `REFUNDED`, 이력 2건, Outbox `PaymentRefunded`. 결제 UPDATE가 0행이면 롤백 후 환불 `NEEDS_MANUAL_CHECK(STATE_CONFLICT)` |
| `Refunded`, 금액·통화 불일치 | 환불 `NEEDS_MANUAL_CHECK(REFUND_AMOUNT_MISMATCH)`, 즉시 알림 |
| `RefundRejected` | 환불 `NEEDS_MANUAL_CHECK(REFUND_REJECTED)`, 알림. `FAILED`는 쓰지 않는다 |
| `Unknown` | `REQUESTED`면 `UNKNOWN`으로, 이미 `UNKNOWN`이면 `next_check_at`만 갱신 |

간격은 SA 9-4대로 10초×6회 → 1분 → 5분 → 15분이다. 실제로 PG를 부른 9번째 호출도 확정하지 못하면 `NEEDS_MANUAL_CHECK`(`PG_UNRESOLVED`·`REFUND_UNRESOLVED`)다(약 22분, 10초×6 + 1분 + 5분 + 15분 = 1320초). CB OPEN으로 부르지 못한 주기는 세지 않으므로 PG 전체 장애 중에는 횟수가 줄지 않고, 그동안의 체류는 최고령 지표와 알림이 본다([observability.md](observability.md)). 확정 때만 이벤트를 발행한다.

### 구현 메모 (ISE-137)

- 위치: 환불 워커 한 건의 처리는 `refund.application.RefundWorkerService.processNext()`다. 재확인 서비스와 같은 구조(후보 잠금 → 허가 → 임대 → 트랜잭션 밖 PG 호출 → 결과 반영)이고, 환불 쿼리는 `RefundRepository`, 결제의 환불 완료 전이는 `PaymentTransitionService.markRefunded`, 이벤트는 `PaymentResultPublisher.publishRefunded`다.
- 환불 claim: 한 트랜잭션에서 `refunds`의 `status IN ('REQUESTED','UNKNOWN') AND next_check_at <= now`(부분 인덱스 `idx_refunds_next_check_at`와 같은 조건)를 `ORDER BY next_check_at LIMIT 1 FOR UPDATE SKIP LOCKED`로 잠그고, `pg-recovery` 허가를 얻은 뒤에 임대 UPDATE를 한다. 못 얻으면 아무것도 바꾸지 않아 시도 횟수를 세지 않는다. 임대 UPDATE는 `next_check_at = now + 30초`, `attempt + 1`, `pg_requested_at = COALESCE(pg_requested_at, now)`다.
- 첫 호출 판단: claim 전에 읽어 둔 `pg_requested_at`이 NULL이면 첫 호출이라 `refund`를 바로 부르고, 값이 있으면 `findRefund`로 먼저 확인한다. 임대가 값을 처음에만 채우므로(COALESCE) "처음 PG를 부른 시각"이 남고, 첫 응답이 유실되거나 서버가 죽어도 다음 처리는 `findRefund`로 시작한다.
- 간격은 `RecheckSchedule`을 그대로 쓴다. 다만 결제 재확인은 TX1이 `next_check_at`을 10초 뒤로 채워 첫 10초를 기다리지만 환불은 첫 호출이 바로 나가므로, 환불의 첫 호출이 불확실하면 10초 뒤(n = 1), 이후 n = 2~5는 10초, 6은 1분, 7은 5분, 8은 15분 뒤이고 9번째도 모르면 소진이다. 첫 호출까지의 10초 대기가 없으므로 총 시간은 재확인보다 10초 짧다.
- 결제 UPDATE가 0행인 경우: 환불 `REFUNDED` 전이, 이력, 결제 전이, Outbox를 한 트랜잭션에서 하고 결제 전이가 0행이면 예외로 전체를 롤백한다. 그다음 새 트랜잭션에서 환불을 `NEEDS_MANUAL_CHECK(STATE_CONFLICT)`로 바꾸고 이력을 남기며 ERROR 로그(`event=REFUND_STATE_CONFLICT`)를 낸다. 환불 UPDATE가 0행이면 다른 경로가 먼저 바꾼 것이라 결제를 포함해 아무것도 더 쓰지 않는다.
- PG를 부르기 전 방어: 임대 전에 결제 상태를 읽어 환불 사유에 맞는 상태(`PG_AMOUNT_MISMATCH` 사유면 `NEEDS_MANUAL_CHECK`, 아니면 `APPROVED`)가 아니거나 `pg_tx_id`가 없으면 PG를 부르지 않고 환불을 `NEEDS_MANUAL_CHECK(STATE_CONFLICT)`로 넘긴다. 이 경우 임대하지 않으므로 시도 횟수를 세지 않는다. 설계 표에는 없는 경로이고, 돈이 나간 뒤에야 결제가 환불 완료로 옮겨지지 않는다는 것을 알게 되는 일을 막으려고 추가했다.
- 두 번째 허가: `findRefund`가 `NotFound`면 `refund` 전에 허가를 한 번 더 얻는다. 못 얻으면 호출하지 않고 `Unknown`처럼 다음 간격으로 미룬다(이력 사유 `RECOVERY_PERMIT_DENIED`, 시도 횟수는 센다).
- `PaymentRefunded`의 `amount`는 결제의 `amount`가 아니라 PG 승인 금액(`pg_approved_amount`)이다. 환불 금액이 승인 금액을 복사하므로 같은 값이고, 승인 금액이 결제 금액과 달라 운영자 확인을 거친 결제(`PG_AMOUNT_MISMATCH`)의 환불에서 두 값이 다르다. `refundedAt`은 PG가 환불한 시각이고 밀리초로 자른다.
- 테스트 범위: 서비스 단위(목 `PgClient`, 실제 테스트 DB와 복구 그룹), 쿼리, 실제 mock-pg를 띄운 E8·E13·응답 유실·`NotFound` 재호출. 환불 워커는 테스트 프로필에서 꺼 두고 서비스 메서드를 직접 부른다. mock-pg의 환불 호출 계수(원장 조회)는 아직 없어 PG 쪽 호출 횟수는 `PgClient` 스파이로 센다.
- 미확인: Ops `refund retry`가 `pg_requested_at`을 비워야 하는지(비우면 재시도가 `findRefund` 없이 `refund`부터 시작해 이중 환불 방어가 PG 멱등키에만 의존한다. 비우지 않으면 `findRefund`부터 시작한다). Ops 명령 구현에서 정한다. 실제 Kafka로 `PaymentRefunded`가 Reservation까지 닿는 흐름(Outbox 행까지만 확인), 환불 장애 주입(ISE-133)에서의 동작, 지표와 알림(ISE-41, 코드에 `TODO(ISE-41)`로 자리만 둠), DLQ 경로가 만든 환불의 처리(ISE-138).

## 7. 실행기와 DB 연결

Spring `@Scheduled`의 기본 스케줄러는 스레드 1개라 PG 재조회가 느려지면 Outbox 릴레이가 같이 멈추고, 승인 결과가 예매에 늦게 닿아 선점이 만료될 수 있다. 작업 성격별로 실행기를 나눈다.

| 실행기 | 작업 | 스레드 | DB 연결 최대 |
|---|---|---|---|
| `outbox-relay` | Outbox 릴레이(500ms) | 1 | 1 |
| `recovery` | 결제 재확인, 환불 워커 | 2`[가정]` | 2 |
| `housekeeping` | 정리(10분`[가정]`), Gauge 갱신(15초), 대사(5분`[가정]`) | 1 | 1 |
| Kafka 리스너 | `payment.command`, `payment.command.dlq` | 각 1 | 2 |

- `recovery`는 Spring `TaskScheduler` 빈이 아니라 `RecoveryScheduler`가 소유하는 전용 실행기(`ScheduledThreadPoolExecutor`, 스레드 이름 `recovery-N`)다. 결제 재확인과 환불 워커가 이 실행기를 함께 쓴다. `TaskScheduler` 빈을 만들면(`ScheduledExecutorService` 빈도 같다. Boot 4.1.2의 `TaskSchedulingConfigurations`가 `@ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})`라서 실행기를 빈으로 노출하면 같은 문제가 생긴다) Boot의 기본 스케줄러가 빠지고 기본 스케줄러를 쓰는 Outbox 릴레이까지 그 빈으로 옮겨 가 섞인다. 한 주기의 예외와 Error는 `Throwable`로 잡아서 ERROR로 남기고 다음 주기를 계속한다(`scheduleWithFixedDelay`는 예외가 나면 이후 실행을 멈추고 예외는 아무도 읽지 않는 Future에만 남는다). 종료할 때는 새 claim을 먼저 멈추고(`stopping` 플래그) 진행 중인 한 건을 최대 15초 기다린다(한 건 최악 시간이 claim 트랜잭션 + PG 조회 2.5초 + 늦은 승인 방지 2.5초 + 전이 트랜잭션으로 약 7초라서). 작업은 `RecoveryJob(name, interval, maxPerTick, next)`로 표현하고 스케줄러가 작업마다 `scheduleWithFixedDelay`를 건다. 한 주기는 `maxPerTick`건까지 `next`를 부르다 `false`면 멈춘다. 작업은 `RecoveryConfiguration`이 등록하고, `enabled=false`인 작업은 등록하지 않는다. 스케줄러 빈은 항상 만들고(`ObjectProvider<RecoveryJob>`로 등록된 작업을 받는다. `@ConditionalOnBean`은 자동 설정 밖에서 빈 등록 순서에 결과가 달라질 수 있어 쓰지 않는다), 등록된 작업이 없으면 `start()`가 실행기를 만들지 않아 스레드도 뜨지 않는다. 설정은 `payment.recheck.{enabled,interval,max-per-tick}`와 `payment.refund-worker.{enabled,interval,max-per-tick}`(기본 `true`, 1초, 20)다. 테스트 프로필은 두 작업을 모두 끈다. `scheduleWithFixedDelay`는 같은 작업을 동시에 두 번 돌리지 않으므로 두 작업이 스레드를 하나씩 쓴다.
- 각 작업은 끝날 때 `payment_job_last_success_timestamp{job}`을 갱신하고, 실패는 `payment_job_failures_total{job}`으로 센다. 정체 알림이 이 값을 본다.
- 배경 작업은 PG 호출 동안 DB 연결을 잡지 않는다. 동시에 잡을 수 있는 연결은 최대 6개다.
- 연결 풀은 SA 9-4의 서비스당 20으로 둔다. 현재 `config-repo/payment-service.yml`은 `maximum-pool-size: 5`라 배경 작업만으로 풀이 찰 수 있어 고친다(S-21). `connection-timeout`은 1초`[가정]`로 둔다. 기본값 30초면 요청 스레드가 연결을 기다리다 Gateway 3초를 넘기고, TX2가 늦어져 재확인 스케줄러와 겹친다.

## 8. 정리 작업 ([0022](../../decisions/0022-outbox-retry-and-retention.md))

| 대상 | 삭제 조건 | 기간 |
|---|---|---|
| `outbox_events` | `SENT`이고 `sent_at` 경과 | 7일 |
| `processed_events` | `processed_at` 경과 | 14일(Kafka·DLQ 보관 + 수동 재처리 기간보다 길게. 시작 때 설정값 비교) |
| `idempotency_records` | `expires_at` 경과, 연결 결제가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`가 아님 | 24시간 |

배치 1,000건 단위, `pg_try_advisory_lock`으로 중복 실행 방지, 실패는 다음 주기(10분`[가정]`)에 재시도한다. 그 밖의 결제 테이블은 지우지 않는다([data-model.md](data-model.md) 12절).

## 9. 오래된 이벤트 재전달 방어

`processed_events`가 지워진 뒤 재전달돼도 다음이 중복 결제·환불을 막는다(INV-1·INV-2).
- 조건부 UPDATE와 영향 행 확인(2절)
- `payments.reservation_id` 전체 UNIQUE([0026](../../decisions/0026-payment-one-per-reservation.md))
- `refunds.payment_id` UNIQUE와 결제 상태 조건이 있는 `INSERT … SELECT … ON CONFLICT`
- 환불 재호출 전 `findRefund`, PG 멱등키(`pg_order_id`·`pg_refund_id`)
- Reservation의 paymentId 대조(0027)

## 10. 대사 ([0029](../../decisions/0029-payment-reservation-reconciliation.md))

`housekeeping` 실행기가 5분`[가정]`마다 결제 DB와 예매 내부 조회 API를 대조해 RC-1~RC-8 불일치를 `reconciliation_mismatches`에 기록하고 Gauge로 알린다. 데이터는 고치지 않는다. 허용 지연은 10분`[가정]`이다.
