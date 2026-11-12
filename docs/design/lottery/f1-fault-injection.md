# F1 장애 주입 절차 (11/11 서버)

상태: 실행 완료(2026-11-11 16:25~16:57 KST, 결과는 7절). 결론: 2026-11-11 배포 서버에서 추첨 자동결제 경로의 장애 3건을 차례로 주입하고, 장애·경보·복구 시각과 DB 수렴 결과를 7절 표에 남긴다. 범위는 ISE-94 "장애 주입" 항목이고, 근거는 [실패 표](details.md) 6절과 [ADR 0038](../../decisions/0038-lottery-payment-failure-by-reason.md)이다. 저장소 코드·compose·스크립트로 확인하지 못해 서버에서 봐야 하는 것은 "서버 확인"으로 적었다.

## 0. 정한 것 (2026-11-11, kangmin)

| # | 항목 | 정한 것 | 감수할 점 |
|---|---|---|---|
| 1 | 누가 실행하나 | 읽기·시드·`docker kill/stop/start`는 단계마다 강민의 허락을 받아 에이전트가 SSH로 실행한다. DB 쓰기는 계정 담당(안준현)이 동의한 뒤에만 한다. 강민은 진행 판단과 시각 확인을 맡는다 | 팀 규칙(`docs/context/conductor.md` 규칙 10, [loadgen.md](../../guides/loadgen.md) 8절: 서버 명령은 사람이 직접 치고 에이전트는 안내만)과 다르다. **이번 F1에만 쓰는 예외**이고, 시작 전에 계정 담당에게 알린다 |
| 2 | F1-b 정지 방법 | Kafka 컨테이너 정지(`docker stop iseonjwa-kafka-1`) | 모든 서비스의 발행·소비가 함께 멈춘다. reservation 릴레이만 끄는 방법은 서버에서 `docker compose up`을 해야 해서 금지 규칙([deploy-server-access.md](../../guides/deploy-server-access.md) 4절)에 걸린다 |
| 3 | `NOT_SENT`를 보이는 방법 | 서버에서는 `kill` 1회로 "진행 중 결제가 `APPROVED`로 수렴하고 이중 결제가 0"임을 보인다. `NOT_SENT`는 통합 테스트 4개(9절) 통과로 대신한다. 서버에서 생기면 함께 적는다 | `payment.command` 소비자가 1개라 한 순간 진행 중인 결제가 1건뿐이다. 그 1건이 TX1(`REQUESTED` 저장)과 PG 호출 사이에 있을 확률이 매우 낮아, 서버 화면에는 `NOT_SENT`가 안 나올 가능성이 크다 |
| 4 | reservation outbox 경보 | 넓히지 않는다. "울려야 했는데 안 울린 경보"로 적고, 대신 `LotterySettlementSlow`가 울리는지 본다. 넓히는 일은 결제 담당에게 개선 후보로 남긴다 | F1-b의 탐지가 10분 넘게 걸린다는 결과가 나온다. 이것도 발견으로 적는다 |
| 5 | 선점 만료 앞당기기 | 계정 담당이 동의하면 F1-b 예매의 `hold_expires_at`을 DB UPDATE 1회로 1분 뒤로 앞당긴다. Kafka는 `LotterySettlementSlow`(10분)를 보려고 약 12분 멈춘다. 동의가 없으면 연장 확인을 빼고 Kafka를 3~5분만 멈췄다 복구해 F1-c만 본다 | 공유 DB 쓰기 1회. 동의가 없으면 "선점 1회 연장"은 서버에서 보이지 못한다 |
| 6 | 측정 모드 | **배포 서버 테스트는 측정 모드를 켜고(`MEASURER=kangmin script/loadtest/measure-mode.sh on`) 팀에 알린 뒤에만 한다(강민, 11/11).** 켜면 슬랙 자동 알림이 가고, 끝나면 `off`로 끈다. 처음 정한 "측정 모드 켜지 않음"은 거부했다([판단 기록](../../ai-judgments/kangmin/2026-11-11-f1-measure-mode-required.md)) | 추적 샘플링이 0.01이라 Zipkin으로 장애 경로를 따라가기 어렵다. 켜고 끌 때 gateway·user·reservation·payment·eureka·prometheus가 다시 뜨고(약 1분), 기존 로그인이 끊긴다 |

