# F1 장애 주입 절차 (11/11 서버)

상태: 초안(2026-11-09 작성, 실행 전). 결론: 11/11 오전에 배포 서버에서 추첨 자동결제 경로의 장애 3건을 차례로 주입하고, 장애·경보·복구 시각과 DB 수렴 결과를 7절 표에 남긴다. 범위는 ISE-94 "장애 주입" 항목이고, 근거는 [실패 표](details.md) 6절과 [ADR 0038](../../decisions/0038-lottery-payment-failure-by-reason.md)이다. 숫자와 명령 중 코드·문서로 확인하지 못한 것은 "확인 필요"로 적었다.

## 1. 무엇을 증명하나

| ID | 주입 | 증명할 것 | 근거 |
|---|---|---|---|
| F1-a | 자동결제 중 결제 서버를 강제 종료(`SIGKILL`) | PG에 보내지 않은 결제는 재확인이 `DECLINED(NOT_SENT)`로 닫고, 추첨은 같은 당첨자에게 새 예매로 1회 재시도한다. PG가 받은 결제는 `APPROVED`로 수렴한다. 이중 결제가 없다 | ADR 0018 처리 순서, ADR 0038, `PaymentRecheckService.preventLateApproval` |
| F1-b | outbox 릴레이(Kafka) 정지 | 자동결제 요청이 나가지 않아 선점이 만료 시각에 이르면 만료 스케줄러가 1회 연장한다(최대 30분, 상한 추첨 + 60분) | details.md 6절 "outbox 릴레이·Kafka 정지", `SpringDataReservationRepository.extendLotteryHoldOnce` |
| F1-c | F1-b 복구 | 밀린 요청이 발행돼 예매당 결제가 1건만 생기고 확정된다 | D12, 결제 멱등(`reservationId` 단위) |

함께 남길 것(F4 겸용): 장애마다 "주입 → 슬랙 firing → 복구 → resolved" 시각. 울려야 했는데 울리지 않은 경보도 적는다.

## 2. 지킬 규칙

- **측정·F1 시간에는 시연 패널을 쓰지 않는다**(ISE-213). 운영자 화면의 시연 장애 패널은 Gateway 운영자 경로(`/api/v1/admin/demo/pg/faults`)로 Mock PG 장애를 건다. `orderId` 없이 건 장애는 서버의 모든 승인 요청에 적용되므로, 측정 중 누가 패널을 누르면 측정 결과와 F1 결과가 함께 오염된다. F1의 Mock PG 조작은 계정 담당이 서버 안에서 `docker exec ... curl`로만 하고, 시작 공지에 "시연 패널 사용 금지"를 넣는다. 패널(PR `#398`)이 서버에 배포됐는지는 확인 필요.
- 서버 컨테이너 조작(종료·정지·재시작), 회차·추첨 생성, DB 쓰기는 계정 담당(안준현)이 한다. 강민은 명령과 시각 기록을 맡는다.
- 측정과 겹치지 않는다. 발생기 측정(L1·L2)이 끝난 뒤 시작하고, F1 동안 발생기 측정을 하지 않는다([loadgen.md](../../guides/loadgen.md) 2절 "측정은 한 번에 한 명"과 같은 이유).
- F1 시작부터 끝까지 main 머지를 멈춘다. Jenkins가 배포하면 Mock PG가 재생성돼 빌링키가 사라지고(F2), 컨테이너 상태가 바뀐다.
- F1은 측정 모드를 켜지 않고 한다. k6를 쓰지 않으므로 측정 전용 키가 필요 없고, 측정 모드가 켜져 있으면 추적 샘플링이 0.01이라 Zipkin으로 따라가기 어렵다. 측정 모드가 켜진 채로 F1을 하게 되면 그 사실을 7절에 적는다.
- **Mock PG는 재시작하지 않는다.** 빌링키가 메모리에만 있어 재시작하면 자동결제가 `BILLING_KEY_MISSING`으로 실패하고 승계된다(시연 문서 5절 L-05 1회차). 그래서 F1-a를 "PG 정지"로 대신하지 않는다.
- 결제 서버 종료 동안 일반 사용자 결제도 503이다. 공유 서버라 팀 채널 공지 없이 시작하지 않는다.

## 3. 사전 준비 (11/10 저녁 또는 11/11 오전)

