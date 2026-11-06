# ISE-79 동시성·부하 측정 결과 (S0~S4)

상태: 1차 측정 완료, 결정 대기 (2026-11-03, 좌석 선점 담당 최권우 요청으로 Claude Code가 측정)

[concurrency-scenarios.md](concurrency-scenarios.md)의 단계 S0~S4를 별도 워크트리에서 차례로 적용했다. 단계마다 동시성 시나리오 C1~C7을 같은 테스트로 다시 돌렸고, 부하 시나리오 L1·L2·L3·L6은 로컬 근사로 쟀다. 출력 원문은 [concurrency-results-raw.md](concurrency-results-raw.md)에 있다.

결론은 세 가지다.
- 동시성 결함은 단계마다 목표대로 사라졌다. TS-2·TS-3·TS-4는 기준선에서 재현됐고 고친 뒤 0이 됐다. TS-1(역순 데드락)은 기준선에서도 재현되지 않았다.
- 처리량의 병목은 DB 잠금이 아니라 커넥션 풀이었다. 풀을 5에서 20으로 바꾸자 처리량이 약 2배가 됐다. 측정 중 잠금 대기 세션은 최대 0~6개였다.
- 고친 코드는 요청마다 DB 왕복이 늘어 처리량이 줄었다. VU 400 처리량 중앙값은 S0 1,125 req/s에서 S4 648 req/s로 내려갔다. 다만 같은 단계 안의 편차도 커서(S0 515~1,159) 크기는 단정하지 않는다.

## 1. 측정 환경

| 항목 | 값 |
|---|---|
| 장비 | Intel Core i7-11800H(8코어 16스레드), RAM 15.7GB, Windows 11 Pro 10.0.26200 |
| Docker | 29.4.0, VM 할당 CPU 16, 메모리 약 7.6GiB |
| DB | `compose.test.yml`의 `postgres:17-alpine`(tmpfs, 기본 설정) |
| 앱 | JDK 21, SpringBootTest(`test` 프로필), Tomcat 기본 스레드 200, Hikari 테스트 기본 10(동시성)·5/20(부하) |
| 부하 생성 | 같은 JVM의 가상 스레드 + `java.net.http.HttpClient`, 요청 타임아웃 3초(Gateway 기준) |
| 코드 | 기준 커밋 `66f9968`. S1~S4는 그 위에 쌓은 미커밋 변경(워크트리 브랜치 `experiment/ISE-79-concurrency-measure`) |

부하 생성기와 대상이 같은 장비·같은 JVM이고 Gateway·인증을 거치지 않는다. SA 3-1의 공식 측정을 대신하지 않고, 같은 장비 안에서 단계와 설정을 비교하는 용도다.

## 2. 단계별 변경

| 단계 | 변경 | 바뀐 파일 |
|---|---|---|
| S1 | 매수 한도: `INSERT ... ON CONFLICT DO NOTHING` 뒤 `held_count + n <= max` 조건부 UPDATE, 0행이면 `PURCHASE_LIMIT_EXCEEDED`(SA 12-7 ①) | `UserScheduleQuotaRepository` 계열, `SyncSeatHoldService#addHeldCount` |
| S2 | 멱등키를 매수보다 먼저 `seat_hold_requests`(REQUESTED)로 INSERT, 끝에 HELD로 UPDATE. PK 위반은 트랜잭션 밖에서 새 읽기 트랜잭션으로 재조회해 재응답 | `SyncSeatHoldService`(트랜잭션을 `TransactionTemplate`으로 직접 연다), `SeatHoldRequestRepository#markHeld` |
| S3 | 멱등키 선점 뒤 `set_config('lock_timeout', '500ms', true)`, 좌석 UPDATE를 `ORDER BY id FOR UPDATE` 하위 쿼리로, 잠금 실패(`PessimisticLockingFailureException`)는 409 `SEAT_ALREADY_HELD` | `SeatHoldInventoryRepository#applyLockTimeout`·`holdAvailableSeats`, `SyncSeatHoldService` |
| S4 | 만료: 건별 트랜잭션 안에서 `FOR UPDATE SKIP LOCKED LIMIT 1`로 한 건씩 집어 처리. 실패한 예매는 그 주기에서 제외. 잠금 순서(예매 → 좌석 → 매수)는 바꾸지 않음 | `ReservationRepository#lockNextExpiredHeldId`, `SeatHoldExpiryService#expireNext`, `SeatHoldExpiryScheduler` |

S4에서 `reservation-service` 전체 테스트를 돌렸다. `./gradlew :reservation-service:test --rerun`은 263건 통과, 실패 0건이었다. 건너뛴 7건은 환경변수로만 켜는 동시성 시나리오다.

## 3. 동시성 시나리오 결과

C1~C7을 단계마다 한 번씩 돌렸다. 테스트 코드는 단계 사이에 같다. S4만 만료 API가 바뀌어 C6의 구동부를 바꿨고, 헛수고의 정의(집었지만 만료하지 않은 시도)는 같다.