## 1. 무엇을 증명하나

| ID | 주입 | 증명할 것 | 근거 |
|---|---|---|---|
| F1-a | 자동결제 중 결제 서버를 강제 종료(`SIGKILL`) | PG에 보내지 않은 결제는 재확인이 `DECLINED(NOT_SENT)`로 닫고, 추첨은 같은 당첨자에게 새 예매로 1회 재시도한다. PG가 받은 결제는 `APPROVED`로 수렴한다. 이중 결제가 없다 | ADR 0018 처리 순서, ADR 0038, `PaymentRecheckService.preventLateApproval` |
| F1-b | outbox 릴레이(Kafka) 정지 | 자동결제 요청이 나가지 않아 선점이 만료 시각에 이르면 만료 스케줄러가 1회 연장한다(최대 30분, 상한 추첨 + 60분) | details.md 6절 "outbox 릴레이·Kafka 정지", `SpringDataReservationRepository.extendLotteryHoldOnce` |
| F1-c | F1-b 복구 | 밀린 요청이 발행돼 예매당 결제가 1건만 생기고 확정된다 | D12, 결제 멱등(`reservationId` 단위) |

함께 남길 것(F4 겸용): 장애마다 "주입 → 슬랙 firing → 복구 → resolved" 시각. 울려야 했는데 울리지 않은 경보도 적는다.

## 2. 지킬 규칙

- **측정·F1 시간에는 시연 패널을 쓰지 않는다**(ISE-213). 운영자 화면의 시연 장애 패널은 Gateway 운영자 경로(`/api/v1/admin/demo/pg/faults`)로 Mock PG 장애를 건다. `orderId` 없이 건 장애는 서버의 모든 승인 요청에 적용되므로, 측정 중 누가 패널을 누르면 측정 결과와 F1 결과가 함께 오염된다. F1의 Mock PG 확인은 서버 안에서 `docker exec ... curl`로만 하고, 시작 공지에 "시연 패널 사용 금지"를 넣는다. 패널은 배포돼 있는 것으로 본다(Gateway 라우트가 main `config-repo/gateway.yml`에 있고 화면 `AdminDemoFaultsPage`도 있다).
- **`POST /mock-pg/reset`은 부르지 않는다.** 원장과 장애 설정을 새 객체로 바꿔 이중 결제 확인 근거가 사라진다(`LedgerController.reset`).
- 실행 주체는 0절 1번을 따른다. 컨테이너 조작(종료·정지·시작)과 시드는 강민의 허락을 단계마다 받고, DB 쓰기는 계정 담당 동의 뒤에만 한다.
- 측정과 겹치지 않는다. 발생기 측정(L1·L2)이 끝난 뒤 시작하고, F1 동안 발생기 측정을 하지 않는다([loadgen.md](../../guides/loadgen.md) 2절 "측정은 한 번에 한 명"과 같은 이유).
- F1 시작부터 끝까지 main 머지를 멈춘다. Jenkins가 배포하면 Mock PG가 재생성돼 원장과 장애 설정이 비고, 컨테이너 상태가 바뀐다. F1 당시(11/11)에는 빌링키도 사라졌다(F2). ISE-235(`#505`) 배포 뒤에는 Mock PG가 빌링키를 서명으로 확인해 재생성해도 빌링키는 유효하다([mock-pg.md](../payment/mock-pg.md) 13절). 원장과 장애 설정은 여전히 메모리라 이 규칙은 그대로다.
- **F1은 측정 모드를 켜고 한다(0절 6번, 강민 11/11).** 켜고 끄는 슬랙 자동 알림으로 팀에 서버 사용을 알린다. 처음에는 "추적 샘플링 1.0을 지키려고 켜지 않는다"였는데 거부했다. 켜기와 끄기는 진행 중인 자동결제와 겹치지 않게 한다(reservation·payment가 다시 뜬다).
- **Mock PG는 재시작하지 않는다.** F1 당시에는 빌링키가 메모리에만 있어 재시작하면 자동결제가 `BILLING_KEY_MISSING`으로 실패하고 승계됐다(시연 문서 5절 L-05 1회차). ISE-235 배포 뒤에는 빌링키가 남지만, 원장이 메모리(`LedgerHolder`)라 재시작하면 "원장 승인 = 결제 DB `APPROVED`"로 이중 결제를 확인할 근거가 사라진다. 그래서 다시 실행할 때도 F1-a를 "PG 정지"로 대신하지 않는다.
- 결제 서버 종료 동안 일반 사용자 결제도 503이다. 공유 서버라 팀 채널 공지 없이 시작하지 않는다.

