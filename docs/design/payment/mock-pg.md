# Mock PG 서버 설계

`mock-pg` 모듈에 만들 모의 PG 서버의 설계다. 응답 모양은 [pg-integration.md](pg-integration.md) 6절 "Mock PG 프로토콜"이 정본이고, 이 문서는 그 표가 정하지 않은 내부 구조(원장, 원자성, 장애 주입, 프로필 제한)와 구현 순서를 정한다. 근거는 SA 6-6·11-4·11-5, S-28·S-29([sa-change-requests.md](sa-change-requests.md)), INV-1·INV-2·INV-8([invariants.md](invariants.md)), ADR 0023이다.

상태: 제안(2026-10-30 Opus 리뷰와 사용자 결정 4건 반영). 구현은 시작하지 않았다. 사용자가 이 개정본을 확인하면 10절의 첫 단계(스파이크)부터 시작한다. 결정 내용은 7절에 있다.

## 1. 완료 기준

`payment-service`의 공통 계약 테스트 `PgClientContractTest`(23개)와, 아래 표에서 "이관"으로 정한 어댑터 고유 테스트를 `FakeMockPgServer` 대신 실제 `mock-pg` 앱에 돌려 모두 통과시킨다. 어댑터가 가정하는 응답과 서버가 실제로 내는 응답이 어긋나면 이 테스트가 잡는다. 장애 유형별 통합 테스트(FT-1~FT-5)가 읽는 원장 조회(S-28)도 같은 앱이 제공한다.

어댑터 고유 테스트(`MockPgAdapterContractTest`) 8개의 처리:

| 테스트 | 처리 | 시점 |
|---|---|---|
| 망취소 409(`ORDER_CANCELED`) | 이관 | PR 1 |
| 그 밖의 409 | 이관. 첫 승인에 `DELAY`를 걸고 지연 중 같은 주문을 다시 승인해 `IN_FLIGHT` 409를 얻는다(3절) | PR 1 |
| 다른 결제의 환불 응답 | 이관 | PR 2 |
| 같은 주문 승인 1회 | 이관. 원장의 `approveCalls`로 단정 | PR 2 |
| 같은 환불 1회 | 이관. 원장의 `refundCalls`로 단정 | PR 2 |
| 멱등 헤더(`Idempotency-Key`) | 이관. 원장이 헤더를 기록한다(2절) | PR 2 |
| 연결 불가 | 서버가 필요 없는 테스트라 어댑터 단위 테스트에 남긴다 | 이관 안 함 |
| 본문 코드 없는 404 | 실제 앱이 만들 수 없는 응답이라 가짜 서버에 남긴다 | 이관 안 함 |

가짜 서버(`FakeMockPgServer`)는 이미 이 설계와 다르다(조회 때 `IN_FLIGHT`를 404로 응답하고, 같은 주문의 동시 승인이 둘 다 저장되고, 환불 검증이 없다). 그래서 가짜 서버는 이관하지 않는 두 테스트용으로만 남기고, 공통 계약의 정본으로 쓰지 않는다.

## 2. 원장

### 구조

메모리 안의 `Ledger` 객체 하나에 `ConcurrentHashMap` 세 개를 묶는다. 별도 저장소는 두지 않는다. `reset`은 맵을 비우지 않고 `AtomicReference<Ledger>`를 새 `Ledger`로 교체한다. 지연 중이던 옛 요청은 옛 `Ledger`에만 쓰므로 새 원장을 오염시키지 못한다(I1).

| 맵 | 키 | 값 |
|---|---|---|
| `orders` | `orderId` | 주문 상태: `IN_FLIGHT`(시도 토큰), `APPROVED`(`pgTxId`·금액·통화·`approvedAt`), `DECLINED`(`declineCode`), `CANCELED`. 호출 계수는 아래 |
| `paymentsByTx` | `pgTxId` | `orderId`, 승인 금액·통화(불변 복사본), 환불 누계 `refundedTotal` |
| `refunds` | `refundId` | `REFUNDED`(`refundTxId`·`pgTxId`·금액·통화·`refundedAt`)만 저장한다. 거절은 저장하지 않는다(8절) |

- `paymentsByTx`에 승인 금액·통화를 복사해 둔다. 환불 연산이 `orders`를 다시 잠그지 않게 하려는 것이다. 승인 경로는 `orders`에서 `paymentsByTx` 순서로 접근하므로, 환불 경로가 반대 순서로 잠그면 교착 위험이 생긴다.
- 주문의 `IN_FLIGHT` 값은 시도마다 새로 만든 토큰을 가진다. 지연 뒤의 확정은 현재 값이 자기 토큰일 때만 성공한다(4절).
- 모든 계수는 해당 `compute` 안에서 갱신하거나 맵을 순회해 계산한다. 전역 계수기를 따로 두지 않는다(reset 뒤 요약이 오염되는 것을 막는다).

