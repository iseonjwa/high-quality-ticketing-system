# 오픈 피크 k6 시나리오 (ISE-181)

오픈 피크 흐름의 구간별 부하 시나리오와 그것들이 같이 쓰는 공통 코드다. 시나리오 명세는 [open-peak-scenarios.md](../../docs/design/infra/open-peak-scenarios.md), 구간별 병목과 개선 계획은 [open-peak-load-plan.md](../../docs/design/infra/open-peak-load-plan.md)에 있다. k6는 설치하지 않고 Docker 이미지(`grafana/k6:1.0.0`)로 돌린다.

좌석 선점 K1·K1-H는 [`reservation-service/k6/`](../../reservation-service/k6/README.md)에 따로 있다. 공통 코드가 자리를 잡으면 이 폴더의 `lib`을 쓰도록 옮길지 정한다.

## 1. 구성

| 경로 | 내용 |
|---|---|
| `run.sh` | Docker로 시나리오 하나를 돌리고 결과를 `results/`에 남긴다. 저장소 루트 `.env`에서 서명 키와 관리자 계정을 읽어 환경변수로만 넘긴다. `PROM_RW_URL`을 주면 결과를 Prometheus remote write로도 보낸다 |
| `lib/env.js` | 공통 환경변수(`BASE_URL`, `TOKEN_MODE`, `USER_ID_BASE`)와 숫자 변수 읽기 |
| `lib/tokens.js` | 측정용 Access 토큰·입장 토큰 서명(`TOKEN_MODE=sign`. 배포는 측정 전용 키), UUID |
| `lib/http.js` | URL, 인증 헤더, 응답 `data`·오류 `code` 읽기, 결과 분류 |
| `lib/admin.js` | 관리자 로그인, 공연·회차·좌석(15,000석) 만들기, 오픈 시각까지 기다리기 |
| `lib/queue.js` | 대기자 미리 진입시키기(`http.batch`), 입장 스케줄러가 꺼졌는지 확인, VU 하나가 대기자 여러 명을 맡는 폴링 도우미(Q2·Q3) |
| `lib/booking.js` | 좌석 목록(조회 한 번), 선점, 결제, 예매 상태 조회 |
| `lib/mockpg.js` | Mock PG 장애 주입(`/mock-pg/faults`). 승인 지연 걸기·풀기 |
| `lib/lottery.js` | 추첨 설정, 응모 요청, 응모·빌링키 묶음 준비 |
| `compose.queue-hold.yml` | Q2·G1용 덮어쓰기. reservation-service의 입장 스케줄러를 끈다. 배포 서버에서는 `measure-mode.sh on`에 인자로 준다 |
| [`../deploy/compose.measure.yml`](../deploy/compose.measure.yml) | 배포 서버 측정 모드 덮어쓰기. 추적 샘플링 0.01, Prometheus remote write 수신과 9090 |
| [`../deploy/compose.seat-before.yml`](../deploy/compose.seat-before.yml) | S1 개선 전 측정용 덮어쓰기. reservation-service 이미지만 P4a 이전 릴리스(4544d1a)로 바꾼다. `measure-mode.sh on`에 인자로 준다 |
| `compose.mockpg-loadtest.yml` | PAY1·R1·L2용 덮어쓰기. local mock-pg를 `local,loadtest` 프로필로 띄워 장애 주입 API를 연다 |
| `compose.expiry-on.yml` | X1용 덮어쓰기. 배포 메모리 덮어쓰기가 끈 선점 만료 스케줄러를 다시 켠다 |
| `tools/watch-admitted.sh` | Q3 보조. Redis의 입장 수·순번·대기 수를 1초마다 CSV로 남긴다 |
| `tools/watch-held.sh` | X1 보조. 회차의 HELD·EXPIRED·만료 시각이 지난 HELD 수를 주기마다 CSV로 남긴다 |
| `tools/watch-lottery.sh` | L2·L3 보조. 추첨 응모 상태별 수와 배정된 예매 수를 주기마다 CSV로 남긴다 |
| `tools/gw-collect.sh` | G1 배포 서버 보조. 서버 `vmstat`·`mpstat`·Gateway `pidstat -t`(Java 스레드별)·`docker stats`·load average를 모으고, Prometheus 17종·Zipkin 요약과 함께 묶는다 |
| `tools/g1-run.sh` | G1 배포 서버 회차 실행(발생기). 웜업 3단계 → 차단기 닫힘 대기 → 본 측정(단계 사이 쉼) → 결과 묶기 |
| `tools/r0_solve.py` | G1 결과 계산(로컬). 단계별 Gateway CPU를 받은 요청·거절 수로 연립해 한 건 비용(받은 요청 a, 거절 r)을 푼다 |
| `scenarios/q1-queue-enter.js` | Q1 대기열 진입 몰림 |
| `scenarios/q1d-queue-burst.js` | Q1-d 오픈 순간 몰림. 사용자 수만큼 VU를 미리 두고, 같은 출발 시각부터 `ENTER_SPREAD_SECONDS` 안에 앞쪽이 붐비게 한 번씩 진입한다 |
| `scenarios/q2-queue-poll.js` | Q2 순번 폴링. VU 하나가 대기자 여러 명의 다음 폴링 시각을 들고 서버가 준 간격대로 부른다 |
| `scenarios/g1-gateway-auth.js` | G1 Gateway 인증 경로. 순번 조회에 초당 요청을 단계적으로 올린다 |
| `scenarios/q3-admission.js` | Q3 입장 속도 유지. 입장 스케줄러를 켠 채 READY가 되면 입장 토큰을 받고 끝낸다 |
| `scenarios/b1-browse.js` | B1 오픈 전 공연 상세·목록 조회. `ENTER_RATE`를 주면 대기열 진입을 같이 건다(B1-b) |
| `scenarios/pay1-payment.js` | PAY1 결제 승인. 반복마다 1석 선점 → 결제, 요청 속도를 단계적으로 올린다 |
| `scenarios/r1-confirm.js` | R1 결제 결과 반영. 선점 → 결제 → 예매 조회로 CONFIRMED까지의 시간을 잰다 |
| `scenarios/x1-expiry.js` | X1 선점 만료. 선점 시간 1분 회차의 선점이 만료되는 동안 다른 회차에 선점 부하를 건다 |
| `scenarios/l1-lottery-entry.js` | L1 응모 마감 직전 몰림. 일부 사용자는 같은 멱등키로 두 번 보낸다. 실패 응답은 `entry_results`에 message 출처 태그 `msg`(`auth_unavailable`·`service_unavailable`·`gateway_error`·`other`·`none`)를 붙여, 인증 벌크헤드 503을 다른 503과 나눠 summary에 센다 |
| `scenarios/l2-lottery-billing.js` | L2 자동결제 폭주. setup에서 빌링키·응모를 끝내고 추첨 시각부터 잰다. `PAY_RATE`를 주면 다른 회차 일반 결제를 같이 건다 |
| `scenarios/l3-lottery-interference.js` | L3 추첨과 일반 판매 간섭. 선점 지연을 추첨 전·중·후(`phase`)로 나눠 기록한다 |
| `scenarios/o1-open-peak.js` | O1 오픈 피크 한 흐름. VU 하나가 사용자 여러 명을 상태 기계로 들고 조회 → 진입 → 폴링 → 토큰 → 좌석 → 선점 → 결제 → 확정을 진행한다 |
| `scenarios/s1-seat-query.js` | S1 회차 좌석 조회 단독. 사용자 한 명이 초당 `RATE`건을 `DURATION_SECONDS`(기본 30초) 동안 보낸다. 좌석 조회 경량화(ISE-189) 단계별 비교용 |
| `scenarios/b1d-concert-detail.js` | B1-d 공연 상세 조회 단독. 초당 `RATE`건을 `DURATION_SECONDS`(기본 30초) 동안 이미 있는 공연(`CONCERT_ID`)으로 보낸다. 관리자 계정과 토큰이 필요 없다 |
| `scenarios/r1d-confirm.js` | R1-d. R1을 배포 서버에서 돌리는 판. 관리자 로그인 없이 서버에서 만든 회차(`SCHEDULE_ID`)를 받는다. 좌석을 팔므로 실행마다 새 회차를 쓴다 |
| `results/` | 요약 JSON, 요청별 원자료 CSV(gzip), 화면 출력. 커밋하지 않는다 |

