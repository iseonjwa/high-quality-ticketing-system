# 이벤트와 신뢰성

결제 결과는 Outbox로 발행하고, 소비는 처리 이력과 상태 전이로 멱등하게 하며, 환불 요청은 PG를 부르기 전에 DB에 영속화한다. 끝내 확정하지 못한 건은 DB 상태로 남겨 재확인 스케줄러·환불 워커·운영자가 이어받고, 서비스 사이 어긋남은 대사가 찾는다. 토픽·봉투는 SA 10장을 따르고 여기에는 구현 규칙만 둔다. 지키는 조건은 [invariants.md](invariants.md)의 INV-2·INV-4·INV-5·INV-6이다.

## 1. 발행과 구독

| 토픽 | 이벤트 | 키 | 이 서비스의 역할 |
|---|---|---|---|
| `payment.result` | `PaymentApproved`·`PaymentDeclined`·`PaymentRefunded` | reservationId | 발행(Outbox). Reservation 그룹 `reservation.payment-result`의 처리 규칙은 [0027](../../decisions/0027-reservation-payment-event-contract.md) |
| `payment.command` | `RefundRequested`(MVP)·`BillingPaymentRequested`(M4) | reservationId | 구독, 그룹 `payment.command` |
| `payment.command.dlq` | 소비 실패 | 원본 키 | 구독, 그룹 `payment.command-dlq`(DLQ 처리기) |

페이로드 변경([SA 변경 요청](sa-change-requests.md) S-18, 0027): `PaymentApproved`·`PaymentRefunded`에 `currency`(`"KRW"`)를 더하고, `RefundRequested`에 `userId`·`amount`·`currency`를 더하며 `reason`에 `DUPLICATE_APPROVAL`·`NOT_CONFIRMABLE`을 추가한다. SA 10-4는 필드 추가만 허용하므로 `version`은 그대로 1이다.

구현 위치(ISE-24): 일반 부품(Outbox 쓰기·릴레이·Kafka 전송, `processed_events` 처리 이력)은 `common`의 `com.iseonjwa.common.messaging.outbox`·`inbox` 패키지에 있고 서비스가 `CommonOutboxConfiguration`을 `@Import`한다([common-modules.md](../../guides/common-modules.md)). 결제 이벤트 페이로드와 발행기(`PaymentResultPublisher`)는 `Payment`에 의존하므로 `payment-service`에 둔다. 저장소는 JPA 엔티티 없이 `JdbcTemplate`으로 쓰며 JPA 트랜잭션의 같은 연결을 쓴다. ISE-24에는 임대·키별 순서·SENT 기록까지 넣었고, 지수 백오프·`FAILED`·전용 실행기는 ISE-145에서 넣었다(3절 구현 메모, 7절). SENT 정리는 ISE-146이다. 한 번의 릴레이 실행은 전송 확인을 최대 전송 시한(12초)까지 기다리고(프로듀서의 `max.block.ms`·`delivery.timeout.ms`가 이 안에 들어오는지 기동 때 검증), 마감을 넘긴 행은 임대를 바로 푼다. 실제 소요는 시한에 `send` 호출이 막히는 시간(`max.block.ms`)과 후처리 쓰기가 더해진다.

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
4. 일시 오류는 `attempts + 1`, `next_attempt_at = now() + 지연`, `claimed_until = NULL`로 기록한다. 지연은 equal jitter를 적용한 `min(500ms × 2^(k-1), 60s)`의 50~100%다(k는 실패 기록 뒤의 `attempts`). 횟수로 포기하지 않는다.
5. 영구 오류(직렬화 실패, 레코드 크기 초과, 배치 크기 초과, 잘못된 토픽)만 `FAILED` + `last_error`로 바꾸고 알림을 낸다. 같은 키의 뒤 행은 `FAILED`가 풀릴 때까지 집지 않는다.
6. `PENDING`·재시도 대기·`FAILED`는 자동 삭제하지 않는다.

`SENT`는 Kafka 발행 성공만 뜻한다. Reservation 소비 실패와 `payment.result` DLQ 복구는 Reservation이 관리한다(반효승 통보 항목). 발행은 됐는데 Reservation이 반영하지 못한 건은 대사(10절)가 찾는다.

### 구현 메모 (ISE-145)

- 백오프는 `OutboxBackoff`(common)의 순수 함수다. k번째 실패 뒤 기본 지연은 `min(500ms × 2^(k-1), 60s)`이고, 지수는 7에서 멈춰 k가 아무리 커도 넘치지 않는다(8번째 실패부터 60초). Jitter는 equal jitter로, 기본 지연의 절반에 `[0, 절반]` 난수를 더한다. 그래서 다음 시도는 기본 지연의 50~100% 사이이고 60초를 넘지 않는다. 완전 무작위(full jitter)를 쓰지 않은 것은 0에 가까운 지연이 나와 장애 중인 브로커를 곧바로 다시 두드리기 때문이다. 난수원은 주입할 수 있다(테스트에서 고정).
- 일시 오류 기록은 `attempts = LEAST(attempts + 1, 32767)`, `last_error`, `next_attempt_at`, `claimed_until = NULL`을 한 문장으로 쓴다(`WHERE status = 'PENDING'`). 임대를 바로 풀어 `next_attempt_at`이 재시도 간격을 정한다. 임대를 남기면 임대 만료(30초)가 사실상의 간격이 되어 백오프가 무의미하다. `attempts`는 `SMALLINT` 상한에서 멈추고 그 뒤에도 60초 간격으로 계속 재시도한다. 임대를 바로 풀어도 "전송이 아직 진행 중인데 다른 실행이 다시 보내는" 문제(임대를 전송 시한의 2배로 둔 이유)는 생기지 않는다. 실패를 기록하는 시점은 프로듀서가 그 레코드를 포기한 뒤이기 때문이다. 기동 검증이 `max.block.ms + delivery.timeout.ms`(2초 + 10초)를 릴레이 전송 시한(12초) 안에 두게 하므로, 시한 초과로 기록할 때는 프로듀서 쪽 전송도 이미 성공이나 실패로 끝나 있다.
- 영구 오류는 `OutboxFailureClassifier` 한 곳에서 가른다. 원인 사슬을 끝까지 훑어(Spring Kafka가 감싼 `KafkaException` 포함) `org.apache.kafka.common.errors`의 `SerializationException`, `RecordTooLargeException`, `RecordBatchTooLargeException`, `InvalidTopicException`이 하나라도 있으면 영구 오류다. 시한 초과, 브로커 불가, 알 수 없는 예외는 모두 일시 오류다. 모르는 예외를 `FAILED`로 보내면 정상 이벤트와 같은 키의 뒤 이벤트가 멈추기 때문이다.
- 영구 오류는 ERROR 로그 `event=OUTBOX_FAILED eventId topic messageKey cause`(값 원문 없이 예외 종류만)를 남긴다. `FAILED`로 바꾼 수는 `outbox_events_failed_total`, 현재 건수는 `outbox_failed_count`가 센다(ISE-156, [observability.md](observability.md)). 알림은 `PaymentOutboxFailed` 규칙(ISE-157), 복구는 Ops `outbox requeue`(ISE-42)다.
- `FAILED`가 같은 키를 막는 것은 의도다. `claimNext`는 같은 `message_key`에 앞선 `PENDING` 또는 `FAILED` 행이 있으면 뒤 행을 집지 않는다. 앞선 이벤트를 건너뛰고 뒤 이벤트를 먼저 내보내면 순서가 뒤집히므로 Ops `outbox requeue` 전까지 그 키는 멈춰 있다. 조건은 `NOT EXISTS`를 `PENDING`용과 `FAILED`용으로 나눈다. `status <> 'SENT'` 하나로 합치면 상태별 부분 인덱스를 못 쓴다. `FAILED`용 인덱스는 `V6__outbox_failed_key_index.sql`이다.
- 테스트 범위: 백오프·분류는 common 단위 테스트, 저장소 동작(일시 오류 기록의 범위·임대 해제, 다음 시도 전에는 집지 않음, `FAILED`와 키 막힘, 복구 뒤 `SENT`)은 PostgreSQL 통합 테스트(`OutboxRelayIntegrationTest`), Kafka 장애에서 승인 API가 200을 주고 Outbox가 쌓였다가 복구 뒤 `SENT`가 되는 흐름(N10)은 가짜 sender로 `OutboxBrokerOutageApiTest`에서 확인했다. 실제 Kafka 브로커를 내리는 장애 주입은 하지 않았다.

