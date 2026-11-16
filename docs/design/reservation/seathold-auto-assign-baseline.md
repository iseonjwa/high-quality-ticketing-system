# 자동 배정(ISE-264) 기준값: 직접 선점과 같은 시점 비교 (로컬 측정)

- 날짜: 2026-11-16
- 브랜치: `fix/ISE-261-seat-hold-lock-timeout`(dev 병합 뒤). 코드는 바꾸지 않았다. 부하 테스트에 자동 배정 모드만 더했다
- 관련: ISE-264(자동 배정, 강민), ISE-287(자동 배정 확인 요청, 이 결과를 전달), [ADR 0059](../../decisions/0059-seat-auto-assign-skip-locked.md), [캐시 사전 거절 측정](seathold-pre-reject-cache-measure.md) 7-2절(개선 제안의 근거)

## 1. 요약
- 자동 배정은 한 건이 상한 자리(=커넥션)를 쥐는 시간이 4.7~5.6ms로, 직접 선점(1.1~1.6ms)의 약 4배였다. 처리 한계가 그만큼 낮아 1,000/s에서 의도 실패 6.0%, 1,800/s에서 45.6%였다. 같은 부하에서 직접 선점은 0%였다.
- 원인은 매진 뒤의 거절 경로다. 이 측정은 구역 하나(1,000석)가 처음 몇 초 안에 팔리고, 그 뒤 요청은 모두 `409 ZONE_SOLD_OUT`이다. 자동 배정은 이 거절에 이르기 전에 멱등키·토큰·회차 조회, 요청 행 INSERT, 매수 UPDATE, 구역 좌석 훑기를 모두 하고 롤백한다. 직접 선점은 같은 상황의 409 대부분을 캐시와 한 문장 사전 판정으로 끝낸다.
- 정합성: 모든 조건에서 중복 판매, 좌석·원장 불일치, 유령 좌석, 500, 3초 타임아웃이 0이었다. 짧은 요청 탐침도 100% 성공했다(상한이 커넥션을 지켰다).

## 2. 측정 방법
- `SeatHoldLoadTest` L9, 잠금 장애 없음. 자동 배정은 잠긴 좌석을 건너뛰므로(SKIP LOCKED) 잠금 장애를 넣지 않았다.
- 새 옵션 `SEATHOLD_LOAD_AUTO=true`: 선점 대신 `POST /api/v1/schedules/{id}/reservations/auto`에 `{"section":"A","quantity":N}`을 보낸다. N은 직접 선점과 같은 분포(1석 30%, 2석 50%, 3·4석 각 10%)다. 재시도 모사·의도 지표·정합성 검사는 그대로 쓴다.
- 공통: 풀 5·상한 4·대기 인원 64, 입장 토큰, 정리 지연 1ms, 재시도 모사, 커넥션 탐침 20/s, 대상 회차 좌석 조회 50/s(직접 선점의 캐시 사전 거절이 켜지는 조건). 회차 하나에 구역 A 1,000석.
- 순서: 직접 → 자동 → 자동 → 직접. 실행마다 3회 반복하고 1회차(예열)는 버렸다(조건별 n=4).

## 3. 결과
| 도착률 | 지표 | 직접 선점 | 자동 배정 |
|---|---|---|---|
| 300/s | 의도 실패 / 의도 p95 | 0% / 8ms | 0% / 11ms |
| | 상한 자리 쥐는 시간 평균 | 1.62ms | 4.93ms |
| 1,000/s | 의도 실패 / 의도 p95 | 0% / 2ms | **6.0% / 7.8s** |
| | 503 비율(요청 기준) | 0% | 43.5% |
| | 상한 자리 쥐는 시간 평균 | 1.14ms | 4.72ms |
| 1,800/s | 의도 실패 / 의도 p95 | 0% / 5ms | **45.6% / 8.1s** |
| | 503 비율 | 0.4% | 82.2% |
| | 상한 자리 쥐는 시간 평균 | 1.09ms | 5.63ms |

- 자동 배정 의도 실패 범위: 1,000/s 5.7~6.6%, 1,800/s 45.3~45.9%. 직접 선점은 4회 모두 0%다.
- 성공한 선점(201)은 자동 배정이 회마다 약 500건이다. 1,000석을 평균 약 2석씩 모두 팔았다. 직접 선점은 인기 좌석 100석에 몰리게 고르는 분포라 같은 시간에 판 좌석 수가 다르다. 그래서 성공 건수는 비교하지 않는다.