시나리오 상태:

| ID | 스크립트 | 상태 |
|---|---|---|
| Q1 | `scenarios/q1-queue-enter.js` | 있음. 2026-11-05 local에서 300명으로 동작 확인(오류 0, 순번 300 = 고유 사용자 300) |
| Q1-d | `scenarios/q1d-queue-burst.js` | 있음(ISE-217). 2026-11-09 local 100명·1초: 201 100건, 오류 0, `send_lag` p95 1.7ms, 순번 100 = 고유 사용자 100. 300명·0.1초와 300명·1초는 Gateway 인증 Redis 차단기(`auth-redis`)가 열려 503이 68~93%였다. 노트북 한계로 보고 Q1과의 비교는 배포 서버에서 한다 |
| Q2 | `scenarios/q2-queue-poll.js` | 있음. 2026-11-05 local에서 대기자 1,000명·60초로 동작 확인(폴링 8,638건 모두 200, p95 10ms) |
| G1 | `scenarios/g1-gateway-auth.js` | 있음. 2026-11-05 local에서 초당 50 → 100건·각 10초로 동작 확인(오류 0, p95 11ms) |
| Q3 | `scenarios/q3-admission.js` | 있음. 2026-11-05 local 대기자 1,000명·60초: 1,000명 모두 토큰 발급, 오류 0, 토큰 발급 p95 15ms. 입장 수는 초당 50씩 늘었다 |
| B1 | `scenarios/b1-browse.js` | 있음. 2026-11-05 local 초당 20 → 40건·각 10초, 진입 초당 10건 동시: 오류 0, 조회 p95 24ms |
| PAY1 | `scenarios/pay1-payment.js` | 있음. 2026-11-05 local 초당 5 → 10건·각 10초, PG 300ms: 승인 208, `PG_UNAVAILABLE` 4, 결제 p95 560ms |
| R1 | `scenarios/r1-confirm.js` | 있음. 2026-11-05 local 초당 5 → 10건·각 10초: 212건 모두 확정, 승인 → 확정 p95 624ms(0.5초 간격 조회) |
| X1 | `scenarios/x1-expiry.js` | 있음. 2026-11-05 local 만료 대상 500건 + 선점 초당 10명·60초: 500건이 만료 시각부터 약 33초 안에 모두 EXPIRED, 그동안 선점 p95 34ms, 오류 0. 묵은 선점이 남아 있으면 결과가 틀어진다(4절 "만료 대기 건") |
| L1 | `scenarios/l1-lottery-entry.js` | 있음. 2026-11-05 local 초당 10 → 20건·각 10초: 요청 449건(연타 포함) 오류 0, 응모 행 424 = 고유 사용자 424, p95 19ms |
| L2 | `scenarios/l2-lottery-billing.js` | 있음. 2026-11-05 local 응모 300명, 추첨석 60석, PG 300ms: 추첨 뒤 약 10초에 당첨 39명, 그 뒤 약 20초 안에 39명 모두 배정·결제(PAID) |
| L3 | `scenarios/l3-lottery-interference.js` | 있음. 2026-11-05 local 선점 초당 10명, 응모 300명: 선점 p95 추첨 전 28ms, 중 30ms, 후 23ms, 오류 0 |
| O1 | `scenarios/o1-open-peak.js` | 있음. 2026-11-05 local 사용자 200명(오픈 전 40초, 오픈 뒤 120초): 확정 180, 결제 전 이탈 18(설정 10%), 오류 2. 좌석 조회 p95 4.15초로 가장 느렸다(K1과 같은 병목) |
| S1 | `scenarios/s1-seat-query.js` | 있음(ISE-189). 로컬(노트북 1대)에서 같은 방식으로 잰 단계별 결과는 ISE-189에 있다. 2026-11-11 배포: 1차 초당 40건까지 실패 0(p95 약 370ms), 상한 약 47건/초. 개선 전은 초당 10건에서 서비스 재시작. 결과는 [open-peak-scenarios.md](../../docs/design/infra/open-peak-scenarios.md) 3절 |
| B1-d | `scenarios/b1d-concert-detail.js` | 있음. 2026-11-06 local(같은 방식, 공연 상세 하나)에서 초당 10~400건으로 잰 결과는 개인 기록에 있다. 2026-11-11 배포: 초당 400건까지 실패 0, p95 4ms 안팎(같은 문서) |
| R1-d | `scenarios/r1d-confirm.js` | 있음(ISE-189). R1과 같은 흐름·판정 기준. 배포 서버 측정 전 |