### 호출 계수와 헤더 기록

"저장 횟수"는 서버가 멱등이라 구조상 1을 넘지 않으므로 V1의 "주문당 승인 ≤ 1"을 검증하지 못한다. 호출자가 PG를 몇 번 불렀는지는 응답 종류와 무관하게 세어야 한다. 500이나 409처럼 상태를 저장하지 않는 응답도 센다.

| 계수 | 센 대상 | 쓰임 |
|---|---|---|
| `approveCalls` | 승인 POST 전부(어떤 응답이든) | V1: `approveCalls ≤ 1`. INV-1 위반(500을 받고 다시 승인 호출)을 잡는다 |
| `lookupCalls` | 주문 조회 GET | 재확인 스케줄러가 몇 번 물었는지 |
| `cancelCalls` | 망취소 POST | 늦은 승인 방지 호출 횟수 |
| `refundCalls` | 환불 POST 전부(거절 포함) | V2: 환불 호출 횟수 |
| `refundLookupCalls` | 환불 조회 GET | 환불 워커의 "재호출 전 조회" 규칙 확인 |

승인·환불 요청의 `Idempotency-Key` 헤더 값을 주문·환불 항목에 기록한다(마지막 값과 서로 다른 값의 수). 어댑터가 주문에서 결정적으로 만든 키를 보내는지 단정하는 데 쓴다.

S-28과 [failure-scenarios.md](failure-scenarios.md) V1의 "주문별 승인 수"는 "주문별 승인 호출 수(`approveCalls`)"로 정정한다(13절).

### 재시작 영향

원장은 프로세스 메모리에만 있다. 재시작하면 모든 주문이 사라져 승인된 주문의 조회는 `NotFound`가 되고, `CANCELED`였던 주문은 다시 승인된다(INV-8 위반처럼 보인다).

- FT-3(서버 재시작)은 `payment-service`를 죽이는 시나리오라 Mock PG 재시작을 요구하지 않는다. 지금은 영속 원장을 만들지 않는다.
- 부하 테스트에서 Mock PG를 재시작하면 결과를 믿을 수 없다. 이 가정을 부하 테스트 문서에 적고, 필요해지면 그때 파일 기반 원장을 추가한다.
- 힙이 작고(`-Xmx256m`) 원장에 축출이 없어서 수십만 건이 쌓이면 메모리 부족으로 재시작되어 원장이 조용히 사라질 수 있다(추측, 측정 전). 부하 테스트 전에 `reset`을 부르고, 요약에 건수를 노출해서 확인한다.

## 3. 멱등

| 호출 | 규칙 |
|---|---|
| 승인 `orderId` | 저장된 결과(승인·거절)를 그대로 돌려준다. `IN_FLIGHT`이면 **409 `REQUEST_IN_PROGRESS`**를 준다(결정, 아래). 요청 금액이 다르더라도 저장된 값을 돌려준다 |
| 환불 `refundId` | 저장된 결과를 돌려준다. 요청의 `pgTxId`가 저장된 것과 다르면 422 `OTHER`다 |
| 장애 주입과의 관계 | 주입은 요청마다 새로 적용한다. 거절 주입은 새 주문의 결과를 `DECLINED`로 정할 때만 영향을 주고, 이미 저장된 결과의 재요청은 바꾸지 못한다 |

- 처리 중 승인 재요청에 409를 주는 것은 토스가 같은 키의 처리 중 요청에 409를 준다고 알려진 동작을 닮게 한 선택이다. ADR 0023 결과의 "구현할 때 정한다"에 대한 결정이며(7절), 어댑터는 202와 `ORDER_CANCELED`가 아닌 409를 모두 `Unknown(PROCESSING)`으로 분류하므로 서비스 동작은 바뀌지 않는다.
- 이렇게 하면 별도의 `CONFLICT` 주입 모드가 필요 없다. "그 밖의 409" 시나리오는 첫 승인에 `DELAY`를 걸고 지연 중 재요청해서 실제 서버 동작으로 만든다.
- "요청 금액이 달라도 저장된 값을 돌려준다"는 실제 PG의 동작을 확정한 것이 아니다(ADR 0023이 문서로 확정하지 못한 항목으로 둠). Mock의 선택이며, 금액 차이는 서비스의 INV-3 대조(승인 응답 금액 ≠ 결제 금액이면 `NEEDS_MANUAL_CHECK`)가 잡는다.

## 4. 승인 저장과 망취소의 원자성

