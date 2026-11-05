---
title: DLQ(Dead Letter Queue)는 무엇이고 이 프로젝트에서는 어떻게 쓰는가
date: 2026-10-26
updated: 2026-11-04
asker: Junhyun An
level: 중급
tags: [kafka, dlq, retry, idempotency, spring-kafka]
---

# DLQ(Dead Letter Queue)는 무엇이고 이 프로젝트에서는 어떻게 쓰는가

## 질문
- 2026-10-26: DLQ가 뭔지 알려줘.
- 2026-11-04: DLQ가 뭔지, 프로젝트 어디서 어떻게 쓰이는지 개념 설명과 코드도 덧붙여서 설명해줘.

## 짧은 답
DLQ는 처리에 계속 실패하는 메시지를 본 흐름에서 빼내 따로 보관하는 큐(Kafka에서는 토픽)다. 실패한 메시지 하나가 같은 자리에서 재시도되며 뒤의 정상 메시지를 막는 일을 막고, 버리지도 않아 원인을 고친 뒤 다시 처리할 수 있다.
이 프로젝트는 Kafka 소비자 두 곳(결제의 `payment.command`, 예매의 `payment.result`)에서 쓴다. 일시 오류는 1·2·4초 간격으로 3번 재시도하고, 형식이 깨졌거나 검증에 실패한 메시지는 재시도 없이 바로 `<원본토픽>.dlq`로 보낸다. DLQ에도 전용 처리기를 붙였다. 처리기는 DLQ에 온 메시지를 DB 테이블 `dead_letters`에 남기고, 자동으로 다시 처리할 수 있으면 다시 처리하고, 못 하면 `OPEN`으로 두고 알림을 보낸다. 사람은 감사 기록이 남는 Ops 명령으로 마무리한다.

## 자세히

### 1. 먼저 알아야 할 것: Kafka 소비와 오프셋
- Kafka 토픽은 파티션으로 나뉘고, 파티션 안의 메시지는 순서대로 쌓인다. 각 메시지에는 번호(오프셋)가 붙는다.
- 소비자는 파티션을 앞에서부터 읽고, "여기까지 처리했다"를 오프셋 커밋으로 남긴다. 재시작하면 커밋한 다음부터 읽는다.
- 처리에 실패해 오프셋을 커밋하지 않으면 같은 메시지를 다시 읽는다. 그런데 파티션은 순서대로 읽으므로 **그 메시지가 끝나기 전에는 뒤의 메시지도 처리되지 않는다.** 줄 맨 앞 한 명이 막혀 줄 전체가 멈추는 셈이라 head-of-line blocking이라고 부른다.

### 2. 실패한 메시지를 어떻게 할까

| 선택 | 결과 |
|---|---|
| 무시하고 다음으로 진행(오프셋 커밋) | 메시지가 사라진다(유실). 환불 요청이었다면 환불 의무가 사라진다 |
| 같은 메시지를 계속 재시도 | 형식이 깨진 메시지(poison message, 독 메시지) 하나가 파티션 전체를 영원히 막는다 |
| 몇 번 재시도한 뒤 DLQ로 보내고 다음으로 진행 | 흐름은 계속되고 실패한 메시지는 보존된다 |

DLQ는 세 번째 선택이다. "버리지도 막히지도 않게, 옆으로 빼 둔다." 이름은 우체국에서 배달할 수 없는 우편물(dead letter)을 따로 모아 두는 곳에서 왔다.

### 3. 재시도해도 되는 실패와 안 되는 실패
- **일시 오류**: DB 연결 끊김, 잠금 대기 시간 초과, 네트워크 순단. 잠시 뒤 다시 하면 성공할 수 있으니 간격을 늘려 가며(지수 백오프) 재시도한다.
- **영구 오류**: JSON이 깨짐, 필수 필드 없음, 금액 불일치 같은 검증 실패. 100번 해도 결과가 같으니 재시도는 시간 낭비다. 바로 DLQ로 보낸다.