| 순서 | 누가 | 할 일 | 완료 확인 |
|---|---|---|---|
| 1 | 강민 | 팀 채널에 F1 시간, 결제 서버 종료 시각, Kafka 정지 시각, "main 머지 금지", "시연 패널 사용 금지"를 공지한다 | 공지 완료 |
| 2 | 계정 담당 | 서버 커밋과 컨테이너 이름을 확인한다(`cat .deploy/current`, `docker ps --format '{{.Names}}'`). 아래 명령은 `iseonjwa-<서비스>-1` 이름을 가정한다(시연 문서 4절 `iseonjwa-mock-pg-1`과 같은 규칙). Kafka 컨테이너 이름은 확인 필요 | 이름 표 |
| 3 | 계정 담당 | Mock PG 장애 설정이 비어 있는지 본다(`docker exec iseonjwa-mock-pg-1 curl -s http://localhost:8090/mock-pg/faults`) | `NONE` |
| 4 | 계정 담당 | F1 전용 회차와 추첨을 만든다. 추첨석은 F1-a에 20석 이상, F1-b에 5석 안팎이면 된다(`[가정]`). 서버에서 추첨을 만드는 방법은 l1l2-ids 작업(seed-demo 확장 또는 운영 화면)의 결과를 따른다. 확인 필요 | 회차 ID, 추첨 ID |
| 5 | 계정 담당 | 응모자 적재: `LOTTERY_SEED_ALLOW_REMOTE` 없이 서버 안에서 `script/deploy/seed-lottery-entrants.sh prepare` → 응모 기간에 `enter <추첨 id>`. 서버 안 기준 주소(기본 `127.0.0.1:8000`이 서버에서도 맞는지)는 확인 필요 | `ENTERED` 건수 |
| 6 | 강민 | Grafana `결제`·`추첨` 대시보드, Alertmanager 슬랙 채널을 연다. Grafana는 SSH 터널로 연다(슬랙 "바로 가기"가 `localhost` 주소다) | 화면 열림 |
| 7 | 강민 | 시작 전 기준값을 적는다: `dead_letters` 건수(예매·결제), `outbox_events` 미발행 건수, 활성 경보 목록. 이전 데이터가 있어 0을 기대하지 않는다 | 7절 "시작 전" 행 |

## 4. F1-a 결제 서버 강제 종료 → `NOT_SENT` 재시도

**원리**: 결제 한 건은 허가 획득 → TX1(`REQUESTED` 커밋) → PG 호출 → TX2 순서다(ADR 0018). TX1 커밋 뒤, PG 호출 전에 프로세스가 죽으면 `REQUESTED`가 남는다. 재확인 스케줄러가 PG를 조회해 주문이 없으면 늦은 승인 방지(망취소)를 하고 `Prevented`면 `DECLINED(NOT_SENT)`로 닫는다. 추첨은 `NOT_SENT`만 `reservation_id`를 비우고 `payment_attempts < 2`면 배정 워커가 새 예매로 다시 결제를 요청한다.

**한계**: TX1 커밋과 PG 호출 사이 간격이 짧아 종료 시점을 맞출 수 없다. 그래서 자동결제가 몰리는 동안 종료해 진행 중인 건 중 일부가 그 간격에 걸리기를 기대한다. 걸린 건이 0이면 `NOT_SENT` 경로는 이번에 재현하지 못한 것으로 적는다. 진행 중이던 나머지(PG가 받은 건)는 `APPROVED`로 수렴해야 하고, 이것도 결과로 쓴다.

1. 추첨 시각 직후 배정 워커가 자동결제를 내보내기 시작하면(Grafana `결제` 승인 수 증가, 또는 `payments`에 `REQUESTED` 생김) 시각 T0를 적는다.
2. 계정 담당이 결제 서버를 강제 종료한다. `docker restart`는 `SIGTERM`이라 정상 종료로 진행 중인 호출을 마치므로(시연 문서 F-10) `kill`을 쓴다.
   ```bash
   docker kill iseonjwa-payment-service-1        # SIGKILL. 시각 T1
   ```
3. 30초 기다린 뒤(`[가정]`, 종료 상태에서 `REQUESTED`가 남은 것을 보려는 시간) 다시 띄운다. `restart: unless-stopped`가 `docker kill` 뒤 자동으로 다시 띄우는지는 확인 필요. 이미 떠 있으면 그 시각을 T2로 적고 이 단계를 건너뛴다.
   ```bash
   docker start iseonjwa-payment-service-1       # 시각 T2. 기동 완료(헬스 정상)는 T3
   ```
4. 재확인 스케줄러가 남은 건을 처리할 때까지 기다린다. 재조회는 10초 간격 6회부터라 2분 안에 끝날 것으로 본다(`[가정]`).
5. 5절 쿼리로 확인한다.

기대 결과:

