---
title: Kafka는 무엇이고 어떻게 동작하며, 이 프로젝트에서는 어떻게 쓰는가
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [kafka, messaging, partition, offset, consumer-group, delivery-semantics]
---

# Kafka는 무엇이고 어떻게 동작하며, 이 프로젝트에서는 어떻게 쓰는가

## 질문
카프카 자체에 대해 좀 설명해 줄래?

## 짧은 답
Kafka는 서비스들이 이벤트(일어난 일)를 주고받게 해 주는 분산 메시지 시스템이다. 핵심은 **추가만 되는 로그(append-only log)**다. 보낸 메시지는 읽어도 지워지지 않고 보관 기간 동안 남는다. 읽는 쪽(소비자 그룹)마다 "어디까지 읽었는지"(오프셋)를 따로 기억하므로, 여러 서비스가 같은 이벤트를 각자 읽고, 실패하면 다시 읽을 수 있다.
순서는 파티션 안에서만 보장되고, 같은 키는 같은 파티션으로 간다. 전달은 보통 "적어도 한 번"이라 소비자는 중복을 견디게(멱등) 만들어야 한다.
이 프로젝트는 예매와 결제 사이(결제 결과, 환불 요청, 자동결제 요청)에 Kafka를 쓴다. 발행은 Outbox, 소비는 처리 이력(`processed_events`)으로 유실과 중복을 막는다.

## 자세히

### 1. 왜 필요한가: 서비스끼리 직접 호출할 때의 문제
결제가 승인되면 예매를 확정해야 한다. 결제 서비스가 예매 서비스 API를 HTTP로 직접 부르면 이런 문제가 생긴다.

| 직접 호출의 문제 | Kafka를 쓰면 |
|---|---|
| 예매 서비스가 죽어 있으면 결제 처리도 실패하거나 재시도 로직을 결제가 떠안는다 | 결제는 이벤트를 Kafka에 쓰고 끝난다. 예매는 살아나면 이어서 읽는다 |
| 같은 사건을 알림·정산 등 여러 곳이 알아야 하면 결제가 모두를 호출해야 한다 | 각 서비스가 같은 토픽을 자기 그룹으로 구독하면 된다. 결제는 누가 읽는지 모른다 |
| 순간적으로 요청이 몰리면 받는 쪽이 같이 넘친다 | Kafka가 버퍼가 되어 받는 쪽은 자기 속도로 읽는다 |
| 처리에 실패하면 그 요청은 사라진다 | 로그에 남아 있으니 오프셋을 되돌려 다시 읽을 수 있다 |

이렇게 보내는 쪽과 받는 쪽이 서로를 직접 모르고, 동시에 살아 있을 필요도 없게 하는 것을 **느슨한 결합(decoupling)**과 **비동기 처리**라고 한다. 대신 결과가 바로 나오지 않는다. 사용자가 결과를 기다리는 일(결제 승인 요청 자체)에는 맞지 않는다.

### 2. 구조와 용어

```text
                       토픽: payment.result
 프로듀서             ┌───────────────────────────────────────┐
 (결제 서비스) ─send─▶ │ 파티션 0: [0][1][2][3][4][5] ← 끝에 추가 │
   key=예매ID          │ 파티션 1: [0][1][2][3]                  │
                       │ 파티션 2: [0][1][2][3][4]               │
                       └───────────────────────────────────────┘
                              │                     │
          컨슈머 그룹 A (예매) │                     │ 컨슈머 그룹 B (알림)
          "파티션0은 6까지 읽음" │                     │ "파티션0은 2까지 읽음"
                              ▼                     ▼
                     각자 자기 오프셋으로 따로 읽는다
```

