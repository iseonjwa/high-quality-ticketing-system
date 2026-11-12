# 좌석 선점 동시성 시나리오와 단계적 도입 (ISE-79)

상태: 설계, 1차 측정 완료 (2026-11-03, 좌석 선점 담당 최권우). 결과는 [concurrency-results.md](concurrency-results.md)

ISE-79의 동시성 방어 네 가지(이슈 항목 TS-1~TS-4)를 한 번에 넣지 않고 한 단계씩 넣으면서 전후를 같은 시나리오로 잰다. 이 문서는 도입 순서, 테스트 세팅, 동시성 시나리오 C1~C7을 정한다. 부하 시나리오는 [load-test-scenarios.md](load-test-scenarios.md), 이슈 전제를 코드와 대조한 검토 결과는 [concurrency-review.md](concurrency-review.md)에 있다.

- 근거: SA 2-5(E1·E2·E3·E16), 3장 오류 정의, 8-4 전이 규칙, 9-4 숫자 기준, 12-7 핵심 쿼리, [ADR 0032](../../decisions/0032-seat-hold-result-and-idempotency-contract.md), [ADR 0035](../../decisions/0035-defer-seathold-concurrency-and-reconciliation-to-m4.md), [hold-expiry.md](hold-expiry.md)
- 이슈 항목 TS-1~TS-4는 `docs/troubleshooting.md`의 TS 번호와 다르다. 기록할 때는 그 파일의 다음 번호를 쓴다(검토 R5).

## 1. 원칙

1. 한 단계에는 변경 하나만 넣는다. 두 변경을 함께 넣으면 어느 변경이 수치를 바꿨는지 가릴 수 없다.
2. 각 단계 전후에 C1~C7 전체를 돌린다. 목표 시나리오만 보면 다른 시나리오의 회귀를 놓친다.
3. 측정마다 커밋 해시와 실행 환경을 남긴다(SA 3-1 측정 환경 기록표).
4. 재현되지 않은 것도 결과로 기록한다. 재현이 안 되면 왜 안 되는지 코드 근거를 적고, 이슈 항목을 고칠지 사용자가 정한다.

## 2. 단계적 도입

| 단계 | 변경 | 재현·검증 시나리오 | 기대 지표 변화 |
|---|---|---|---|
| S0 | 하네스 추가, 코드 변경 없음. 기준선 실행 | C1~C7, 부하 L1~L3 | 기준선 수치 확보 |
| S1 | TS-2: 매수 한도를 조건부 UPDATE로 직렬화(SA 12-7 ①) | C4 | 한도 초과 라운드 > 0 → 0, 매수 불일치 → 0 |
| S2 | TS-3: 멱등키를 트랜잭션 첫 쓰기로 선점하고, 유니크 위반은 트랜잭션 밖에서 재조회 | C5 | 자기 요청 409 → 0, 예매 1건 유지 |
| S3 | TS-1: `SET LOCAL lock_timeout = '500ms'`, 좌석 오름차순 `FOR UPDATE`, `55P03` → 409 매핑 | C3(C2는 회귀 확인) | 잠금 대기 최대 약 2,000ms → 약 500ms, 부하 L2 p95 감소 |
| S4 | TS-4: 만료 대상을 건별 트랜잭션 안에서 `FOR UPDATE SKIP LOCKED`로 집고, 팀이 정한 잠금 순서 적용 | C6, C7 | 헛수고(wasted) > 0 → 0, 데드락 0 유지 |
| S5 | 처리량 개선 후보를 하나씩 적용 | 부하 L1~L6 | 포화점·p95 변화. ISE-18(접수형 비교)로 넘긴다 |

순서의 이유는 다음과 같다.
- S1을 먼저 한다. 코드상 확실히 재현되는 정합성 결함이고(4절 C4), 다른 단계와 잠금 대상이 겹치지 않는다.
- S2는 S3보다 앞선다. 멱등키 선점은 트랜잭션 맨 앞에 잠금을 하나 더 만든다. S3 뒤에 넣으면 S3에서 잰 잠금 대기 수치를 다시 재야 한다.
- S4는 마지막이다. 잠금 순서는 결제 결과 소비자(반효승)와 같이 정해야 하는 공유 규칙이라 contract 이슈 합의가 먼저다(검토 R4).

단계마다 커밋은 "재현 테스트 실행 결과 기록 → 수정 → 재실행 결과 기록" 순서로 나눈다. 수정 커밋 메시지에 전후 수치를 한 줄 적는다.

## 3. 테스트 세팅

### 3-1. 위치

