# ISE-18 동기형 대비 접수형 좌석 선점 재측정 계획

- 상태: 계획. 구현과 측정 전(2026-11-15 작성)
- 관련 이슈: ISE-18(이 문서), ISE-17(소비자, 이월 측정), ISE-16(접수), ISE-262(Synthetic Data), ISE-265(Kafka-UI 캡처, 마감 11-16), ISE-266(접수형 A0, 마감 11-17), ISE-255(비상 동기형 전환 범위)
- 근거: SA MASTER 3장·3-1절(포화점 비교), 10-1절 비고(동기형·직접 발행·Outbox 세 경우 비교), [ADR 0057](../../decisions/0057-async-seat-hold-intake-and-mode.md) 결정 5와 후속 작업
- 작업 브랜치: `test/ISE-18-async-seathold-remeasure`(dev `6468f257`에서 시작). 구현과 측정은 다른 기기에서 이 브랜치를 받아 이어 한다

같은 환경에서 동기형, 접수형(직접 발행), 접수형(Outbox) 세 방식의 응답 시간·처리량·포화점을 재고 병목 구간을 찾는다. 결과는 발표 근거와 `docs/troubleshooting.md`에 남긴다. ISE-17 PR #609에서 "오픈 피크 측정으로 미룬다"고 한 소비자 지표도 이 측정에서 함께 잰다(2026-11-15 사용자 결정).

## 1. 현재 있는 것과 없는 것

dev `6468f257` 기준으로 확인했다.

| 항목 | 상태 | 위치 |
|---|---|---|
| 동기형 선점 | 있음. K1·K1-H 기준선 측정 기록 있음 | `SyncSeatHoldService`, [k6-seathold-bottleneck-plan.md](k6-seathold-bottleneck-plan.md) |
| 접수형 Outbox 접수 | 있음 | `AsyncSeatHoldService`(ISE-16) |
| 접수형 소비자·DLQ·30초 시한 | 있음 | `SeatHoldRequestProcessor`, `SeatHoldRequestTimeoutScheduler`(ISE-17) |
| 소비자 지표 | 있음 | `seathold.consumer.result{outcome}`, `seathold.consumer.delay`([seathold-metrics.md](seathold-metrics.md)) |
| 접수형 직접 발행 경로 | **없음** | ADR 0057 결정 5가 ISE-18로 미뤘다 |
| k6 접수형 시나리오(202 뒤 결과 폴링) | **없음** | `reservation-service/k6/k1-seathold.js`는 201·409만 다룬다. `loadtest/k6/scenarios/a0-assignment-baseline.js`는 202를 성공으로 셀 뿐 폴링하지 않는다 |
| 구역별 Outbox PENDING 적체 지표 | **없음** | reservation-service에는 Outbox DB 집계 Gauge가 없다. 측정 중 SQL로 표본을 뜬다(4-3절) |

## 2. 비교 대상

| 기호 | 방식 | 설정 | 접수 → 처리 완료에 들어가는 것 |
|---|---|---|---|
| S | 동기형 | `seat-hold.mode=sync` | 없음(201에 결과가 있다) |
| D | 접수형 직접 발행 | `seat-hold.mode=async` + 직접 발행 켬(3-1절에서 만든다) | Kafka 전송 + 소비자 랙 |
| O | 접수형 Outbox | `seat-hold.mode=async`(현재 구현) | Outbox 폴링(`messaging.outbox.interval` 500ms) + 같은 키 한 건씩 전송 + 소비자 랙 |

모드는 기동할 때 한 번 읽으므로 방식마다 reservation-service를 재시작한다(ADR 0057 결정 2). 소비자는 세 방식 모두 켜져 있다.

## 3. 구현할 것

### 3-1. 직접 발행 경로(측정 전용)
- 접수 트랜잭션에서 Outbox 행 대신 `seat-hold.request`로 바로 보낸다. 키(`{scheduleId}:{section}`, ADR 0056)와 봉투(`SeatHoldRequested` v1)는 Outbox 경로와 같아야 소비자를 바꾸지 않는다.
- 설정 하나로 켜고 끈다. 기본값은 끔(Outbox)이고, 운영 설정(`config-repo`)은 바꾸지 않는다. 측정용 덮어쓰기에서만 켠다.
- 정해야 할 것(구현 전에 사용자 확인)
  - 보내는 시점: 커밋 전 동기 전송인지 커밋 뒤 전송인지. 커밋 전이면 DB 롤백과 메시지가 어긋날 수 있고, 커밋 뒤면 전송 실패 때 `REQUESTED` 행만 남아 30초 시한 정리가 닫는다.
  - 전송 실패 응답: SA 10-1절의 `lottery.entry`처럼 `acks=all`, 실패 시 503으로 맞출지.
