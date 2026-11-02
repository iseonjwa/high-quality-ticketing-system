# 추첨 E2E 실행 절차 (ISE-143 준비)

상태: 준비 (2026-11-02). 서비스 사이 흐름을 compose 스택에서 한 번 돌려 보는 절차와 기대 결과다. 서비스 내부 흐름은 `LotteryScenarioTest`(ISE-151)가 자동으로 확인한다.

## 1. 지금 돌릴 수 있는 범위

| 구간 | 상태 | 이슈 |
|---|---|---|
| 설정 → 응모 → 마감 → 추첨 → 배정(예매 HELD) | 가능 | ISE-85·86·87·90 |
| 자동결제 요청 outbox → `payment.command` → 결제 리스너 → 빌링키 자동결제 → `payment.result` 발행 | 가능 | ISE-147·92·89·96 |
| `payment.result` → 예매 확정·발권 → 추첨 응모 PAID | **불가**(소비자 없음) | ISE-73(예매 흐름) → ISE-150 |
| 결과 미수신 만료 → 1회 연장 → FAILED → 승계 | 가능(30분 + 30분을 기다리거나 DB 시각 조정) | ISE-149·93 |
| 종결·추첨석 반환 | 결제 결과 연결 뒤 가능 | ISE-91 |

그래서 지금 E2E의 끝은 "payment DB에 `method = BILLING` 결제가 APPROVED이고 `payment.result` outbox 행이 SENT"까지다. 응모가 PAID로 바뀌는 것은 ISE-150 뒤에 확인한다.

## 2. 준비

```bash
./gradlew initLocalEnv
```
- 11/2부터 `BILLING_KEY_SECRET`이 필요하다(ADR 0042). 이미 `.env`가 있어도 한 번 다시 실행하면 빠진 키만 더한다.
- compose 스택을 띄운다(`docker compose up -d --build`). config-server가 빈 설정을 주면 TS-29를 본다.
- IntelliJ HTTP Client 환경 `local`을 쓴다. 관리자 비밀번호는 `http/http-client.private.env.json`에 `{"local": {"adminPassword": "..."}}`로 둔다(`.env`의 `ADMIN_PASSWORD`, 커밋하지 않는다).
- `venueId`는 시드 공연장 ID다. 1이 아니면 `docker compose exec postgres-reservation psql -U iseonjwa reservation -c "SELECT id, name FROM venues"`로 확인해 `http/http-client.env.json`을 고친다.

## 3. 실행 순서(`http/lottery-e2e.http`)

| 단계 | 요청 | 기대 결과 |
|---|---|---|
| 1~4 | 관리자 로그인, 공연·회차·좌석 생성 | 201. 회차 오픈은 1일 뒤라 추첨 설정 시점 규칙을 통과한다 |
| 5 | 추첨 설정(S석 10, 예비 5, 응모 1분 뒤 시작·3분 뒤 마감·4분 뒤 추첨) | 201, `warnings`에 저트래픽 시간대 경고가 있을 수 있다. S석 10개가 `LOTTERY` |
| 6~8 | 응모자 가입·로그인·빌링키 등록 | 201, 응답에 `cardLast4`만 |
| 10 | 응모(응모 시작 뒤) | 201 `ENTERED` |
| 11 | 추첨 시각 + 10초 뒤 내 결과 | `WON`이고 `reservationId`가 있다 |

응모자가 1명이면 당첨이다(좌석 10 > 응모 1). 여러 명으로 해 보려면 6~10을 반복한다.

## 4. 확인 쿼리

```sql
-- reservation DB: 당첨 예매와 자동결제 요청
SELECT id, status, source, hold_expires_at FROM reservations WHERE schedule_id = :scheduleId;
SELECT status, event_type, message_key FROM outbox_events WHERE event_type = 'BillingPaymentRequested' ORDER BY id DESC LIMIT 5;
-- payment DB: 자동결제와 결과 이벤트
SELECT reservation_id, method, status, decline_code FROM payments WHERE method = 'BILLING' ORDER BY id DESC LIMIT 5;
SELECT status, event_type, message_key FROM outbox_events WHERE event_type IN ('PaymentApproved', 'PaymentDeclined') ORDER BY id DESC LIMIT 5;
```
- 기대: reservation outbox `SENT`, payment `BILLING`·`APPROVED`, payment outbox `PaymentApproved` `SENT`.
- 빌링키를 등록하지 않은 응모자는 `DECLINED`·`BILLING_KEY_MISSING`이다.
- Kafka UI(`payment.command`, `payment.result`)에서 같은 키(`reservationId`)의 메시지를 볼 수 있다.

## 5. 실패 경로 확인(선택)
- 결제 거절: Mock PG 장애 주입(`POST /mock-pg/faults`, 측정·테스트 프로필)으로 다음 승인을 `DECLINE`로 둔다. 결과 소비자(ISE-150)가 생기기 전에는 payment DB의 `DECLINED`까지만 확인된다.
- 허가 대기(백프레셔): Mock PG에 지연을 넣어 `pg-approve` 동시 10을 채우면 자동결제 메시지가 DLQ로 가지 않고 다시 읽힌다. 로그 `PG 승인 허가 거절` 빈도와 `payment.command.dlq`가 비어 있는지 본다.

## 6. ISE-150 뒤에 더할 확인
- 응모 `PAID`, 예매 `CONFIRMED`·티켓 발권.
- 결제 거절 → 응모 `FAILED` → 예비자 승계 → 새 예매·새 자동결제.
- 승계 마감(추첨 후 30분) 뒤 종결 → `lotteries.status = DONE`, 남은 추첨석이 S석으로 일반 판매에.