## 4. 소비 `payment.command` ([0020](../../decisions/0020-payment-refund-dlq-manual-check.md))

소비자는 환불 요청을 검증하고 DB에 영속화만 한다. PG 환불은 부르지 않는다. PG 호출은 환불 워커(6절) 한 곳에서만 한다.

1. `RefundRequested`를 아래 표로 교차 검증한다.
2. 통과하면 한 트랜잭션에서 `processed_events(event_id, 'payment.command')`, 환불 `INSERT … SELECT … ON CONFLICT (payment_id) DO NOTHING`(`REQUESTED`, `next_check_at` = 현재, `source=EVENT`), 이력을 쓴다([data-model.md](data-model.md) 4절). 0행이고 환불 행이 이미 있으면 중복이라 `processed_events`만 남긴다.
3. 검증 실패와 역직렬화 실패는 영구 오류다. 재시도하지 않고 바로 DLQ로 보낸다(재시도 제외 예외로 등록).
4. 일시 오류(DB 연결, 잠금 시간 초과)는 1·2·4초 간격 3회 재시도 뒤 DLQ로 보낸다.
5. 오프셋은 처리 성공 뒤에 커밋한다(`ack-mode=record`). DLQ로 보내는 것이 실패하면 원 메시지의 오프셋을 올리지 않는다. 깨진 메시지는 리스너가 읽다가 영구 오류로 DLQ에 보내 원문을 보존한다(아래 구현 메모).

### 구현 메모 (ISE-136)

- 위치: 환불은 결제와 다른 집합이라 `com.iseonjwa.payment.refund`(presentation·domain·application·infrastructure)에 둔다. 소비자는 `refund.presentation.messaging`, 교차 검증과 영속화는 `refund.application.RefundRequestService`다. 환불 상태 이력은 새 테이블 없이 `payment_status_histories`에 `target_type=REFUND`로 쓴다. 이 테이블은 환불에만 있는 `FAILED`를 담도록 상태 컬럼을 열거형이 아닌 문자열로 매핑한다.
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

- 위치: 리스너는 `refund.presentation.messaging.PaymentCommandDlqListener`, 처리는 `refund.application.DeadLetterService`, 저장은 `dead_letters`(`V4__dead_letters.sql`, [data-model.md](data-model.md) 8절)다. 리스너는 `ConsumerRecord<String, String>`을 받아 봉투를 4절의 파서로 읽고(실패는 영구 오류를 잡아 "역직렬화·봉투 오류" 사유로 다룬다. 파싱은 트랜잭션 밖이라 rollback-only 문제가 없다) 서비스에 넘긴다.
- `event_key`에 원본 위치를 쓰는 이유: 봉투를 읽지 못한 메시지의 키는 DLQ 레코드가 아니라 **원본** `topic-partition-offset`이다. 원본 위치는 Spring Kafka가 DLQ 레코드에 붙이는 `kafka_dlt-original-topic`(UTF-8)·`-partition`(4바이트 int)·`-offset`(8바이트 long) 헤더에서 읽는다. DLQ 전송은 성공했는데 원본 오프셋 커밋이 실패하면 원본이 다시 처리되어 같은 메시지가 DLQ에 두 번 들어오는데, 두 DLQ 레코드의 위치는 달라도 원본 위치는 같아서 한 건으로 묶인다. 헤더가 없거나 길이가 맞지 않으면(직접 넣은 메시지) DLQ 레코드 자신의 위치를 쓰고, `source_topic`·`source_partition`·`source_offset` 컬럼에도 같은 값을 넣는다. 봉투를 읽은 메시지는 `eventId`가 키라 다른 DLQ 레코드로 와도 한 건이다. 봉투 검사에서 걸린 메시지는 `eventId` 필드가 있어도 읽은 것으로 보지 않고 위치 키를 쓴다(`event_id`도 NULL).
- 검증 재사용은 결과 값으로 한다: 4절의 교차 검증과 환불 생성을 `RefundRequestService`에서 `verify`(읽기만, `Valid(reason)`·`Invalid(message)`)와 `create`(원자적 생성, `Created`·`Duplicate`·`NotApproved`)로 나눴다. 소비자 경로(`handle`)는 `Invalid`·`NotApproved`를 `NonRetryableMessageException`으로 바꿔 던져 ISE-136 동작을 그대로 지킨다. DLQ 처리기는 내용 문제를 예외로 던지면 안 되는데, 바깥 트랜잭션에 참여한 코드가 예외를 던지면 그 트랜잭션이 rollback-only가 되어 예외를 삼켜도 커밋 때 `UnexpectedRollbackException`이 나기 때문이다(`ProcessedEventService.processOnce`의 Note). 결과 값이면 `OPEN` 기록으로 닫고 정상 커밋한다.
- `processed_events`는 `processOnce`를 쓰지 않고 `ProcessedEventRepository.markProcessed`로 같은 트랜잭션에서 직접 쓴다. 중복 판단은 `dead_letters.event_key` UNIQUE가 먼저 하고(삽입이 0행이면 이미 처리한 메시지라 아무것도 하지 않고 끝난다), `processOnce`는 업무가 예외를 던지면 바깥 트랜잭션을 rollback-only로 만들기 때문이다. `processed_events`는 `eventId`를 읽었을 때만 쓴다.
- 순서는 한 트랜잭션(`TransactionTemplate`)에서 `dead_letters` OPEN 삽입(`ON CONFLICT (event_key) DO NOTHING`, 사유는 우선 "처리 중") → `processed_events` → 파싱 실패면 `OPEN` 유지 → 검증 → 생성(`source=DLQ`) → 결과 반영이다. `Created`는 `RESOLVED(AUTO_PERSISTED)`와 `refund_id`, `Duplicate`는 `RESOLVED(DUPLICATE)`와 `refund_id`, `Invalid`·`NotApproved`는 `OPEN`과 사유다. 사유는 `VARCHAR(500)`이라 길면 자른다. 원문의 NUL 문자는 PostgreSQL 텍스트가 저장하지 못해 U+FFFD로 바꿔 저장한다(내용 때문에 예외가 나면 같은 메시지를 영원히 다시 처리하게 된다). DB·인프라 오류만 예외로 나가고 트랜잭션이 롤백되어 `dead_letters`·환불·`processed_events`가 모두 남지 않는다.
- 오류 처리기와 컨테이너: `paymentCommandDlqListenerFactory`는 ISE-136 팩토리처럼 Boot configurer 적용 뒤 ack-mode `RECORD`, 동시성 1로 고정하고 전역 `CommonErrorHandler` 빈은 만들지 않는다. 오류 처리기는 복구기 없는 `DefaultErrorHandler`에 무제한 백오프(1초 시작, 2배, 최대 간격 60초, 횟수·경과 시간 제한 없음)를 쓴다. 다른 토픽으로 보내지 않고 건너뛰지도 않아서, DB 장애 동안 같은 레코드를 계속 다시 처리하며 오프셋을 커밋하지 않는다. 모든 예외를 재시도 대상으로 분류한다. `DefaultErrorHandler`는 기본으로 변환·역직렬화 예외를 재시도 없이 복구하고 기본 복구기는 로그만 남기고 건너뛰는데, 이 리스너에서 건너뛰면 환불 의무가 지워질 수 있기 때문이다.
- 최대 간격 60초는 `max.poll.interval.ms`(기본 5분)보다 작다. 재시도 대기 중에는 컨슈머가 poll하지 못하므로 한 번의 대기가 이 시간을 넘으면 그룹에서 쫓겨나 리밸런스가 반복된다. 이 값을 키우려면 `max.poll.interval.ms`도 함께 본다.
- `OPEN`으로 끝난 건은 커밋된 뒤 ERROR 로그만 남긴다. 줄은 `event=DEAD_LETTER_OPEN paymentId=<n> userId=<n> reservationId=<n> paidAt=<KST ISO> eventKey=<키> reason=<사유>` 순서이고, 봉투나 결제를 읽지 못하면 결제 필드는 `-`다(ISE-183, 자유 문장 사유는 맨 뒤라 Loki 건별 규칙이 라벨로 뽑지 않는다). `OPEN` 건수와 최고령은 `dead_letter_open_count`·`dead_letter_open_oldest_seconds{topic}`가 보이고(ISE-156), 처리기가 예외로 끝나면 `payment_dlq_handler_failures_total{topic}`이 오른다(예외는 그대로 다시 던진다). 즉시 알림은 `PaymentDeadLetterOpen`·`PaymentDlqHandlerFailing`·`PaymentDlqConsumerLag` 규칙이다(ISE-157, `infra/prometheus/rules/payment-alerts.yml`). 건별 알림은 Loki 규칙 `DeadLetterOpenEvent`가 위 로그 줄로 낸다(`infra/loki/rules/payment-events.yml`). `OPEN` 건의 해결(`REPLAYED`·`NO_OBLIGATION`)은 Ops `dead-letter resolve`라 이 이슈 범위 밖이다.
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
- 결정(ISE-160): Ops `refund retry`는 `pg_requested_at`을 비우지 않는다. 비우면 재시도가 `findRefund` 없이 `refund`부터 시작해 이중 환불 방어가 PG 멱등키(`pg_refund_id`) 하나에만 기대게 된다. 비우지 않으면 워커가 `findRefund`부터 시작하고 `NotFound`일 때만 같은 `pg_refund_id`로 `refund`를 부른다. 명령이 계획 때 본 `NotFound`가 적용 시점까지 이어진다는 보장도 없다(그사이 PG에서 환불이 끝날 수 있다). 명령은 `attempt`를 0으로, `next_check_at`을 실행 시각으로 바꿔 `REQUESTED`로 되돌릴 뿐 `pg_refund_id`와 `pg_requested_at`은 그대로 둔다([manual-recovery.md](manual-recovery.md) 2.3절). 통합 테스트가 적용 뒤 워커의 `findRefund` → `refund` 순서와 같은 `pg_refund_id`를 확인한다.
- 미확인: 실제 Kafka로 `PaymentRefunded`가 Reservation까지 닿는 흐름(Outbox 행까지만 확인), 환불 장애 주입(ISE-133)에서의 동작, DLQ 경로가 만든 환불의 처리(ISE-138).