- 측정 전용 분기라 ADR은 새로 쓰지 않는 것으로 본다. 위 두 가지를 정하면 ADR 0057의 후속 작업 줄에 결과를 덧붙인다. 범위가 커지면 그때 ADR로 올린다.

### 3-2. k6 접수형 시나리오
- `k1-seathold.js`에 접수형 분기(예: `HOLD_MODE=async`)를 더하거나 같은 폴더에 새 파일을 둔다. 대기열 입장 → 좌석 조회 → 선점 흐름과 사용자·토큰 준비는 K1을 그대로 쓴다.
- 202를 받으면 `Location`을 `nextPollAfterSeconds` 간격으로 폴링해 `HELD`·`REJECTED`가 될 때까지 기다린다. 폴링 상한은 시한 정리를 포함한 최대 약 35초보다 길게 둔다.
- 따로 기록할 지표
  - 접수 응답 시간(202·409)
  - 접수 → 처리 완료 시간(202를 받은 시각부터 최종 상태를 본 폴링까지)
  - 최종 결과별 건수: `HELD`, `REJECTED(SEAT_ALREADY_HELD | PURCHASE_LIMIT_EXCEEDED | PROCESSING_FAILED | TIMEOUT)`
  - 폴링 요청 수(결과 조회 API가 커넥션 풀을 나눠 쓰므로 부하에 포함된다)
- 409는 실패가 아니라 정상 거절로 분리해 센다. 동기형 409와 접수 단계 409(ADR 0057 결정 1)를 같은 기준으로 비교한다.

## 4. 측정

### 4-1. 시나리오
| 시나리오 | 내용 | 근거 |
|---|---|---|
| 발제 기준 | 동시 100명, 1초 안에 시작, 각 10회 | SA 3장, `a0-assignment-baseline.js` |
| 포화점 | 도착률을 단계로 올려 처리량이 멈추고 p95가 급증하는 지점을 찾는다(K1 `MODEL=ramp`) | SA 3-1절 |
| 쏠림 | 인기 회차·구역에 몰리는 Synthetic Data | ISE-262, `loadtest/synthetic/` |

세 방식(S·D·O) × 세 시나리오를 같은 환경, 같은 데이터, 같은 커밋으로 잰다. 회마다 좌석 데이터를 초기화한다.

### 4-2. 공통 지표
- 평균·p95 응답 시간, 처리량(TPS), 포화점
- 5xx·타임아웃 오류율. 409는 정상 거절로 따로 센다
- 접수형은 접수 응답 시간과 접수 → 처리 완료 시간을 나눠 적는다
- Zipkin 구간별 지연: Gateway → Reservation → DB → Kafka → 소비자

### 4-3. ISE-17에서 넘어온 측정
ISE-17 PR #609가 범위 밖으로 둔 항목이다. D·O 측정에서 함께 본다.

| 항목 | 수집 방법 | 왜 보나 |
|---|---|---|
| 늦게 처리된 요청 비율 | `seathold.consumer.result{outcome="late"}` / 전체 | 30초 시한을 넘긴 메시지가 많으면 소비 속도가 접수 속도를 못 따라간다 |
| 잠금 시한 초과 수 | `seathold.consumer.result{outcome="lock_timeout"}` | 소비자 잠금 시한 100ms가 만드는 거짓 거절의 크기. 크면 `seat-hold.consumer.lock-timeout`을 조정한다 |
| 접수 → 처리 시작 지연 | `seathold.consumer.delay` 분포 | SA 9-4절 목표(2초 이내)와 비교 |
| 구역별 Outbox PENDING 적체 | 측정 중 15초마다 SQL 표본(아래) | `OutboxRepository.claimNext`가 같은 `message_key`의 PENDING을 한 번에 한 건만 보내, 몰린 구역은 초당 약 2건이 상한으로 추정된다(코드 기준, 미측정) |
| 섞인 구역 요청 비율 | 접수 요청 중 좌석이 두 구역 이상인 비율 | ADR 0056의 가장 작은 구역 키가 한 파티션에 몰리는지 |
| 접수 단계 409 비율 | k6 접수 응답 상태별 건수 | ADR 0057 결정 1이 줄에 들어가는 헛일을 얼마나 줄였나 |