## 3. 사전 준비 (11/10 저녁 또는 11/11 오전)

| 순서 | 누가 | 할 일 | 완료 확인 |
|---|---|---|---|
| 1 | 강민 | 계정 담당에게 0절 1·5번(에이전트 서버 실행 예외, DB 만료 앞당기기 동의)을 알리고, 팀 채널에 F1 시간, 결제 서버 종료 시각, Kafka 정지 시각(약 12분), "main 머지 금지", "시연 패널 사용 금지"를 공지한다 | 공지 완료, 동의 여부 |
| 2 | 에이전트 | 서버 커밋과 컨테이너 이름을 확인한다(`cat .deploy/current`, `docker ps --format '{{.Names}} {{.Status}}'`). compose 프로젝트 이름이 `iseonjwa`라(`compose.yml` 1행) 이름은 `iseonjwa-<서비스>-1`이고 Kafka는 `iseonjwa-kafka-1`이다. 서버 확인 | 이름 표 |
| 3 | 에이전트 | Mock PG 장애 설정이 비어 있는지 본다(`docker exec iseonjwa-mock-pg-1 curl -s http://localhost:8090/mock-pg/faults`) | `NONE` |
| 4 | 에이전트 | F1 전용 회차와 추첨을 서버 안에서 만든다: `LOTTERY_SEAT_COUNT=<석 수> script/deploy/seed-demo.sh lottery <응모 시작> <응모 마감> <추첨>`. **추첨석 기본값이 3,000석이라 `LOTTERY_SEAT_COUNT`를 꼭 넘긴다**(F1-a 20석, F1-b 5석, `[가정]`). 응모 시작은 지금부터 1시간 안이어야 한다. 출력의 `SCHEDULE_ID`, `LOTTERY_ID`를 적는다 | 회차 ID, 추첨 ID |
| 5 | 에이전트 | 응모자 적재: 서버 안에서 `script/deploy/seed-lottery-entrants.sh prepare http://127.0.0.1` → 응모 기간에 `enter <추첨 id> http://127.0.0.1`. **기본 주소 `127.0.0.1:8000`은 로컬 nginx 포트라 서버에서는 틀린다.** 배포 nginx는 80이다. loopback이라 `LOTTERY_SEED_ALLOW_REMOTE`는 필요 없다. 응모자 기본 120명(2매 30명, 카드 없음 12명) | `ENTERED` 건수 |
| 6 | 강민 | Grafana `결제`·`추첨` 대시보드, Alertmanager 활성 경보, 슬랙 채널을 연다. 터널: `ssh -L 3000:127.0.0.1:3000 -L 9093:127.0.0.1:9093 ubuntu@<서버>`(슬랙 "바로 가기"가 `localhost` 주소다) | 화면 열림 |
| 7 | 에이전트 | 시작 전 기준값을 적는다: `dead_letters` 건수(예매·결제), 예매 `outbox_events`의 `status`별 건수, 모든 추첨의 `WON` 건수, 활성 경보 목록. 이전 데이터가 있어 0을 기대하지 않는다. `WON`이 이미 있으면 `LotterySettlementSlow`가 시작 전부터 울리고 있을 수 있다(집계가 모든 추첨의 합이다) | 7절 "시작 전" 행 |

## 4. F1-a 결제 서버 강제 종료 → `NOT_SENT` 재시도