주문 하나의 상태는 `orders.compute(orderId, ...)`로만 바꾼다. `ConcurrentHashMap`은 키가 속한 bin 단위로 잠그므로 `FakeMockPgServer`의 전역 락과 달리 서로 다른 주문이 거의 막히지 않는다(같은 bin에 든 키끼리는 잠깐 막힐 수 있다). 지연은 `compute` 밖에서 한다.

```
없음 --approve--> IN_FLIGHT --(지연 뒤, 내 시도일 때만)--> APPROVED | DECLINED
없음 --cancel---> CANCELED
```

| 현재 상태 | approve | cancel | GET |
|---|---|---|---|
| 없음 | `IN_FLIGHT`(새 시도 토큰)로 바꾸고 진행 | `CANCELED`로 바꾸고 `{status: CANCELED}` | 404 `NOT_FOUND` |
| `IN_FLIGHT` | **409 `REQUEST_IN_PROGRESS`** | **202 `PROCESSING`**(`CANCELED`를 주지 않는다) | 202 `PROCESSING` |
| `APPROVED` | 저장된 승인 응답 | 승인 응답(`AlreadyApproved`) | 승인 응답 |
| `DECLINED` | 저장된 거절 응답 | 상태는 `DECLINED`로 유지하고 `{status: CANCELED}`만 응답 | 거절 응답 |
| `CANCELED` | 409 `ORDER_CANCELED` | `{status: CANCELED}` | 404 `NOT_FOUND` |

- 취소가 `CANCELED`를 응답했다면 그 주문은 이후 `APPROVED`가 될 수 없다. `IN_FLIGHT → APPROVED` 전이는 현재 값이 자기 시도 토큰일 때만 성공하는 조건부 전이라 취소가 끼어들 틈이 없다(INV-8). `DECLINED`에 취소가 와도 상태는 거절 그대로이므로 이후 승인이 될 수 없다는 보장이 유지된다. 상태를 `CANCELED`로 바꾸지 않는 이유는 3절의 "저장된 거절을 그대로 돌려준다"와 어긋나지 않게 하려는 것이다. 응답 본문을 거절 응답으로 주면 어댑터가 `Unknown(PG_ERROR)`로 분류하므로(`MockPgAdapter`) `{status: CANCELED}`만 준다.
- `IN_FLIGHT`가 영구히 남지 않게 한다. 승인 스레드는 `try/finally`로 감싸 지연 중 예외나 인터럽트가 나면 자기 토큰의 `IN_FLIGHT`를 "없음"으로 되돌린다(승인한 적이 없으므로 사실과 맞다). 되돌릴 때도 현재 값이 자기 토큰일 때만 바꾼다.
- 확정 단계에서 `Ledger`가 교체되어 있으면(`reset`) 옛 `Ledger`에만 쓰고 끝난다. 새 원장에는 영향이 없다.
- `paymentsByTx` 색인은 `APPROVED`로 바꾸는 `compute` 안에서 함께 넣는다. 나중에 넣으면 조회가 `pgTxId`를 보여준 직후에 환불이 `NOT_REFUNDABLE`이 되는 창이 생긴다.
- 장애 설정은 요청이 들어올 때 한 번 읽어(스냅숏) 그 요청이 끝날 때까지 쓴다. 지연 뒤에 다시 읽지 않는다.
- `IN_FLIGHT`를 `GET`에서 202로 돌려주는 것은 프로토콜 표에 없는 동작이다. 어댑터는 202를 `Unknown(PROCESSING)`으로 해석하므로 호환된다. 표에 한 줄을 추가한다(13절).
- 응답을 잃은 승인(`DROP_RESPONSE`)도 이 표를 그대로 따른다. 저장은 끝났고 응답만 사라진 상태라 `APPROVED`로 남는다.

## 5. 장애 주입

### API

`POST /mock-pg/faults`는 현재 설정을 바꾸고 `GET /mock-pg/faults`는 현재 설정을 돌려준다. SA 11-5의 `{mode, delayMs, rate}`를 확장한다(SA 번호는 6-6, 11-4, 11-5에 흩어져 있다. 갱신은 S-29).

```json
{ "target": "APPROVE", "mode": "DROP_RESPONSE", "delayMs": 0, "rate": 1.0, "count": 1, "orderId": "..." }
```