## 4. 왜 이렇게 나왔나
- 처리 한계 ≈ 상한 ÷ 자리 쥐는 시간. 자동 배정은 4 ÷ 약 4.9ms ≈ 820/s(계산값)라 1,000/s부터 넘친다. 직접 선점은 약 3,500/s다.
- 자동 배정의 매진 뒤 거절(`ZONE_SOLD_OUT`) 경로가 하는 일(`SyncSeatHoldService.holdAutoInTransaction` → `SeatHolding.holdAuto`):
  1. 멱등키 조회, 입장 토큰 사용 조회, 회차 조회(왕복 3번)
  2. 요청 행 INSERT, 잠금 시한 설정
  3. 매수 조건부 UPDATE
  4. 구역 좌석 중 AVAILABLE을 ID 순으로 찾기(`FOR UPDATE SKIP LOCKED`). 매진 뒤에는 남은 좌석이 없어 구역의 좌석 행을 끝까지 훑고 빈손으로 끝난다(코드 기준 추정: 자동 배정 인덱스 `(schedule_id, section, id)`에 status가 없어 HELD 행도 읽고 거른다, TS-72·`V20261116_6`)
  5. 모자라면 `ZONE_SOLD_OUT`으로 롤백
- 직접 선점의 409는 대부분(정상 95%) 좌석 조회 1초 캐시에서 멱등키 조회 한 번으로 끝난다. 캐시가 모르면 한 문장 사전 판정 뒤 롤백이다.

## 5. 개선 제안 (담당에게 전달, 적용 전)
효과가 클 것으로 보는 순서다. 모두 미측정 추정이다.
1. **매진 사전 거절:** 좌석 조회 1초 캐시(직접 선점이 쓰는 것과 같은 것)로 구역(또는 등급)의 선점 가능 좌석 수가 요청 매수보다 적으면 트랜잭션 없이 `409 ZONE_SOLD_OUT`. 매진 뒤 요청 대부분이 위 1~5를 건너뛴다. 대가는 직접 선점과 같다(1초 안에 풀린 좌석을 놓칠 수 있음). 같은 멱등키 재응답을 위해 거절 전 멱등키 조회 한 번은 남긴다.
2. **사전 조회 합치기:** 멱등키·토큰·회차를 한 문장으로(왕복 3 → 1번).
3. **비싼 쓰기 전에 고를 좌석이 있는지 보기:** 요청 행 INSERT와 매수 UPDATE 전에 잠그지 않는 가벼운 존재 확인을 두면, 매진 뒤 요청이 쓰기 두 번과 롤백을 하지 않는다. 1을 하면 대부분 거기서 걸러지므로 1의 보완이다.
4. **잠금 뒤 재조회 없애기:** 잠금 쿼리가 가격·구역을 함께 돌려주면 잠금 구간의 `findSeats` 한 번이 준다(성공 경로).

## 6. 재현
```bash
env SEATHOLD_LOAD=true SEATHOLD_LOAD_AUTO=true SEATHOLD_LOAD_POOL=5 SEATHOLD_LOAD_MAX_CONCURRENT=4 SEATHOLD_LOAD_MAX_QUEUE=64 \
  SEATHOLD_LOAD_CONNECTION_TIMEOUT=1000 SEATHOLD_LOAD_REPEAT=3 \
  SEATHOLD_LOAD_PROBE_RATE=20 SEATHOLD_LOAD_PROBE_TARGET=requests SEATHOLD_LOAD_CLIENT_RETRY=true \
  SEATHOLD_LOAD_ENTRY_TOKEN=true SEATHOLD_LOAD_DISCARD_DELAY_US=1000 SEATHOLD_LOAD_SEAT_VIEW_RATE=50 \
  SEATHOLD_LOAD_RATES=300,1000,1800 SEATHOLD_LOAD_L9_LOCKED=false \
  ./gradlew :reservation-service:test --tests 'com.iseonjwa.reservation.seathold.concurrency.SeatHoldLoadTest.L9*' -i
```
