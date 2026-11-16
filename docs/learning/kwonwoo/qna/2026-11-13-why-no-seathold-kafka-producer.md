---
title: Kafka를 쓰려면 producer와 consumer가 다 필요한데, 좌석 선점에는 왜 producer를 따로 만들지 않았나
date: 2026-11-13
updated: 2026-11-13
asker: KWON-WOO
level: 입문
tags: [kafka, producer, outbox, dual-write, seathold]
---

# Kafka를 쓰려면 producer와 consumer가 다 필요한데, 좌석 선점에는 왜 producer를 따로 만들지 않았나

## 질문
원래 Kafka를 이용할 때 producer와 consumer를 만들어야 하는 것 아닌가. producer를 만들지 않은 이유는 무엇인가.

## 답
producer는 있다. 좌석 선점 코드가 Kafka에 직접 보내지 않을 뿐이고, 보내는 일은 공통 Outbox 릴레이가 한다. 그 릴레이가 이 서비스의 producer다.

### 1. 메시지가 가는 길
```
접수 API(AsyncSeatHoldService)
  └─ 한 트랜잭션: seat_hold_requests(REQUESTED) INSERT + outbox_events INSERT (OutboxWriter)
                                   │
공통 릴레이(OutboxRelay, 별도 스레드)
  └─ outbox_events에서 PENDING을 집어 KafkaTemplate.send()  ← 여기가 producer
                                   │
Kafka seat-hold.request
                                   │
소비자(SeatHoldRequestListener → SeatHoldRequestProcessor)  ← ISE-17에서 만든 consumer
```

- producer: `common/.../outbox/OutboxRelay`가 `KafkaOutboxEventSender`(내부는 Spring `KafkaTemplate`)로 보낸다. ISE-16(접수)에서 이미 쓰고 있었고, 결제 결과·환불 요청 이벤트도 같은 길로 나간다.
- consumer: ISE-17에서 새로 만든 것이다.

### 2. 왜 업무 코드가 Kafka에 직접 보내지 않나: 이중 쓰기 문제
접수는 "DB에 요청 행을 쓴다"와 "Kafka에 메시지를 보낸다" 두 가지를 해야 한다. DB와 Kafka는 한 트랜잭션으로 묶이지 않는다.

| 순서 | 실패하면 |
|---|---|
| DB 커밋 → Kafka 전송 | 커밋 뒤 전송 전에 서버가 죽거나 Kafka가 멈추면, 요청은 `REQUESTED`인데 메시지가 없다. 사용자는 30초 뒤 `TIMEOUT`만 받는다 |
| Kafka 전송 → DB 커밋 | 커밋이 실패하면 DB에 없는 요청의 메시지가 나간다. 소비자가 좌석을 잡을 수도 있다 |

Transactional Outbox는 메시지를 DB 테이블(`outbox_events`)에 업무 변경과 같은 트랜잭션으로 쓴다. 둘 다 쓰이거나 둘 다 안 쓰인다. 보내는 일은 릴레이가 커밋된 행만 읽어 나중에 하고, 실패하면 다시 보낸다. 그래서 "요청은 있는데 메시지가 영영 없는" 경우가 사라진다. 같은 메시지가 두 번 갈 수는 있어서, 소비자는 `processed_events`로 중복을 거른다(SA 10-4).

### 3. 앞서 "기존 테스트는 producer를 만들지 않았다"는 말의 뜻
코드에 producer가 없다는 뜻이 아니었다. 테스트 실행 중에 producer 객체(`KafkaProducer`)가 한 번도 생성되지 않았다는 뜻이다.
- 테스트 설정은 `messaging.outbox.relay-enabled: false`라 릴레이가 돌지 않는다. 테스트는 Outbox 행이 쓰였는지만 본다.
- `KafkaTemplate`은 처음 보낼 때 producer를 만든다. 아무도 보내지 않으니 producer 설정이 잘못돼 있어도(테스트 설정의 `delivery.timeout.ms`가 기본 `request.timeout.ms`보다 짧음) 드러나지 않았다.
- `SeatHoldKafkaFlowTest`에서 릴레이를 켜자 처음으로 producer가 만들어지며 그 설정 오류가 나왔다.

### 4. 대가와 다른 선택
- 대가: 메시지가 릴레이 주기만큼 늦게 나가고, 지금 릴레이는 같은 키를 한 건씩만 보내 인기 구역이 초당 약 2건으로 묶일 수 있다(추정, ISE-17 학습 노트 4절).
- 직접 발행: 접수 트랜잭션 커밋 뒤 `KafkaTemplate`으로 바로 보내는 경로다. 빠르지만 2절의 유실 위험을 진다. ADR 0057 결정 5대로 ISE-18에서 측정용으로 만들어 Outbox 경로와 비교한다.

## 관련
- `common/src/main/java/com/iseonjwa/common/messaging/outbox/OutboxWriter.java`, `OutboxRelay.java`, `KafkaOutboxEventSender.java`
- `reservation-service/.../seathold/application/AsyncSeatHoldService.java`(Outbox에 쓰는 곳)
- `docs/learning/kangmin/qna/2026-11-02-reservation-outbox-usage.md`