| 용어 | 뜻 |
|---|---|
| 브로커(broker) | Kafka 서버 한 대. 여러 대가 모여 클러스터가 된다 |
| 토픽(topic) | 이벤트 종류별 이름 붙은 로그. 예: `payment.result` |
| 파티션(partition) | 토픽을 나눈 조각. 파티션 하나는 순서가 있는 로그 하나이고, 병렬 처리의 단위다 |
| 오프셋(offset) | 파티션 안 메시지의 번호. 0부터 1씩 증가한다 |
| 프로듀서(producer) | 메시지를 쓰는 쪽 |
| 컨슈머(consumer) | 메시지를 읽는 쪽. 브로커가 밀어 주지 않고 컨슈머가 가져간다(pull) |
| 컨슈머 그룹(consumer group) | 같은 일을 나눠 하는 컨슈머 묶음. 그룹마다 오프셋을 따로 저장한다 |
| 키(key) | 메시지에 붙이는 값. 어느 파티션으로 갈지 정한다 |
| 복제(replication) | 파티션을 여러 브로커에 복사해 둔다. 하나는 리더, 나머지는 팔로워다 |
| 보관(retention) | 읽었는지와 상관없이 정해진 기간(기본 7일)이나 크기만큼 남긴다 |
| KRaft | Kafka가 자기 메타데이터(토픽·파티션·리더 정보)를 직접 관리하는 방식. 예전에는 ZooKeeper라는 별도 시스템이 필요했고, Kafka 4.0부터는 KRaft만 지원한다 |

### 3. 꼭 알아야 할 성질 네 가지

**(1) 읽어도 지워지지 않는다**
일반적인 큐(RabbitMQ 등)는 소비자가 처리 완료(ack)하면 메시지를 지운다. Kafka는 지우지 않고 오프셋만 앞으로 옮긴다. 그래서 그룹을 새로 만들거나 오프셋을 되돌리면 과거 이벤트를 다시 읽을 수 있다(재처리, 장애 복구, 새 서비스 추가에 유리).

**(2) 순서는 파티션 안에서만 보장된다**
- 같은 키는 같은 파티션으로 간다(기본: 키 해시 % 파티션 수).
- 그래서 "같은 예매에 대한 이벤트는 순서대로"가 필요하면 키를 예매 ID로 잡는다.
- 파티션이 다르면 순서 보장이 없다. 토픽 전체 순서가 필요하면 파티션을 1개로 둬야 하는데, 그러면 병렬 처리를 못 한다.
- 파티션 수를 나중에 늘리면 같은 키가 다른 파티션으로 갈 수 있어 늘리는 순간 앞뒤 순서가 섞일 수 있다.

**(3) 컨슈머 그룹: 그룹 사이는 복사, 그룹 안은 분담**
- 다른 그룹은 같은 메시지를 각자 다 받는다(예매 그룹도, 알림 그룹도 모든 결제 결과를 받는다).
- 같은 그룹 안에서는 파티션을 나눠 맡는다. **파티션 하나는 그룹 안에서 컨슈머 하나만 읽는다.** 그래서 파티션 3개에 컨슈머 5개를 두면 2개는 논다. 병렬 처리 상한 = 파티션 수다.
- 컨슈머가 들어오거나 나가면(또는 너무 오래 응답이 없으면) 파티션을 다시 나눈다. 이것을 **리밸런스**라고 하고, 그동안 소비가 잠깐 멈춘다. `max.poll.interval.ms`(기본 5분) 안에 다음 poll을 못 하면 죽은 것으로 보고 리밸런스한다.

**(4) 전달 보장: 보통 "적어도 한 번"**
오프셋을 언제 커밋하느냐로 정해진다.

| 방식 | 언제 커밋 | 결과 |
|---|---|---|
| 최대 한 번(at-most-once) | 처리 전에 커밋 | 처리 중 죽으면 그 메시지를 잃는다 |
| 적어도 한 번(at-least-once) | 처리 후에 커밋 | 처리하고 커밋 전에 죽으면 다시 처리한다(중복) |
| 정확히 한 번(exactly-once) | Kafka 트랜잭션 | Kafka에서 읽어 Kafka로 쓰는 범위에서만 보장된다. DB 쓰기 같은 외부 부수효과까지는 보장하지 않는다 |