## 2. 실행

Docker Desktop과 Gateway(`localhost:8080`)가 떠 있어야 한다. 저장소 루트에서 Git Bash로 실행한다. 배포 메모리로 재려면 [`reservation-service/k6/README.md`](../../reservation-service/k6/README.md) 3절의 덮어쓰기 파일과 함께 스택을 띄운다.

```bash
# Q1: 30,000명이 10초 안에 진입(기본값). 회차를 만들고 40초 뒤 오픈에 맞춰 시작한다
bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
# 짧게 확인: 300명, 10%가 연타
USERS=300 DUP_RATIO=0.1 USER_ID_BASE=5000000 bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
# Q1-d: 5,000명이 오픈 1초 뒤부터 1초 안에 앞쪽이 붐비게 진입(기본값)
bash loadtest/k6/run.sh scenarios/q1d-queue-burst.js
# 짧게 확인: 100명, 1초
USERS=100 ENTER_SPREAD_SECONDS=1 USER_ID_BASE=6000000 bash loadtest/k6/run.sh scenarios/q1d-queue-burst.js
```

- `run.sh`는 k6 진행 표시줄을 기본으로 끈다(`--quiet`). 터미널이 아닌 곳에서는 진행 표시줄이 1초마다 새 줄로 찍혀 출력의 9할을 넘기기 때문이다(ISE-219). 화면에는 `setup()` 로그와 마지막 요약만 나온다. 진행 상황을 보려면 앞에 `K6_PROGRESS=1`을 붙인다.
- 에이전트에게 결과를 읽힐 때는 stdout 전체가 아니라 `results/<이름>-summary.json`이나 stdout 끝부분(`tail -n 60`)만 읽게 한다.

Q2·G1은 입장 스케줄러를 끈 상태에서 잰다. 끄지 않으면 Q2의 setup이 측정을 시작하지 않는다.

```bash
# reservation-service만 다시 띄운다(배포 메모리 덮어쓰기를 쓰고 있으면 그 파일도 같이 준다)
docker compose -f compose.yml -f compose.local.yml -f loadtest/k6/compose.queue-hold.yml up -d --no-deps --wait reservation-service
# Q2: 대기자 30,000명, 10분(기본값). VU 300개가 100명씩 맡는다
bash loadtest/k6/run.sh scenarios/q2-queue-poll.js
# G1: 대기자 10,000명, 초당 1,000 → 2,000 → 4,000 → 8,000건(기본값)
bash loadtest/k6/run.sh scenarios/g1-gateway-auth.js
# 짧게 확인
USERS=1000 VUS=20 DURATION_SECONDS=60 bash loadtest/k6/run.sh scenarios/q2-queue-poll.js
USERS=500 STAGES=50,100 STAGE_SECONDS=10 bash loadtest/k6/run.sh scenarios/g1-gateway-auth.js
```

측정이 끝나면 덮어쓰기 파일 없이 reservation-service를 다시 띄워 입장 스케줄러를 켠다.

Q3은 입장 스케줄러를 켠 상태에서 잰다. 측정 중 다른 창에서 입장 수를 1초마다 기록한다.

```bash
bash loadtest/k6/run.sh scenarios/q3-admission.js
# setup 출력의 회차 ID로
bash loadtest/k6/tools/watch-admitted.sh <회차 ID> 700
```

B1은 공개 API라 덮어쓰기가 필요 없다. PAY1·R1은 mock-pg를 `loadtest` 프로필로 띄운다. mock-pg를 다시 띄운 직후에는 결제 서비스의 PG 연결이 끊겨 차단기가 열릴 수 있으니 30초 넘게 기다린 뒤 잰다.

```bash
bash loadtest/k6/run.sh scenarios/b1-browse.js
ENTER_RATE=1000 bash loadtest/k6/run.sh scenarios/b1-browse.js   # B1-b
docker compose -f compose.yml -f compose.local.yml -f loadtest/k6/compose.mockpg-loadtest.yml up -d --no-deps --wait mock-pg
bash loadtest/k6/run.sh scenarios/pay1-payment.js
PG_DELAY_MS=1900 STAGES=40 bash loadtest/k6/run.sh scenarios/pay1-payment.js   # PAY1-b
bash loadtest/k6/run.sh scenarios/r1-confirm.js
```