## 7. 실행기와 DB 연결

Spring `@Scheduled`의 기본 스케줄러는 스레드 1개라 PG 재조회가 느려지면 Outbox 릴레이가 같이 멈추고, 승인 결과가 예매에 늦게 닿아 선점이 만료될 수 있다. 작업 성격별로 실행기를 나누고, 결제는 `@Scheduled`를 쓰지 않는다.

| 실행기 | 작업 | 스레드 | DB 연결 최대 |
|---|---|---|---|
| `outbox-relay` | Outbox 릴레이(500ms) | 1 | 1 |
| `recovery` | 결제 재확인, 환불 워커 | 2`[가정]` | 2 |
| `housekeeping` | 정리(10분`[가정]`), Gauge 갱신(15초), 대사(5분`[가정]`) | 1 | 1 |
| Kafka 리스너 | `payment.command`, `payment.command.dlq` | 각 1 | 2 |

- `outbox-relay`는 `OutboxRelayScheduler`(common)가 소유하는 단일 스레드 실행기(`ScheduledThreadPoolExecutor`, 스레드 이름 `outbox-relay`)다. `SmartLifecycle`로 시작·종료하고(`RecoveryScheduler`와 같은 방식) 간격은 `messaging.outbox.interval`(500ms, `scheduleWithFixedDelay`)이다. 한 주기에 최대 10번 집어 보내고, 가득 집어 모두 보냈을 때만 이어서 돈다. 브로커가 죽어 일부라도 못 보냈으면 멈춘다(라운드마다 마감까지 기다리며 스레드를 독점하기 때문이다). 한 주기의 예외와 `Error`는 `Throwable`로 잡아 ERROR로 남기고 다음 주기를 계속한다. 종료할 때는 새 라운드를 먼저 멈추고(`stopping` 플래그) 진행 중인 한 번의 실행을 전송 시한 + 5초(기본 17초)까지 기다린 뒤 인터럽트한다. 확인을 못 받은 행은 임대가 끝난 뒤 다시 집힌다. `messaging.outbox.relay-enabled=false`면 실행기를 만들지 않는다.
- 릴레이가 `@Scheduled`를 쓰지 않으므로 `CommonOutboxConfiguration`은 `@EnableScheduling`을 켜지 않는다(ISE-145에서 뗐다). 결제에는 다른 `@Scheduled`가 없어 Boot의 기본 스케줄러가 필요한 곳이 없다. 이후 결제에 `@Scheduled`를 쓰려면 서비스가 `@EnableScheduling`을 따로 켜야 한다(Reservation의 `SeatHoldSchedulingConfiguration`이 그렇게 한다).
- `recovery`는 Spring `TaskScheduler` 빈이 아니라 `BackgroundJobScheduler`(접두사 `recovery`, 스레드 2개)가 소유하는 전용 실행기(`ScheduledThreadPoolExecutor`, 스레드 이름 `recovery-N`)다. 결제 재확인과 환불 워커가 이 실행기를 함께 쓴다. `TaskScheduler` 빈을 만들면(`ScheduledExecutorService` 빈도 같다. Boot 4.1.2의 `TaskSchedulingConfigurations`가 `@ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})`라서 실행기를 빈으로 노출하면 같은 문제가 생긴다) Boot의 기본 스케줄러가 빠지고 기본 스케줄러를 쓰는 다른 `@Scheduled` 작업이 그 빈으로 옮겨 가 섞인다. 릴레이도 같은 이유로 빈 실행기를 쓰지 않는다. 한 주기의 예외와 Error는 `Throwable`로 잡아서 ERROR로 남기고 다음 주기를 계속한다(`scheduleWithFixedDelay`는 예외가 나면 이후 실행을 멈추고 예외는 아무도 읽지 않는 Future에만 남는다). 종료할 때는 새 claim을 먼저 멈추고(`stopping` 플래그) 진행 중인 한 건을 최대 15초 기다린다(한 건 최악 시간이 claim 트랜잭션 + PG 조회 2.5초 + 늦은 승인 방지 2.5초 + 전이 트랜잭션으로 약 7초라서). 작업은 `BackgroundJob(name, interval, maxPerTick, next)`로 표현하고 스케줄러가 작업마다 `scheduleWithFixedDelay`를 건다. 한 주기는 `maxPerTick`건까지 `next`를 부르다 `false`면 멈춘다. 작업은 `RecoveryConfiguration`이 등록하고, `enabled=false`인 작업은 등록하지 않는다. 스케줄러 빈은 항상 만들고(`@Qualifier("recovery") ObjectProvider<BackgroundJob>`로 등록된 작업을 받는다. `@ConditionalOnBean`은 자동 설정 밖에서 빈 등록 순서에 결과가 달라질 수 있어 쓰지 않는다), 등록된 작업이 없으면 `start()`가 실행기를 만들지 않아 스레드도 뜨지 않는다. 설정은 `payment.recheck.{enabled,interval,max-per-tick}`와 `payment.refund-worker.{enabled,interval,max-per-tick}`(기본 `true`, 1초, 20)다. 테스트 프로필은 두 작업을 모두 끈다. `scheduleWithFixedDelay`는 같은 작업을 동시에 두 번 돌리지 않으므로 두 작업이 스레드를 하나씩 쓴다.
- `housekeeping`도 같은 `BackgroundJobScheduler`로 만든 별도 빈(접두사 `housekeeping`, 스레드 1개, 스레드 이름 `housekeeping-N`)이다. 정리 작업 셋(`outbox-cleanup`, `processed-events-cleanup`, `idempotency-cleanup`)이 이 실행기를 나눠 쓰고, 두 스케줄러의 작업은 빈의 `@Qualifier`(`recovery`·`housekeeping`)로 갈라 받는다. 정리 배치가 느려져도 PG 재확인과 환불이 늦어지지 않는다. `gauge-refresh`(DB 집계 Gauge 갱신, 15초, 기동 직후 바로 한 번)도 이 실행기에 올라 있다(ISE-156). 설정은 `payment.metrics.gauge-refresh.{enabled, interval}`이고 정리의 `enabled`와 따로 켜고 끈다. `reconciliation`(대사, 5분`[가정]`)도 이 실행기에 올라 있다(ISE-167, 10절).
- `BackgroundJobScheduler`는 주기가 예외 없이 끝나면 `payment_job_last_success_timestamp{task}`를 갱신하고(0건을 처리한 주기도 성공이다), `Throwable`로 끝나면 `payment_job_failures_total{task}`를 올린다. `task` 값은 등록된 작업 이름이다(`recheck`, `refund-worker`, `outbox-cleanup`, `processed-events-cleanup`, `idempotency-cleanup`, `gauge-refresh`, `reconciliation`). Outbox 릴레이는 common의 전용 실행기라 `outbox_relay_last_success_timestamp`·`outbox_relay_failures_total`을 따로 남긴다. 정체 알림이 이 값을 본다. 릴레이의 마지막 성공은 라운드 반복이 예외 없이 끝난 시각이라 Kafka 장애로 전송이 실패해도 갱신된다(브로커 장애는 `outbox_pending_oldest_seconds`가 잡는다).
- 배경 작업은 PG 호출 동안 DB 연결을 잡지 않는다. 동시에 잡을 수 있는 연결은 최대 6개다.
- 연결 풀은 SA 9-4의 서비스당 20으로 둔다. 현재 `config-repo/payment-service.yml`은 `maximum-pool-size: 5`라 배경 작업만으로 풀이 찰 수 있어 고친다(S-21). `connection-timeout`은 1초`[가정]`로 둔다. 기본값 30초면 요청 스레드가 연결을 기다리다 Gateway 3초를 넘기고, TX2가 늦어져 재확인 스케줄러와 겹친다.
- DB 시한은 `config-repo/payment-service.yml`의 `spring.datasource.hikari` 아래에 둔다(ISE-163). `connection-timeout`은 풀에 빈 연결이 없을 때만 걸리고 이미 빌린 연결의 쿼리에는 시한이 없어서, DB가 응답 없이 멈추면 요청 스레드가 시한 없이 기다려 Gateway 504가 났다(2026-11-03 compose에서 `docker pause`로 확인). 연결을 빌릴 때 둘, 빌린 연결의 문장에 둘을 건다. 값은 모두 `[가정]`이다.
  - `connection-timeout: 1000`(연결 대기 1초): 풀에 빈 연결이 없을 때 기다리는 시간. 이전부터 있던 값이다.
  - `validation-timeout: 1000`(생존 검사 1초): Hikari는 0.5초 넘게 쉰 연결을 빌려줄 때 살아 있는지 검사한다. 기본값 5초면 DB가 멈췄을 때 검사만으로 Gateway 3초를 넘긴다. HikariCP 7.0.2는 이 값을 `connection-timeout`으로 줄여 주지 않으므로 직접 맞춘다(Opus 리뷰에서 찾았고 FT-4 (h)가 확인한다).
  - `data-source-properties.options: "-c statement_timeout=1500"`(문장 시한 1.5초): DB가 살아 있지만 느릴 때(잠금 대기, 과부하) 서버가 문장을 취소한다(SQLState `57014`). 서버에서 일이 끝나므로 잠금을 쥔 채 도는 쿼리가 남지 않고 연결도 다시 쓸 수 있다.
  - `data-source-properties.socketTimeout: 2`(소켓 시한 2초): 서버가 아예 응답하지 않을 때(프리즈, 네트워크 단절) 클라이언트가 읽기를 포기한다. 서버 취소가 오지 못하는 경우만 맡도록 문장 시한보다 길게 둔다. 연결은 버려지고 Hikari가 새로 만든다.
  - 승인 요청: DB가 멈추면 PG 호출 전 구간(TX1까지)은 연결을 빌릴 때 1초, 또는 빌린 연결의 첫 문장에서 2초 안에 끊겨 503 `SERVICE_UNAVAILABLE`이 된다. 시한은 문장마다 걸리므로, DB가 멈추지 않고 느리기만 하면 이 구간의 여러 문장(멱등 조회, 예매별 결제 조회, TX1 쓰기, 커밋)이 각각 1.5초 가까이 걸려 합이 3초를 넘을 수 있다(그때는 504와 같은 키 재시도, N29). TX2 구간은 기존대로 202이고 재확인이 확정한다.
  - 한계(커밋 응답 유실): TX1의 커밋 응답을 2초 안에 받지 못하면 클라이언트는 연결을 버리고 503을 준다. 그런데 커밋 메시지가 이미 서버에 닿았다면(멈춘 컨테이너의 커널 버퍼, 늦어진 WAL 기록) DB가 풀린 뒤 커밋된다. PostgreSQL은 커밋 기록 중에는 문장 시한으로 취소하지 않는다. 그러면 `REQUESTED` 결제와 멱등 기록이 남고 PG는 불리지 않았으므로, 같은 키 재시도는 202를 받고 재확인이 PG에서 주문을 찾지 못해 `DECLINED`(`NOT_SENT`)로 닫는다. 이 예매는 다시 결제할 수 없다(`PAYMENT_ALREADY_CLOSED`). FT-3의 "TX1 커밋 뒤 프로세스 중단"과 같은 결말이고, 이제 프로세스가 살아 있어도 DB 지연만으로 생길 수 있다. 시한 전에는 커밋 응답을 끝까지 기다려 승인으로 이어졌다. 재시도가 `REQUESTED`를 이어받아 승인하는 방법은 PG 호출 기록이 없어 INV-1을 깰 수 있어 거부했고, 이 결말을 한계로 둔다([0048](../../decisions/0048-payment-tx1-commit-response-loss.md)). 승인은 0건이라 돈은 안전하고, `prevent_late_approval`의 `prevented` 지표가 0이 아니면 TX1 커밋에만 긴 소켓 시한을 두는 안을 먼저 한다.
  - 배경 작업(재확인, 환불 워커, 정리 등)은 DB가 멈추면 그 주기만 실패한다. `payment_job_failures_total{task}`가 오르고 다음 주기에 다시 한다.
  - Kafka 소비자: 전에는 DB가 멈추면 리스너 스레드가 기다렸다가 이어서 처리했다. 이제는 시도마다 1~2초 안에 실패해 1·2·4초 재시도 뒤 DLQ로 간다. 대략 10~15초 넘게 멈추면 DLQ로 넘어가고 DLQ 처리기가 DB가 돌아올 때까지 다시 시도한다(FT-4 (c)의 연결 거절과 같은 경로).
  - Ops 명령 JVM(`ops` 프로필)은 `config-repo/payment-service-ops.yml`에서 소켓 시한 30초, 문장 시한 15초로 늘린다. 사람이 한 번 실행하는 명령이라 Gateway 시한과 상관없고, 부하 테스트 뒤 커진 Outbox를 `inspect`가 조회해도 끊기지 않게 한다. 로컬 `application.yml`의 ops 문서에 두면 설정 서버의 값이 이기므로 프로필 파일에 둔다.
  - 한계(마이그레이션): Flyway도 같은 풀로 마이그레이션하므로 문장 시한 1.5초와 소켓 시한 2초가 마이그레이션 문장에도 걸린다. 큰 테이블을 바꾸는 오래 걸리는 마이그레이션이 생기면 시한을 걸지 않은 Flyway 전용 연결을 따로 둬야 한다.