| 시나리오 | 지표 | S0 | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|---|
| C1 같은 좌석 100명 × 3라운드 | 성공 / 409 / 중복 판매 | 3 / 297 / 0 | 3 / 297 / 0 | 3 / 297 / 0 | 3 / 297 / 0 | 3 / 297 / 0 |
| C2 역순·교차 좌석 × 50라운드 | 데드락 / 5xx | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| C3 좌석 잠금 2초 보유 중 5명 × 3라운드 | 성공 / 409, p50 / 최대 | 3 / 12, 2,034 / 2,215ms | 3 / 12, 2,035 / 2,174ms | 3 / 12, 2,022 / 2,167ms | **0 / 15, 564 / 1,060ms** | 0 / 15, 572 / 1,305ms |
| C4 같은 사용자 4탭 × 30라운드 | 한도 초과 라운드 / 최대 보유 | 30 / 8매 | **0 / 4매** | 0 / 4매 | 0 / 4매 | 0 / 4매 |
| C5 같은 멱등키 3연타 × 30라운드 | 201 / 자기 요청 409 | 30 / 60 | 30 / 60 | **90 / 0** | 90 / 0 | 90 / 0 |
| C6 만료 100건, 인스턴스 2개 | 헛수고 / 소요 | 100 / 1,191ms | 100 / 1,300ms | 100 / 1,420ms | 100 / 1,366ms | **0 / 733ms** |
| C7 만료와 같은 사용자 선점 × 30라운드 | 데드락 / 불변식 위반 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

해석:
- C2·C7은 기준선에서도 데드락이 0이었다. [concurrency-review.md](concurrency-review.md) R1·R4의 가설(정렬과 단일 UPDATE, 선점 UPDATE의 `AVAILABLE` 조건)과 맞다. TS-1의 개선 근거는 C3다.
- C5의 기준선 증상은 500이 아니라 자기 요청 409였다(검토 R2와 같음).
- C3은 S3에서 성공이 0이 되고 p50이 약 2,000ms에서 약 560ms로 줄었다. 그러나 최댓값이 1,060~1,305ms라 테스트 목표(1,000ms 미만)를 넘는다. 원인은 4-3절이다.

## 4. 부하 시나리오 결과

### 4-1. 커넥션 풀 크기 (단일 실행)

| 코드 | 풀 | L3 VU 400 처리량 | L3 VU 400 p95 | L3 VU 800 타임아웃 | L6 409 p95 |
|---|---|---|---|---|---|
| S0 | 5 | 453 req/s | 1,155ms | 0 | 1,980ms |
| S0 | 20 | 869 req/s | 651ms | 0 | 440ms |
| S4 | 5 | 327 req/s | 1,561ms | 3,744 / 6,437 | 2,152ms |
| S4 | 20 | 821 req/s | 678ms | 0 | 1,641ms |

- 풀 5에서는 VU 50부터 처리량이 멈췄다(S0 446~478, S4 273~327 req/s). 커넥션 대기 스레드가 최대 196개로 Tomcat 스레드 거의 전부가 커넥션을 기다렸다.
- 측정 중 `pg_stat_activity`의 잠금 대기 세션은 모든 실행에서 최대 0~6개였다. 핫 좌석 시나리오(L2, L6)에서도 병목은 DB 잠금이 아니라 커넥션 획득이었다.
- 중복 판매와 좌석 상태 불일치는 모든 실행에서 0이었다.

### 4-2. 단계별 처리량 (풀 20, 3회 반복)

L3는 VU 100·400을 각 15초, L6는 VU 1,000을 1초 안에 1회씩 보냈다. 회차마다 단계 순서를 바꿨다. 값은 3회의 중앙값이고, 괄호는 최솟값~최댓값이다.

| 단계 | L3 VU 100 처리량 | L3 VU 400 처리량 | L3 VU 400 p95 | L6 409 p95 |
|---|---|---|---|---|
| S0 | 607 (277~764) | 1,125 (515~1,159) | 615ms (535~1,362) | 661ms (176~1,441) |
| S1 | 648 (365~725) | 892 (592~1,276) | 655ms (484~1,173) | 659ms (99~951) |
| S2 | 482 (429~631) | 759 (722~1,142) | 816ms (524~886) | 824ms (353~1,368) |
| S3 | 411 (298~440) | 669 (617~802) | 961ms (718~1,204) | 1,147ms (851~1,206) |
| S4 | 356 (346~557) | 648 (606~942) | 848ms (635~1,095) | 741ms (451~773) |

- 추세: S1은 S0과 구별되지 않는다. S2·S3에서 처리량이 내려간다. S3은 세 번 모두 S0 중앙값보다 낮았다.
- 원인 가설: 요청 하나의 DB 왕복이 S0보다 3번 늘었다. S1이 매수 INSERT를 따로 하고, S2가 선점 결과 UPDATE를 더하고, S3이 `set_config` SELECT를 더한다. S2부터는 멱등키 행이 트랜잭션 맨 앞에서 쓰여 커넥션 점유 시간도 길어진다. 풀이 병목인 상태에서는 왕복 수가 곧 처리량이다. 미확인: 구간별 시간을 Zipkin이나 쿼리 로그로 아직 나눠 보지 않았다.
- 편차: 같은 S0도 VU 400 처리량이 515~1,159 req/s로 2배 넘게 흔들렸다. 1회차는 모든 단계가 느렸다. 같은 장비에서 부하 생성기와 앱, Docker가 CPU를 나눠 쓰기 때문으로 보인다. 10~20% 차이는 이 측정으로 가릴 수 없다.

