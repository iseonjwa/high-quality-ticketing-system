# 추첨 E2E 실행 절차 (ISE-143 준비)

상태: 준비 (2026-11-02). 서비스 사이 흐름을 compose 스택에서 한 번 돌려 보는 절차와 기대 결과다. 서비스 내부 흐름은 `LotteryScenarioTest`(ISE-151)가 자동으로 확인한다.

## 1. 지금 돌릴 수 있는 범위

| 구간 | 상태 | 이슈 |
|---|---|---|
| 설정 → 응모 → 마감 → 추첨 → 배정(예매 HELD) | 가능 | ISE-85·86·87·90 |
| 자동결제 요청 outbox → `payment.command` → 결제 리스너 → 빌링키 자동결제 → `payment.result` 발행 | 가능 | ISE-147·92·89·96 |
| `payment.result` → 예매 확정·발권 → 추첨 응모 PAID | 연결됨(11/4 ISE-150). 서비스 통합 테스트까지 확인, compose 스택에서는 미확인 | ISE-73(예매 흐름) → ISE-150 |
| 결과 미수신 만료 → 1회 연장 → FAILED → 승계 | 가능(30분 + 30분을 기다리거나 DB 시각 조정) | ISE-149·93 |
| 종결·추첨석 반환 | 연결됨(11/4 ISE-150). 서비스 통합 테스트까지 확인, compose 스택에서는 미확인 | ISE-91 |

아래 4절의 끝은 "payment DB에 `method = BILLING` 결제가 APPROVED이고 `payment.result` outbox 행이 SENT"까지다. 응모가 PAID로 바뀌는 것은 6절에서 확인한다.

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
- 결제 거절: Mock PG 장애 주입(`POST /mock-pg/faults`, 측정·테스트 프로필)으로 다음 승인을 `DECLINE`로 둔다. payment DB의 `DECLINED` 뒤의 응모 전이는 6절에서 확인한다.
- 허가 대기(백프레셔): Mock PG에 지연을 넣어 `pg-approve` 동시 10을 채우면 자동결제 메시지가 DLQ로 가지 않고 다시 읽힌다. 로그 `PG 승인 허가 거절` 빈도와 `payment.command.dlq`가 비어 있는지 본다.

## 6. 결제 결과 반영 확인(ISE-150 연결, 2026-11-05 로컬 compose에서 확인. 7절 참고)
- 응모 `PAID`, 예매 `CONFIRMED`·티켓 발권.
- 결제 거절 → 응모 `FAILED` → 예비자 승계 → 새 예매·새 자동결제.
- 승계 마감(추첨 후 30분) 뒤 종결 → `lotteries.status = DONE`, 남은 추첨석이 S석으로 일반 판매에.

## 7. 실행 기록

### 2026-11-02 로컬 compose(dev `5071f3b`, ISE-89까지)
- 방법: 위 절차를 스크립트로 실행했다(응모자 3명, 그중 1명은 빌링키 미등록, 추첨석 3, 예비 2, 응모 1분·추첨 5초 뒤).
- 결과

| 확인 | 결과 |
|---|---|
| 설정·응모 | 201, 응모 3건 `ENTERED` |
| 추첨·배정 | 3명 모두 `WON`, 순위 1·2·3, 예매 3건 `HELD`·`LOTTERY` |
| 예매 outbox | `BillingPaymentRequested` 3건 `SENT`(재시도 0) |
| 결제 | 빌링키 있는 2명 `BILLING`·`APPROVED`, 없는 1명 `DECLINED`·`BILLING_KEY_MISSING` |
| 결제 outbox | `PaymentApproved` 2건, `PaymentDeclined` 1건 `SENT` |
| 빌링키 저장 | 암호문 92자, 원문(`bk-`)이 DB에 없음 |
| 공개 조회(로그인 없이) | 401. ISE-152(#241) 머지 전이라 예상대로다 |

- 겪은 것: 스택을 막 띄운 직후 첫 로그인이 503이었다. 서비스가 healthy여도 Gateway가 Eureka에서 등록 정보를 받기까지 시간이 걸린다. 수십 초 뒤 다시 실행하면 된다.
- 확인하지 못한 것: 응모 `PAID`·예매 `CONFIRMED`(ISE-73·150 뒤), 허가 대기 백프레셔(Mock PG 지연 주입 필요).

### 2026-11-05 로컬 compose(dev `b7217fc`, ISE-150·184·190까지)
- 방법: 위 절차를 스크립트로 실행했다. 시나리오 1은 추첨석 4·예비 3에 응모자 7명(1명은 수량 2, 3명은 빌링키 없음, 1명은 취소)이고, 시나리오 2는 추첨석 5에 빌링키 없는 응모자 2명이다. 승계 마감을 기다리지 않도록 로컬 DB의 추첨 시각을 61분 앞으로 옮겨 종결을 확인했다. 관측 도구(Prometheus·Grafana·Kafka-UI·Zipkin)는 띄우지 않았다.
- 결과: 시나리오 1은 60개 확인, 시나리오 2는 12개 확인 항목이 모두 통과했다.

| 확인 | 결과 |
|---|---|
| 응모 규칙 | 시작 전·마감 뒤 409, 인증 없음 401, 멱등키 없음·매수 초과 400, 같은 키 재요청은 처음 응모 반환, 같은 키 다른 내용·중복 응모 409 |
| 취소 | `CANCELED`, 재요청 200, 마감 뒤 409, 취소한 응모는 추첨 제외 |
| 결제 성공 | 응모 `PAID`, 예매 `CONFIRMED`, 수량만큼 티켓 발권, 결제 `BILLING`·`APPROVED` |
| 결제 거절 | 빌링키 없는 2명이 `FAILED`·`BILLING_KEY_MISSING`, 예매 `RELEASED` |
| 승계 | 거절로 빈 2석에 순위 5(수량 2) 예비자가 `WON`을 거쳐 `PAID`, 순위 6은 자리가 없어 종결 때 `LOST` |
| 종결 | `DONE`. 시나리오 1은 판매된 4석이 `S`·`SOLD`로, 시나리오 2는 남은 5석이 `S`·`AVAILABLE`로 돌아옴(S석 6000) |
| 메시징 | 결제·예매 `dead_letters` 0건, 시나리오 1 시점 outbox 전부 `SENT` |

- 겪은 것: 2026-11-02 실행의 예매 1·3에 남아 있던 결제 결과가 스택을 다시 띄울 때 처리되어 `RefundRequested`(`EXPIRED_AFTER_APPROVAL`)가 나갔고, Mock PG가 `NOT_REFUNDABLE`로 거절했다. 이번 실행의 예매와는 무관하다. Mock PG 재기동으로 승인 기록이 사라졌기 때문인지는 확인하지 못했다.
- 확인하지 못한 것: 결과 미수신 만료 → 1회 연장, 재시도, 허가 대기 백프레셔, 배포 서버에서의 실행.