### 기동 웜업 (ISE-243)

- **왜**: 11/12 배포 측정에서 결제 서비스를 다시 띄운 직후 첫 단계(초당 20건)의 승인 p99가 세 번 모두 1.0~1.4초였고 다음 단계는 약 0.33초였다. 같은 구간의 Prometheus 최대값은 승인 1.4~2.0초, PG 호출 0.45~0.55초(Mock PG 지연 300ms 포함), 첫 `findByReservationId` 0.6~0.9초, `saveAndFlush` 0.11~0.12초, 커넥션 획득 0.03~0.16초였다. 첫 JPA 조회가 가장 컸지만 원인이 첫 실행 초기화(클래스 로딩, Hibernate 쿼리 준비, 연결 수립)인지 JIT 컴파일 전의 느린 실행인지는 가르지 못했다.
- **무엇을 미리 실행하나**(모두 읽기 전용, 결제·멱등 기록·이력·Outbox 행을 만들지 않는다)
  - `db`: 승인 흐름의 조회 세 가지를 없는 값으로 부른다. `IdempotencyRecordRepository.find(0, 임의 UUID)`, `findByReservationId(-1)`, `findById(-1)`.
  - `token`: 형식만 맞는 가짜 토큰으로 `PaymentTokenVerifier.verify`를 부르고 거절 예외를 삼킨다. 디코더와 서명 검증 클래스를 미리 올린다. 서명 키로 진짜 토큰을 만들지 않는다.
  - `pg`: 서버 기본 PG가 Mock일 때만 Mock 어댑터의 `findPayment`를 없는 주문 ID로 부른다. `NotFound`가 정상이고 다른 결과면 그 단계를 실패로 본다. Bulkhead·차단기(`PgApprovalGate`, `PgRecoveryGate`)와 PG 호출 지표를 거치지 않고 지표로 감싸기 전 어댑터(`UnmeteredMockPg`)를 직접 부른다. 웜업 실패가 차단기 실패로 세어지거나 호출 지표를 오염시키지 않게 하려는 것이다. 토스 어댑터는 외부 호출 한도 때문에 부르지 않는다.
