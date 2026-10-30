# 0032. 좌석 선점 결과 응답에 seatIds를 포함하고 동기형 멱등 재요청 규칙을 고정한다

- 상태: 제안 (반효승 확인 필요, ISE-21)
- 날짜: 2026-10-29
- 결정자: 최권우 (좌석 선점 담당)
- 관련 AI 판단: [좌석 선점 결과 계약(ISE-21)의 멱등 재요청 처리 규칙과 응답 seatIds 포함 결정](../ai-judgments/kwonwoo/2026-10-29-seat-hold-contract-and-idempotency.md)

## 맥락

SA MASTER v3.3 11-5절의 선점 API(`POST /api/v1/reservations`) 응답 초안에는 `requestId`, `reservationId`, `reservationNo`, `status`, `holdExpiresAt`, `totalAmount`, `paymentToken`, `paymentTokenExpiresAt`만 있고 선점된 좌석 ID 목록(`seatIds`)이 빠져 있었다.

또한 SA 11-1절과 12-3절은 선점의 멱등성을 `seat_hold_requests` 테이블의 PK(`request_id` = `Idempotency-Key`)로 처리한다고 정했으나, 다음 세부 동작이 비어 있었다.

1. 동기형(M2)에서 `409 SEAT_ALREADY_HELD`나 `409 PURCHASE_LIMIT_EXCEEDED`로 실패해 트랜잭션이 롤백될 때 `seat_hold_requests` 행을 남길지 함께 롤백할지
2. 같은 `Idempotency-Key`로 재요청이 왔을 때 본문(`scheduleId`, `seatIds`) 일치 여부를 어떻게 판별할지
3. 이미 선점에 성공한 키로 재요청이 왔는데 그사이 예매가 만료(`EXPIRED`)·해제(`RELEASED`)·취소(`CANCELED`)된 경우 무엇을 응답할지
4. 버튼 연타로 첫 요청의 트랜잭션이 커밋되기 직전에 같은 키의 두 번째 요청이 도착해 PK/Unique 충돌이 날 때 어떻게 처리할지

## 결정

1. **선점 성공 응답에 `seatIds` 포함**:
   - 동기형 `201 Created`의 `data`와 접수형 결과 조회(`GET /api/v1/reservations/requests/{requestId}`)의 `data.reservation` 블록에 오름차순으로 정렬된 `seatIds`(`List<Long>`)를 포함한다.
   - 프론트엔드가 결제 이동 화면에서 선점된 좌석 목록을 바로 표시하거나 생략할 수 있도록 선택지를 제공한다.
2. **동기형(M2) 멱등 기록과 실패 롤백 규칙**:
   - 요청 진입 시 먼저 `seat_hold_requests`(`request_id = Idempotency-Key`)를 조회한다.
   - **기존 행이 없는 경우**: 한 트랜잭션 안에서 매수 한도 조건부 UPDATE(D1) → 회차 좌석 오름차순 조건부 UPDATE → `reservations`·`reservation_seats`·이력·`seat_hold_requests(HELD)` 저장을 수행한다. 매수 한도 초과나 좌석 경합으로 실패하면 **트랜잭션 전체를 롤백**해 실패한 `seat_hold_requests` 행을 남기지 않는다(오픈 정각 이선좌 폭주 시 불필요한 DB INSERT 부하 방지).
   - **기존 행이 있는 경우 (멱등 재요청)**:
     - 요청 사용자(`X-User-Id`), `scheduleId`, 오름차순 정렬한 `seatIds`를 기존 `seat_hold_requests` 행의 `(user_id, schedule_id, seat_ids)`와 비교한다. 하나라도 다르면 **`409 IDEMPOTENCY_KEY_REUSED`**를 반환한다.
     - 모두 같고 연결된 예매가 `HELD`(또는 `CONFIRMED`)이면 좌석 잠금을 다시 시도하지 않고 기존 예매 정보와 결제 토큰으로 **`201 Created`**를 그대로 반환한다. (단, `now >= hold_expires_at - 10초`이거나 이미 `CONFIRMED`면 `paymentToken`과 `paymentTokenExpiresAt`은 `null`로 내려 만료된 토큰 사용을 막는다.)
     - 모두 같지만 연결된 예매가 이미 `EXPIRED`·`RELEASED`·`CANCELED`로 닫혔으면 더 이상 그 선점으로 결제할 수 없으므로 **`409 RESERVATION_NOT_PAYABLE`**을 반환한다.