**원리**: 결제 한 건은 허가 획득 → TX1(`REQUESTED` 커밋) → PG 호출 → TX2 순서다(ADR 0018). TX1 커밋 뒤, PG 호출 전에 프로세스가 죽으면 `REQUESTED`가 남는다. 재확인 스케줄러가 PG를 조회해 주문이 없으면 늦은 승인 방지(망취소)를 하고 `Prevented`면 `DECLINED(NOT_SENT)`로 닫는다. 추첨은 `NOT_SENT`만 `reservation_id`를 비우고 `payment_attempts < 2`면 배정 워커가 새 예매로 다시 결제를 요청한다.

**한계**: TX1 커밋과 PG 호출 사이 간격이 짧아 종료 시점을 맞출 수 없다. 게다가 `payment.command` 소비자가 1개라 한 순간 진행 중인 결제가 1건뿐이다([numbers-and-measurement.md](numbers-and-measurement.md) 3-2절). 걸린 건이 0이면 `NOT_SENT` 경로는 0절 3번대로 통합 테스트로 대신한다. 진행 중이던 건(PG가 받은 건)은 `APPROVED`로 수렴해야 하고, 결제 서버가 받아 놓고 처리하지 못한 메시지는 재기동 뒤 다시 받아 한 번만 처리돼야 한다. 이것을 서버 결과로 쓴다.

1. 추첨 시각 직후 배정 워커가 자동결제를 내보내기 시작하면(Grafana `결제` 승인 수 증가, 또는 `payments`에 `REQUESTED` 생김) 시각 T0를 적는다.
2. 에이전트가 강민의 허락을 받아 결제 서버를 강제 종료한다. `docker restart`는 `SIGTERM`이라 정상 종료로 진행 중인 호출을 마치므로(시연 문서 F-10) `kill`을 쓴다.
   ```bash
   docker kill iseonjwa-payment-service-1        # SIGKILL. 시각 T1
   ```
3. `docker ps`로 자동 재기동 여부를 먼저 본다. 결제 서버는 `restart: unless-stopped`인데(`compose.deploy.yml`), `docker kill`이 손으로 멈춘 것으로 처리돼 다시 띄우지 않는지는 서버 확인이다. 이미 떠 있으면 그 시각을 T2로 적고 이 단계를 건너뛴다. 꺼져 있으면 30초 기다린 뒤(`[가정]`, 종료 상태에서 `REQUESTED`가 남은 것을 보려는 시간) 다시 띄운다.
   ```bash
   docker start iseonjwa-payment-service-1       # 시각 T2. 기동 완료(헬스 정상)는 T3
   ```
4. 재확인 스케줄러가 남은 건을 처리할 때까지 기다린다. `REQUESTED`는 TX1 + 10초에 재확인 대상이 되고(`Payment.FIRST_RECHECK_DELAY`), 스케줄러는 1초마다 최대 20건을 본다(`PaymentRecheckProperties`). PG에 주문이 없으면 첫 재확인에서 망취소 → `NOT_SENT`다. 그래서 결제 서버가 뜬 뒤 수십 초 안에 끝날 것으로 본다(계산값, 기동 시간 제외). 22분 넘게 확정 못 하면 `NEEDS_MANUAL_CHECK`다([events-and-reliability.md](../payment/events-and-reliability.md) 6절).
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

에이전트가 서버에서 읽기 전용으로 실행한다. 컬럼 이름은 마이그레이션 기준으로 확인했다.

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

-- reservation DB: outbox 미발행(PENDING)과 영구 실패(FAILED)
SELECT status, count(*) FROM outbox_events GROUP BY status;

-- 두 DB: 실패 메시지
SELECT count(*) FROM dead_letters;
```

`payments.reservation_id`는 UNIQUE라(`V1__payment_core.sql`) 같은 예매에 결제 행이 두 개 생길 수 없다. 이중 결제는 결제 행이 아니라 PG 쪽 승인 수로 본다. Mock PG 원장은 서버 안에서 `docker exec iseonjwa-mock-pg-1 curl -s http://localhost:8090/mock-pg/...`로 본다(포트 8090, 이미지에 curl 있음). 요약 `ledger/summary`, 승인 주문 목록 `ledger/orders?status=APPROVED&limit=1000`, 주문 1건 `ledger/payments/{orderId}`가 있다(`LedgerController`). `NOT_SENT` 재시도는 새 예매로 새 주문을 만들므로, 이중 결제는 "같은 응모의 `APPROVED` 결제 수"와 "원장의 승인 주문 수 = 결제 DB `APPROVED` 수"로 본다.