- **무엇을 하지 않나**: 쓰기 경로(INSERT·UPDATE)는 데우지 않는다. 롤백하더라도 식별자 시퀀스를 쓰고, 도메인 쓰기를 가짜 데이터로 돌리는 위험이 있다. 첫 쓰기의 비용(`saveAndFlush` 0.11~0.12초)은 남는다.
- **언제**: `SmartLifecycle` 빈(`PaymentWarmup`)의 `start()`에서 동기로 실행한다. phase는 웹 서버 시작 단계(`WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE`)보다 1 작은 값이라 Tomcat이 연결을 받기 전이다. Eureka 등록은 웹 서버가 뜬 뒤이므로 그보다도 앞선다. `BackgroundJobScheduler`는 기본 phase(가장 늦음)라 웜업이 끝난 뒤에 시작해 충돌하지 않는다. 로컬 기동 로그에서 `event=PAYMENT_WARMUP step=total`이 `Tomcat started on port`보다 먼저 나오는 것을 확인했다(`PaymentWarmupStartupOrderTest` 실행 출력).
- **실패 처리**: 웜업은 최적화이지 기동 조건이 아니다. 단계의 예외는 WARN(`event=PAYMENT_WARMUP_STEP_FAILED`)으로 남기고 다음 단계로 간다.
- **설정**: `payment.warmup.enabled`(기본 true), `iterations`(기본 20, 1~1000), `max-duration`(기본 5초, 웜업 전체 상한). 범위를 벗어나면 기동하지 않는다. 테스트 프로필과 ops 프로필(`payment-service-ops.yml`)은 끈다.
- **로그와 지표 읽는 법**: 단계마다 `event=PAYMENT_WARMUP step=db firstMs=… secondMs=… restAvgMs=… iterations=…` 한 줄과 전체 `step=total totalMs=… aborted=…` 한 줄이 나온다. 첫 회가 둘째 회 이후보다 훨씬 크면 첫 실행 초기화(클래스 로딩, 연결, 쿼리 준비)가 원인이고, 둘째 회 이후도 느리다가 점점 줄면 JIT 전의 느린 실행이다. 지표는 `payment_warmup_first_call_seconds{step}`와 `payment_warmup_seconds`(기동 때 한 번 기록하는 Gauge)다.
- **로컬 테스트 환경 값**(실제 DB, 로컬 가짜 Mock PG, 20회, `PaymentWarmupIntegrationTest`. 배포 서버 값과 다르다): `db` 첫 회 184ms·둘째 회 4.4ms·나머지 평균 2.1ms, `token` 18ms·0.2ms·0.2ms, `pg` 37ms·1.1ms·0.7ms, 전체 301ms. 배포 값은 측정 전이다.
- **한계**: 회차 20번으로는 JIT의 C2 컴파일까지 데우지 못한다(수천 회가 필요해 하지 않는다). 쓰기 경로는 데우지 않는다. 인스턴스가 하나라 기동하는 시간만큼의 공백은 그대로다. 웜업은 그 공백 뒤 첫 요청의 비용만 줄인다.

## 8. 정리 작업 ([0022](../../decisions/0022-outbox-retry-and-retention.md))