실무 기본은 **적어도 한 번 + 멱등 소비자**다. 중복은 오더라도 처리 결과는 한 번처럼 만든다. 소비자가 "이 eventId는 이미 처리했다"를 DB에 남겨 두 번째를 건너뛰는 방식(Inbox, 처리 이력 테이블)이 흔하다.

### 4. 프로듀서 쪽 신뢰성 설정
- `acks`: 브로커가 "받았다"고 답하는 기준.
  - `0`: 기다리지 않음(빠르지만 유실 가능)
  - `1`: 리더가 쓰면 응답(리더가 복제 전에 죽으면 유실)
  - `all`: 동기화된 복제본(ISR) 모두가 쓰면 응답(가장 안전)
- `enable.idempotence=true`: 네트워크 문제로 프로듀서가 같은 메시지를 다시 보내도 브로커가 프로듀서 ID와 순번으로 중복을 걸러 한 번만 저장한다. 프로듀서 재전송으로 생기는 중복만 막고, 애플리케이션이 같은 이벤트를 두 번 `send`하는 것은 막지 못한다.
- Kafka 3.0 이후 클라이언트는 이 둘의 기본값이 `acks=all`, `enable.idempotence=true`다(KIP-679).

### 5. Kafka가 해 주지 않는 것
- **DB 커밋과 발행의 원자성**: "DB에 결제 승인 저장"과 "Kafka에 이벤트 발행"은 한 트랜잭션이 아니다. 저장 후 발행 전에 죽으면 이벤트가 사라지고, 발행 후 저장이 롤백되면 없는 사건이 알려진다. 그래서 이벤트를 같은 DB 트랜잭션으로 Outbox 테이블에 쓰고, 별도 릴레이가 Kafka로 보낸다(Transactional Outbox).
- **소비 중복 제거**: 적어도 한 번 전달이므로 소비자가 직접 멱등하게 만들어야 한다.
- **처리 실패 메시지 격리**: Kafka에는 DLQ 기능이 없다. 소비자가 다른 토픽으로 직접 보낸다.
- **파티션을 넘는 순서**: 필요하면 키 설계로 풀어야 한다.

### 6. RabbitMQ와 짧게 비교

| | Kafka | RabbitMQ |
|---|---|---|
| 모델 | 로그. 읽어도 남고 오프셋으로 위치를 기억한다 | 큐. 처리 완료하면 지운다 |
| 재처리 | 오프셋을 되돌리면 된다 | 지운 메시지는 다시 못 읽는다 |
| 여러 소비자 | 그룹마다 같은 로그를 따로 읽는다 | exchange로 큐마다 복사해 보낸다 |
| 순서 | 파티션 안 보장 | 큐 하나 안 보장(소비자가 여럿이면 깨질 수 있음) |
| 강점 | 대량 처리, 이벤트 보관·재생, 여러 서비스가 같은 이벤트 구독 | 유연한 라우팅, 메시지별 처리, 지연·우선순위 큐 |

## 이 프로젝트에서는

### 어디에 쓰고 어디에 쓰지 않나 (SA MASTER 4-2, 결정 기록 #44)

| 쓰는 곳 | 이유 |
|---|---|
| 결제 결과 → 예매 확정·좌석 반환·환불 | 서비스 경계를 넘고, 한 사건을 여러 곳이 각자 처리하며, 실패하면 다시 읽어야 한다 |
| 좌석 선점 접수(접수형, Should) | 오픈 정각 요청을 DB가 감당할 속도로 줄 세우고, 같은 구역 요청을 한 파티션에서 순서대로 처리해 락 경합을 줄인다 |
| 추첨 응모 접수 | 결과가 바로 필요 없고 마감 직전에 몰린다 |
| 추첨 결과 → 자동결제 요청 | 당첨자 결제를 Payment가 감당할 속도로 나눠 처리한다 |
| **쓰지 않음**: 결제 승인 요청, 대기열 순번, 조회 | 결제는 사용자가 결과를 기다린다. 순번 표시는 Redis가 맞다 |