추첨(L1~L3)은 덮어쓰기 없이 돈다. L2·L3의 자동결제는 PAY1처럼 mock-pg를 `loadtest` 프로필로 띄워 둔다. 빌링키는 사용자마다 하나라 실행마다 `USER_ID_BASE`를 바꾼다.

```bash
bash loadtest/k6/run.sh scenarios/l1-lottery-entry.js
bash loadtest/k6/run.sh scenarios/l2-lottery-billing.js          # L2-a
PAY_RATE=20 bash loadtest/k6/run.sh scenarios/l2-lottery-billing.js   # L2-b
bash loadtest/k6/tools/watch-lottery.sh <추첨 ID> 720 10
bash loadtest/k6/run.sh scenarios/l3-lottery-interference.js
```

O1은 기본값(3만 명, 오픈 전 10분, 오픈 뒤 15분)이 25분 넘게 돈다. 측정 뒤 teardown이 출력하는 대사 SQL로 예매 상태와 SOLD 좌석 수를 맞춘다.

```bash
bash loadtest/k6/run.sh scenarios/o1-open-peak.js
USERS=200 VUS=20 PRE_OPEN_SECONDS=40 AFTER_OPEN_SECONDS=120 bash loadtest/k6/run.sh scenarios/o1-open-peak.js   # 짧게 확인
```

X1은 만료 스케줄러를 켜고, **만료 시각이 지났는데 HELD로 남은 예매가 없는 DB**에서 잰다(4절).

```bash
docker compose -f compose.yml -f compose.local.yml -f reservation-service/k6/compose.deploy-memory.yml   -f reservation-service/k6/compose.g1.yml -f loadtest/k6/compose.expiry-on.yml up -d --no-deps --wait reservation-service
bash loadtest/k6/run.sh scenarios/x1-expiry.js
bash loadtest/k6/tools/watch-held.sh <만료 회차 ID> 420 10
```

- `USER_ID_BASE`를 실행마다 바꾸면 같은 회차에서도 앞 실행의 사용자와 겹치지 않는다. 기본은 매번 새 회차를 만든다.
- Q1이 끝나면 teardown이 확인할 Redis 키를 출력한다. 순번이 정확한지 local에서는 이렇게 본다.

```bash
docker exec iseonjwa-redis-1 redis-cli GET "queue:{8}:seq"
docker exec iseonjwa-redis-1 redis-cli ZCARD "queue:{8}:waiting"
```