3. **동시 중복 요청(따닥)의 PK/Unique 충돌 처리**:
   - 같은 키의 두 요청이 동시에 진입해 한쪽이 `seat_hold_requests.request_id` 또는 `reservations.request_id` 유니크 제약 위반(`DataIntegrityViolationException`)을 만나면 `500` 에러를 내지 않고, 롤백 후 별도 읽기 트랜잭션에서 방금 커밋된 기존 행을 다시 읽어 위 2번의 기존 행 규칙대로 `201 Created`(또는 본문 불일치 시 `409 IDEMPOTENCY_KEY_REUSED`)를 반환한다.
4. **접수형(M4) 멱등 재요청 규칙**:
   - 입구 API가 `seat_hold_requests(REQUESTED)`와 `outbox_events`를 한 트랜잭션에 커밋하므로, 같은 키·같은 본문 재요청 시 `POST /api/v1/reservations`는 항상 `202 Accepted`와 `Location: /api/v1/reservations/requests/{requestId}`를 돌려주고, 최종 성공(`HELD`)·거절(`REJECTED` + `reasonCode`)은 결과 조회 API에서 확인한다.

## 검토한 대안

- **동기형 실패(409)도 `REQUIRES_NEW` 트랜잭션으로 `seat_hold_requests(REJECTED)`에 커밋**:
  - 동기형과 접수형의 DB 기록이 완전히 같아진다. 하지만 오픈 정각에는 성공 건보다 `409 SEAT_ALREADY_HELD`(이선좌) 실패 건이 훨씬 많으므로, 실패할 때마다 DB INSERT를 커밋하면 쓰기 부하와 WAL이 급증한다. 동기형은 HTTP 응답으로 즉시 거절 사유를 돌려주므로 실패 행을 남기지 않는 쪽을 택했다.
- **응답에서 `seatIds` 생략 (SA 초안 유지)**:
  - 페이로드가 수십 바이트 줄어들지만, 프론트엔드가 요청 상태를 별도로 들고 있지 않거나 새로고침·재시도로 응답을 받았을 때 어떤 좌석이 선점됐는지 알기 위해 예매 상세 조회를 한 번 더 호출해야 한다.

## 결과

- 얻는 것:
  - 프론트엔드가 선점 응답 하나만으로 결제 진입과 선택 좌석 확인을 모두 처리할 수 있다.
  - 네트워크 타임아웃이나 연타로 같은 요청을 재전송한 정상 사용자가 자기가 잡은 좌석 때문에 `409 SEAT_ALREADY_HELD`를 받는 사고를 막고, 오픈 정각 이선좌 실패의 불필요한 DB 쓰기를 없앤다.
- 감수하는 것:
  - 동기형에서는 거절된 선점 시도가 `seat_hold_requests` 테이블에 남지 않는다(접수형 전환 시에만 `REJECTED`가 기록됨). 이선좌 비율 지표는 메트릭 카운터로 수집한다.
  - 동시 요청의 유니크 제약 위반을 잡아서 재조회하는 예외 처리 코드가 필요하다.
- 후속 작업:
  - [`docs/design/reservation/contracts.md`](../design/reservation/contracts.md)에 계약 명세를 작성하고 예매 흐름 담당(반효승) 확인 후 확정으로 바꾼다.
  - [`docs/core/SA MASTER.md`](../core/SA%20MASTER.md) 11-5절과 12-3절에 반영한다.