## 6. F1-b 릴레이 정지 → 선점 1회 연장, F1-c 복구 뒤 결제 1회

**원리**: 배정 워커가 만든 결제 요청은 reservation outbox로 쓰이고 릴레이가 `payment.command`로 보낸다. 릴레이가 보내지 못하면 결제 요청이 결제 서버에 닿지 않는다. 추첨 예매의 선점은 생성 + 30분이고, 만료 시각에 `HELD`면 만료 스케줄러가 1회만 30분 연장한다(상한 추첨 + 60분). 복구 뒤 같은 예매의 요청이 발행되고 결제 멱등이 한 번만 처리한다.

**정지 방법 선택**

| 방법 | 장점 | 대가 |
|---|---|---|
| A. Kafka 컨테이너 정지(`docker stop`) | 설정 변경 없이 바로 된다. details.md 6절의 "릴레이·Kafka 정지" 그대로다. 데이터는 이름 있는 볼륨 `kafka`(`/var/lib/kafka/data`)에 있어 `stop/start` 뒤에도 토픽과 오프셋이 남는다 | 모든 서비스의 발행과 소비가 함께 멈춘다. 일반 예매 확정도 밀린다 |
| B. reservation 릴레이만 끄고 재기동(`messaging.outbox.relay-enabled=false`) | reservation 발행만 멈춘다 | compose 덮어쓰기와 서버에서 직접 `docker compose up`이 필요해 금지 규칙에 걸린다. 쓸 수 없다 |

정한 것: A(0절 2번). 일반 예매가 함께 밀리는 대가는 공지로 감수한다.

**시간 문제**: 연장은 선점 생성 30분 뒤에 일어난다. 그대로 기다리면 Kafka를 30분 넘게 멈춰야 한다. 그래서 0절 5번대로 계정 담당이 동의하면 F1-b 예매의 `hold_expires_at`을 1분 뒤로 앞당긴다(시연 리허설 L-08에서 로컬 DB 시각을 조정한 것과 같은 방식). 앞당겨도 연장 쿼리의 "1회" 조건(`hold_expires_at < created_at + 31분`)은 그대로 맞는다. 동의가 없으면 4·5단계를 건너뛰고 Kafka를 3~5분만 멈췄다 복구한다.

1. F1-a가 끝나고 결제가 모두 수렴한 뒤 시작한다. F1-b용 추첨은 추첨 시각 전이어야 한다.
2. 추첨 시각 직전에 Kafka를 정지한다. 시각 T4.
   ```bash
   docker stop iseonjwa-kafka-1
   ```
3. 추첨과 배정이 끝나 추첨 예매가 `HELD`로 생기고 reservation `outbox_events`에 `PENDING` 행이 쌓이는지 본다. 결제 서버에 `REQUESTED`가 생기지 않아야 한다. 브로커 연결 실패는 일시 오류라 `FAILED`가 아니라 재시도로 남는다(`OutboxRelay`, `OutboxFailureClassifier`). `FAILED`가 생기면 그 키가 멈추므로 바로 적는다.
4. (동의를 받았으면) F1-b 예매의 선점 만료를 1분 뒤로 앞당긴다. 쿼리 문장은 계정 담당과 정하고, 동의 뒤에만 실행한다.
5. 만료 시각이 지나고 만료 스케줄러(5초 주기, `seathold.expiry.interval`)가 돈 뒤 5절 두 번째 쿼리로 연장을 확인한다. 시각 T5. Kafka는 추첨 뒤 약 12분까지 멈춘 채 `LotterySettlementSlow`(당첨자 `WON` 10분 초과) firing을 기다린다.
6. Kafka를 다시 띄운다. 시각 T6.
   ```bash
   docker start iseonjwa-kafka-1
   ```
7. 미발행 행이 발행되고 결제가 `APPROVED`, 응모가 `PAID`, 예매가 `CONFIRMED`가 될 때까지 기다린다. 시각 T7. 재시도 간격은 최대 60초라(`OutboxBackoff`, 지터로 30~60초) Kafka가 뜬 뒤 약 1분 안에 발행될 것으로 본다(계산값).