### 환경변수

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | Gateway. 발생기 EC2에서는 Gateway 사설 주소 |
| `TOKEN_MODE` | `sign` | k6가 토큰을 직접 서명한다. 배포는 측정 모드의 측정 전용 키를 발생기 `.env`에 두고 쓴다(아래 "배포 서버 측정") |
| `USER_ID_BASE` | 1000000 | 가상 사용자 ID 시작값 |
| `SCHEDULE_ID` | 없음 | 측정 대상 회차. Q1·Q2·Q3·G1: 주면 관리자 로그인과 회차 만들기를 건너뛰고 그 회차로 잰다(열려 있어야 한다). L1~L3: 선택이며, 주면 `LOTTERY_ID` 추첨의 회차와 같은지 확인한다(오타 방지). 배포 측정에서 관리자 비밀번호를 발생기에 두지 않으려는 것이다 |
| `LOTTERY_ID` | 없음 | L1~L3: 서버에서 만든 추첨(상태 `OPEN`). 주면 관리자 로그인과 회차·추첨 만들기를 건너뛰고, 로그인 없는 추첨 조회로 회차와 응모·추첨 시각을 읽는다. 이때 `START_IN_SECONDS`·`SEAT_COUNT`·`WAITLIST_COUNT`·`DRAW_AFTER_SECONDS`는 쓰지 않는다(서버에서 만들 때 정해진다). 회차 ID만 주고 `LOTTERY_ID`를 빼면 실패한다 |
| `SALE_SCHEDULE_ID` | 없음 | L2-b(`PAY_RATE > 0`)·L3: 추첨 회차와 다른, 판매 중인 일반 판매 회차. `LOTTERY_ID`를 줄 때 반드시 같이 준다. L1·L2-a에 주면 실패한다 |
| `USERS` / `ENTER_SECONDS` | 30000 / 10 | Q1: 진입할 사용자 수와 그 시간(초). 초당 속도는 둘을 나눈 값 |
| `DUP_RATIO` / `REPEAT` | 0 / 3 | Q1-c: 연타하는 사용자 비율과 횟수 |
| `USERS` / `ENTER_SPREAD_SECONDS` (Q1-d) | 5000 / 1 | Q1-d: 사용자 수(= VU 수)와, 출발 시각부터 마지막 사용자가 출발할 때까지의 시간(초). 앞쪽이 가장 붐비는 삼각 분포로 흩는다 |
| `OPEN_IN_SECONDS` | 40 | 회차를 만든 뒤 오픈까지. 회차 캐시 갱신(30초)보다 길어야 진입이 캐시를 탄다 |
| `PRE_VUS` / `MAX_VUS` | Q1: 초당 속도(최대 2000) / 5000, G1: 500 / 3000 | 열린 모델의 VU 풀. `dropped_iterations`가 생기면 늘린다 |
| `DURATION_SECONDS` / `VUS` | 600 / 300 | Q2: 측정 시간과 VU 수. `USERS`가 `VUS`로 나누어떨어져야 한다 |
| `FIRST_POLL_SPREAD_SECONDS` | 10 | Q2: 첫 폴링을 흩는 시간. 실제 진입이 10초에 걸쳐 오는 것을 흉내 낸다 |
| `STAGES` / `STAGE_SECONDS` | G1 `1000,2000,4000,8000` / 60, B1 `500,1000,2000,4000` / 60, PAY1 `20,40,80,160` / 45, R1 `25,50,100,200` / 30 | 단계별 초당 요청 수(쉼표로)와 단계 유지 시간. 단계 사이 5초 동안 올린다. PAY1·R1은 반복마다 1석을 쓰므로 합계가 15,000석을 넘지 않게 둔다 |
| `CONCERTS` / `SCHEDULES_PER_CONCERT` / `DETAIL_RATIO` | 20 / 3 / 0.8 | B1: 미리 만들 공연·회차 수, 상세 조회 비율(나머지는 목록) |
| `ENTER_RATE` | 0 | B1-b: 같이 걸 초당 대기열 진입 수 |
| `PG_DELAY_MS` | O1·PAY1·L2 300, R1 0 | Mock PG 승인 지연(ms). 기준 지연은 300ms다(open-peak-load-plan.md 8절 4번, 2026-11-08). R1은 승인 → 확정 지연만 보려고 0이다. 끝나면 teardown이 0으로 되돌린다 |
| `MOCK_PG_URL` | `http://host.docker.internal:8090` | Mock PG 주소. 비우면 장애 주입을 건너뛴다(배포 환경은 비우고 서버 안에서 따로 건다) |
| `PROM_RW_URL` | 없음 | 있으면 결과를 Prometheus remote write로 보낸다. 쓰기 주소 전체(`http://172.31.4.93:9090/api/v1/write`). 지표 `k6_*`에 `testid`(결과 파일 이름) 태그가 붙는다. 추세 통계는 `K6_PROMETHEUS_RW_TREND_STATS`로 바꾼다(기본 `p(95),p(99),avg,max`) |
| `CONFIRM_POLL_SECONDS` / `CONFIRM_MAX_POLLS` | 0.5 / 10 | R1: 확정 확인 간격과 횟수 |
| `EXPIRING` / `RATE` | 5000 / 100 | X1: 만료시킬 선점 수, 측정 중 초당 선점 사용자 수(L3도 `RATE`를 쓴다) |
| `START_IN_SECONDS` | L1: 15, Q1-d: 5 | L1: 추첨을 만든 뒤 응모 시작까지. Q1-d: `SCHEDULE_ID`를 줄 때 setup이 끝나고 출발까지 |
| `ENTRANTS` / `SEAT_COUNT` / `WAITLIST_COUNT` | 30000 / 3000 / 1000 | L2·L3: 응모자 수, 추첨석, 예비자 수 |
| `PAY_RATE` | 0 | L2-b: 같이 걸 초당 일반 결제 수 |
| `PG_MODE` / `DECLINE_RATE` | `delay` / 0.05 | L2: `delay`는 승인 지연 `PG_DELAY_MS`, `decline`은 승인의 일부를 거절. Mock PG는 호출 종류마다 규칙이 하나라 둘을 같이 걸 수 없다 |
| `DRAW_AFTER_SECONDS` / `DRAW_WINDOW_SECONDS` / `WITH_BILLING` | 60 / 120 / 1 | L3: 측정 시작 뒤 추첨까지, 추첨 뒤 "중"으로 볼 시간, 빌링키 등록 여부(자동결제까지 겹칠지) |
| `PRE_OPEN_SECONDS` / `AFTER_OPEN_SECONDS` | 600 / 900 | O1: 오픈 전 조회 시간, 오픈 뒤 진행 시간 |
| `ENTER_SPREAD_SECONDS` / `ABANDON_RATIO` / `MAX_RETRY` | 10 / 0.1 / 2 | O1: 진입을 흩는 시간, 선점 뒤 결제하지 않고 떠나는 비율, 409 뒤 재시도 |

새 시나리오가 변수를 더하면 `run.sh`의 `PASS_ENV` 목록에도 더한다. Docker가 목록에 있는 변수만 컨테이너로 넘긴다.

### 배포 서버 측정 (ISE-191)

배포 서버를 재는 날의 순서는 [`deploy-server-access.md`](../../docs/guides/deploy-server-access.md) 5-5절, 설계는 [`load-test.md`](../../docs/design/infra/load-test.md) 5절이다. 여기에는 k6 쪽만 적는다. 발생기에서 저장소 루트로 `cd`한 뒤 쓴다.

팀 공용 발생기를 쓰는 규칙(한 번에 한 명, 팀 채널 알림, 각자 폴더 `~/<영문 이름>/iseonjwa`, 시작·끝 체크리스트)은 [`loadgen.md`](../../docs/guides/loadgen.md)에 있다. 주소는 사설 IP `172.31.4.93`만 쓴다. 공인 IP로 보내면 전송 요금이 붙고 보안 그룹에도 막힌다.

**토큰**: 서버에서 `script/loadtest/measure-mode.sh on`이 만든 `~/iseonjwa/.env.measure`(`JWT_ACCESS_SECRET`, `ENTRY_TOKEN_SECRET`)를 발생기 저장소 루트의 `.env`로 옮기면 `run.sh`가 읽는다. 운영 키는 발생기에 두지 않는다. 측정이 끝나면 서버에서 `measure-mode.sh off`로 키를 폐기하고 발생기 `.env`를 지운다.

**관리자 계정이 필요한 시나리오**: 회차를 만드는 `lib/admin.js`의 `adminLogin()`이 `ADMIN_EMAIL`·`ADMIN_PASSWORD`를 쓴다. 운영 관리자 비밀번호는 발생기에 두지 않으므로 Q1~Q3은 `SCHEDULE_ID`를, L1~L3은 `LOTTERY_ID`(L2-b·L3은 `SALE_SCHEDULE_ID`도)를 줘서 이 단계를 건너뛴다. 추첨은 서버에서 만든다(ISE-216). G1도 `SCHEDULE_ID`를 주면 건너뛴다. 그 밖의 시나리오(B1, PAY1, R1, X1, O1)는 아직 공연·회차를 직접 만들어 관리자 계정이 필요하다. 어느 시나리오를 어떻게 돌릴지는 그 측정 전에 정한다.

