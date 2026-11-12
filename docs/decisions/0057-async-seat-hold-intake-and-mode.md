# 0057. 접수형 선점은 접수에서 이미 잡힌 좌석을 즉시 거절하고, 모드는 기동 시 정하며, 결과 조회는 모드와 상관없이 연다

- 상태: 채택. 단, 결정 1(접수형 POST의 즉시 409)은 ISE-120 담당(안준현) 확인 뒤 확정한다.
- 날짜: 2026-11-12
- 결정자: 최권우 (좌석 선점 담당)
- 관련 이슈: ISE-16, ISE-17, ISE-18, ISE-120
- 관련 AI 판단: [ISE-16 남은 결정 다섯 가지를 추천안대로 정함](../ai-judgments/kwonwoo/2026-11-12-async-seat-hold-intake-decisions.md)
- 관련 학습 노트: [남은 결정의 트레이드오프](../learning/kwonwoo/qna/2026-11-12-ise16-remaining-decisions-tradeoffs.md)
- 바꾸는 계약: `docs/design/reservation/contracts.md` 2-2, 3-2 ⑤의 접수형 칸, 4절. SA 10-1 D4의 "좌석 행을 잠그지 않고 접수한다"는 그대로 지킨다. Notion 항목은 대체하지 않는다.

## 맥락
ISE-16은 접수형 선점(`seat-hold.mode=async`)의 접수 API와 결과 조회 API를 만든다. 메시지 키는 [ADR 0056](0056-async-seat-hold-key-for-mixed-sections.md)으로 정했다. 구현 전에 남은 결정을 정리하면서 확인한 사실은 다음과 같다.
- 동기형 K1-H 측정에서 선점 응답 중 409가 59,476건이었고, 201과 409 중 87%였다(`docs/design/reservation/k6-seathold-bottleneck-plan.md` 5-4절).
- 접수는 구역을 정하려고 요청 좌석 행을 읽어야 한다. 같은 행에 `status`와 `grade`가 있어 좌석 상태 확인에 쿼리가 더 들지 않는다.
- 공통 Outbox 릴레이는 같은 키의 PENDING을 한 건씩만 보낸다. 요청이 몰린 구역은 초당 약 2건이 상한으로 추정된다(코드 기준, 미측정).
- Actuator는 `health,prometheus`만 연다(`config-repo/application.yml`).
- 결과 조회 API는 입장 토큰 검사 대상이 아니다(`EntryTokenPolicy`는 선점 POST만 본다).

## 결정
1. **접수 단계 즉시 거절.** 접수형 POST는 동기형과 같은 입력 검증을 한다. 1~4매, 중복 좌석, 회차 존재, 판매 기간, 그 회차의 좌석인지를 본다.
   - 요청 좌석 중 하나라도 일반 선점이 잡을 수 없는 상태면(`AVAILABLE`이 아니거나 추첨석) 행을 남기지 않고 `409 SEAT_ALREADY_HELD`로 즉시 거절한다. 잠금 없는 읽기다.
   - 매수 한도는 접수에서 보지 않고 소비자가 `REJECTED(PURCHASE_LIMIT_EXCEEDED)`로 알린다.
   - 통과하면 `seat_hold_requests(REQUESTED)`와 Outbox `SeatHoldRequested`를 한 트랜잭션에 쓰고 `202 Accepted`를 준다.
2. **모드는 기동 시 정한다.** `seat-hold.mode`(`sync` 기본, `async`)를 기동할 때 한 번 읽는다. 바꾸려면 reservation-service를 재시작한다. `@RefreshScope`를 쓰지 않는다. 소비자(ISE-17)는 모드와 상관없이 항상 켠다.
3. **결과 조회는 모드와 상관없이 연다.** `GET /api/v1/reservations/requests/{requestId}`는 동기형 모드에서도 동작한다. 동기형은 성공한 선점만 행을 남기므로 `HELD`나 `404`만 나온다.
4. **같은 키의 접수형 POST 재요청**은 기존 행의 상태와 상관없이 `202`와 같은 본문(`status: "REQUESTED"`, `nextPollAfterSeconds: 1`)을 준다. 202의 `status`는 "결과는 `Location`에서 확인하라"는 뜻이다. 실제 결과(`HELD`·`REJECTED`)는 조회 API로만 알린다. 202 본문에 `HELD`를 넣으면, 동기형 201처럼 즉시 완료로 분기하는 클라이언트가 `reservation` 블록 없이 결제로 넘어간다.
5. **미룬 것**
   - Outbox 릴레이의 키별 한 건 제약은 ISE-17에서 측정한 뒤, 접수형 토픽만 키별 순서를 푸는 contract 이슈로 다룬다.
   - 직접 발행 경로는 ISE-18에서 측정용으로 만든다.

## 검토한 대안
- 결정 1의 대안
  - 모두 202로 받기(기존 계약): 계약 변경이 없고 거절 경로가 하나다. 그러나 이미 잡힌 좌석을 노린 요청(측정 87%)도 행 2개, 메시지 1건, 소비자 트랜잭션 1번을 쓴다. 이 헛일이 구역당 처리 상한을 나눠 먹는다.
  - 매수 한도까지 접수에서 확인: 앞선 REQUESTED 요청의 매수를 모르므로 판단이 틀릴 수 있다. 한도 초과는 드물어 얻는 것도 작다.
- 결정 2의 대안: `@RefreshScope` + `/actuator/refresh`. 공통 Actuator 노출을 바꿔야 하고, 전환 순간에 남은 요청과 갱신되는 빈 사이의 경계를 다뤄야 한다.
- 결정 3의 대안: 접수형 모드에서만 열기. 모드를 바꾼 직후 남은 접수형 요청의 결과를 볼 길이 없다.

## 결과
- 얻는 것
  - 줄(Outbox, Kafka, 소비자)이 잡을 수 있는 요청에만 쓰인다.
  - 사용자는 이미 잡힌 좌석이면 폴링 없이 바로 안다.
  - 프론트는 동기형에서 이미 409를 처리하므로 같은 화면 코드로 두 모드를 다룬다(ISE-120 완료 기준).
  - 모드 분기가 POST 하나에만 있다.
- 감수하는 것
  - 접수형 거절 경로가 둘이다(즉시 409와 조회의 REJECTED).
  - 잠금 없이 읽은 상태라, 그사이 만료로 풀린 좌석을 거절할 수 있다. 같은 순간 동기형도 409를 준다.
  - 모드를 바꿀 때마다 재시작해야 한다.
- 후속 작업
  - ISE-120 담당에게 contract 이슈로 결정 1·3·4를 알린다.
  - ISE-17: 소비자, DLQ, 30초 타임아웃, Outbox 지연 측정.
  - ISE-18: 직접 발행 경로, 접수 단계 409 비율, 섞인 구역 요청 비율.
