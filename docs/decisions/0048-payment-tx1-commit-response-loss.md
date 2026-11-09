# 0048. TX1 커밋 응답 유실로 남은 REQUESTED는 재시도가 이어받지 않고, 재확인의 DECLINED(NOT_SENT) 종결을 한계로 둔다

- 상태: 채택
- 날짜: 2026-11-09
- 결정자: 안준현
- 관련 AI 판단: [ISE-169 재시도가 REQUESTED를 이어받는 안 거부](../ai-judgments/junhyun/2026-11-09-ise169-reject-retry-takeover.md) (다른 브랜치에 있는 기록이다)
- 관련 결정: [0017](0017-payment-idempotency-in-progress.md), [0018](0018-payment-pg-permit-before-tx1.md), [0023](0023-payment-pg-client-port.md), [0026](0026-payment-one-per-reservation.md)
- 관련 문서: [events-and-reliability.md](../design/payment/events-and-reliability.md) 7절, ISE-169, ISE-163(PR #278)
- 불변 조건: INV-1([invariants.md](../design/payment/invariants.md))

## 맥락

ISE-163은 결제 DB에 소켓 시한 2초와 문장 시한 1.5초를 걸었다. 그 전에는 DB가 멈추면 요청 스레드가 시한 없이 기다렸고 Gateway 504가 났다.

시한 때문에 새 경우가 생긴다. TX1(멱등 기록 ORIGIN과 결제 `REQUESTED` 저장)의 커밋 응답을 2초 안에 받지 못하면 클라이언트는 연결을 버리고 503 `SERVICE_UNAVAILABLE`을 준다. 그런데 커밋 메시지가 이미 서버에 닿았다면 DB가 풀린 뒤 커밋이 끝날 수 있다. 이때 남는 것은 PG를 부르지 않은 `REQUESTED` 결제와 멱등 기록이다.

- 같은 키 재시도는 멱등 기록을 만나 현재 상태(202)를 돌려주고 PG를 부르지 않는다(0017).
- 새 키로 같은 예매를 다시 결제하면 ALIAS로 같은 결제에 연결되고 마찬가지로 202다(0017, 0026).
- 재확인(10초 뒤)이 PG에서 주문을 찾지 못하면(NotFound) 늦은 승인 방지를 한 뒤 `DECLINED`(`NOT_SENT`)로 닫는다(0018, 0023). 그 예매는 `PAYMENT_ALREADY_CLOSED`로 다시 결제할 수 없다.

돈은 안전하다. 승인 호출이 한 번도 가지 않았으므로 승인은 0건이다. 대가는 사용자가 이 예매로 재결제하지 못하는 것이다.

코드 근거는 다음과 같다.
- `PaymentApprovalService`: PG를 부르기 전 구간의 `DataAccessException`·`TransactionException`을 503 `SERVICE_UNAVAILABLE`로 바꾼다. TX1 커밋 응답 유실도 이 경로다.
- 같은 키의 기록이 있으면 현재 상태로 응답하고 PG를 부르지 않는다(replay).
- `PaymentRecheckService`: `PgClient.NotFound`를 받으면 `preventLateApproval`을 거쳐 `DeclineReason.NOT_SENT`로 `DECLINED` 전이를 한다.

이 결말은 FT-3 "TX1 커밋 뒤 프로세스 중단"과 같다. 달라진 점은 프로세스가 살아 있어도 DB 지연만으로 생긴다는 것이다. ISE-163 전에는 시한이 없어 커밋 응답을 끝까지 기다렸고, 요청이 그대로 승인까지 갔다.

이슈 원안은 같은 키 재시도가 "PG 호출 기록 없는 `REQUESTED`"를 이어받아 승인하는 것이었다. 2026-11-09에 거부하고 한계로 남기기로 했다.

## 결정

- 같은 키 재시도와 새 키(ALIAS) 재시도는 `REQUESTED`를 만나도 승인 호출을 하지 않는다. 0017·0018·0023은 바꾸지 않는다.
- TX1 커밋 응답이 유실돼 남은 `REQUESTED`는 재확인이 늦은 승인 방지 뒤 `DECLINED`(`NOT_SENT`)로 닫는다. 승인은 0건이다.
- 이 결말을 설계의 알려진 한계로 두고 [events-and-reliability.md](../design/payment/events-and-reliability.md) 7절, [api.md](../design/payment/api.md), [failure-scenarios.md](../design/payment/failure-scenarios.md)에 적는다.

## 검토한 대안

- **A. PG 호출 직전 표시를 남기고 재시도가 이어받기(이슈 원안, 기각)**: `payments`에 PG 호출 기록이 없어 "안 불렀다"를 판정할 수 없다. `REQUESTED`에는 ① TX1 응답 유실 ② 원 요청이 PG 호출 중 ③ PG 승인 뒤 TX2 실패 ④ PG 승인 뒤 프로세스 중단이 섞여 있다. ②③④에서 이어받으면 승인 호출이 두 번 간다(INV-1). PG 호출 직전 표시를 커밋해 판정하면 그 표시 커밋의 응답도 같은 방식으로 유실될 수 있어 한계가 없어지지 않고 위치만 옮겨 간다. 게다가 모든 승인에 DB 왕복이 하나 는다.
- **D. 표시 없이 같은 PG 멱등키로 재승인(기각)**: 행당 승인 1회를 PG 멱등에 맡기는 것이다(0023 결정 7). 토스 승인 API의 멱등은 확인되지 않았다.
- **E. 원 요청이 자기 `pg_order_id`에 "보내지 않았다" 포기 표시를 남기고, 재시도가 그때만 조건부로 한 번 claim(후보로 남김)**: 안전하다. 그러나 DB가 멈춘 동안에는 표시를 쓸 수 없어 비동기 기록이 필요하고, 재시도가 먼저 202를 받으면 프론트가 폴링으로 넘어가 효과가 좁다. 0017·0018·0023을 고쳐야 한다.
- **G. TX1 커밋에만 긴 소켓 시한(예: 8초, 첫 재확인 10초보다 짧게)(후보로 남김, 재검토 때 먼저 한다)**: 결정 3개(0017·0018·0023)를 그대로 두고 원 요청이 승인까지 간다. 대기하는 스레드는 `approve` Bulkhead(동시 10)로 묶인다.

## 결과

- 얻는 것: 설계 결정을 바꾸지 않고 INV-1(행당 승인 1회)과 "돈은 안전"을 지킨다.
- 감수하는 것: DB 지연 중 TX1 커밋 응답이 유실되면 그 예매는 `DECLINED`(`NOT_SENT`)로 닫혀 재결제할 수 없다. 결제 시도 한 건이 사용자 몫으로 사라진다.
- 감시: `payment_pg_call_seconds_count{operation="prevent_late_approval",outcome="prevented"}`와 결제 이력의 `REQUESTED → DECLINED(NOT_SENT)` 전이로 본다. 라벨 값은 `PgOperation.PREVENT_LATE_APPROVAL`, `PgCallOutcome.PREVENTED`를 소문자로 바꾼 것이다(`MeteredPgClient`, `PaymentMetrics`). 이 지표는 TX1 응답 유실뿐 아니라 TX1 커밋 뒤 프로세스 중단(FT-3)에서도 오른다.
- 재검토 조건: 위 지표가 부하 측정이나 운영에서 0이 아니면 G를 먼저 한다. 토스 승인 멱등이 확인되면 D를 다시 본다.
- 확인하지 못한 것: "PostgreSQL은 커밋 기록 중에는 문장 시한으로 취소하지 않는다"는 이슈의 전제를 직접 확인하지 않았다. G를 할 때는 HikariCP가 연결을 반환할 때 network timeout을 되돌리는지도 확인해야 한다.
