---
title: 좌석 선점 결과 계약(ISE-21)의 멱등 재요청 처리 규칙과 응답 seatIds 포함 결정
date: 2026-10-29
updated: 2026-10-29
judge: kwonwoo
ai: Antigravity (Gemini)
verdict: 수용
area: 동시성
presentation: 후보
shared: 예
---

# 좌석 선점 결과 계약(ISE-21)의 멱등 재요청 처리 규칙과 응답 seatIds 포함 결정

## 맥락
- 해결하려던 작업: Linear `ISE-19` 하위 이슈인 `ISE-21`(계약 ② 좌석 선점 결과 형식 정의)을 확정하기 위해 선점 성공·실패 응답 필드와 `Idempotency-Key` 재요청 시 동작 규칙을 설계하던 중이었다.
- AI 활용 방식: 멱등 재요청이 실제로 어떤 시나리오에서 발생하는지 묻고, 동기형(M2)과 접수형(M4)에서의 멱등 재요청 처리 규칙과 응답 필드 구성을 제안받았다.

## AI 제안
1. **멱등 재요청 4대 시나리오 분리**:
   - 같은 키 + 같은 본문(기존 성공 `HELD`): 첫 응답과 동일한 `201 Created`(결제 토큰 포함) 반환. 이미 만료·해제된 예매면 `409 RESERVATION_NOT_PAYABLE` 거절.
   - 같은 키 + 다른 본문(`scheduleId` 또는 정렬된 `seatIds` 불일치) 또는 다른 사용자: `409 IDEMPOTENCY_KEY_REUSED` 거절.
   - 동기형(M2)에서 첫 요청이 `409`(`SEAT_ALREADY_HELD` 등)로 실패한 경우: 트랜잭션을 전체 롤백해 실패한 `seat_hold_requests` 행을 남기지 않고, 오픈 정각 이선좌 폭주 시 불필요한 DB INSERT 부하를 막는다. 재요청 시 다시 선점을 시도한다.
   - 동시 따닥 요청으로 인한 PK/Unique 충돌: `500` 에러 대신 커밋된 첫 요청의 결과를 재조회해 `201 Created`로 정상 응답한다.
2. **응답 필드 선택지 제시**: SA 11-5 초안에는 선점 응답에 `seatIds`가 없으므로 포함 여부를 결정하도록 제안했다.

## 판단
- 결과: 수용 (응답 필드에 `seatIds` 포함 결정 추가)
- 판단자: kwonwoo
- 근거:
  - 동기형 실패 시 `seat_hold_requests`까지 롤백해 DB 쓰기 부하를 줄이고, 동시 요청 PK 충돌 시 재조회로 `201`을 반환하는 AI 제안을 그대로 수용한다.
  - 선점 성공 응답에 `seatIds`(오름차순 정렬된 좌석 ID 목록)를 넣는 방향으로 진행한다. 프론트엔드에서 사용자에게 선점된 좌석 정보를 바로 출력해 보여줄 수도 있고, 필요 없다면 보여주지 않을 수도 있어 프론트엔드에게 선택지를 줄 수 있기 때문이다.
- 검토한 대안:
  - 동기형 실패도 별도 트랜잭션(`REQUIRES_NEW`)으로 `seat_hold_requests(REJECTED)`에 커밋하는 안: 오픈 정각 이선좌 경합에서 실패 요청마다 DB INSERT가 발생해 DB 부하가 커지므로 채택하지 않았다(접수형 M4에서만 비동기 소비자가 `REJECTED`를 기록).
  - 선점 응답에서 `seatIds`를 빼는 안(SA 11-5 초안): 응답 크기는 조금 작아지지만 프론트엔드가 요청 상태를 별도로 들고 있지 않으면 어떤 좌석이 선점됐는지 예매 상세 조회를 한 번 더 해야 한다.

## 반영
- [`docs/design/reservation/contracts.md`](../../design/reservation/contracts.md)
- [`docs/decisions/0031-seat-hold-result-and-idempotency-contract.md`](../../decisions/0031-seat-hold-result-and-idempotency-contract.md)
- [`docs/core/SA MASTER.md`](../../core/SA%20MASTER.md) 11-5절, 12-3절

## 검증
- 구현 전 (계약 문서화 단계)

## 판단 이력
- 2026-10-29 수용: 동기형 멱등 재요청 규칙(실패 시 롤백, 동시 PK 충돌 시 재조회 응답, 본문 불일치 409)을 수용하고, 프론트엔드 선택지를 위해 응답에 `seatIds`를 포함하기로 결정

## 발표·면접 포인트
- 좌석 선점의 멱등키(`Idempotency-Key`)는 단순 중복 방지가 아니라, 네트워크 타임아웃 후 재시도한 정상 사용자가 자기가 선점한 좌석 때문에 `409 SEAT_ALREADY_HELD`(이선좌)를 받고 결제 화면으로 넘어가지 못하는 문제를 막기 위한 장치다.
- 동기형 선점에서는 409 실패 건까지 DB에 커밋하면 오픈 정각 이선좌 폭주 때 불필요한 INSERT 부하가 쌓이므로 실패 시 `seat_hold_requests`를 함께 롤백하고, 성공(`HELD`) 건만 커밋해 멱등 재응답과 DB 부하 절감을 함께 달성했다.
