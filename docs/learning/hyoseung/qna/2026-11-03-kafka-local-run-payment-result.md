---
title: 로컬 Kafka로 결제 결과 → 예매 확정 흐름을 실제로 돌려 보면 어떻게 움직이나
date: 2026-11-03
asker: hyos1
level: 기초
tags: [kafka, docker-compose, dlq, saga, reconciliation]
---

# 로컬 Kafka로 결제 결과 → 예매 확정 흐름을 실제로 돌려 보면 어떻게 움직이나

## 질문
준현 님이 compose에 Kafka를 넣어 두셨다는데, ISE-73·74·75가 실제 Kafka와 결제 서비스 사이에서 어떻게 돌아가는지 직접 보고 싶다.

## 답

### 1. 띄우기
```bash
docker compose -f compose.yml -f compose.local.yml up -d --build --wait --wait-timeout 300
```
- 앱 7개, PostgreSQL 3개, Redis, Kafka, Kafka-UI, Prometheus, Grafana, Zipkin이 뜬다.
- 처음에는 `.env`에 새 비밀키 3개(`ENTRY_TOKEN_SECRET`, `PAYMENT_TOKEN_SECRET`, `BILLING_KEY_SECRET`)가 없어 멈췄다. `docs/guides/workspace.md` 안내대로 `openssl rand -hex 32` 값을 넣었다. `.env`는 커밋하지 않는다.
- 예매 서비스가 뜨면 소비자 두 개가 파티션을 받는다.
```
reservation.payment-result: partitions assigned: [payment.result-0]
reservation.payment-result-dlq: partitions assigned: [payment.result.dlq-0]
```

### 2. 준비 데이터
게이트웨이와 대기열을 거치지 않으려고 예매 DB에 공연·회차(오픈 1시간 전)·좌석 6석(5만 원)을 직접 넣었다. 선점과 결제는 각 서비스 포트에 `X-User-Id`·`X-User-Role` 헤더를 붙여 바로 불렀다. 이 헤더는 원래 게이트웨이가 붙인다.

### 3. 시나리오와 결과

| # | 한 일 | 결과 |
|---|---|---|
| 1 | 좌석 1·2 선점(8082) → 받은 결제 토큰으로 결제 승인(8083, Mock PG) | 결제 서비스가 `payment.result`에 `PaymentApproved` 발행 → 예매 1 `CONFIRMED`, `payment_id=1`, 좌석 `SOLD`, 티켓 2장(`T20261103-...`), 이력 `PAYMENT_APPROVED` |
| 2 | `payment.result`에 깨진 메시지(`not json`) 직접 넣기 | 재시도 없이 `payment.result.dlq`로 감(`event=MESSAGE_DEAD_LETTERED`) → DLQ 처리기가 `dead_letters`에 `OPEN`, 키 `payment.result-0-1`, 사유 "역직렬화·봉투 오류" |
| 3 | 1번 승인 메시지(같은 `eventId`)를 한 번 더 넣기 | 아무것도 바뀌지 않음. 티켓 2장 그대로, `processed_events`가 막음 |
| 4 | 좌석 3·4 선점 → `PaymentDeclined` 직접 넣기 | 예매 2 `RELEASED`, 좌석 `AVAILABLE`, 보유 매수 0, 이력 `PAYMENT_DECLINED` |
| 5 | 좌석 5·6 선점 → DB에서 선점 기한을 1초 전으로 당김 → 실제 결제 승인 | 예매 3 `EXPIRED`(이력 `EXPIRED_AFTER_APPROVAL`) → 예매 outbox에 `RefundRequested` → `payment.command` → 결제 서비스가 환불(`refunds` `REFUNDED`, 사유 `EXPIRED_AFTER_APPROVAL`) → `PaymentRefunded` → 예매 서비스가 "환불 완료를 받았습니다" 로그 |
| 6 | ISE-75 API 호출(ISE-75 코드를 잠깐 함께 빌드) | ID 조회는 3건(없는 999는 빠짐), 변경 조회는 `CONFIRMED`인 예매 1만, 101건은 400, 게이트웨이(8080)로 부르면 404로 막힘 |

5번이 SAGA의 **보상**이다. 예매가 이미 끝났는데 돈이 나갔으니, 예매 서비스가 "환불해 줘"를 보내 결제 서비스가 돈을 돌려준다.

### 4. 직접 볼 때 쓴 명령
```bash
# 토픽 목록
docker exec iseonjwa-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# payment.result 메시지 처음부터 읽기
docker exec iseonjwa-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payment.result --from-beginning --property print.key=true --timeout-ms 5000
# 메시지 넣기(키#값). 구분자를 |로 하면 셸 파이프로 읽혀 실패했다
echo '99#not json' | docker exec -i iseonjwa-kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic payment.result --property parse.key=true --property key.separator=#
# 예매 DB 보기
docker exec -i iseonjwa-postgres-reservation-1 psql -U iseonjwa -d reservation -c "select id,status,payment_id from reservations"
```
Kafka-UI(http://localhost:8085)에서도 토픽과 메시지를 볼 수 있다.

### 5. 실제로 받은 메시지
```json
{"eventId": "62b78c4e-...", "eventType": "PaymentApproved", "version": 1, "producer": "payment-service",
 "payload": {"amount": 100000, "userId": 777, "currency": "KRW", "paymentId": 1,
             "approvedAt": "2026-11-03T11:29:25.256Z", "reservationId": 1}}
```

## 확인하지 못한 것
- 정상 메시지가 처리 중 실패해 DLQ로 간 뒤 다시 처리되는 경로는 실제 스택에서 만들지 못했다. 이 경로는 테스트(`PaymentResultDlqListenerTest`)에서 실패를 주입해 확인했다.
- 결제 쪽 대사 작업(ISE-43)은 아직 없어서 ISE-75 API를 결제 서비스가 부르는 것은 보지 못했다.