기대 결과:

| 확인 | 기대 |
|---|---|
| T5 | F1-b 예매 `HELD`, `hold_expires_at`이 연장 직전 만료 시각 + 30분(상한 안). 앞당겼다면 앞당긴 시각 + 30분이다(`extendLotteryHoldOnce`). 응모 `WON` |
| T7 | 결제 `APPROVED`, PG 승인은 예매당 1회. 응모 `PAID`. `dead_letters` 증가 없음 |
| 연장 뒤에도 복구하지 않으면 | 두 번째 만료에서 `FAILED` + 승계. 이번 F1에서는 재현하지 않는다 |
| 경보 | reservation outbox에는 경보 규칙이 없다(규칙은 `application="payment-service"`만 본다). `PaymentOutboxRelayStalled`는 릴레이 주기가 예외 없이 끝나면 성공 시각이 갱신돼 울리지 않을 것으로 본다(`OutboxRelayScheduler.runTick` 주석). 결제 쪽 미발행이 300초를 넘으면 `PaymentOutboxDelayed`가 울릴 수 있다. 울려야 하는 것은 `LotterySettlementSlow`다(모든 추첨의 `WON` 합 > 0이 10분 지속, warning). Prometheus 평가와 Alertmanager `group_wait` 30초가 더해져 추첨 뒤 10분 남짓에 올 것으로 본다. reservation outbox 경보가 없는 것은 0절 4번대로 "안 울린 경보"로 적는다 |

## 7. 시각 기록표