| 필드 | 의미 |
|---|---|
| `target` | 호출 종류: `APPROVE`(승인 POST), `PAYMENT_LOOKUP`(주문 조회 GET), `CANCEL`(망취소 POST), `REFUND`(환불 POST), `REFUND_LOOKUP`(환불 조회 GET). 필수. 호출 종류마다 설정을 따로 가진다 |
| `mode` | 아래 표의 모드 |
| `delayMs` | `DELAY`의 지연 시간. 0 이상 상한(예: 60,000) 이하 |
| `rate` | 요청마다 주입을 적용할 확률 0~1. 생략하면 1 |
| `count` | 주입을 적용할 횟수(1 이상). 다 쓰면 `NONE`으로 돌아간다. 생략하면 해제할 때까지 유지(부하 테스트용) |
| `orderId` / `refundId` | 선택. 있으면 그 주문·환불을 대상으로 한 요청에만 적용하고 소비한다. 배경에서 도는 재확인 스케줄러와 환불 워커가 다른 주문의 요청으로 `count`를 가져가는 것을 막는다 |

규칙:
- 적용 순서는 호출 종류 일치 → `orderId`·`refundId` 일치 → `rate` 통과 → 모드 적용 가능 여부 확인이다. **적용되지 않은 요청은 `count`를 소비하지 않는다.** 모드가 그 호출에 적용되지 않는 경우(아래 표에서 `-`)도 같다.
- 설정은 불변 객체이고 `AtomicReference`로 갈아 끼운다. `count`는 compare-and-set으로 줄여서, 동시에 들어온 요청이 같은 한 번을 나눠 쓰지 않는다. 설정 교체와의 경합에서도 옛 설정의 `count`가 새 설정을 덮어쓰지 않는다.
- `count`는 `PgScenario`의 "바로 다음 호출 한 번에만 적용" 규칙을 구현하려고 더한다. `count`만으로는 다른 스레드의 호출이 그 한 번을 가져갈 수 있으므로, 배경 호출이 있는 통합 테스트(FT)에서는 `orderId`·`refundId` 매칭을 함께 쓴다.

### 모드와 적용 표

`O`는 적용, `-`는 해당 호출에 적용하지 않음(소비도 없음)이다.

| 모드 | 동작 | APPROVE | PAYMENT_LOOKUP | CANCEL | REFUND | REFUND_LOOKUP | 계약 테스트 |
|---|---|---|---|---|---|---|---|
| `DELAY` | `delayMs`만큼 처리를 늦춘다. 응답을 커밋하기 전에 늦춘다. 승인은 `IN_FLIGHT`로 기다린다 | O | O | O | O | O | 응답 시간 초과 |
| `DECLINE` | 새 주문을 `DECLINED(CARD_DECLINED)`로 저장한다 | O | - | - | - | - | 거절 |
| `DROP_RESPONSE` | 결과를 저장한 뒤 응답 없이 연결을 닫는다(6절) | O | - | O | O | - | 응답 유실 |
| `PROCESSING` | 저장 없이 202 `PROCESSING` | O | O | O | O | O | 처리 중 응답 |
| `SERVER_ERROR` | 500 | O | O | O | O | O | `PG_ERROR` |
| `BAD_REQUEST` | 400 `INVALID_REQUEST` | O | - | - | - | - | `CLIENT_ERROR` |
| `MISSING_AMOUNT` | 저장은 정상, 응답에서 `amount`·`currency`만 뺀다 | O | O | - | O | O | 필수 필드 누락 |
| `WRONG_AMOUNT` | 요청 금액에서 1,000원을 뺀 금액으로 **저장하고** 승인한다 | O | - | - | - | - | 금액 불일치 감지 |
| `FRACTIONAL_AMOUNT` | 0.5원을 뺀 금액으로 **저장하고** 승인한다 | O | - | - | - | - | 정수가 아닌 금액 |
| `REJECT` | 422 `{status: REJECTED, code: NOT_REFUNDABLE}`, 저장하지 않는다 | - | - | - | O | - | 환불 거절 |

- `DROP_RESPONSE`는 POST(승인·망취소·환불)에만 허용한다. JDK `HttpClient`는 응답을 한 바이트도 받지 못한 채 끊긴 GET/HEAD를 자동으로 한 번 다시 보낸다(리뷰가 JDK 소스로 확인). 조회에 `count=1`로 걸면 재시도가 성공해서 `CONNECTION_LOST`를 만들 수 없고, 서버는 요청을 두 건 받는다. POST는 자동 재시도하지 않는다.
- `WRONG_AMOUNT`·`FRACTIONAL_AMOUNT`는 저장하는 금액 자체를 바꾸므로 "원장의 의미를 건드리지 않는다"고 할 수 없다. 나머지(`PROCESSING`·`BAD_REQUEST`·`SERVER_ERROR`·`MISSING_AMOUNT`·`REJECT`)는 응답을 만드는 쪽에서만 바뀐다.
- `PROCESSING`과 `SERVER_ERROR` 같은 "응답을 만들지 않거나 오류만 내는" 모드는 상태를 저장하지 않지만 호출 계수(2절)는 센다.
- `CONFLICT` 모드는 두지 않는다(3절).