**L1~L3 추첨 준비**(서버에서 만들 때 맞출 시각):

| 측정 | 서버에서 준비 | 응모 기간 | k6 |
|---|---|---|---|
| L1 응모 몰림 | 추첨 1개 | 측정 시간(기본 단계 4개 × 65초 = 약 260초)보다 길게. 짧으면 setup이 실패한다 | `LOTTERY_ID=<id> ... l1-lottery-entry.js` |
| L2-a 자동결제 | 추첨 1개(좌석·예비 수 포함) | 빌링키 등록과 응모(`ENTRANTS`명, 초당 약 200건 가정)를 마감 전에 끝낼 만큼 | `LOTTERY_ID=<id> ... l2-lottery-billing.js` |
| L2-b 간섭 | L2-a와 같은 추첨 + 판매 중인 다른 회차 | L2-a와 같음 | `LOTTERY_ID=<id> SALE_SCHEDULE_ID=<id> PAY_RATE=<n> ... l2-lottery-billing.js` |
| L3 추첨·선점 간섭 | 추첨 1개 + 판매 중인 다른 회차 | L2-a와 같음. 추첨 시각은 응모 마감 뒤 측정 중에 오게 | `LOTTERY_ID=<id> SALE_SCHEDULE_ID=<id> ... l3-lottery-interference.js` |

추첨은 배포 서버에서 `seed-demo.sh lottery`로 만든다. 마지막 두 줄에 나오는 `LOTTERY_ID`를 k6에 넘긴다(`SCHEDULE_ID`는 확인용으로 함께 줘도 된다). 회차 일반 오픈이 실행 시각 + 1시간이라 응모 시작은 그 안이어야 하므로 측정 직전에 만든다. 추첨석·예비 수·1인 매수는 `LOTTERY_SEAT_COUNT`(3000)·`LOTTERY_WAITLIST_COUNT`(1000)·`LOTTERY_MAX_PER_ENTRY`(2)로 바꾼다. L2-b·L3의 판매 중인 회차(`SALE_SCHEDULE_ID`)는 `seed-demo.sh open`으로 만든 회차를 쓴다.

```bash
# 배포 서버에서: 2분 뒤 응모 시작, 7분 동안 응모, 마감 1분 뒤 추첨
ssh iseonjwa 'cd ~/iseonjwa && script/deploy/seed-demo.sh lottery "$(date -u -d "+2 minutes" +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d "+9 minutes" +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d "+10 minutes" +%Y-%m-%dT%H:%M:%SZ)"'
```

**Q1~Q3 회차 준비**: 배포 서버에서 관리자 도구로 회차를 하나 만든다. 오픈 시각이 이미 지난 회차를 만들면 바로 측정할 수 있다. 회차 캐시 갱신(30초)이 있으므로 측정 1분 전에는 만든다. 시나리오마다 새 회차를 쓴다(앞 시나리오의 대기자가 남아 있으면 순번이 섞인다).

```bash
# 배포 서버에서: 5분 전에 열린 회차를 만든다. 출력의 "새 회차 id"가 SCHEDULE_ID다
ssh iseonjwa 'cd ~/iseonjwa && script/deploy/seed-demo.sh open "$(date -u -d "-5 minutes" +%Y-%m-%dT%H:%M:%SZ)"'
```

**실행**(발생기에서). `BASE_URL`은 Gateway 사설 주소, `MOCK_PG_URL`은 비워 발생기에서 Mock PG를 부르지 않게 한다.

```bash
export BASE_URL=http://172.31.4.93:8080 MOCK_PG_URL= PROM_RW_URL=http://172.31.4.93:9090/api/v1/write
SCHEDULE_ID=<회차 id> bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
```

| 측정 | 서버에서 켜는 것 | 회차 | k6 |
|---|---|---|---|
| Q1 대기열 진입 | `measure-mode.sh on` | Q1용 새 회차 | `SCHEDULE_ID=<id> ... q1-queue-enter.js` |
| Q2 순번 폴링 | `measure-mode.sh on loadtest/k6/compose.queue-hold.yml` (입장 스케줄러 끔) | Q2용 새 회차 | `SCHEDULE_ID=<id> ... q2-queue-poll.js` |
| Q3 입장 속도 | `measure-mode.sh on` (덮어쓰기 파일 없이 다시. 스케줄러 켬) | Q3용 새 회차 | `SCHEDULE_ID=<id> ... q3-admission.js`. 입장 기록은 아래 도구 |
| Q1 Gateway auth-redis 비교(ISE-229) | `AUTH_BULKHEAD=<값> MEASURER=<이름> measure-mode.sh on loadtest/deploy/compose.gateway-bulkhead.yml` (bulkhead 끄기 `AUTH_BULKHEAD_ENABLED=false`, 느린 호출 기준 `AUTH_SLOW_CALL=100ms`처럼 준다. 한 번에 하나만 바꾼다). bulkhead는 켠 뒤 Grafana `resilience4j_bulkhead_max_allowed_concurrent_calls{name="auth-redis"}`로 적용을 먼저 확인하고, 느린 호출 기준은 재기동 직후 50ms를 넘는 호출이 있어도 차단기가 열리지 않는지로 확인한다 | Q1용 새 회차 | 워밍업 뒤 `SCHEDULE_ID=<id> ... q1-queue-enter.js`. 값을 바꿀 때마다 gateway가 다시 떠 식으므로 다시 워밍업한다 |
| S1 좌석 조회(현재 배포) | `measure-mode.sh on` | 15,000석 회차(`seed-demo.sh open`) | 아래 S1 명령. 초당 50·150·200·300을 차례로 |
| S1 좌석 조회(개선 전) | `measure-mode.sh on loadtest/deploy/compose.seat-before.yml` (reservation-service만 4544d1a로 다시 뜬다) | 위와 같은 회차 | 아래 S1 명령 그대로. 끝나면 파일 없이 `on`이나 `off`로 지금 배포 이미지로 돌아온다 |
| B1-d 공연 상세 | `measure-mode.sh on` | 시연 공연(`seed-demo.sh`가 출력한 공연 id) | 아래 B1-d 명령 |
| R1-d 결제 → 확정 | `measure-mode.sh on` (앱이 다시 떠 Mock PG 지연이 없는 상태) | 실행마다 새 15,000석 회차(`seed-demo.sh open`, 오픈 시각이 지난 뒤 잰다) | 아래 R1-d 명령. 빈 좌석이 모자라면(이미 쓴 회차) setup에서 멈춘다 |