### 토픽과 키 (`docs/design/payment/events-and-reliability.md` 1절)

| 토픽 | 이벤트 | 키 | 발행 → 구독(그룹) |
|---|---|---|---|
| `payment.result` | `PaymentApproved`·`PaymentDeclined`·`PaymentRefunded` | reservationId | 결제(Outbox) → 예매(`reservation.payment-result`) |
| `payment.command` | `RefundRequested`·`BillingPaymentRequested` | reservationId | 예매 → 결제(`payment.command`) |
| `*.dlq` | 소비 실패 | 원본 키 | 각 DLQ 처리기 |

키를 예매 ID로 잡아 같은 예매의 이벤트(승인 → 환불 등)가 같은 파티션에서 순서대로 처리된다. 리스너 동시성도 1로 고정했다(`ConsumerContainerFactories.recordAck`).

### 인프라 (`compose.yml`의 `kafka` 서비스)
- 이미지 `apache/kafka:4.1.1`, **KRaft 모드 단일 노드**다. 한 프로세스가 브로커와 컨트롤러를 겸한다(`KAFKA_PROCESS_ROLES: broker,controller`). ZooKeeper가 없다.
- 리스너가 둘이다. 컨테이너끼리는 `kafka:9092`(INTERNAL), 내 PC에서 테스트·도구로는 `localhost:19092`(EXTERNAL)로 붙는다. 브로커는 접속한 클라이언트에게 "앞으로 이 주소로 붙어라"(advertised listener)를 알려 주는데, 컨테이너 안과 밖에서 보이는 주소가 달라서 둘로 나눈다.
- 복제 계수 1이다. 브로커가 한 대라 브로커 자체가 디스크와 함께 사라지는 유실은 범위 밖으로 두었다(SA MASTER 3장 검증 기준의 메시지 신뢰성 항목).
- 토픽을 따로 만들지 않아 처음 쓸 때 브로커 기본값으로 자동 생성된다. 2026-11-04에 로컬 compose의 Kafka에서 `kafka-topics.sh --describe`로 확인하니 `payment.result`, `payment.command`, 두 `.dlq` 토픽 모두 **파티션 1개, 복제 계수 1**이었다. 지금은 그룹당 컨슈머 하나만 일하고, 처리량을 늘리려면 파티션을 늘려야 한다. 키가 예매 ID라 늘려도 같은 예매의 순서는 지켜진다(늘리는 순간의 경계만 주의).
- 같은 날 확인한 소비자 그룹: `reservation.payment-result`, `reservation.payment-result-dlq`, `payment.command`, `payment.command-dlq`.
- Kafka-UI(`kafka-ui` 서비스)로 토픽·메시지·컨슈머 랙을 본다. 배포 환경(`compose.deploy.yml`)에서는 이벤트 위조 경로를 줄이려고 `KAFKA_CLUSTERS_0_READONLY: "true"`로 읽기 전용이다(ADR 0020, S-24). 로컬 `compose.yml`은 읽기 전용이 아니라 테스트 메시지를 직접 넣을 수 있다.

### 공통 클라이언트 설정 (`config-repo/application.yml`의 `spring.kafka`)

```yaml
producer:
  key-serializer: StringSerializer
  value-serializer: StringSerializer   # 값은 JSON 문자열
  acks: all
  properties:
    enable.idempotence: true
consumer:
  value-deserializer: StringDeserializer   # 깨진 JSON도 문자열로 받아 리스너가 판단 -> DLQ로 원문 보존
  auto-offset-reset: earliest   # 커밋한 오프셋이 없는 새 그룹은 처음부터 읽는다(기동 전에 쌓인 이벤트를 놓치지 않음)
  enable-auto-commit: false     # 주기적 자동 커밋 끔. 처리 성공 뒤에만 커밋
listener:
  ack-mode: record              # 레코드 하나 처리에 성공할 때마다 커밋 = 적어도 한 번
  observation-enabled: true     # Zipkin 추적에 Kafka 구간을 잇는다
```