### 프로필 제한

장애 주입과 원장 조회 컨트롤러는 `@Profile`로 등록하고, 모드는 위험도로 나눈다.

| 구분 | 모드 | 허용 프로필 |
|---|---|---|
| 응답을 만지지 않는 운영형 | `DELAY`, `DECLINE`, `DROP_RESPONSE`, `PROCESSING`, `SERVER_ERROR`, `REJECT` | `test`, `loadtest` (CB OPEN 시연, 부하 측정에 쓴다) |
| PG가 잘못 응답하는 변조형 | `BAD_REQUEST`, `MISSING_AMOUNT`, `WRONG_AMOUNT`, `FRACTIONAL_AMOUNT` | `test`만 |

`prod`와 기본 프로필 `local`에서는 컨트롤러 빈이 없어 요청은 404다. `mock-pg`의 기본 프로필이 `local`이므로 `prod`나 `local` 단독으로는 열리지 않는다. 다만 `prod,loadtest`처럼 함께 켜면 열리므로 `prod`와 `loadtest`를 함께 켜지 못하게 시작 검증을 둔다.

확인은 `ApplicationContextRunner`로 프로필별 빈 존재 여부를 본다. `local`·`prod` 컨텍스트를 실제 앱으로 띄우는 방식은 쓰지 않는다. 설정 서버를 가져오는 `import`가 `!test` 프로필에서 실패하면 재시도 끝에 종료하기 때문에 설정 서버 없이는 뜨지 않는다.

compose의 프로필 문제(공유 파일이 모든 서비스를 `local`로 띄워서 측정·시연 때 `/faults`가 404가 되는 것)는 이 설계가 정하지 않고 팀 확인을 기다린다(7절, S-30).

## 6. 응답 유실 구현 방식

요구: 승인(또는 환불, 망취소)을 저장한 뒤 HTTP 응답을 한 바이트도 보내지 않고 연결을 끊어, 어댑터가 `CONNECTION_LOST`로 분류하게 한다. 지연으로 흉내 내면 `TIMEOUT`이 되어 시나리오가 달라진다.

**클라이언트 쪽 분류는 이미 증명됐다.** `FakeMockPgServer`가 `exchange.close()`로 0바이트를 보낸 채 끊을 때 어댑터는 `CONNECTION_LOST`로 분류하고 해당 테스트가 통과한다. POST가 0바이트로 끊기면 `IOException`이 나고, `DefaultRestClient`가 이를 `ResourceAccessException`으로 감싸고, 응답 시간 초과가 아니면 `CONNECTION_LOST`가 된다. 서버가 같은 상태(0바이트 뒤 종료)를 만들면 된다.

**1안(채택): Tomcat Valve에서 연결을 즉시 닫는다.**
- 컨트롤러는 결과를 저장한 뒤 응답 본문을 쓰지 않고 요청 속성만 세운다(`ResponseEntity`를 반환하면 Spring 메시지 컨버터가 본문을 쓰고 flush해서 이미 커밋된다).
- Valve는 `getNext().invoke()`가 끝난 뒤 속성이 있으면 `response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null)`을 부른다. Valve는 `org.apache.catalina.connector.Request/Response`를 직접 받는다. `RequestFacade`·`ResponseFacade`는 내부 객체의 공개 getter가 없어서 필터로는 리플렉션이 필요하다.
- Spring Boot의 Tomcat 설정에서 컨텍스트 Valve를 추가한다(리뷰가 Boot 4.1.2에 `addContextValves`가 있음을 확인).
- Tomcat 소스를 읽은 추론으로는 `CLOSE_NOW`가 이후 쓰기를 막고 flush를 건너뛰어 헤더가 소켓 버퍼에서 버려진다. **실행으로 확인하지 않았다.** 아래 스파이크에서 확인한다.
- `DELAY`는 응답을 커밋하기 전에 걸어야 한다. 헤더를 보낸 뒤 지연하면 `TIMEOUT`이 아니라 `CONNECTION_LOST`가 된다.

**대안(2안): 본문을 쓰다 예외를 던진다.** `ErrorReportValve`가 커밋 뒤의 오류에서 `CLOSE_NOW`를 건다. 동작은 하지만 상태 줄과 헤더가 이미 나가서 요구("한 바이트도")와 어긋나고, 매번 ERROR 스택 로그가 남는다. 1안이 막힐 때만 쓴다.