| 장애 | 주입 시각 | 슬랙 firing(경보 이름) | 복구 조작 시각 | 서비스 정상 시각 | 슬랙 resolved | DB 수렴 시각 | 결과 요약 |
|---|---|---|---|---|---|---|---|
| 시작 전 | — | 활성 경보: 0건(2026-11-11 15:29 KST, Alertmanager API) | — | — | — | — | `dead_letters` 예매 0 / 결제 0. outbox 예매 `SENT` 32,252 / 결제 `SENT` 75,522, `PENDING`·`FAILED` 0. 응모 `WON` 0(`PAID` 14,125, `FAILED` 18,127, `LOST` 1,053,150). 추첨 예매 `HELD` 0. 결제 `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 0(`APPROVED` 57,394, `DECLINED` `BILLING_KEY_MISSING` 18,127·`CARD_DECLINED` 1). Mock PG 장애 5종 모두 `NONE`, 원장 비어 있음(Mock PG 15시간 전 재생성) |
| F1-a | T1 16:25:05.282 `docker kill`(T0 16:25:05.108에 첫 결제 행 감지, 그때 5행) | 없음. 활성 경보 0건(16:27, Alertmanager API). 서비스 다운 경보 규칙이 없고 `REQUESTED`가 남지 않아 `PaymentRequestedStuck`도 없음 | T2 16:25:35.085 `docker start`(자동 재기동 안 됨: `Exited (137)`, `RestartCount` 0) | T3 16:25:50.668 healthy(기동 15.6초) | — | 16:25:52.147(마지막 결제 갱신) | kill 시점 결제 `APPROVED` 7, `REQUESTED` 0. 재기동 뒤 나머지 처리: `APPROVED` 17, `DECLINED(BILLING_KEY_MISSING)` 2, `NOT_SENT` 0, `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 0. 응모 `PAID` 17(20매), `FAILED` 2(카드 없음), `WON` 0. 예매 `CONFIRMED` 17, `RELEASED` 2. 원장 승인 34 = 결제 DB `APPROVED` 34(추첨 23·24 합), 중복 주문 0. `dead_letters` 0/0, outbox `PENDING`·`FAILED` 0 |
| F1-b | T4 16:32:47.307 `docker stop iseonjwa-kafka-1`(강민이 직접 실행. 에이전트 실행은 권한 분류기가 막음) | `LotterySettlementSlow`(warning) Alertmanager `startsAt` 16:45:49.894(`WON` 생성 16:35:04 + 10분 45초). 슬랙 수신 시각은 확인 못 함(강민 확인). reservation outbox 경보는 없어 울리지 않음(0절 4번) | — | — | 활성 목록에서 사라짐 16:47:50 이전(F1-c 수렴 뒤). 슬랙 resolved는 확인 못 함 | T5 16:36:58.748~.763(연장) | 추첨 25(5석) 16:35:04에 `WON` 4(5매), 예매 `HELD` 4(78519~78522), reservation outbox `PENDING` 4, 결제 행 0. 16:35:55.736에 만료를 1분 뒤로 앞당김(대상 SELECT 4건 확인 뒤 `UPDATE reservations SET hold_expires_at = now() + interval '1 minute' WHERE schedule_id = 95 AND source = 'LOTTERY' AND status = 'HELD' AND id IN (78519,78520,78521,78522)`, 결과 `UPDATE 4`, 새 만료 16:36:55.787). 만료 3초 뒤 4건 모두 1회 연장: `hold_expires_at` 17:06:55.787(앞당긴 값 + 30분), `HELD` 유지, 응모 `WON` 유지 |
| F1-c | — | (F1-b 경보와 같음) | T6 16:46:14.034 `docker start iseonjwa-kafka-1` | Kafka healthy 16:46:26.505 | 위 F1-b 칸 | T7 16:46:49.214(마지막 예매 확정) | 밀린 outbox 4건 발행, `PENDING` 0. 결제 첫 생성 16:46:18.954. 당첨 4건 중 1건(78521)이 카드 없음으로 `BILLING_KEY_MISSING` → 승계 78523도 카드 없음 → 승계 78524 결제. 결과: 결제 `APPROVED` 4, `DECLINED(BILLING_KEY_MISSING)` 2, 응모 `PAID` 4(5매)·`FAILED` 2, 예매 `CONFIRMED` 4·`RELEASED` 2. 예매당 결제 1건. 원장 승인 38 = 결제 DB `APPROVED` 38(추첨 23·24·25 합), 중복 주문 0. `dead_letters` 0/0, `REQUESTED`·`UNKNOWN`·`NEEDS_MANUAL_CHECK` 0 |
| 끝(16:47:51) | — | 활성 경보 0건 | — | 컨테이너 21개 실행 중(헬스체크 있는 것 모두 healthy, `kafka-init`은 일회성으로 종료 상태), 스모크 4개 통과 | — | — | Mock PG 장애 5종 `NONE`. 기준값 대비: 응모 `WON` 0(같음), `PAID` +38, `FAILED` +6, 결제 `APPROVED` +38, `BILLING_KEY_MISSING` +6, `dead_letters` 0/0(같음), 추첨 예매 `HELD` 0(같음). `WAITLISTED` 575는 시작 전에 없던 값으로, 세 추첨의 예비자다(281+281+294=856과 차이 281은 확인 못 함). 측정 모드는 강민이 직접 `off`(16:53쯤, 재기동 시각으로 추정). 16:57:42 확인: 꺼짐, `.env.measure` 없음, 추적 샘플링 1.0, Prometheus 9090은 127.0.0.1에만 열림, 컨테이너 모두 healthy, 스모크 4개 통과, 활성 경보 0, 원장 승인 38 그대로 |

실행 조건(2026-11-11 F1 실행): 서버 커밋 `74f91ffb`, **측정 모드 켬**(측정자 kangmin, 16:04:30 KST, 강민이 직접 실행, 0절 6번). 응모자 300명(계획 `300 75 30`: 다른 PC에서 11/6 만든 시드 상태를 복사해 썼다. 상태 디렉터리는 `127.0.0.1_80`이라 기준 주소를 `http://127.0.0.1:80`으로 넘겼다. 포트를 빼면 `127.0.0.1_default`를 읽는다). 추첨 23(회차 93, 20석, 16:10): 감시를 측정 모드 누락으로 멈춰 kill 없이 끝난 일반 동작 기록(`PAID` 17, `FAILED` 2, 결제 16:10:04.98~16:10:08.71). 추첨 24(회차 94, 20석, 16:25): F1-a. 추첨 25(회차 95, 5석, 16:35): F1-b·c. F1-a kill은 사람이 T0를 보고 치지 않고, 서버 감시 스크립트가 0.3초마다 결제 행을 보다 첫 행에서 바로 `docker kill`했다(결제 19건이 약 4초에 끝나 사람 손으로는 늦다). 이전 기준(15:29): 측정 모드 꺼짐(`.deploy/measure-mode` 없음. gateway·payment·reservation·user·eureka·prometheus는 05:21Z에 다시 떴다), 컨테이너 21개 모두 실행 중(헬스체크가 있는 것은 모두 healthy), 응모자 시드 상태 디렉터리 없음(처음 `prepare`).

