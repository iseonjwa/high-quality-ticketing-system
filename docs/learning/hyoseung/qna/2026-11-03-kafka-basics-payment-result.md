---
title: Kafka는 무엇이고, payment.result 소비자(ISE-73)는 무엇을 하나
date: 2026-11-03
asker: hyos1
level: 기초
tags: [kafka, messaging, consumer, saga, package-structure]
---

# Kafka는 무엇이고, payment.result 소비자(ISE-73)는 무엇을 하나

## 질문
Kafka가 뭔지 모르는데 지금 만드는 ISE-73이 무엇을 하는 건가? 팀이 소비자는 presentation, 생산자는 infrastructure에 두기로 했다는데 무슨 뜻인가?

## 답

### 1. Kafka는 서비스 사이의 우체통이다
예매 서비스와 결제 서비스는 서로 다른 프로그램이고 DB도 따로 쓴다. 결제가 끝났다는 사실을 예매 서비스에 알려야 하는데, 방법은 두 가지다.

- **직접 전화(HTTP 호출)**: 결제 서비스가 예매 서비스 API를 부른다. 예매 서비스가 잠깐 꺼져 있으면 알림이 사라진다.
- **우체통(Kafka)**: 결제 서비스는 "예매 10번 결제 승인됨"이라는 편지를 우체통에 넣고 자기 일을 계속한다. 예매 서비스는 우체통을 계속 들여다보다가 편지를 꺼내 처리한다. 예매 서비스가 꺼져 있어도 편지는 우체통에 남아 있어, 다시 켜지면 이어서 읽는다.

용어는 이렇게 대응한다.

| 용어 | 비유 | 이 프로젝트 예 |
|---|---|---|
| 토픽(topic) | 우체통 이름 | `payment.result`, `payment.command` |
| 메시지(이벤트) | 편지 | `PaymentApproved {reservationId: 10, amount: 330000}` |
| 생산자(producer) | 편지를 넣는 쪽 | 결제 서비스의 `PaymentResultPublisher` |
| 소비자(consumer) | 편지를 꺼내 읽는 쪽 | 예매 서비스의 `PaymentResultListener`(ISE-73) |
| DLQ | 읽다 실패한 편지를 모아 두는 반송함 | `payment.result.dlq`(ISE-74가 처리) |

### 2. ISE-73이 하는 일
사용자가 좌석을 선점하면 예매는 HELD(결제 대기) 상태가 된다. 결제 서비스가 결과를 `payment.result`에 넣으면 예매 서비스가 꺼내서 예매 상태를 바꾼다.

```
결제 서비스 ──PaymentApproved──▶ [payment.result] ──▶ PaymentResultListener
                                                        │ 편지 읽기(파서)
                                                        ▼
                                                 PaymentResultService
                                                   - 승인: HELD → CONFIRMED, 좌석 SOLD, 티켓 발급
                                                   - 거절: HELD → RELEASED, 좌석 반환
                                                   - 늦은 승인: 좌석 반환 + 결제에 "환불해 줘" 편지 보내기
```

"환불해 줘" 편지(`RefundRequested`)는 예매 서비스가 **생산자**가 되어 `payment.command` 우체통에 넣는다. 이때 바로 Kafka로 보내지 않고 같은 DB 트랜잭션 안에서 outbox 테이블에 먼저 적는다. 공통 모듈의 릴레이가 그 행을 읽어 Kafka로 보낸다(11/02 노트 [Outbox](2026-11-02-outbox-pr-231.md) 참고).

### 3. 패키지 위치 결정(ADR 0043)
기준은 "밖에서 들어오는가, 밖으로 나가는가"다.

- **들어오는 입구 → presentation**: HTTP 컨트롤러가 요청을 받는 것처럼, Kafka 소비자도 밖에서 온 편지를 받아 application 서비스를 한 번 부른다. 그래서 컨트롤러 옆에 둔다. 위치는 `presentation/messaging`, 스케줄러는 `presentation/scheduling`이다.
- **나가는 구현 → infrastructure**: DB 저장(JPA), Redis, Kafka로 보내는 생산자·outbox 릴레이는 infrastructure다.

이 결정에 맞춰 ISE-73 파일을 옮겼다.

| 파일 | 전 | 후 |
|---|---|---|
| `PaymentResultListener` | `reservation/infrastructure/messaging` | `reservation/presentation/messaging` |
| `PaymentResultMessageParser` | 같음 | 같음 |
| `PaymentResultKafkaConfiguration` | 같음 | 같음 |
| `PaymentResultMessageParserTest` | 테스트 쪽 같음 | 테스트 쪽 같음 |

`RefundRequested` 발행은 공통 `OutboxWriter`(나가는 쪽)를 쓰고 있어 옮길 것이 없다. 결제 서비스의 `PaymentCommandListener`도 같은 날 `refund/presentation/messaging`으로 옮겨졌다.

## 더 볼 것
- `docs/decisions/0043-inbound-adapters-in-presentation.md`
- `docs/decisions/0027-reservation-payment-event-contract.md`