| 파일 | 역할 |
|---|---|
| `reservation-service/src/test/java/com/iseonjwa/reservation/seathold/concurrency/ConcurrentRunner.java` | 작업 N개를 시작 신호 하나로 함께 출발시키고, 결과를 에러 코드·SQLState로 분류하고, 지연 p50·p95·최댓값을 낸다 |
| `.../concurrency/SeatHoldFixture.java` | 라운드마다 새 회차·좌석 생성, 정리, 불변식 쿼리(중복 판매, 매수 불일치, 좌석 상태 불일치, 데드락 누적 수) |
| `.../concurrency/SeatHoldConcurrencyTest.java` | 시나리오 C1~C7. 단언은 고친 뒤의 목표 기준이다 |

단언을 목표 기준으로 둔 이유: 기준선에서 실패한 단언과 출력 줄이 그대로 문제 재현 기록이 되고, 고친 뒤 통과하면 같은 테스트가 회귀 테스트가 된다. 기준선을 기대값으로 단언하면 고칠 때마다 테스트를 뒤집어야 한다.

### 3-2. 실행

기본 빌드에서는 돌지 않는다. 클래스에 `@EnabledIfEnvironmentVariable(SEATHOLD_CONCURRENCY=true)`와 `@Tag("concurrency")`가 있다. 기준선에서 실패가 정상이고, 스레드 100개와 라운드 반복으로 시간이 걸리기 때문이다. Gradle 설정은 바꾸지 않았다.

```bash
# Git Bash
SEATHOLD_CONCURRENCY=true ./gradlew :reservation-service:test --tests '*SeatHoldConcurrencyTest' --rerun --no-daemon
```

```powershell
# PowerShell
$env:SEATHOLD_CONCURRENCY = 'true'; ./gradlew :reservation-service:test --tests '*SeatHoldConcurrencyTest' --rerun --no-daemon
```

- `--rerun`이 필요하다. 환경변수는 Gradle 입력이 아니라서 소스가 그대로면 테스트가 UP-TO-DATE로 건너뛰어진다.
- `:reservation-service:test`는 `:prepareTestInfra`로 `compose.test.yml`(Docker)을 먼저 띄운다. Docker Desktop이 켜져 있어야 한다.
- 결과는 `reservation-service/build/reports/tests/test/index.html`에서 클래스를 열고 Standard output의 `[SEATHOLD-CONCURRENCY]` 줄을 본다. 한 줄에 라운드 수, 결과별 건수, 지연 p50·p95·최댓값, 시나리오별 추가 지표가 있다.
- 데드락 수는 `pg_stat_database.deadlocks`의 시나리오 전후 차이다. DB 전체 누적값이므로 이 클래스만 단독으로 돌린다.

### 3-3. 구성 원리

- 동시 출발: 스레드를 모두 띄워 준비 래치를 기다린 뒤 시작 래치 하나로 출발시킨다. 그래도 출발은 수 ms씩 어긋나므로 결과는 확률적이다. 그래서 라운드를 반복하고 비율로 본다.
- 결과 분류: `BusinessException`은 에러 코드(`SEAT_ALREADY_HELD` 등, 정상 거절)로, 그 밖의 예외는 `ERROR:<SQLState>`(5xx)로 센다. `40P01`은 데드락, `55P03`은 lock_timeout, `23505`는 유니크 위반이다.
- 호출 경로: HTTP를 거치지 않고 `SyncSeatHoldService`와 `SeatHoldExpiryService`를 직접 부른다. 동시성 결함은 DB 트랜잭션에서 생기므로 이 경로로 충분하다. 예외가 실제로 몇 번 HTTP 상태가 되는지는 `SeatHoldApiTest`가 따로 확인한다.
- 불변식은 대사(ISE-14) 기준과 같다. 판매 여부의 기준은 `reservation_seats`다(D2).

### 3-4. 한계

- 테스트 커넥션 풀은 Hikari 기본값(10)이다. 운영 설정(`config-repo`의 5)과 다르다. 풀 크기에 따른 영향은 부하 시나리오에서 잰다.
- 로컬 Docker 한 대에서 돈다. 지연 수치는 같은 장비 안에서 전후 비교용이다.
- 수동 관찰이 필요하면 실행 중에 아래 쿼리로 잠금 대기를 본다. `log_lock_waits`를 켜려면 공유 파일 `compose.test.yml`을 바꿔야 하므로 이번에는 넣지 않았다.

```sql
SELECT pid, wait_event_type, wait_event, state, now() - xact_start AS tx_age, left(query, 80)
  FROM pg_stat_activity
 WHERE datname = current_database() AND wait_event_type = 'Lock';
```

## 4. 동시성 시나리오

"기준선 예상"은 코드를 읽고 세운 가설이다. 2026-11-03 실측에서 일곱 개 모두 예상대로 나왔다. 단계별 수치는 [concurrency-results.md](concurrency-results.md) 3절에 있다.