**제외: `Content-Length`를 실제 본문보다 크게 주는 방식.** Tomcat이 남은 바이트를 검사하지 않아서 연결이 열린 채 남고, 어댑터의 읽기 시한(테스트 300ms)이 지난 뒤 본문 스트림이 닫히며 `IOException`이 나서 `CONNECTION_LOST`가 된다. 테스트는 통과하지만 연결이 끊겨서가 아니라 시한이 지나서 통과하는, 잘못된 이유의 통과다.

검증을 두 가지 더한다.
- 원시 소켓 테스트: 장애 주입으로 `DROP_RESPONSE`를 건 서버에 소켓으로 요청을 보내 받은 바이트가 0이고 `read()`가 `-1`을 돌려주는지 단정한다.
- 계약 테스트: 경과 시간이 어댑터의 읽기 시한보다 짧은지 단정한다(시한이 지나서 통과하는 경우를 걸러낸다).

저장은 응답 단계보다 앞서 끝난다. 저장 → 응답 유실 순서를 바꾸지 않는다.

## 7. 결정 기록

2026-10-30 사용자 결정과 이전 확인 항목 5개의 결론이다. 판단 기록은 [AI 판단 기록](../../ai-judgments/junhyun/2026-10-30-mock-pg-design-review.md)에 있다.

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 개정 범위 | 리뷰 지적(차단 B1, 중요 I1~I8, 사소)을 모두 이 문서에 반영한다 | 이후 ISE-38·39·44의 테스트가 이 서버 위에서 돌아서 서버가 비결정적이면 모두 흔들린다 |
| 2 | 계약 테스트 실행 방법 | **(C) `mock-pg:bootJar`를 별도 프로세스로 띄운다.** (A) 같은 JVM과 (B) Testcontainers는 쓰지 않는다 | 앱 모듈은 plain jar가 꺼져 있고(루트 `build.gradle`), 두 모듈의 `application.yml`이 클래스패스에서 충돌하며, `payment-service` 테스트는 이미 Docker(`prepareTestInfra`)에 의존한다. 저장소에 선례가 있다(`system-tests`가 eureka `bootJar`를 `ProcessBuilder`로 실행) |
| 3 | 처리 중 승인 재요청 | **409 `REQUEST_IN_PROGRESS`**. 조회와 취소의 `IN_FLIGHT`는 202 | 토스의 알려진 동작을 닮게 하고 `CONFLICT` 주입 모드를 없앤다. 어댑터는 둘 다 `PROCESSING`으로 분류해서 서비스 동작은 같다 |
| 4 | `count`·`target` 추가 | 채택. `target`은 호출 단위, `orderId`·`refundId` 매칭을 더한다. SA 갱신은 S-29 | `count`만으로는 배경 호출이 한 번을 가져갈 수 있다(이전 문서의 "count가 동시 실행을 해결한다"는 근거는 틀렸다. `count`도 전역 상태다) |
| 5 | 계약용 모드를 `mode`에 넣는가 | 채택. 운영형과 변조형으로 나눠 프로필을 다르게 허용한다(5절) | 실제 앱으로 시나리오를 돌려야 완료 기준이 의미를 갖는다 |
| 6 | 가짜 서버 | 이관하지 않는 어댑터 고유 테스트 두 개용으로만 남긴다 | 이미 설계와 다르다(1절) |
| 7 | compose의 `local` 프로필 | **이 설계는 정하지 않는다. 팀 확인 대기**(S-30). mock-pg만 `local,loadtest`로 띄우는 override 파일을 제안한다 | `compose.yml`은 팀 공유 파일이고 팀 합의를 개인 판단으로 덮어쓰지 않는다. 시연(11/6) 전에 확인이 필요하다 |

## 8. 환불 규칙

`POST /mock-pg/refunds`의 처리 순서다. 바깥의 빠른 경로와 `compute` 안의 정본 경로를 나눈다.

1. (빠른 경로, 선택) 같은 `refundId`가 `refunds`에 있으면 저장된 결과(3절)를 돌려준다. 이 확인만으로는 정확하지 않으므로 아래 `compute`가 정본이다.
2. `paymentsByTx.compute(pgTxId, ...)` 안에서 다음을 모두 한다.
   1. `refunds`에 같은 `refundId`가 이미 있으면 그 결과를 돌려준다(재확인). 요청의 `pgTxId`가 저장된 것과 다르면 422 `OTHER`다.
   2. `pgTxId`가 없으면(`paymentsByTx`에 항목이 없으면) 422 `{status: REJECTED, code: NOT_REFUNDABLE}`. (`APPROVED` 여부는 항목의 존재로 판정한다. 색인이 `APPROVED` 확정과 같은 `compute`에서 들어가므로 항목이 있으면 `APPROVED`다.)
   3. `currency`가 항목의 승인 통화와 다르거나 `refundedTotal + amount`가 승인 금액을 넘으면 422 `NOT_REFUNDABLE`(V2의 "PG 환불 합계 ≤ 승인 금액"을 서버가 지킨다).
   4. 통과하면 `refundedTotal`을 늘리고 `refunds.put`으로 `REFUNDED`를 저장한다.