```sql
-- O 측정 중 표본. 구역 키별 대기 건수와 가장 오래된 행의 나이
SELECT message_key, count(*) AS pending, now() - min(created_at) AS oldest
  FROM outbox_events
 WHERE topic = 'seat-hold.request' AND status = 'PENDING'
 GROUP BY message_key
 ORDER BY pending DESC
 LIMIT 20;
```

컬럼은 `V20261102_2__outbox_and_processed_events.sql` 기준이다. 적체가 크게 나오면 접수형 토픽만 키별 순서를 푸는 변경을 `contract` 이슈로 결제 담당에게 올린다. `OutboxRelay`는 공통 코드라 단독으로 바꾸지 않는다(AGENTS.md 대전제 11).

### 4-4. 측정 회 유효 판정
ISE-79 측정과 같은 기준을 쓴다.
- `k6_exit`가 137(메모리 부족으로 종료)이 아니다
- Prometheus 표본 간격이 15초 이내다
- 버린 요청(`dropped_iterations`)이 적다
- 좌석 대사(Reconciliation) 위반이 0이다
- k6 원자료의 `http_reqs` 행은 중복이 아니므로 2로 나누지 않는다. `setup`·`warmup`·`probe` 태그는 집계에서 뺀다

### 4-5. 측정 환경 기록표
회마다 아래 칸을 채운다.

| 칸 | 값 |
|---|---|
| 날짜·측정자 | |
| 커밋 해시 | |
| 방식(S·D·O)·시나리오 | |
| 환경(local·배포 서버) | |
| CPU·메모리(서버, 발생기) | |
| JVM `-Xmx` | |
| 커넥션 풀(`maximum-pool-size`) | |
| 소비자 동시성·파티션 수·잠금 시한 | 기본 2·6·100ms |
| Outbox 간격·배치 | 기본 500ms·20 |
| 결과 파일 위치 | |

## 5. 산출물
- 비교 결과표와 측정 환경 기록표: 이 문서 6절에 채운다. 길어지면 `async-seathold-remeasure-results.md`로 나눈다
- 병목 분석과 해결: `docs/troubleshooting.md`의 TS 항목
- Kafka-UI 토픽·컨슈머 그룹 캡처(ISE-265): 측정 중에 뜬다. 파티션 6과 키 `{scheduleId}:{section}`의 근거를 함께 적는다
- 접수형 A0(ISE-266): 3-2절 시나리오를 발제 기준으로 돌린 결과를 쓴다

## 6. 결과
측정 전이다.

## 7. 다른 기기에서 시작하는 순서
1. `git fetch && git switch test/ISE-18-async-seathold-remeasure && git merge origin/dev`로 최신 dev를 반영한다.
2. 나에게 배정된 열린 `contract` 이슈를 확인한다(`docs/guides/contract-issues.md`).
3. 3-1절의 정해야 할 것 두 가지를 정하고 직접 발행 경로를 만든다. 테스트는 `docs/guides/testing.md`를 따른다.
4. 3-2절 k6 시나리오를 만들고 local에서 짧게 돌려 폴링과 집계를 확인한다.
5. 4절 순서로 S → O → D를 잰다. 배포 서버에서 재면 `docs/guides/deploy-server-access.md`와 `docs/guides/loadgen.md`를 먼저 읽고 팀 채널에 알린다.
6. 6절에 결과를 채우고 PR을 올린다.

일정이 부족하면 S와 O 두 방식 비교를 먼저 끝내고 D는 뒤로 미룬다. 이렇게 하면 ADR 0057 결정 5를 다시 미루게 되므로 그 사실을 ADR 0057 후속 작업 줄에 적는다. 어느 쪽으로 갈지는 아직 정하지 않았다.