| 대상 | 삭제 조건 | 기간 |
|---|---|---|
| `outbox_events` | `SENT`이고 `sent_at` 경과 | 7일 |
| `processed_events` | `processed_at` 경과 | 14일(Kafka·DLQ 보관 + 수동 재처리 기간보다 길게. 시작 때 설정값 비교) |
| `idempotency_records` | `expires_at` 경과, 연결 결제가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`가 아님 | 24시간 |

배치 1,000건 단위, `pg_try_advisory_lock`으로 중복 실행 방지, 실패는 다음 주기(10분`[가정]`)에 재시도한다. 그 밖의 결제 테이블은 지우지 않는다([data-model.md](data-model.md) 12절).

### 구현 메모 (ISE-146)

- **쿼리**: 배치마다 `DELETE … WHERE 키 IN (SELECT 키 … ORDER BY … LIMIT :batch)`로 지우고 지운 행 수를 돌려준다. `outbox_events`는 `status = 'SENT' AND sent_at < cutoff`(오래된 순)만 지운다. `PENDING`·재시도 대기·`FAILED`는 `sent_at`이 있어도 지우지 않는다(상태 조건이 지킨다). `processed_events`는 `processed_at < cutoff`, `idempotency_records`는 `expires_at < now`이면서 연결 결제(`resource_id` = `payments.id`)가 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK`가 아닌 행이다(ORIGIN·ALIAS 같음, 결제 행이 없는 고아 기록은 지운다). 결제 상태 조건은 서브쿼리의 `WHERE`에 있어 `LIMIT`이 지울 수 있는 행에만 걸린다. 남겨야 하는 기록이 배치 크기만큼 앞에 쌓여도 뒤의 지울 행이 굶지 않는다.
- **인덱스**: `outbox_events`는 V1의 `idx_outbox_events_sent_at`(`WHERE status = 'SENT'`)이 오래된 순 읽기를 받치고, `processed_events`는 `idx_processed_events_processed_at`이 받친다. `idempotency_records`에는 `expires_at` 인덱스가 없어 `V7__idempotency_expires_index.sql`로 더했다.
- **배치와 쉼**: 정리 클래스는 호출 한 번에 배치 하나·트랜잭션 하나만 한다. 반복은 작업(`CleanupJobs`)이 한다. 지운 수가 `batch-size`(1,000)보다 적거나 잠금을 못 잡아 0이면 그 주기를 끝내고, 가득 지웠으면 `pause-between-batches`(100ms)만큼 쉬고 이어 간다. 한 주기의 배치 수는 `max-batches-per-tick`(100)이 막는다(한 작업이 주기당 최대 10만 행). 남은 양은 10분 뒤 다음 주기가 이어서 지운다. 쉬는 중 인터럽트되면 인터럽트 상태를 되살리고 주기를 끝낸다.
- **중복 실행 방지: 세션 잠금 대신 트랜잭션 잠금**: ADR 0022 결정 6은 "정리 시작 때 `pg_try_advisory_lock`"이라 했지만, 세션 잠금은 풀에서 빌린 연결을 정리가 끝날 때까지 붙잡고 있어야 하고(풀로 돌려주면 잠금이 다른 사용자에게 딸려 간다), 연결이 끊기거나 반납을 잊으면 잠금이 남는다. 그래서 배치 트랜잭션 안에서 `pg_try_advisory_xact_lock(작업별 고정 키)`를 먼저 잡고, 못 잡으면 0을 돌려 이번 실행을 끝낸다. 커밋·롤백 때 저절로 풀린다. 두 인스턴스가 같은 배치를 동시에 지우지 않는다는 목적은 같고, 다른 점은 잠금이 정리 전체가 아니라 배치 하나 동안만 유지된다는 것이다(그 사이에 다른 인스턴스가 다음 배치를 가져갈 수 있다. 지우는 조건이 같아 결과는 같다). 키는 작업마다 다르다(`outbox_events` 146001, `processed_events` 146002, `idempotency_records` 146003). ADR 본문은 고치지 않았다.
- **실행기 분리**: 정리는 `housekeeping` 실행기(7절)에서 돈다. 간격은 `payment.housekeeping.interval`(10분`[가정]`)이다.
- **설정**: `payment.housekeeping.{enabled, interval(10m), batch-size(1000), max-batches-per-tick(100), pause-between-batches(100ms), outbox-sent-retention(7d), processed-events-retention(14d), topic-retention(7d), dlq-retention(7d)}`. 테스트 프로필은 `enabled=false`다. `enabled=false`면 정리 작업을 등록하지 않고 실행기도 만들지 않지만, 설정 클래스는 늘 만들어져 보관 기간 검증은 한다.
- **기동 검증**: `processed-events-retention`이 `max(topic-retention, dlq-retention)`보다 짧거나 같으면 설정 클래스를 만들 때 `IllegalArgumentException`으로 기동이 실패한다(메시지에 세 값). `topic-retention`과 `dlq-retention`은 브로커의 토픽 보관 설정을 앱이 읽는 값이 아니라 운영자가 브로커 설정에 맞춰 적는 값이다. 브로커 보관을 늘리고 이 값을 같이 올리지 않으면 검증이 의미를 잃으므로 브로커 설정을 바꿀 때 함께 고쳐야 한다.
- **재전달 방어(N11)**: `processed_events`가 정리된 뒤 같은 `RefundRequested`가 다시 와도 환불은 한 건이다. 일반 경로에서는 이력이 한 건으로 되살아나고(재처리), 환불 중복은 `refunds`의 UNIQUE와 조건부 `INSERT … SELECT … ON CONFLICT`(9절)가 막는다. DLQ 처리기 경로는 `dead_letters.event_key` UNIQUE가 먼저 걸러 이력을 다시 쓰지 않는다(`dead_letters`는 정리 대상이 아니다). 근거는 `RedeliveryAfterRetentionCleanupTest`(실제 정리 쿼리, 기준 시각을 미래로).
- **테스트 범위**: 쿼리와 잠금은 PostgreSQL 통합 테스트(`RetentionCleanupTest`, `IdempotencyRecordCleanupTest`), 보관 기간 검증·작업 등록·실행기 분리는 단위·컨텍스트 테스트다. 잠금은 두 번째 연결이 같은 키의 세션 잠금을 쥔 채 호출해 0을 확인한다.
- **미확인**: 두 인스턴스의 실제 동시 실행(잠금 경합을 두 연결로만 흉내 냈다), 대량 적체에서의 정리 소요 시간, `config-repo`의 운영 값(이 이슈는 config-repo를 고치지 않았다).

## 9. 오래된 이벤트 재전달 방어

`processed_events`가 지워진 뒤 재전달돼도 다음이 중복 결제·환불을 막는다(INV-1·INV-2).
- 조건부 UPDATE와 영향 행 확인(2절)
- `payments.reservation_id` 전체 UNIQUE([0026](../../decisions/0026-payment-one-per-reservation.md))
- `refunds.payment_id` UNIQUE와 결제 상태 조건이 있는 `INSERT … SELECT … ON CONFLICT`
- 환불 재호출 전 `findRefund`, PG 멱등키(`pg_order_id`·`pg_refund_id`)
- Reservation의 paymentId 대조(0027)

## 10. 대사 ([0029](../../decisions/0029-payment-reservation-reconciliation.md))

`housekeeping` 실행기가 5분`[가정]`마다 결제 DB와 예매 내부 조회 API를 대조해 RC-1~RC-8 불일치를 `reconciliation_mismatches`에 기록하고 Gauge로 알린다. 데이터는 고치지 않는다. 허용 지연은 10분`[가정]`이다.