3. `DELAY`는 `compute` 밖에서, **저장하기 전에** 건다. 환불 워커가 응답 시간 초과를 받고 같은 `refundId`로 다시 호출하는 상황을 만들 수 있어야 하기 때문이다. 이때 첫 요청은 아직 저장 전이고 둘째 요청이 먼저 저장하며, 첫 요청이 깨어나면 2-1에서 저장된 결과를 만나 같은 응답을 돌려준다.

이전 설계는 1번(멱등 조회)을 원자 연산 밖에 두어, 같은 `refundId`로 동시에 들어온 두 요청이 모두 통과하는 구멍이 있었다. 둘째 요청이 한도 초과로 422를 받아 서비스가 환불을 `NEEDS_MANUAL_CHECK`로 잘못 돌리고, 부분 환불이면 `refundedTotal`이 두 번 더해지는데 원장에는 한 건만 남는다. 지금 설계에서는 2-1이 같은 `compute` 안에 있어서 이 경로가 막힌다.

거절 응답은 저장하지 않는다. 같은 `refundId`로 다시 보내면 조건을 다시 평가하고, 그 사이 조건이 바뀌었으면 성공할 수 있다. 거절된 환불의 환불 조회(GET)는 404다. 거절은 `refundCalls`에는 센다.

## 9. 원장 조회 (S-28)

`test`·`loadtest` 프로필 전용이다. 값은 모두 읽기 전용이다.

| API | 응답 |
|---|---|
| GET `/mock-pg/ledger/payments/{orderId}` | `{orderId, status, pgTxId, amount, currency, approveCalls, lookupCalls, cancelCalls, refundedTotal, idempotencyKeys}` 없으면 404 |
| GET `/mock-pg/ledger/refunds?pgTxId=` | 해당 결제의 환불 `[{refundId, refundTxId, amount, currency, refundCalls}]`와 `refundCount`, `refundedTotal` |
| GET `/mock-pg/ledger/summary` | 승인 수, 거절 수, 취소 수, 환불 수, 승인 합계, 환불 합계, **`inFlightCount`**, 주문·환불 건수 |
| GET `/mock-pg/ledger/orders?status=APPROVED` | 승인된 주문 id와 `pgTxId` 목록. V1의 "PG에만 있는 승인"을 결제 DB와 대조하는 데 쓴다 |
| POST `/mock-pg/reset` | `Ledger`와 장애 설정을 새 객체로 교체한다. 테스트 사이 격리용 |

- 목록은 `limit`·`cursor`를 받는다. `ConcurrentHashMap`의 순회 순서는 변경 중에 불안정하므로 cursor는 주문·환불이 만들어질 때 붙이는 단조 증가 순번을 기준으로 한다.
- `inFlightCount`는 PR 1부터 제공한다. 테스트의 `awaitPendingEffects()`는 이 값이 0이 될 때까지 폴링해서 구현한다(`sleep`을 쓰지 않는다).

## 10. 구현 단위와 PR 분할