### 4. Kafka와 Spring Kafka에서의 DLQ
- Kafka 브로커에는 DLQ 기능이 없다. DLQ는 그냥 "다른 토픽"이고, 소비자가 실패한 메시지를 그 토픽에 직접 발행한다. RabbitMQ·SQS는 브로커가 DLQ를 제공한다는 점과 다르다.
- Spring Kafka는 이 패턴을 부품으로 제공한다.
  - `DefaultErrorHandler`: 리스너가 예외를 던지면 백오프(`BackOff`)만큼 같은 레코드를 다시 처리하고, 재시도가 끝나면 복구기(recoverer)를 부른다. 특정 예외는 재시도 없이 바로 복구기로 보내게 등록할 수 있다(`addNotRetryableExceptions`).
  - `DeadLetterPublishingRecoverer`: 복구기의 한 종류로, 실패한 레코드를 DLQ 토픽에 발행한다. 원본 토픽·파티션·오프셋과 예외 정보를 `kafka_dlt-*` 헤더로 붙인다. 기본 토픽 이름은 `<원본>.DLT`이고 바꿀 수 있다.

### 5. DLQ를 쓰면 함께 풀어야 하는 문제
- **DLQ로 보내기도 실패하면?** 그 상태로 원본 오프셋을 커밋하면 메시지를 잃는다. DLQ 전송 결과를 기다렸다가 실패하면 커밋하지 않아야 한다.
- **중복**: DLQ 전송은 성공했는데 원본 오프셋 커밋이 실패하면 원본을 다시 처리해 DLQ에 같은 메시지가 두 번 들어간다. DLQ를 읽는 쪽도 멱등이어야 한다.
- **순서**: 실패한 메시지를 빼고 다음으로 넘어가면 같은 키의 메시지 순서가 바뀔 수 있다.
- **방치**: DLQ에 쌓이기만 하고 아무도 안 보면 "보존"이 아니라 "무덤"이다. 누가 읽고, 어떻게 알리고, 어떻게 다시 처리할지를 정해야 한다.

## 이 프로젝트에서는

### 전체 지도

```text
[예매] --RefundRequested·BillingPaymentRequested--> payment.command --> [결제] PaymentCommandListener
                                                                          | 일시 오류: 1·2·4초 3회 재시도 뒤
                                                                          | 영구 오류: 바로
                                                                          v
                                                                  payment.command.dlq --> [결제] PaymentCommandDlqListener
                                                                                            -> dead_letters 테이블(결제 DB)
                                                                                            -> 자동 복구(환불 생성) 또는 OPEN + 알림
                                                                                            -> Ops dead-letter resolve

[결제] --PaymentApproved 등--> payment.result --> [예매] 결제 결과 리스너
                                                    v
                                            payment.result.dlq --> [예매] PaymentResultDlqListener
                                                                     -> dead_letters 테이블(예매 DB), 한 번 더 처리 또는 OPEN
```

설계 근거는 [ADR 0020](../../../decisions/0020-payment-refund-dlq-manual-check.md)(환불 요청 DLQ도 자동 복구 경로로), 결제 설계 [events-and-reliability.md](../../../design/payment/events-and-reliability.md) 4·5절, 운영 절차는 [manual-recovery.md](../../../design/payment/manual-recovery.md) 3절이다.