### 구현 (ISE-167: 기반과 RC-7·RC-8)

규칙 중 결제 DB만으로 판단하는 RC-7·RC-8과 기록·Gauge·알림·`inspect` 기반을 구현했다. 예매 서비스 내부 조회 API로 대조하는 RC-1~RC-6은 ISE-168에서 더했고 아래 "구현 (ISE-168)"에 적었다. 규칙은 `ReconciliationRule` 인터페이스 구현 빈이고, 빈을 더하면 `ReconciliationService`가 저절로 돌린다.

- **위치**: `reconciliation` 패키지. 규칙·기록 저장소 인터페이스는 `domain`, 한 회차 실행(`ReconciliationService`)과 규칙별 한 트랜잭션 기록(`ReconciliationRecorder`)은 `application`, JDBC 저장소와 RC-7·RC-8 SQL은 `infrastructure`, 작업 등록(`ReconciliationJobConfiguration`)은 입구 어댑터라 `presentation/scheduling`이다([0043](../../decisions/0043-inbound-adapters-in-presentation.md)).
- **작업과 설정**: 작업 이름 `reconciliation`, `housekeeping` 실행기. `payment.reconciliation.{enabled, interval, allowed-lag, window, event-window}`(기본 true, 5분, 10분, 7일, 24시간`[가정]`). 테스트 프로필과 ops 프로필은 작업을 끈다. 테스트는 `ReconciliationService.runOnce(now)`를 직접 부르고, 기준 시각을 인자로 받아 `sleep` 없이 허용 지연을 넘긴다.
- **기록 규칙**: 회차마다 규칙별로 한 트랜잭션에서 (a) 찾은 불일치는 `OPEN` 행을 만들거나 같은 키의 `OPEN` 행의 `last_seen_at`·`detail`을 갱신하고, (b) 이번에 끝까지 판단했는데 다시 나오지 않은 `OPEN` 행은 `RESOLVED`로 닫는다. 결제·예매 데이터는 고치지 않는다. 한 규칙이 실패해도 나머지 규칙은 돌고, 실패가 있으면 회차 끝에 예외를 던져 `payment_job_failures_total{task="reconciliation"}`이 오른다.
- **판단 대상**: 기간 안의 대상에 더해 그 규칙의 `OPEN` 행이 가리키는 결제는 기간과 상관없이 다시 판단한다. 기간을 벗어났다는 이유로 닫지 않기 위해서다.
- **허용 지연 L**: 판단 대상이 마지막으로 갱신된 지 L이 지나지 않았으면 그 대상은 이번 회차에 판단하지 않는다(기록도, 닫기도 하지 않는다). RC-7은 결제와 환불 중 늦은 `updated_at`, RC-8은 확정 전이 이력의 `created_at`이 기준이다.
- **RC-7**: 결제의 현재 `status`가 `target_type='PAYMENT'` 마지막 이력의 `to_status`와 다르거나, 환불의 현재 `status`가 `target_type='REFUND'` 마지막 이력의 `to_status`와 다르다. 대상은 최근 7일 안에 갱신된 결제(환불 갱신 포함)이고, 마지막 이력은 `DISTINCT ON` 하나로 구한다(결제마다 조회하지 않는다). 키는 결제 하나이고 결제·환불 어긋남은 `detail`의 `payment`·`refund`에 함께 담는다. 결제는 생성 때 이력을 쓰지 않으므로 결제 이력이 없으면 `REQUESTED`를 기대 상태로 본다. ADR 0029 결정 3은 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 결제를 대사에서 뺐지만 RC-7은 이들도 본다. 결제 DB 안의 이력 일관성 검사라 결제 쪽 지표와 겹쳐도 오탐이 없기 때문이다.
- **RC-8**: 최근 24시간 `target_type='PAYMENT'` 이력의 확정 전이(→ `APPROVED`·`DECLINED`·`REFUNDED`)마다 Outbox 행(`PaymentApproved`·`PaymentDeclined`·`PaymentRefunded`)이 있어야 한다. 행은 `message_key`(예매 ID), `event_type`, 봉투 안 `payload.paymentId`로 찾는다. 모든 확정 전이는 `PaymentTransitionService`가 이력과 Outbox를 같은 트랜잭션에 쓰고, 승인 API·재확인·환불 워커·Ops `payment resolve`·`refund resolve`가 모두 이 서비스를 거친다. 이벤트를 쓰지 않는 정상 확정 전이는 없어서 규칙에서 뺀 전이가 없다. 키는 결제 하나이고 빠진 전이들은 `detail.missingEvents`에 모은다. 판단 대상은 기간(24시간) 안의 확정 전이와, 이 규칙의 `OPEN` 행이 가리키는 결제의 전이 중 "그 행이 처음 발견됐을 때 판단 범위(기간 + 허용 지연)에 있던 전이와 그 뒤의 전이"(`h.created_at >= first_detected_at - (event-window + allowed-lag)`)다. 보관 정리가 `SENT` Outbox를 지운 뒤에도 정상이던 예전 전이를 누락으로 다시 보지 않기 위해서다. 대응하는 Outbox 행은 `created_at >= 전이 시각 - 1시간`이기만 하면 인정하고 상한은 두지 않는다(사람이 runbook대로 한참 뒤에 보충한 행도 닫는다). 쿼리는 창 가지(`idx_payment_status_histories_payment_created`)와 `OPEN` 가지(결제 ID)의 `UNION`이고, 대응 확인은 `idx_outbox_events_message_key`로 예매 키를 찾는다. 기동 때 `event-window + allowed-lag < payment.housekeeping.outbox-sent-retention`(기본 7일)을 검증하고 어긋나면 기동에 실패한다.
- **한계**:
  - `housekeeping` 실행기는 스레드가 하나라 대사 한 회차가 길어지면 같은 실행기의 `gauge-refresh`(15초)와 정리 작업이 그만큼 밀린다. 그러면 `gauge-refresh` 정체 알림(1분)이 먼저 울릴 수 있다. RC-1~RC-6은 예매 서비스 호출마다 시한(읽기 2초)을 두어 회차를 묶는다. 다만 대상 예매가 많으면 호출 수(대상 수 / 100 + 변경 목록 쪽 수)만큼 길어지므로 7일 안 대상 규모가 커지면 호출 수를 본다.
  - RC-7은 `updated_at`을 건드리지 않고 상태만 바꾼 직접 SQL을 기간 밖의 오래된 결제에서는 찾지 못한다(이미 `OPEN`이면 계속 다시 판단한다). 매 회차 `payments`·`refunds`·`payment_status_histories`를 훑는다. 18만 건 기준 0.25~0.4초(Opus 리뷰 측정, 부하 없음)다. 문장 시한 1.5초에 가까워지면 `payments(updated_at)`·`refunds(updated_at)` 인덱스와 `UNION` 분리, 또는 대사 트랜잭션에 `SET LOCAL statement_timeout`과 `Connection.setNetworkTimeout`을 함께 거는 방법을 쓴다(문장 시한만 늘리면 소켓 시한 2초가 먼저 끊는다). 결제 쓰기 경로에 인덱스를 더하는 비용 때문에 이번에는 더하지 않았다.
  - RC-8의 Outbox 대응 시각은 하한만 둔다. 이미 `OPEN`인 RC-8은 보충 이벤트가 생기면 다음 회차에 `RESOLVED`가 되고, 보충하지 않고 닫는 명령은 MVP에 없다(알림이 계속 울린다).
  - 규칙마다 `pg_try_advisory_xact_lock(167000 + 규칙 순번)`을 트랜잭션 첫 문장에서 잡는다(정리 작업 키 146001~146003과 겹치지 않는다). 다른 인스턴스가 쥐고 있으면 그 규칙은 기록도 닫기도 없이 건너뛰고(결과에 `skipped`) 다음 회차에 다시 한다. 판단과 `OPEN` 키 조회가 같은 잠금 아래라 서로 다른 스냅숏을 보지 않는다. 유니크 인덱스와 `ON CONFLICT`는 잠금이 뚫려도 중복 행을 막는 두 번째 방어다.