### 4-3. lock_timeout은 잠금 시도마다 따로 걸린다

C3에서 S3 뒤에도 일부 요청이 1초 넘게 기다렸다(라운드별 최댓값 811·1,060·547ms, 다른 실행에서 1,238·537·1,053ms). PostgreSQL 문서는 `lock_timeout`에 대해 "The time limit applies separately to each lock acquisition attempt"라고 적는다. 같은 행을 여러 요청이 기다리면 뒤 요청은 앞 요청이 쥔 튜플 잠금을 먼저 기다리고, 그것을 얻은 뒤 행을 쥔 트랜잭션을 다시 기다린다. 그래서 한 문장이 `lock_timeout`의 약 2배까지 기다릴 수 있다. 위 설명은 문서와 관측을 맞춘 해석이며, 잠금 대기 단계를 `pg_locks`로 직접 확인하지는 않았다.

## 5. 정리와 결정이 필요한 것

| # | 내용 | 제안 |
|---|---|---|
| 1 | TS-1은 데드락이 재현되지 않았다 | ISE-79 TS-1을 "핫 좌석 잠금 대기 빠른 실패"로 다시 쓴다. C2·C7은 회귀 테스트로 남긴다 |
| 2 | `lock_timeout` 500ms의 실제 상한은 약 1초다 | (가) SA 9-4의 의미를 "잠금 시도당 500ms"로 고치고 C3 목표를 1,500ms로 바꾼다. (나) 문장 상한이 필요하면 `statement_timeout`을 함께 건다. 가를 추천한다. 나는 느린 정상 쿼리도 끊는다 |
| 3 | 부하에서 `lock_timeout`의 효과는 거의 보이지 않았다 | 잠금 대기가 생기기 전에 커넥션 풀이 먼저 막힌다. lock_timeout은 처리량 개선이 아니라 "잠금이 오래 쥐어졌을 때의 상한"으로 기록한다 |
| 4 | 풀 5 → 20이 처리량을 약 2배로 올렸다 | `config-repo`의 풀 크기(5)를 SA 9-4(20)에 맞출지 contract 이슈로 제안한다. 공유 설정이다 |
| 5 | S2·S3이 처리량을 낮춘다 | 왕복을 줄이는 후보를 한 번에 하나씩 잰다. 매수 INSERT와 UPDATE를 `ON CONFLICT DO UPDATE ... WHERE` 한 문장으로 합치기(SA 12-7과 다르므로 확인 필요), `lock_timeout`을 앱 DB 계정 기본값으로 두기(만료·결제 소비자에도 걸린다), 결제 토큰 서명을 커밋 뒤로 옮기기(H3) |
| 6 | 측정 편차가 크다 | ISE-25 도구로 부하 생성기를 다른 기기에서 돌려 다시 잰다. 이 문서의 수치는 추세 확인용이다 |
| 7 | 추첨 선점(`SystemSeatHoldService`)도 기준선과 같은 "읽고 검사한 뒤 더하기" 매수 반영을 쓴다 | 추첨 담당과 같이 볼지 정한다. 이번 변경에는 넣지 않았다 |

다음 순서는 이 결정을 받은 뒤 S1~S4를 본 브랜치에 단계별 커밋으로 옮기고 `docs/troubleshooting.md`에 기록하는 것이다.

## 6. 재현 방법

저장소 루트에서 실행한다. Docker Desktop이 켜져 있어야 한다. 테스트는 `reservation-service`의 `seathold.concurrency` 패키지에 있고 환경 변수를 켤 때만 돈다. 본 브랜치 코드는 아직 기준선(S0)이므로 지금 돌리면 S0 수치가 나온다.

```bash
# 동시성 C1~C7
SEATHOLD_CONCURRENCY=true ./gradlew :reservation-service:test --tests 'com.iseonjwa.reservation.seathold.*' --rerun --no-daemon
# 부하 L1·L2·L3·L6 (풀 크기 선택)
SEATHOLD_LOAD=true SEATHOLD_LOAD_POOL=20 ./gradlew :reservation-service:test --tests '*SeatHoldLoadTest' --rerun --no-daemon
# 반복 측정용 축소 L3
SEATHOLD_LOAD=true SEATHOLD_LOAD_POOL=20 SEATHOLD_LOAD_STEPS=100,400 SEATHOLD_LOAD_STEP_SECONDS=15 ./gradlew :reservation-service:test --tests '*SeatHoldLoadTest.L3*' --rerun --no-daemon
```

결과는 `reservation-service/build/test-results/test/*.xml`의 `[SEATHOLD-CONCURRENCY]`·`[SEATHOLD-LOAD]` 줄이다.