시각은 KST로 초까지 적는다. 서버 시계는 chrony로 맞춰져 있다([loadgen.md](../../guides/loadgen.md) 1절). 함께 적을 것: 서버 커밋, 측정 모드 여부, 추첨 ID와 추첨석 수, 응모자 수.

## 8. 끝낼 때

1. Mock PG 장애 설정을 다시 확인한다(`NONE`).
2. 모든 컨테이너가 떠 있는지 보고 스모크를 돈다(서버 안에서 인자 없이 `script/deploy/smoke.sh`. 기본 주소가 nginx 80이고 항목마다 최대 120초 다시 확인한다). 시작 전 기준값을 다시 보고 비교한다.
3. 팀 채널에 끝 공지(main 머지 재개, 시연 패널 사용 가능)를 올린다.
4. 결과를 이 문서 7절에 채우고, 판정은 [numbers-and-measurement.md](numbers-and-measurement.md)와 ISE-94 완료 기준에 옮긴다.

## 9. `NOT_SENT` 대체 테스트와 남은 확인

0절 3번에서 `NOT_SENT`를 대신하는 통합 테스트(F1 전에 로컬에서 돌려 통과를 남긴다):

| 서비스 | 테스트 | 보이는 것 |
|---|---|---|
| payment | `Ft3RestartScenarioTest.a_TX1_커밋_뒤_PG_호출_전에_죽으면_PG에_주문이_없어_NotFound_뒤_늦은_승인을_막고_DECLINED_NOT_SENT가_된다` | 결제 서버가 PG 호출 전에 죽으면 재확인이 `NOT_SENT`로 닫는다 |
| reservation | `LotteryScenarioTest.S3_NOT_SENT면_같은_당첨자가_새_예매로_다시_결제한다` | 추첨이 같은 당첨자에게 새 예매로 다시 결제한다 |
| reservation | `LotteryScenarioTest.S13_NOT_SENT_거절_뒤_2매_응모가_새_예매로_다시_배정되어_결제된다` | 2매 응모도 같다 |
| reservation | `PaymentResultLotteryTest.NOT_SENT_거절은_응모를_WON으로_두고_예매_연결을_비워_재배정에_맡긴다` | 결과 반영 규칙 |

실행 결과(2026-11-11 15:15 KST, 로컬 `dev` `e122d335` + 미커밋 문서 변경만, Docker Desktop 29.8.2): **4개 모두 통과, 실패 0, 건너뜀 0.** 개수는 JUnit 결과 XML(`build/test-results/test/TEST-*.xml`)에서 읽었다.

```bash
./gradlew :payment-service:test --tests 'com.iseonjwa.payment.scenario.Ft3RestartScenarioTest.a_*' --no-daemon
# 1개 통과(40초)
./gradlew :reservation-service:test --tests 'com.iseonjwa.reservation.lottery.scenario.LotteryScenarioTest.S3_*' --tests 'com.iseonjwa.reservation.lottery.scenario.LotteryScenarioTest.S13_*' --tests 'com.iseonjwa.reservation.reservation.application.PaymentResultLotteryTest.NOT_SENT_*' --no-daemon
# 3개 통과(23초)
```

서버 확인(저장소로 확인하지 못한 것):
- `docker kill` 뒤 결제 서버가 자동으로 다시 뜨는지
- 결제 서버 기동 시간
- 시드 상태 디렉터리(`~/.iseonjwa/lottery-seed/127.0.0.1_80`)에 앞선 `prepare` 기록이 있는지
- 시작 전 `WON` 건수와 활성 경보
- F1-b 선점 만료 앞당기기에 계정 담당이 동의하는지(0절 5번)
