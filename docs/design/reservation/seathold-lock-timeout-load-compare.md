# 잠금 대기 50ms 대 500ms 부하 비교 (ADR 0061 후속, 로컬 측정)

- 날짜: 2026-11-16
- 브랜치: `fix/ISE-261-seat-hold-lock-timeout`(dev 병합 뒤, 캐시 사전 거절 켬). `SyncSeatHoldService.LOCK_TIMEOUT` 상수만 잠시 500ms로 바꾼 사본과 번갈아 쟀고, 측정 뒤 원래 파일로 돌아왔는지 파일 비교로 확인했다
- 관련: [ADR 0061](../../decisions/0061-seat-hold-lock-timeout-50ms.md)(후속 작업 "Z 시나리오와 K1 부하로 500ms와 50ms를 비교"), [캐시 사전 거절 측정](seathold-pre-reject-cache-measure.md)

## 1. 요약
- ADR 0061의 50ms가 부하에서도 맞았다. 잠금 장애에서 500ms는 처리 가능한 300/s부터 무너졌고(의도 실패 0% → 77%), 50ms는 300/s를 실패 없이 처리했다.
- 정상 부하(잠긴 좌석 없음)에서는 두 값의 차이가 없었다. 기다릴 잠금이 없으면 시한은 쓰이지 않는다.
- 정합성: 모든 조건에서 중복 판매, 좌석·원장 불일치, 유령 좌석, 500, 3초 타임아웃이 0이었다. 커넥션 탐침도 100% 성공했다.

## 2. 측정 방법
- `SeatHoldLoadTest` L9. 옵션은 [캐시 사전 거절 측정](seathold-pre-reject-cache-measure.md)의 켬 조건과 같다: 풀 5·상한 4·대기 인원 64, 입장 토큰, 정리 지연 1ms, 재시도 모사, 커넥션 탐침 20/s, 대상 회차 좌석 조회 50/s, 캐시 사전 거절 켬
- 잠금 장애: 인기 구역 좌석 20석을 다른 트랜잭션이 측정 내내 쥔다. 그 좌석을 노린 선점은 잠금 대기 시한까지 기다린 뒤 409가 된다
- 순서: 50 → 500 → 500 → 50. 실행마다 3회 반복하고 1회차(예열)는 버렸다(조건별 n=4)

## 3. 결과
| 부하 | 지표 | 50ms | 500ms |
|---|---|---|---|
| 잠금 장애 300/s | 의도 실패 / 의도 p95 | **0% / 103ms** | **77.2% / 8.6s** |
| | 201(성공한 선점) | 427 | 221 |
| | 상한 자리 쥐는 시간 평균 | 10.6ms | 84.2ms |
| 잠금 장애 600/s | 의도 실패 | 19.5% | 88.8% |
| | 201 | 501 | 228 |
| 잠금 장애 900/s | 의도 실패 | 40.8% | 93.0% |
| 정상 1,800/s | 의도 실패 / 의도 p95 | 0% / 10ms | 0% / 10ms |
| 정상 3,000/s | 의도 실패 / 의도 p95 | 0% / 32ms | 0% / 32ms |

- 잠금 장애의 의도 실패 범위: 300/s 50ms 0.0%(4회) 대 500ms 75.6~79.3%. 범위가 크게 떨어져 있다.
- 정상 부하는 자리 쥐는 시간(1.07 대 1.08ms)까지 같아 효과 없음(차이 없음)이다.

## 4. 왜 이렇게 나왔나
- 잠긴 좌석을 노린 요청은 시한만큼 상한 자리(와 커넥션)를 쥔다. 상한 4개 자리를 그런 요청이 차지하면 다른 좌석 요청도 줄을 선다.
- 평균 자리 쥐는 시간이 10.6ms → 84.2ms(8배)가 되자 처리 한계(상한 ÷ 자리 쥐는 시간)가 약 380/s에서 약 50/s로 떨어졌다(계산값). 300/s는 50ms에서는 한계 안, 500ms에서는 한계의 6배다. 그래서 대부분이 503을 받고 재시도까지 실패했다.
- 성공한 선점(201)이 반으로 준 것도 같은 이유다. 빈 좌석을 노린 요청까지 자리를 못 얻어 기회를 잃었다.
- 상한이 없던 때(ISE-79)에는 같은 현상이 커넥션 풀 고갈과 좌석 조회 실패로 나타났다. 이번에는 상한이 커넥션을 지켜 탐침과 좌석 조회는 100% 성공했고, 대신 선점 요청이 503을 받았다.

## 5. 판단
- 50ms를 유지한다(ADR 0061 그대로). 후속 작업 줄의 "부하로 500ms와 50ms 비교"를 끝낸 것으로 본다.
- 잠금 장애 600/s 이상의 실패(19~41%)는 50ms에서도 남는다. 잠긴 좌석을 노린 요청이 50ms씩 자리를 쥐는 것이 병목이다. 더 줄이려면 시한을 더 낮추거나 잠금 감지 방식을 바꿔야 하는데, 0에 가까운 시한은 롤백 중인 짧은 잠금까지 건너뛰어 빈 좌석에 409를 준다(TS-73, SKIP LOCKED에서 확인). 이번 범위에서 바꾸지 않는다.

## 6. 재현
`SyncSeatHoldService.LOCK_TIMEOUT`을 `Duration.ofMillis(500)`으로 바꾼 뒤 아래를 돌리고, 끝나면 되돌린다.
```bash
env SEATHOLD_LOAD=true SEATHOLD_LOAD_POOL=5 SEATHOLD_LOAD_MAX_CONCURRENT=4 SEATHOLD_LOAD_MAX_QUEUE=64 \
  SEATHOLD_LOAD_CONNECTION_TIMEOUT=1000 SEATHOLD_LOAD_REPEAT=3 \
  SEATHOLD_LOAD_PROBE_RATE=20 SEATHOLD_LOAD_PROBE_TARGET=requests SEATHOLD_LOAD_CLIENT_RETRY=true \
  SEATHOLD_LOAD_ENTRY_TOKEN=true SEATHOLD_LOAD_DISCARD_DELAY_US=1000 SEATHOLD_LOAD_SEAT_VIEW_RATE=50 \
  SEATHOLD_LOAD_RATES=300,600,900 SEATHOLD_LOAD_L9_LOCKED=true \
  ./gradlew :reservation-service:test --tests 'com.iseonjwa.reservation.seathold.concurrency.SeatHoldLoadTest.L9*' -i
```