S1·B1-d·R1-d는 주소를 명령에 직접 넣어 쓴다. 사설 IP `172.31.4.93`만 쓴다(공인 IP는 전송 요금이 붙고 보안 그룹에 막힌다, `docs/guides/loadgen.md` 2절).

```bash
# S1 좌석 조회 (발생기에서)
BASE_URL=http://172.31.4.93:8080 PROM_RW_URL=http://172.31.4.93:9090/api/v1/write SCHEDULE_ID=<회차 id> RATE=50 bash loadtest/k6/run.sh scenarios/s1-seat-query.js
# B1-d 공연 상세 (발생기에서)
BASE_URL=http://172.31.4.93:8080 PROM_RW_URL=http://172.31.4.93:9090/api/v1/write CONCERT_ID=<공연 id> RATE=100 bash loadtest/k6/run.sh scenarios/b1d-concert-detail.js
# R1-d 결제 → 확정 (발생기에서. 회차는 실행마다 새로)
BASE_URL=http://172.31.4.93:8080 PROM_RW_URL=http://172.31.4.93:9090/api/v1/write SCHEDULE_ID=<회차 id> bash loadtest/k6/run.sh scenarios/r1d-confirm.js
```

`measure-mode.sh on`은 인자로 준 파일이 전체 목록이다. Q2에서 Q3로 넘어갈 때 `queue-hold.yml` 없이 다시 실행하면 reservation-service가 다시 만들어지며 스케줄러가 켜진다. `.env.measure`의 키는 그대로라 발생기 `.env`를 다시 옮기지 않아도 된다. reservation-service가 다시 뜬 뒤 30초 넘게 기다렸다가 잰다.

**Mock PG 장애 주입(O1, PAY1, L2 등 결제 시나리오)**: Mock PG 포트는 서버 밖에 열려 있지 않다. 서버 안에서 compose 네트워크(`iseonjwa_default`, 프로젝트 이름이 `iseonjwa`)에 일회용 curl 컨테이너를 붙여 건다. 앱이 다시 뜨면 설정이 사라지므로 `measure-mode.sh on` 뒤, 측정 직전에 건다. 기준 지연은 300ms다.

```bash
# 승인 지연 300ms를 건다 (서버에서)
docker run --rm --network iseonjwa_default curlimages/curl:8.10.1 -s -X POST -H 'Content-Type: application/json' \
  -d '{"target":"APPROVE","mode":"DELAY","delayMs":300}' http://mock-pg:8090/mock-pg/faults
# 현재 설정 확인
docker run --rm --network iseonjwa_default curlimages/curl:8.10.1 -s http://mock-pg:8090/mock-pg/faults
# 풀기: mode를 NONE으로
docker run --rm --network iseonjwa_default curlimages/curl:8.10.1 -s -X POST -H 'Content-Type: application/json' \
  -d '{"target":"APPROVE","mode":"NONE"}' http://mock-pg:8090/mock-pg/faults
```

시나리오가 `MOCK_PG_URL`을 비워 장애 주입을 건너뛰므로(콘솔에 경고가 나온다) 위 명령으로 건 값이 그대로 쓰인다. 배포 mock-pg는 이미 `loadtest` 프로필이라 `compose.mockpg-loadtest.yml`은 필요 없다.

**기록 도구(`tools/`)**: 세 스크립트는 컨테이너 이름(`iseonjwa-redis-1`, `iseonjwa-postgres-reservation-1`)이 배포 서버와 같아 고치지 않고 서버에서 돈다. 서버에서 k6를 돌리는 것이 아니라 기록만 한다. 측정과 같은 시간에 SSH 창을 따로 열어 실행하고, 결과 CSV는 `loadtest/k6/results/`(서버의 `~/iseonjwa`)에 쌓이니 `scp`로 내려받는다.

```bash
ssh iseonjwa 'cd ~/iseonjwa && bash loadtest/k6/tools/watch-admitted.sh <회차 ID> 700'   # Q3
ssh iseonjwa 'cd ~/iseonjwa && bash loadtest/k6/tools/watch-held.sh <회차 ID> 420 10'    # X1
ssh iseonjwa 'cd ~/iseonjwa && bash loadtest/k6/tools/watch-lottery.sh <추첨 ID> 720 10' # L2·L3
```

`watch-held.sh`·`watch-lottery.sh`는 DB 사용자 `iseonjwa`를 코드에 쓰고, 배포 `.env`의 `DB_USER`도 `iseonjwa`라 그대로 돈다. 시각은 서버의 `date +%s%3N`(GNU date)이다.