PR 1에 미지수가 둘 몰려 있다. 응답 유실 방식(6절)과 실제 앱을 띄우는 테스트 기반(7절 #2)이다. 둘 중 하나가 막히면 PR 전체가 멈추므로, 첫 단계로 둘만 먼저 확인한다.

| 단계 | 범위 | 완료 확인 |
|---|---|---|
| 0 스파이크 | `mock-pg`에 승인 엔드포인트의 최소 구현 + Valve `DROP_RESPONSE` + `bootJar` 프로세스로 띄우는 테스트 기반(`mock-pg/application-test.yml`에서 Eureka·설정 서버 가져오기를 끈다) | "처음 보는 주문의 조회는 `NotFound`"와 "응답이 유실되면 `CONNECTION_LOST`" 두 계약 테스트 통과, 원시 소켓 테스트로 0바이트 확인. 막히면 6절을 이 문서에 다시 적는다 |
| 1 | 원장·상태 머신(2~4절), 승인·조회·망취소, 승인 쪽 장애 주입(`target=APPROVE·PAYMENT_LOOKUP·CANCEL`, `count`, `orderId` 매칭, 프로필 제한), `inFlightCount`, `PgScenario` 구현 | 승인·조회·망취소·멱등·늦은 승인 방지 시나리오와 "그 밖의 409" 이관 테스트. 환불 시나리오는 제외 표시 |
| 2 | 환불·환불 조회·환불 멱등(8절), `REFUND`·`REFUND_LOOKUP` 주입, 원장 조회·초기화(9절) | 공통 23개 전체 + 이관 고유 테스트 통과. 가짜 서버는 이관하지 않는 두 테스트만 남김 |

요청된 처음 분할(1: 승인·조회·망취소 / 2: 환불·장애 주입·원장 조회)과 다른 점은 장애 주입 중 승인 쪽을 PR 1로 당긴 것이다. 그대로 하면 PR 1에서 승인 시나리오 대부분(거절·지연·유실·처리 중·형식 오류)이 장애 주입 없이는 돌지 않는다.

PR 1에서 환불 테스트를 제외하는 방법: 공통 테스트 메서드는 접근 제어자가 없고(package-private) 하위 클래스가 다른 패키지에 있어서 재정의해 `@Disabled`할 수 없다. 환불 성공·환불 1회 같은 테스트는 `scenario()`를 부르지 않아 시나리오 쪽 `assume`으로도 막을 수 없다. 환불 관련 테스트(리뷰 기준 6개, 구현 때 세어 확인)에 `@Tag`를 달고 PR 1의 Gradle 태스크에서 제외한다. 공통 테스트 파일을 고치므로 PR 1 범위에 든다. 테스트마다 장애 설정을 `NONE`으로 되돌린다.

테스트:
- 서버 단위 테스트: 4절 상태 표의 칸마다.
- 실제 스레드 경합 테스트: 동시 `approve`·`cancel` 1,000회에서 "취소가 `CANCELED`를 준 주문이 `APPROVED`가 되지 않음", 같은 `refundId` 동시 환불에서 `refundedTotal`이 한 번만 늘고 응답이 같음, `count=1`을 동시에 소비해도 정확히 한 건만 적용됨, 지연 중 `reset`이 와도 새 원장이 오염되지 않음, 승인 스레드가 예외로 끝나면 `IN_FLIGHT`가 복구됨.
- 프로필별 빈 등록 테스트(`ApplicationContextRunner`), 계약 테스트, 6절의 원시 소켓 테스트.

## 11. 응답 본문 고정

어댑터는 응답 본문의 `code`·`status`로 분류한다. Boot 기본 오류 본문(`ResponseStatusException`)에는 `code`가 없어서 404가 `CLIENT_ERROR`로 분류된다. 서버는 아래 본문을 항상 직접 만든다.

| 상황 | 상태 | 본문 | 어댑터 해석 |
|---|---|---|---|
| 없는 주문·환불 조회 | 404 | `{code: NOT_FOUND}` | `NotFound` |
| 망취소된 주문 승인 | 409 | `{code: ORDER_CANCELED}` | `Declined(NOT_SENT)` |
| 처리 중 승인 재요청 | 409 | `{code: REQUEST_IN_PROGRESS}` | `Unknown(PROCESSING)` |
| 환불 거절 | 422 | `{status: REJECTED, code: NOT_REFUNDABLE 또는 OTHER}` | `RefundRejected` |
| 처리 중(조회·취소·`PROCESSING` 모드) | 202 | `{status: PROCESSING}` | `Unknown(PROCESSING)` |
| 잘못된 요청(`BAD_REQUEST` 모드) | 400 | `{code: INVALID_REQUEST}` | `Unknown(CLIENT_ERROR)` |

## 12. 문서 간 정합성 (함께 고칠 것)

이 개정과 같은 PR에서 다음을 맞춘다.

- [pg-integration.md](pg-integration.md) 6절 프로토콜 표: 처리 중 승인 재요청 409 `REQUEST_IN_PROGRESS`, GET의 `IN_FLIGHT` 202, `CANCELED` 주문의 GET 404, 취소 응답이 `DECLINED`에서도 `{status: CANCELED}`인 것, "4개 모드로 재현" 문구(장애 주입 확장). ADR 0023 결정 10도 같은 문구가 있으면 맞춘다.
- [sa-change-requests.md](sa-change-requests.md): S-28의 "주문별 승인 수"를 호출 수로 정정하고, S-29(장애 주입 확장)와 S-30(compose 프로필)을 추가한다.
- [failure-scenarios.md](failure-scenarios.md) V1: "주문별 승인 수"를 "주문별 승인 호출 수"로 정정한다. 원장 조회가 `test`·`loadtest` 프로필 전용임을 적는다.
- SA 참조 번호: 이 문서와 S-28은 "SA 10-6"을 인용했지만 SA에 그 절은 없다. 장애 주입은 6-6, 11-4, 11-5에 있다.