| # | 이슈 항목·SA | 상황 | 기준선 예상(코드 근거) | 목표(단언) | 라운드 |
|---|---|---|---|---|---|
| C1 | E1 | 같은 좌석 1개에 100명이 동시에 선점 | 통과. 좌석 UPDATE의 `status = 'AVAILABLE'` 조건이 먼저 커밋된 쪽 뒤에 다시 평가되어 1명만 갱신한다(`JpaSeatHoldInventoryRepository#holdAvailableSeats`) | 라운드마다 성공 1, `SEAT_ALREADY_HELD` 99, 중복 판매 0 | 3 |
| C2 | TS-1 | 좌석 4개를 정순·역순·무작위 순서로 10명이 동시에 요청 | 데드락 0. 서비스가 좌석 ID를 정렬하고(`sortedDistinctSeatIds`), 좌석 갱신이 UPDATE 한 문장이라 모든 트랜잭션이 같은 순서로 잠근다(검토 R1) | 데드락 0, 5xx 0, 중복 판매 0 | 50 |
| C3 | TS-1, 9-4 | 다른 트랜잭션이 좌석 행 잠금을 2초 쥔 동안 10명이 그 좌석을 요청 | 실패. lock_timeout이 없어 2초를 기다리고, 잠금이 풀린 뒤 1명이 성공한다 | 전원 `SEAT_ALREADY_HELD`, 최대 지연 1,500ms 미만(처음 1,000ms. `lock_timeout`이 잠금 시도마다 걸려 실제 상한이 약 1초라 2026-11-06에 고쳤다. [concurrency-results.md](concurrency-results.md) 4-3절) | 3 |
| C4 | TS-2, D1·E3·E16 | 같은 사용자가 탭 4개에서 서로 다른 좌석 2개씩 동시에 선점. 매수 행이 없는 상태에서 시작 | 실패. 보유 매수를 읽고 검사한 뒤 따로 더해서(`addHeldCount`), 같은 순간에 읽은 탭이 모두 검사를 통과한다. 최대 8매 | 한도 초과 라운드 0, `held_count` = 활성 예매 좌석 수, 5xx 0 | 30 |
| C5 | TS-3, E2 | 같은 멱등키·같은 본문으로 3번 연타 | 실패하지만 500은 아니다. 두 번째 요청은 사전 조회가 비어 선점 경로로 들어가고, 매수 행 잠금을 기다린 뒤 좌석 갱신 0행이 되어 `SEAT_ALREADY_HELD`를 받는다(contracts.md 3-2 ⑥, 검토 R2) | 전원 성공(같은 선점 결과), 예매 1건 | 30 |
| C6 | TS-4 | 만료 대상 100건을 스케줄러 두 인스턴스가 동시에 처리 | 정합성은 통과, 헛수고는 발생. 두 인스턴스가 같은 ID 목록을 읽고, 늦은 쪽은 예매 행 잠금을 기다린 뒤 0행이 된다 | 만료 100건, 오류 0, 데드락 0, 좌석 불일치 0, 헛수고 0 | 1 |
| C7 | TS-4, 8-4 | 사용자 A의 만료 대상 예매를 회수하는 동안 A가 새 좌석을, B가 회수 중인 좌석을 동시에 선점 | 데드락 0. 만료(예매 → 좌석 → 매수)와 선점(매수 → 좌석)은 순서가 반대지만, 선점의 좌석 UPDATE는 커밋 전 HELD로 보이는 좌석을 조건 불일치로 건너뛰고 잠금을 기다리지 않는다(검토 R4) | 데드락 0, 5xx 0, 매수·좌석 불변식 유지 | 30 |

C2와 C7은 기준선에서 통과할 것으로 본다. 통과하면 "재현되지 않음"으로 기록하고 회귀 테스트로 남긴다. 이 경우 TS-1의 개선 서사는 C3(잠금 대기 빠른 실패)와 부하 L2·L6으로 옮긴다(검토 R1).

## 5. 기록 형식

단계마다 아래 표를 PR 본문에 남기고, 단계를 마치면 `docs/troubleshooting.md`에 항목 하나로 옮긴다.

| 시나리오 | 지표 | 기준선 (`커밋`) | 적용 후 (`커밋`) |
|---|---|---|---|
| C4 | 한도 초과 라운드 / 30 | | |
| C4 | 최대 활성 좌석 수 | | |

`docs/troubleshooting.md` 항목에는 문제(재현 조건과 출력 줄), 원인(코드 위치), 해결(변경과 이유), 전후 수치, 배운 점을 쓴다. 출력 줄은 원문 그대로 붙인다.