- `acks=all`인데 복제본이 1개라 지금은 리더 한 곳만 쓰면 응답한다. 브로커를 늘리면 설정을 바꾸지 않아도 더 안전해진다.
- `enable-auto-commit: false` + `ack-mode: record`가 "처리 후 커밋 = 적어도 한 번"을 만든다. 자동 커밋을 켜 두면 처리가 끝나기 전에 커밋돼 유실될 수 있다.

### 유실·중복을 막는 장치
- **발행(유실 방지)**: 결제 결과는 DB 트랜잭션 안에서 `outbox` 테이블에 쓰고, `OutboxRelay`가 읽어 `KafkaOutboxEventSender`로 보낸다(`kafka.send(topic, key, payload)`, `common/.../messaging/outbox/`). 전송 실패는 지수 백오프로 재시도하고 끝내 실패하면 `FAILED`로 남겨 알린다(ADR 0022).
- **소비(중복 방지)**: `processed_events`에 (eventId, 소비 그룹)을 업무 쓰기와 같은 트랜잭션으로 `INSERT … ON CONFLICT DO NOTHING`해, 같은 이벤트가 다시 오면 건너뛴다(`common/.../messaging/inbox/ProcessedEventRepository`). 소비 그룹이 키에 들어가므로 같은 이벤트를 다른 그룹(DLQ 처리기 등)이 처리하는 것은 막지 않는다.
- **소비 실패(격리)**: 재시도 뒤 `<토픽>.dlq`, DLQ 처리기와 `dead_letters`([DLQ 노트](2026-10-26-dlq-dead-letter-queue.md)).
- **서비스 사이 어긋남**: 대사(reconciliation)가 결제와 예매 상태를 맞춰 본다(ADR 0029).

이 조합으로 "적어도 한 번 전달"을 "업무 결과는 한 번"으로 만든다.

## 면접 포인트
- **Kafka를 왜 썼나?** 결제 결과는 서비스 경계를 넘고, 여러 처리(확정·좌석 반환·환불)로 이어지고, 실패하면 다시 읽어야 해서다. 반대로 사용자가 결과를 기다리는 결제 승인은 동기 호출로 두었다.
- **순서를 어떻게 보장하나?** 파티션 안에서만 보장되므로 키를 예매 ID로 잡아 같은 예매 이벤트를 한 파티션에 모으고, 리스너 동시성은 1로 고정했다.
- **중복은 어떻게 처리하나?** Kafka는 적어도 한 번 전달이 기본이다. 처리 후 커밋하고, 소비자는 eventId 처리 이력으로 멱등하게 만들었다. 프로듀서 멱등(`enable.idempotence`)은 프로듀서 재전송 중복만 막는다.
- **DB 저장과 발행은 어떻게 맞추나?** 한 트랜잭션이 아니므로 Outbox 테이블에 같이 쓰고 릴레이가 발행한다.
- **컨슈머를 늘리면 처리량이 늘어나나?** 파티션 수까지만 늘어난다. 지금 파티션이 1개라 컨슈머를 늘려도 하나만 일한다.

## 참고
- Apache Kafka 문서, Introduction·Design: https://kafka.apache.org/documentation/
- KIP-679 Producer will enable the strongest delivery guarantee by default: https://cwiki.apache.org/confluence/display/KAFKA/KIP-679%3A+Producer+will+enable+the+strongest+delivery+guarantee+by+default
- Kafka 4.0 ZooKeeper 제거(KRaft 전용): https://kafka.apache.org/documentation/#upgrade_4_0_0
- 이 저장소: `compose.yml`의 `kafka` 서비스, `config-repo/application.yml`의 `spring.kafka`, `common/src/main/java/com/iseonjwa/common/messaging/`, `docs/core/SA MASTER.md` 4-2, `docs/design/payment/events-and-reliability.md`
- 실행 확인(2026-11-04, 로컬 compose): `kafka-topics.sh --describe`, `kafka-consumer-groups.sh --list`