| 확인 | 기대 |
|---|---|
| T1 시점 `REQUESTED`였던 결제 | 모두 `APPROVED` 또는 `DECLINED(NOT_SENT)`로 수렴. `NEEDS_MANUAL_CHECK` 0건 |
| `NOT_SENT` 결제의 응모 | `WON` 유지, `payment_attempts` 2, 새 예매로 결제 1건 더. 2회째도 실패하면 `FAILED` + 승계 |
| PG 원장 | 주문당 승인 1건 이하. `NOT_SENT`로 닫힌 주문은 승인 0건 |
| `dead_letters` | 시작 전보다 늘지 않음 |
| 경보 | 결제 서버가 내려가 있는 동안 결제 서비스 지표가 없어진다. 규칙 파일(`infra/prometheus/rules/*.yml`)에 `up == 0` 같은 서비스 다운 경보가 없어 이 동안 슬랙이 오지 않을 것으로 본다. 재기동 뒤 `REQUESTED`가 60초 넘게 남으면 `PaymentRequestedStuck`이 울릴 수 있다 |

## 5. 확인 쿼리

계정 담당이 서버에서 읽기 전용으로 실행한다. 컬럼 이름은 마이그레이션 기준이다.

```sql
-- reservation DB: 이 추첨의 응모 상태
SELECT status, payment_attempts, count(*) FROM lottery_entries
 WHERE lottery_id = :lottery_id GROUP BY status, payment_attempts ORDER BY 1, 2;

-- reservation DB: 추첨 예매의 선점 만료와 연장(연장되면 hold_expires_at이 created_at + 30분보다 뒤다)
SELECT id, status, created_at, hold_expires_at, hold_expires_at - created_at AS held_for
  FROM reservations WHERE source = 'LOTTERY' AND schedule_id = :schedule_id ORDER BY id;

-- payment DB: 상태와 사유
SELECT status, decline_code, count(*) FROM payments
 WHERE created_at >= :t0 GROUP BY status, decline_code;

-- 두 DB: 실패 메시지
SELECT count(*) FROM dead_letters;
```

`payments.reservation_id`는 UNIQUE라(`V1__payment_core.sql`) 같은 예매에 결제 행이 두 개 생길 수 없다. 이중 결제는 결제 행이 아니라 PG 쪽 승인 수로 본다. Mock PG 원장 요약은 `docker exec iseonjwa-mock-pg-1 curl -s http://localhost:8090/mock-pg/ledger/summary`로 보고, 주문별 승인 수를 볼 수 있는지는 확인 필요(경로는 ISE-213 라우트 설명 기준).

## 6. F1-b 릴레이 정지 → 선점 1회 연장, F1-c 복구 뒤 결제 1회

**원리**: 배정 워커가 만든 결제 요청은 reservation outbox로 쓰이고 릴레이가 `payment.command`로 보낸다. 릴레이가 보내지 못하면 결제 요청이 결제 서버에 닿지 않는다. 추첨 예매의 선점은 생성 + 30분이고, 만료 시각에 `HELD`면 만료 스케줄러가 1회만 30분 연장한다(상한 추첨 + 60분). 복구 뒤 같은 예매의 요청이 발행되고 결제 멱등이 한 번만 처리한다.

**정지 방법 선택**

| 방법 | 장점 | 대가 |
|---|---|---|
| A. Kafka 컨테이너 정지(`docker stop`) | 설정 변경 없이 바로 된다. details.md 6절의 "릴레이·Kafka 정지" 그대로다 | 모든 서비스의 발행과 소비가 함께 멈춘다. 일반 예매 확정도 밀린다. Kafka 데이터가 볼륨에 남는지(compose `kafka` 볼륨이 있다)와 재기동 뒤 토픽이 그대로인지 확인 필요 |
| B. reservation 릴레이만 끄고 재기동(`messaging.outbox.relay-enabled=false`) | reservation 발행만 멈춘다 | compose 덮어쓰기가 필요하고 서버에서 직접 `docker compose up` 하지 않는 규칙의 예외가 된다. 재기동 두 번이 든다 |

추천: A. 서버 규칙을 바꾸지 않고, 계정 담당이 컨테이너 하나만 멈췄다 켜면 된다. 일반 예매가 함께 밀리는 대가는 공지로 감수한다.

**시간 문제**: 연장은 선점 생성 30분 뒤에 일어난다. 그대로 기다리면 Kafka를 30분 넘게 멈춰야 한다. 줄이려면 계정 담당이 F1-b 예매의 `hold_expires_at`을 앞당기는 DB 쓰기가 필요하다(시연 리허설 L-08에서 로컬 DB 시각을 조정한 것과 같은 방식). 공유 서버 DB 쓰기라 계정 담당 동의가 필요하다. 앞당겨도 연장 쿼리의 "1회" 조건(`hold_expires_at < created_at + 31분`)은 그대로 맞는다.