### 구현 (ISE-168: 예매 내부 조회와 RC-1~RC-6)

예매 서비스의 읽기 전용 내부 조회 API([contracts.md](contracts.md) 5절, #283(ISE-75) 기준, 머지 전)로 결제와 예매를 대조하는 RC-1~RC-6을 더했다. 규칙 판정은 ADR 0029 규칙표를 그대로 따른다. 키는 `(예매 ID, 결제 ID)`이고, `detail`에는 `reason`과 양쪽 상태·금액·사용자 ID·시각을 담으며 개인정보는 담지 않는다.

- **준비와 판단 두 단계**: `ReconciliationRule`에 준비(`prepare`)를 더했다. 서비스가 규칙마다 준비(트랜잭션 밖) → 판단·기록(기존처럼 잠금 + 한 트랜잭션) 순서로 부른다. 준비는 예매 API를 부르는 단계라 DB 연결과 advisory 잠금을 쥐지 않는다(7절 원칙). 판단 단계는 결제 DB를 다시 읽어 준비한 스냅숏과 비교하고 원격 호출을 하지 않는다. 준비가 실패하면 그 규칙은 기록도 닫기도 없이 실패하고 `OPEN` 행은 그대로 남는다. RC-7·RC-8은 준비가 비어 있다(기본 메서드).
- **회차당 한 번 조회**: 서비스가 회차 시작에 `ReconciliationRound`(기준 시각, 정책, 스냅숏 로더)를 만들어 모든 규칙의 준비에 넘긴다. 스냅숏(`ReservationView`)은 처음 요구될 때 `ReservationViewLoader`가 한 번 가져와 RC-1~RC-6이 나눠 쓴다. 대상 예매 ID는 7일 안에 갱신된 `APPROVED`·`REFUNDED` 결제(환불 갱신 포함)와 RC-1~RC-6 `OPEN` 행의 예매이고, 묶음 조회로 100건씩 묻는다. 변경 목록(RC-4의 출발점이자 RC-2 등 결제 쪽 대상 보강)은 먼저 커서로 읽어 묶음 조회 결과와 같은 저장소에 합치고, 묶음 조회에서는 변경 목록에 이미 나온 예매를 뺀다. 조회가 실패하면 회차 문맥이 실패를 기억해 같은 회차의 나머지 규칙이 다시 부르지 않는다(규칙마다 시한을 기다리지 않는다).
- **판단 대상**: 결제 쪽 규칙(RC-1·2·3·5·6)은 각 규칙 조건에 맞는 7일 안 결제, 그 규칙의 `OPEN` 행 결제, 예매가 변경 목록에 나온 결제(결제는 오래됐는데 예매가 최근 취소된 경우)다. RC-4는 변경 목록의 `CONFIRMED` 예매와 RC-4 `OPEN` 행의 예매(묶음 조회로 다시 확인)다. 준비 때 예매 ID를 묻지 않은 결제(준비 뒤에 생긴 대상)와 허용 지연 안의 대상은 판단을 미루고 `OPEN`을 닫지 않는다.
- **RC-5·RC-6 범위**: RC-5의 예매 금액 비교와 RC-6은 `APPROVED` 결제만 본다(양쪽 값이 바뀌지 않아 `REFUNDED`까지 보면 환불해도 닫히지 않는다, ADR 0029 개정 2026-11-03). RC-5의 환불 금액 ≠ `pg_approved_amount` 비교는 `REFUNDED`를 본다.
- **허용 지연 기준**: 결제·환불·예매 중 가장 늦은 갱신 시각이다. 예매가 없으면(묶음 응답에서 빠짐) 결제·환불 시각만 쓴다.
- **RC-2의 예매 없음**: 묶음 응답에서 빠진 ID만 뜻한다. 404와 시한 초과는 조회 실패이고 예매 없음이 아니다.
- **켜고 끄기**: `payment.reconciliation.reservation.{enabled, base-url, connect-timeout, read-timeout, batch-size}`(기본 `false`, `http://reservation-service`, 1초, 2초, 100`[가정]`) 외에 `load-balanced`(기본 `true`, `false`면 LoadBalancer 없이 `base-url`로 바로 부름)와 대량 누락 기준 `missing-batch-min-size`(2)·`missing-min-count`(5)·`missing-max-ratio`(0.1), 변경 목록 쪽 수 상한 `max-change-pages`(1,000)`[가정]`. `enabled`가 `false`면 RC-1~RC-6 규칙·조회 어댑터·로더 빈을 만들지 않아 RC-7·RC-8만 돈다. 테스트 프로필과 `config-repo/payment-service.yml`은 `false`다. 예매 내부 조회 API(ISE-75)가 생기면 켠다. 켜 둔 채 API가 없으면 회차마다 RC-1~RC-6이 실패해 대사 정체 알림이 울린다.
- **HTTP 클라이언트**: 이 어댑터만의 `RestClient`를 직접 만들고 Spring Cloud LoadBalancer 인터셉터를 그 클라이언트에만 붙인다. 앱 전역 `RestClient.Builder` 빈을 만들거나 `@LoadBalanced`로 바꾸지 않아 PG 어댑터 등 다른 HTTP 호출은 영향이 없다.
- **한계**:
  - 스냅숏은 판단 기준 시각 이후에 찍힌다. 그 사이 예매가 바뀌면 L을 넘긴 실제 불일치가 다음 회차에 닫힐 수 있다. 반대로 스냅숏 뒤에 바뀐 예매는 한 회차 동안 잘못 `OPEN`으로 기록될 수 있고 다음 회차에 닫힌다. `reconciliation_mismatch_open` 알림에는 `for`가 없어 그 한 회차 동안 즉시 울릴 수 있다.
  - 대상 예매가 많으면 한 회차의 호출 수가 늘어 `housekeeping` 실행기를 오래 쥔다. 부분 결과로 판단하면 못 받은 예매가 "예매 없음"(RC-2)으로 보이므로 호출 하나라도 실패하면 회차 전체의 예매 규칙을 실패시킨다. 200 응답이라도 대량으로 빠졌으면(한 묶음이 통째로 비었거나 회차 전체 누락이 5건 이상이고 10% 초과) 같은 이유로 조회 실패로 본다. 변경 목록은 빈 쪽이면 끝내고 최대 쪽 수(1,000)를 넘으면 실패로 본다.
  - 인스턴스마다 회차가 전량을 각자 부르고(규칙 잠금은 준비 뒤 판단 때 잡는다), 실행기 스레드가 하나라 예매 호출이 길어지면 같은 실행기의 작업이 밀린다. 이번에는 바꾸지 않았다. 배포는 결제 서비스 한 대이고, 커지면 회차 잠금을 준비 전에 잡거나 전용 실행기로 옮긴다.
  - 실제 예매 서비스와는 붙여 보지 못했다. 가짜 서버(JDK `HttpServer`)로 응답 해석, 실패 처리, 호출 수, 트랜잭션 밖 호출을 확인했다.