**G1 Gateway 측정 도구**(배포 서버 6·7차 절차를 옮긴 것, [`open-peak-scenarios.md`](../../docs/design/infra/open-peak-scenarios.md) "배포 서버 6차"·"7차"). 회차마다 측정 모드를 켜 Gateway가 다시 뜬 뒤 순서대로 쓴다.

```bash
# 1) 서버: 수집 시작. check가 실패하면 본 측정으로 가지 않는다
ssh iseonjwa 'cd ~/iseonjwa && MEASURER=<이름> bash loadtest/k6/tools/gw-collect.sh start <회차> 1200 && sleep 6 && MEASURER=<이름> bash loadtest/k6/tools/gw-collect.sh check <회차>'
# 2) 발생기(tmux 안): 회차 실행. 결과는 ~/<이름>/<회차>-*와 gen-<회차>.tar.gz
PRE_VUS=3000 MAX_VUS=6000 bash loadtest/k6/tools/g1-run.sh <회차> <SCHEDULE_ID> [본 측정 STAGES]
# 3) 서버: 수집을 멈추고 /tmp/server-<회차>.tar.gz로 묶는다
ssh iseonjwa 'cd ~/iseonjwa && S=$(date -d "$(head -1 /tmp/<이름>-<회차>/meta.log)" +%s) && MEASURER=<이름> bash loadtest/k6/tools/gw-collect.sh stop <회차> && MEASURER=<이름> bash loadtest/k6/tools/gw-collect.sh archive <회차> $S $(date +%s)'
```

- `gw-collect.sh`는 Gateway 컨테이너의 `docker-init`(compose의 `init: true`) 아래 Java PID를 찾아 `pidstat -t -p`에 준다. 6·7차 수집기는 `docker inspect`의 `State.Pid`(docker-init)를 그대로 써서 스레드 자료가 비었다. `check`가 이 경우와 Gateway 재시작을 잡는다. 다른 서비스는 `CONTAINER=iseonjwa-<서비스>-1`로 준다.
- `g1-run.sh`의 기본값은 6·7차와 같다(`USERS` 10000, 본 측정 `1000,0,1500,0,2000,0,2500`, 단계마다 상승 5초 + 30초). 상한(`iseonjwa.gateway.in-flight.max-requests`)을 1로 두는 회차는 setup이 상한에 걸리므로 `USERS=1`을 준다.
- `r0_solve.py`는 내려받은 두 tar를 풀어 로컬에서 돌린다(사용법은 파일 머리말). 6·7차 자료처럼 pidstat이 비었으면 `--cpu dstats`로 `docker stats`를 쓴다.

## 3. 시나리오를 더할 때

- 파일은 `scenarios/<id>-<내용>.js`로 둔다. 첫 줄 주석에 명세의 ID와 한 줄 설명을 적는다.
- 회차는 `setup()`에서 `createSchedule`로 새로 만든다. 15,000석 생성에 수 초 걸리므로 `setupTimeout`을 넉넉히 둔다.
- 반복 하나를 새 사용자 한 명으로 본다(`userIdOf(exec.scenario.iterationInTest)`). 같은 사용자를 다시 쓰면 매수 한도·멱등키·입장 토큰 만료가 섞인다.
- 명세의 판정 기준은 `thresholds`에 적는다. 넘어도 측정은 끝까지 돈다.
- 정상 거절(409 등)이 오류율에 섞이지 않게 `http.setResponseCallback(http.expectedStatuses(...))`를 둔다.

## 4. 주의

- 측정 결과는 측정 환경 기록표([load-test.md](../../docs/design/infra/load-test.md) 7절)와 함께 남긴다. local에서 잰 결과에는 "local"을 붙인다.
- `.env`의 키와 관리자 비밀번호는 환경변수로만 넘어간다. 화면 출력과 결과 파일에 쓰지 않는다.
- **local의 Docker Desktop에서는 k6 컨테이너가 약 32초마다 3초씩 멈춘다.** 2026-11-05에 서버 없이 `sleep(0.1)`만 반복하는 스크립트로 확인했다(멈춤 3,028~3,032ms). Q2의 `poll_lateness`(서버가 준 시각보다 늦게 부른 정도)가 같은 주기로 튀어 알았다. local 결과에서 3초 근처의 최대값과 이 주기의 몰림은 발생기 탓일 수 있다. 본 측정은 발생기 EC2에서 한다. 같은 멈춤이 Docker VM 전체에서 생기는지(서버 컨테이너도 멈추는지)는 확인하지 못했다.
- **측정 전에 서비스 이미지가 지금 코드인지 확인한다.** 2026-11-05에 local reservation-service 이미지가 11-04 빌드라 예매 조회 API(ISE-171)가 없어 R1이 모두 404였다. `docker compose ... build <서비스>`로 다시 만들고, 측정 기록표의 커밋 해시는 이미지를 만든 커밋으로 적는다.
- **만료 대기 건.** 배포 메모리 덮어쓰기는 만료 스케줄러를 끄므로, 그 상태로 잰 측정의 선점은 만료되지 않고 남는다. 스케줄러를 다시 켜면 그 묵은 선점부터 처리해 X1의 만료 대상이 뒤로 밀린다. X1 전에 `select count(*) from reservations where status='HELD' and hold_expires_at < now()`가 0인지 본다. 2026-11-05에는 묵은 선점 7,000여 건이 남아 있었다. 이를 처리하는 속도는 5초 주기·100건 묶음에서 초당 약 15.6건이었다(35주기 3,500건 224초, local 1회).
- Gateway의 503 중 지연이 수 ms인 것은 인증 Redis 차단기(`auth-redis`)가 열린 것일 수 있다. Prometheus `resilience4j_circuitbreaker_state{name="auth-redis",state="open"}`로 출처를 나눈다.