1. F1-a가 끝나고 결제가 모두 수렴한 뒤 시작한다. F1-b용 추첨은 추첨 시각 전이어야 한다.
2. 추첨 시각 직전에 Kafka를 정지한다. 시각 T4.
   ```bash
   docker stop iseonjwa-kafka-1                  # 컨테이너 이름 확인 필요
   ```
3. 추첨과 배정이 끝나 추첨 예매가 `HELD`로 생기고 reservation `outbox_events`에 미발행 행이 쌓이는지 본다. 결제 서버에 `REQUESTED`가 생기지 않아야 한다.
4. (동의를 받았으면) 계정 담당이 F1-b 예매의 선점 만료를 1분 뒤로 앞당긴다. 쿼리 문장은 계정 담당과 정한다.
5. 만료 시각이 지나고 만료 스케줄러(5초 주기)가 돈 뒤 5절 두 번째 쿼리로 연장을 확인한다. 시각 T5.
6. Kafka를 다시 띄운다. 시각 T6.
   ```bash
   docker start iseonjwa-kafka-1
   ```
7. 미발행 행이 발행되고 결제가 `APPROVED`, 응모가 `PAID`, 예매가 `CONFIRMED`가 될 때까지 기다린다. 시각 T7.

기대 결과:

| 확인 | 기대 |
|---|---|
| T5 | F1-b 예매 `HELD`, `hold_expires_at`이 처음 값 + 30분(상한 안), 응모 `WON` |
| T7 | 결제 `APPROVED`, PG 승인은 예매당 1회. 응모 `PAID`. `dead_letters` 증가 없음 |
| 연장 뒤에도 복구하지 않으면 | 두 번째 만료에서 `FAILED` + 승계. 이번 F1에서는 재현하지 않는다 |
| 경보 | reservation outbox에는 경보 규칙이 없다(규칙은 `application="payment-service"`만 본다). `PaymentOutboxRelayStalled`는 릴레이 주기가 예외 없이 끝나면 성공 시각이 갱신돼 울리지 않을 것으로 본다(`OutboxRelayScheduler.runTick` 주석). 결제 쪽 미발행이 300초를 넘으면 `PaymentOutboxDelayed`가 울릴 수 있다. 슬랙이 오지 않으면 "울려야 했는데 안 울린 경보"로 적는다 |

## 7. 시각 기록표

| 장애 | 주입 시각 | 슬랙 firing(경보 이름) | 복구 조작 시각 | 서비스 정상 시각 | 슬랙 resolved | DB 수렴 시각 | 결과 요약 |
|---|---|---|---|---|---|---|---|
| 시작 전 | — | 활성 경보: | — | — | — | — | `dead_letters` 예매 / 결제, 미발행 outbox |
| F1-a | T1 | | T2 | T3 | | | `REQUESTED` n건 → `APPROVED` / `NOT_SENT` |
| F1-b | T4 | | — | — | | T5(연장) | 연장 건수 |
| F1-c | — | | T6 | | | T7 | 예매당 PG 승인 수, `PAID` 건수 |

시각은 KST로 초까지 적는다. 서버 시계는 chrony로 맞춰져 있다([loadgen.md](../../guides/loadgen.md) 1절). 함께 적을 것: 서버 커밋, 측정 모드 여부, 추첨 ID와 추첨석 수, 응모자 수.

## 8. 끝낼 때

1. Mock PG 장애 설정을 다시 확인한다(`NONE`).
2. 모든 컨테이너가 떠 있는지 보고 스모크를 돈다(`script/deploy/smoke.sh`, 서버 안 인자는 확인 필요).
3. 팀 채널에 끝 공지(main 머지 재개, 시연 패널 사용 가능)를 올린다.
4. 결과를 이 문서 7절에 채우고, 판정은 [numbers-and-measurement.md](numbers-and-measurement.md)와 ISE-94 완료 기준에 옮긴다.

## 9. 미확인

- 서버 컨테이너 이름(특히 Kafka), `docker kill` 뒤 자동 재기동 여부
- F1-a에서 PG 호출 전 간격에 걸리는 결제가 생기는지. 생기지 않으면 `NOT_SENT` 재시도는 통합 테스트 결과로 대신 제시한다(테스트 이름 확인 필요)
- 서버에서 추첨을 만드는 방법, `seed-lottery-entrants.sh`의 서버 안 기준 주소
- F1-b 선점 만료 앞당기기 DB 쓰기에 계정 담당이 동의하는지. 동의하지 않으면 Kafka를 30분 넘게 멈춘다
- reservation outbox 경보 규칙을 F1 전에 넓힐지(결제 담당 결정, handoff 11/8 메모)
- 시연 패널(PR `#398`) 배포 여부