### 1단계: 공통 부품 (`common/.../messaging/consumer/`)
두 서비스가 같은 규칙을 쓰도록 오류 처리기를 공통 모듈에 두었다(ISE-27, PR #225).

`ConsumerErrorHandlers.retryThenDeadLetter`: 일반 소비자용 "재시도 뒤 DLQ"

```java
public static BackOff defaultRetryBackOff() {
    var backOff = new ExponentialBackOff(1_000, 2.0);  // 1초에서 시작해 2배씩
    backOff.setMaxAttempts(3);                         // 3회 재시도: 1·2·4초
    return backOff;
}

public static DefaultErrorHandler retryThenDeadLetter(KafkaOperations<?, ?> template, BackOff backOff) {
    var publisher = new DeadLetterPublishingRecoverer(template,
            (record, exception) -> new TopicPartition(record.topic() + ".dlq", -1));  // 파티션 -1 = 키로 배정
    publisher.setFailIfSendResultIsError(true);       // DLQ 전송이 실패하면 예외 -> 오프셋 커밋 안 함
    var handler = new DefaultErrorHandler((record, exception) -> {
        publisher.accept(record, exception);
        log.error("event=MESSAGE_DEAD_LETTERED ...");  // 전송이 성공한 뒤에만 로그
    }, backOff);
    handler.addNotRetryableExceptions(NonRetryableMessageException.class);  // 영구 오류는 재시도 없이 바로
    return handler;
}
```

- **파티션을 `-1`로 두는 이유**: 원본과 같은 파티션 번호를 쓰면, DLQ 토픽의 파티션이 원본보다 적을 때 전송이 실패한다. `-1`이면 키로 파티션을 고르고, 키는 원본 그대로라 같은 예매의 메시지는 같은 파티션에 모인다.
- **`setFailIfSendResultIsError(true)`**: DLQ 전송 결과를 기다리고 실패하면 예외를 던진다. 그러면 복구가 실패한 것으로 처리돼 원본 오프셋이 커밋되지 않고 같은 메시지를 다시 처리한다. 5절 첫 문제에 대한 답이다.
- **`NonRetryableMessageException`**: 파서가 JSON을 못 읽거나 검증에 실패하면 이 예외를 던진다(`RefundRequestedMessageParser`, `RefundRequestService.handle`, 예매의 `PaymentResultMessageParser`). 이 예외가 나면 재시도하지 않고 바로 DLQ로 간다.

`ConsumerErrorHandlers.retryForever`: DLQ 처리기용 "끝없이 재시도"

```java
public static DefaultErrorHandler retryForever(BackOff backOff) {  // 1초 시작, 2배, 최대 60초 간격, 횟수 제한 없음
    var handler = new DefaultErrorHandler(backOff);  // 복구기 없음 = 건너뛰지 않음
    handler.setClassifications(Map.of(), true);      // 모든 예외를 재시도 대상으로
    return handler;
}
```

- DLQ 처리기는 더 보낼 곳이 없다. 건너뛰면 환불 의무나 결제 결과가 사라지므로 원인이 풀릴 때까지 같은 레코드를 계속 다시 처리한다.
- `setClassifications(Map.of(), true)`가 필요한 이유: `DefaultErrorHandler`는 기본적으로 역직렬화·변환 예외를 재시도 없이 바로 복구하는데, 기본 복구기는 로그만 남기고 건너뛴다. 이 소비자에서는 어떤 예외로도 건너뛰면 안 된다.
- 최대 간격을 60초로 둔 이유: 재시도 대기 중에는 소비자가 poll하지 못한다. 대기가 `max.poll.interval.ms`(기본 5분)를 넘으면 소비자 그룹에서 쫓겨나 리밸런스가 반복된다.
- 이 처리기를 전역 `CommonErrorHandler` 빈으로 등록하지 않은 이유: 리스너마다 규칙이 달라서다. 전역 빈 하나가 모든 리스너에 붙으면 DLQ 처리기가 메시지를 건너뛸 수 있다.

`ConsumerContainerFactories.recordAck`는 리스너 컨테이너에 ack-mode `RECORD`(레코드 하나 처리에 성공할 때마다 커밋), 동시성 1(키 순서 유지), 위 오류 처리기를 붙인다.

### 2단계: 결제의 컨테이너 연결 (`PaymentCommandKafkaConfiguration`)

```java
@Bean(PaymentCommandListener.CONTAINER_FACTORY)      // payment.command
... recordAck(configurer, consumerFactory,
        paymentCommandErrorHandler(kafkaTemplate, ConsumerErrorHandlers.defaultRetryBackOff()));

@Bean(PaymentCommandDlqListener.CONTAINER_FACTORY)   // payment.command.dlq
... recordAck(configurer, consumerFactory, ConsumerErrorHandlers.retryForever());

static DefaultErrorHandler paymentCommandErrorHandler(KafkaOperations<?, ?> template, BackOff backOff) {
    var handler = ConsumerErrorHandlers.retryThenDeadLetter(template, backOff);
    // PG 허가를 못 얻은 자동결제는 DLQ로 보내지 않고 1초마다 무제한으로 다시 읽는다(백프레셔)
    handler.setBackOffFunction((record, exception) -> isPgUnavailable(exception)
            ? new FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS) : null);
    return handler;
}
```

예외가 하나 있다. 추첨 자동결제(`BillingPaymentRequested`)가 PG 호출 허가를 못 얻으면(`PgUnavailableException`) DLQ로 보내지 않는다. PG가 바쁘거나 장애라서 생긴 일시 상태이고 결제 행을 만들기 전이라 다시 해도 안전하다. 3번 재시도 뒤 DLQ로 보내면 당첨자의 결제가 DLQ로 빠져 유실처럼 보이므로, 오프셋을 넘기지 않고 1초마다 다시 읽어 PG 처리 속도에 맞춘다. 대가로 같은 파티션의 뒤 메시지(환불 포함)도 기다린다.

값 역직렬화기를 `StringDeserializer`로 둔 것도 DLQ와 관련 있다. 깨진 JSON도 역직렬화 단계에서는 실패하지 않고 문자열로 리스너에 오고, 리스너의 파서가 `NonRetryableMessageException`을 던지면 **원문 문자열 그대로** DLQ로 간다. 그래서 `ErrorHandlingDeserializer`를 쓰지 않는다.

### 3단계: DLQ 처리기 (`PaymentCommandDlqListener`, `DeadLetterService`)
ADR 0020의 판단: DLQ의 원인은 대부분 몇 분짜리 DB·네트워크 장애다. DLQ에 온 환불 요청을 모두 사람이 처리하게 하면 일이 너무 많다. 그래서 DLQ 처리기가 **같은 검증을 한 번 더 해서 통과하면 자동으로 환불을 만든다.**

```java
// DeadLetterService.run, 한 트랜잭션
var id = deadLetters.insertOpen(...);         // INSERT ... ON CONFLICT (event_key) DO NOTHING
if (id.isEmpty()) return;                     // 이미 처리한 DLQ 메시지(재전달) -> 아무것도 안 함
if (payload == null) return open(id, 사유);   // JSON을 못 읽음 -> OPEN
return switch (refundRequests.settle(payload, RefundSource.DLQ, eventId, SYSTEM)) {
    case Rejected r  -> open(id, r.message());                      // 검증 실패 -> OPEN
    case Created c   -> resolve(id, AUTO_PERSISTED, c.refundId());  // 환불 REQUESTED 생성, 환불 워커가 이어받음
    case Duplicate d -> resolve(id, DUPLICATE, d.refundId());       // 이미 환불 있음
};
// 커밋된 뒤 OPEN이면 log.error("event=DEAD_LETTER_OPEN ...")
```

| DLQ에 온 메시지 | 처리 | `dead_letters` 상태 |
|---|---|---|
| 검증 통과, 환불 행 없음 | 환불 `REQUESTED` 생성(`source=DLQ`) | `RESOLVED(AUTO_PERSISTED)` |
| 검증 통과, 환불 행 있음 | 변경 없음 | `RESOLVED(DUPLICATE)` |
| 검증 실패, JSON 깨짐 | 환불을 만들 근거 없음 | `OPEN` + 즉시 알림 |

설계 포인트는 셋이다.
- **내용 문제로는 예외를 던지지 않는다.** 검증 실패는 `OPEN`으로 기록하고 정상 커밋한다. 예외는 DB·인프라 오류일 때만 나고, 그때만 `retryForever`가 같은 레코드를 다시 처리한다. 그래서 무한 재시도인데도 독 메시지 하나가 DLQ 파티션을 영원히 막지 않는다.
- **`event_key`로 멱등**: 봉투를 읽었으면 `eventId`를, 못 읽었으면 **원본** 위치(`topic-partition-offset`)를 키로 쓴다. 원본 위치는 `kafka_dlt-original-topic`·`-partition`·`-offset` 헤더에서 읽는다. 같은 원본 메시지가 DLQ에 두 번 들어와도 DLQ 레코드의 위치는 다르지만 원본 위치는 같아서 한 건으로 묶인다(5절 "중복"의 답).
- **DB가 받지 못하는 값 정리**: PostgreSQL 텍스트는 NUL 문자를 저장하지 못한다. 원문에 NUL이 있어 저장할 때마다 예외가 나면 그 메시지를 영원히 다시 처리하게 되므로 U+FFFD로 바꿔 저장한다.

`dead_letters` 테이블(`payment-service/.../V4__dead_letters.sql`)은 원본 위치, 원문(`payload_raw`), 사유, 상태(`OPEN`·`RESOLVED`), 닫힌 방식(`AUTO_PERSISTED`·`DUPLICATE`·`REPLAYED`·`NO_OBLIGATION`)을 담는다. `event_key`에 UNIQUE가 걸려 있고, `RESOLVED`면 닫힌 방식이 반드시 있어야 한다는 CHECK 제약이 있다.

### 4단계: 알림과 사람의 마무리
- 지표: `dead_letter_open_count`, `dead_letter_open_oldest_seconds{topic}`, `payment_dlq_handler_failures_total{topic}`.
- 알림(`infra/prometheus/rules/payment-alerts.yml`): `PaymentDeadLetterOpen`(OPEN이 1건이라도 있으면 즉시), `PaymentDlqHandlerFailing`, `PaymentDlqConsumerLag`. 돈이 걸린 미해결 건이라 기준 초과가 아니라 1건부터 부른다.
- Ops 명령 `dead-letter resolve`(`DeadLetterResolveCommand`, 두 사람 확인 필요):
  - `--replay`: 원인(데이터)을 고친 뒤 DLQ 처리기와 같은 검증·생성 경로로 다시 처리한다 → `RESOLVED(REPLAYED)`.
  - `--no-obligation`: 환불 의무가 없음을 확인하고 닫는다 → `RESOLVED(NO_OBLIGATION)`, 근거 메모 필수.
- 운영자는 SQL로 상태를 직접 바꾸지 않고 감사 기록이 남는 이 명령으로만 바꾼다(ADR 0028).

### 예매 쪽 (`payment.result.dlq`)
예매 서비스도 같은 공통 부품을 쓴다(`PaymentResultKafkaConfiguration`: 일반 리스너는 `retryThenDeadLetter`, DLQ 리스너는 `retryForever`). DLQ 처리기(`PaymentResultDlqService`)는 결제 결과를 한 번 더 처리해 성공하면 `RESOLVED(REPLAYED)`, 실패하면 `OPEN`으로 예매 DB의 `dead_letters`에 남긴다. DLQ에 올 즈음(1·2·4초 재시도 뒤)이면 동시 전이 같은 일시 문제가 풀려 있는 경우가 많다는 판단이다. DB에 닿지 못한 오류는 `OPEN`으로 기록하지 않고 던져 무한 재시도에 맡긴다. `OPEN`으로 기록해 버리면 승인된 결제가 반영되지 않아 예매가 만료되고 "돈은 받았는데 티켓도 환불 요청도 없는" 상태가 되기 때문이다.

### 장애 흐름 예시
**DB가 30초 멈춘 동안 환불 요청이 온 경우**
1. `payment.command` 리스너가 DB 오류(일시 오류)로 실패 → 1·2·4초 재시도도 실패 → `payment.command.dlq`로 보내고 원본 오프셋 커밋 → 다음 메시지로 진행.
2. DLQ 처리기가 읽음 → DB가 아직 멈춰 예외 → 트랜잭션 롤백 → 1·2·4·8…초(최대 60초) 간격으로 같은 레코드 재처리, 오프셋 커밋 안 함.
3. DB 복구 → `dead_letters` 삽입, 검증 통과, 환불 `REQUESTED`(`source=DLQ`) 생성 → `RESOLVED(AUTO_PERSISTED)` → 환불 워커가 PG 환불 진행. 사람 개입 없음.

**금액이 결제와 다른 환불 요청(위조나 버그)**
1. 검증 실패 → `NonRetryableMessageException` → 재시도 없이 바로 DLQ.
2. DLQ 처리기도 검증 실패 → `OPEN`으로 기록, 커밋 → `event=DEAD_LETTER_OPEN` 로그, `PaymentDeadLetterOpen` 알림.
3. 운영자가 `inspect`로 원문·사유를 보고 `dead-letter resolve --no-obligation` 또는 데이터를 고친 뒤 `--replay`.

### 검증
- 단위: `ConsumerErrorHandlersTest`(영구 오류 즉시 DLQ, 일시 오류는 재시도 소진 뒤 DLQ, DLQ 전송 실패 시 재처리, 무제한 백오프가 60초에서 멈추고 끝나지 않음), `PaymentCommandErrorHandlerTest`, `PaymentCommandContainerWiringTest`.
- 통합(실제 테스트 DB): `PaymentCommandDlqListenerTest`(표의 세 경우, 재전달 멱등, 원본 위치 키, DB 오류 시 롤백 뒤 재처리), 예매 `PaymentResultDlqListenerTest`.
- 2026-11-04에 위 다섯 테스트 클래스를 테스트용 DB(docker compose)로 실행해 46건(공통 11, 결제 7·17·2, 예매 9)이 모두 통과했다.
- 실제 브로커에서 DLQ 토픽 도착과 오프셋 보존은 compose 스택에서 확인할 항목으로 남아 있다(설계 문서 4·5절 "미확인"). compose에서 "DLQ OPEN 알림 → `dead-letter resolve --no-obligation` → 알림 해제"는 실제로 돌렸다(2026-11-03 하루 정리).

## 면접 포인트
- **DLQ를 왜 쓰는가**: 독 메시지가 파티션 소비를 막지 않게 하면서, 유실 없이 실패 건을 보존·재처리하기 위해서다.
- **재시도와 DLQ의 관계**: 일시 오류는 지수 백오프로 재시도, 한도를 넘거나 영구 오류면 DLQ. 이 프로젝트는 영구 오류를 `NonRetryableMessageException`으로 구분해 바로 보낸다.
- **DLQ 전송이 실패하면?**: 전송 결과를 기다려 실패하면 원본 오프셋을 커밋하지 않는다(`failIfSendResultIsError`). 그러면 같은 메시지를 다시 처리한다.
- **DLQ 처리기도 실패하면?**: 더 갈 곳이 없으므로 무제한 재시도한다. 대신 내용 문제는 예외 없이 `OPEN`으로 기록해 독 메시지가 DLQ를 막지 않게 하고, 인프라 오류만 재시도한다.
- **DLQ 중복은?**: `dead_letters.event_key` UNIQUE와 `ON CONFLICT DO NOTHING`. 봉투를 못 읽은 메시지는 DLQ 레코드가 아니라 원본 위치를 키로 써서 두 번 들어와도 한 건이다.
- **Outbox와 DLQ의 차이**: Outbox는 보내는 쪽에서 "DB 커밋과 발행"의 유실을 막고, DLQ는 받는 쪽에서 "처리 실패"를 보존한다. 유실을 막는 위치가 다르다.

## 참고
- Spring for Apache Kafka 문서, Handling Exceptions(`DefaultErrorHandler`, `DeadLetterPublishingRecoverer`): https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- 이 저장소: `common/src/main/java/com/iseonjwa/common/messaging/consumer/`, `payment-service/.../refund/presentation/messaging/`, `DeadLetterService`, `V4__dead_letters.sql`, `PaymentResultDlqService`
- 결정·설계: ADR 0020·0028, `docs/design/payment/events-and-reliability.md` 4·5절, `docs/design/payment/manual-recovery.md` 3절
- 하루 정리: [2026-11-03](../daily/2026-11-03.md)
