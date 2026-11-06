# 좌석 선점 k6 스크립트

좌석 선점 담당(ISE-79)의 부하 시나리오 K1 스크립트다. 시나리오와 예상 병목, 개선 단계는 [k6-seathold-bottleneck-plan.md](../../docs/design/reservation/k6-seathold-bottleneck-plan.md)에 있다. k6는 설치하지 않고 Docker 이미지(`grafana/k6:1.0.0`)로 돌린다.

팀 공통 측정 기반(발생기 EC2, 가상 사용자·토큰 준비, 회차 초기화, remote write)은 ADR 0046에 따라 결제 담당(안준현)이 만든다. 이 폴더는 그것과 별개로 선점 시나리오만 담는다. 공통 기반이 나오면 토큰 CSV를 그쪽 도구로 만들고 이 스크립트는 그대로 쓴다.

## 1. 파일

| 파일 | 내용 |
|---|---|
| `k1-seathold.js` | K1: 초당 `RATE`명이 입장해 좌석 조회 → 고민 → 선점, 409면 다시 조회해 최대 `MAX_RETRY`번 재시도. `SEAT_MAP=once`면 K1-H: 좌석 조회는 `setup`에서 한 번만 하고 반복은 선점만 보낸다 |
| `run.sh` | Docker로 k6 실행, 결과를 `results/`에 저장 |
| `compose.deploy-memory.yml` | local 스택에서 gateway와 reservation-service의 메모리만 배포 서버 값으로 맞추고, 선점 만료 스케줄러를 끄는 측정 전용 덮어쓰기 |
| `compose.g1.yml` | 위 파일에 얹어 reservation-service의 GC를 G1으로 고정하고 GC 로그를 컨테이너 `/tmp/gc.log`에 남긴다. P0'' 이후 단계의 기본 환경 |
| `compose.b8-observe.yml` | B8(과부하 뒤 회복) 측정용. Tomcat MBean 지표와 요청 지연 1초·3초 경계 버킷을 켠다. 동작은 바꾸지 않는다 |
| `compose.b8-r1.yml` / `compose.b8-r123.yml` | B8 대응 R1(커넥션 대기 1초), R2·R3(요청 스레드 50, 연결 상한 200, OS 대기열 50) 측정용 |
| `compose.b8-r13.yml` | R3 재측정용(연결 상한 30, OS 대기열 10, 스레드 기본값). `compose.b8-r1.yml` 위에 얹는다 |
| `tools/b8-run.sh` | B8 한 회: reservation-service를 덮어쓰기로 새로 띄우고 새 회차에 K1 + 회복 탐침(`RECOVERY_SECONDS`)을 건 뒤 지표를 모은다 |
| `tools/create-schedule.sh` | 측정용 새 회차(판매 중, 15,000석)를 만들고 회차 ID만 출력한다. 관리자 계정은 `.env`에서 읽고 출력하지 않는다 |
| `tools/b8_metrics.py` | Prometheus에서 Tomcat·Hikari·좌석 조회 지표를 10초 간격 CSV와 판정용 요약으로 꺼낸다 |
| `tools/b8_k6.py` | k6 원자료에서 사용자 쪽 좌석 조회·선점 상태별 건수와 오류 응답 p50·p95를 센다 |
| `data/users.csv` | 사용자 토큰(직접 준비, 커밋 금지) |
| `results/` | 요약 JSON, 요청별 원자료 CSV(gzip), 화면 출력(커밋 금지) |

## 2. 준비물

`data/users.csv`는 첫 줄이 머리글이고 한 줄에 사용자 한 명이다. 같은 사용자를 두 번 쓰지 않으므로 열린 모델에서는 `RATE × DURATION_SECONDS`명(기본 30,000명)이 필요하다. 모자라면 `setup`이 측정을 시작하지 않는다.

```csv
accessToken,entryToken
eyJ...,eyJ...
```

- `accessToken`: `POST /api/v1/auth/login` 응답의 `data.accessToken`. 유효 30분이다.
- `entryToken`: 대상 회차에서 `POST /api/v1/schedules/{id}/queue` → READY → `POST /api/v1/schedules/{id}/queue/token` 응답의 `data.entryToken`. 유효 10분이다.
- 대상 회차는 판매 중이어야 한다(오픈 시각이 지났고 공연 시작 전). 좌석은 시드 공연장(15,000석, 구역 A~Y)으로 만든다.

### local 스택에서 CSV 없이 돌리기 (`TOKEN_MODE=sign`)

토큰 CSV 도구가 나오기 전에 local 스택에서 재려면 `TOKEN_MODE=sign`을 쓴다. k6가 반복마다 사용자 ID(`USER_ID_BASE`부터, 기본 1,000,000)로 Access 토큰과 입장 토큰을 직접 서명한다. `run.sh`가 저장소 루트 `.env`에서 `JWT_ACCESS_SECRET`과 `ENTRY_TOKEN_SECRET`을 읽어 환경변수로만 넘기고 출력하지 않는다.

- 반복 시점에 서명하므로 입장 토큰 10분 만료 문제(5절)가 없다.
- 로그인·대기열 API를 거치지 않는다. 선점 경로는 user-service를 부르지 않아 가입 행이 없어도 된다.
- local 전용이다. 배포 환경의 키를 발생기에 두지 않는다(ADR 0046에서 측정용 서명 방식은 미정).

```bash
SCHEDULE_ID=12 TOKEN_MODE=sign bash reservation-service/k6/run.sh
```

## 3. 실행

Docker Desktop과 Gateway(`localhost:8080`)가 떠 있어야 한다. 저장소 루트에서 실행한다.

배포 서버와 같은 메모리로 재려면 스택을 덮어쓰기 파일과 함께 띄운다. 5분 측정 중 선점 만료가 끼지 않게 만료 스케줄러도 꺼진다. 기동 뒤 Gateway가 인스턴스를 받기까지 약 40초 걸린다.

```bash
docker compose -f compose.yml -f compose.local.yml -f reservation-service/k6/compose.deploy-memory.yml up -d --wait
# P0'' 이후 단계: G1까지 켠다
docker compose -f compose.yml -f compose.local.yml -f reservation-service/k6/compose.deploy-memory.yml \
  -f reservation-service/k6/compose.g1.yml up -d --wait
```

- GC 로그를 꺼낼 때 Git Bash는 `/tmp`를 Windows 경로로 바꾼다. `MSYS_NO_PATHCONV=1`을 앞에 붙인다.
- 컨테이너 안에서 `java -version` 같은 명령을 따로 띄우지 않는다. `JAVA_TOOL_OPTIONS`를 물려받아 같은 GC 로그 파일을 열기 때문에 앱의 로그가 `gc.log.0`으로 밀려난다.

```bash
# Git Bash
SCHEDULE_ID=12 bash reservation-service/k6/run.sh
# 닫힌 모델 비교(VU 100명이 쉬지 않고 반복)
SCHEDULE_ID=12 MODEL=closed bash reservation-service/k6/run.sh
# 짧게 시험(초당 10명, 30초, 사용자 300명)
SCHEDULE_ID=12 RATE=10 DURATION_SECONDS=30 bash reservation-service/k6/run.sh
# K1-H: 선점 경로만(좌석 조회는 setup에서 1번). 결과 파일 이름에 holdonly가 붙는다
SCHEDULE_ID=12 TOKEN_MODE=sign SEAT_MAP=once bash reservation-service/k6/run.sh
# 측정 종류(계획 문서 1-6절). 워밍업용 회차를 하나 더 만든다
WARM=$(bash reservation-service/k6/tools/create-schedule.sh "k6 warmup")
# 부하(L): 초당 100명 5분, 30초 워밍업
SCHEDULE_ID=12 TOKEN_MODE=sign WARMUP_SECONDS=30 WARMUP_SCHEDULE_ID=$WARM bash reservation-service/k6/run.sh
# 스트레스(S)와 회복(R): 초당 5명부터 두 배씩 60초, 끝나고 120초 탐침. 높은 단계는 VU가 모자라면 MAX_VUS를 올린다
SCHEDULE_ID=12 TOKEN_MODE=sign MODEL=ramp MAX_VUS=4000 RECOVERY_SECONDS=120 WARMUP_SECONDS=30 WARMUP_SCHEDULE_ID=$WARM bash reservation-service/k6/run.sh
# K1-H 스트레스
SCHEDULE_ID=12 TOKEN_MODE=sign SEAT_MAP=once MODEL=ramp STAGES=50,100,200,400,800 MAX_VUS=4000 bash reservation-service/k6/run.sh
```

K1-H는 좌석 조회 병목(B2)을 떼고 선점 쪽 개선(P1~P3)을 재려고 만들었다. 좌석 목록이 측정 시작 시점에 고정이라 시간이 갈수록 409가 K1보다 많고, 좌석 조회와 커넥션을 나눠 쓰지 않아 실제보다 좋게 나온다. 그래서 K1 결과와 섞지 않는다. 설계는 계획 문서 1-5절, 기준선은 5-4절이다.

```powershell
# PowerShell
docker run --rm -v "${PWD}/reservation-service/k6:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e SCHEDULE_ID=12 grafana/k6:1.0.0 run --summary-export /scripts/results/summary.json /scripts/k1-seathold.js
```

| 환경변수 | 기본값 | 뜻 |
|---|---|---|
| `SCHEDULE_ID` | 없음(필수) | 대상 회차 |
| `BASE_URL` | `http://host.docker.internal:8080` | Gateway. 컨테이너 안의 localhost는 컨테이너 자신이다 |
| `MODEL` | `open` | `open`(초당 도착 고정), `closed`(VU 고정), `ramp`(스트레스: 단계마다 도착률을 올림) |
| `STAGES` / `STAGE_SECONDS` | `5,10,20,40,80,160,320` / 60 | `ramp`의 단계별 초당 도착 수와 단계 길이. 단계 사이 5초는 올리는 구간이라 `stage:ramp`로 따로 태그한다. K1-H는 `50,100,200,400,800`을 쓴다 |
| `WARMUP_SECONDS` / `WARMUP_RATE` / `WARMUP_SCHEDULE_ID` | 0 / 10 / 없음 | 0보다 크면 부하 전에 다른 회차로 워밍업한다(`api:warmup` 태그, 결과에서 빠짐). `TOKEN_MODE=sign`에서만 된다 |
| `RATE` / `DURATION_SECONDS` | 100 / 300 | 초당 입장 수와 측정 시간 |
| `CLOSED_VUS` | 100 | 닫힌 모델의 VU 수 |
| `THINK_MIN` / `THINK_MAX` | 1 / 3 | 첫 선점 전 고민 시간(초). 재시도 전에는 0.5~1초 |
| `MAX_RETRY` | 2 | 409 뒤 재시도 횟수 |
| `HOT_RATIO` / `HOT_SHARE` | 0.7 / 0.1 | 사용자의 70%가 응답 앞쪽 10%(구역 A~C 근처)에서 고른다 |
| `PRE_VUS` / `MAX_VUS` | 600 / 2000 | 열린 모델의 VU 풀. `dropped_iterations`가 생기면 늘린다 |
| `TOKEN_MODE` / `USER_ID_BASE` | `csv` / 1000000 | `sign`이면 CSV 대신 local 키로 토큰을 서명한다(2절) |
| `SEAT_MAP` | `each` | `each`는 K1(시도마다 좌석 조회), `once`는 K1-H(선점만) |
| `RECOVERY_SECONDS` | 0 | 0보다 크면 부하가 끝나고 30초 뒤부터 이 시간 동안 10초마다 좌석 조회 탐침을 보낸다(B8) |

B8(과부하 뒤 회복) 측정은 `tools/b8-run.sh`로 한 회씩 돈다. 결과는 `results/b8-<이름>-<시각>-*`(k6 로그, 탐침, 지표 CSV, 요약)이다. 판정 기준은 [k6-overload-recovery.md](../../docs/design/reservation/k6-overload-recovery.md) 6절이다.

```bash
bash reservation-service/k6/tools/b8-run.sh r0
bash reservation-service/k6/tools/b8-run.sh r1 reservation-service/k6/compose.b8-r1.yml
bash reservation-service/k6/tools/b8-run.sh r123 reservation-service/k6/compose.b8-r1.yml reservation-service/k6/compose.b8-r123.yml
```

## 4. 결과 읽기

| 지표 | 뜻 |
|---|---|
| `http_req_duration{api:seats}`, `{api:hold}` | API별 p95·p99 |
| `seat_map_duration{phase:sale}`, `{phase:soldout}` | 좌석 조회 지연을 판매 구간과 매진 뒤 구간으로 나눈 값. 응답에 선택 가능한 좌석이 없으면 매진 뒤로 센다 |
| `hold_duration{result:created}`, `{result:conflict}` | 선점 201과 409의 지연 |
| `hold_results` | 선점 결과 건수. 원자료 CSV의 `code` 태그로 409 사유(`SEAT_ALREADY_HELD` 등)를 나눈다 |
| `users_finished` | 사용자 결과(`held`, `soldout`, `gave_up`, `error`) |
| `requests_per_user` | 사용자당 요청 수. 409 재시도가 요청을 얼마나 불렸는지 |
| `dropped_iterations` | 부하 생성기가 도착률을 못 맞춘 횟수. 0이 아니면 서버가 아니라 생성기 한계일 수 있다 |
| `{stage:<초당 명>}` | `MODEL=ramp`에서 그 단계에 도착한 사용자의 요청. 단계별 `http_req_failed`·`http_req_duration{api:seats}`·`{api:hold}`가 요약에 따로 나온다 |

임계값은 `[가정]`이라 넘어도 측정은 끝까지 돈다. 결과는 설계 문서 5절 표에 옮긴다. 매진 시각은 측정 뒤 DB에서 마지막 선점 성공 시각으로 정한다.

- local Docker Desktop의 k6 컨테이너는 약 32초마다 3초씩 멈춘다(2026-11-05 확인, [loadtest/k6/README.md](../../loadtest/k6/README.md) 4절). local 결과의 3초 근처 최대값은 발생기 탓일 수 있다.
- Gateway의 503 중 지연이 수 ms인 것은 인증 Redis 차단기(`auth-redis`)가 열린 것일 수 있다. Prometheus `resilience4j_circuitbreaker_state{name="auth-redis",state="open"}`과 Gateway 로그 `No servers available`(Eureka 이탈)로 출처를 나눈다.
- 좌석 조회 응답(15,000석, 약 1MB로 추정)을 `JSON.parse`하지 않고 문자열 검색으로 좌석을 고른다. 파싱하면 부하 생성기 CPU가 먼저 포화돼 서버 병목과 섞인다. 측정 중 `docker stats`로 k6 컨테이너 CPU도 같이 본다.

## 5. 준비 상태 (2026-11-04 확인)

| 준비물 | 상태 | 근거·막는 점 |
|---|---|---|
| 회원 가입·로그인 API | 있음 | `POST /api/v1/users`, `POST /api/v1/auth/login` |
| 사용자 3만 명 일괄 생성·토큰 CSV 도구 | 없음 | `system-tests`의 `WorkspaceClient`가 한 명씩 만드는 코드만 있다. ADR 0046 공통 기반(안준현) 범위 |
| 입장 토큰 발급 API | 있음 | `POST /api/v1/schedules/{id}/queue/token`(ISE-69) |
| 입장 토큰 3만 개 사전 발급 | 현재 설정으로 불가 | 입장 속도 `queue.admit-per-second` 50이면 3만 명 입장에 600초가 걸린다. 입장 토큰 유효 시간 `queue.entry-token-ttl`이 10분이라 마지막 토큰을 받을 때쯤 첫 토큰이 만료된다. 측정용으로 입장 속도·유효 시간을 올리는 loadtest 설정이 필요하다. 측정용 서명은 운영 키 노출 위험이 있어 ADR 0046에서 미정이다. local에서는 `TOKEN_MODE=sign`으로 우회한다(2026-11-04 P0 측정에 사용) |
| 공연장·좌석 | 있음 | 마이그레이션 시드 `V20261030_2__venue_seed.sql`(15,000석). 회차·좌석은 관리자 API로 만든다(`BookingFlowSystemTest`와 같은 절차) |
| 측정마다 회차·좌석 초기화 스크립트 | 없음 | 새 회차를 만들면 대신할 수 있다. 공통 기반 범위 |
| `loadtest` 프로필 | 일부 | `config-repo/application-loadtest.yml`에 추적 샘플링 0.01만 있다. 입장 속도·토큰 유효 시간·풀 크기 덮어쓰기는 없다 |
| Gateway 요청 제한 | 없음 | `gateway`와 `config-repo`에서 rate limiter 설정을 찾지 못했다. 측정을 막지 않는다 |
| Prometheus remote write | 없음 | `infra/prometheus.yml`에 수신 설정이 없다. 그래서 `run.sh`는 결과를 파일(요약 JSON, CSV)로 남긴다 |

## 6. 단계 측정 (측정 브랜치 `experiment/ISE-79-k6-steps` 전용)

수정하지 않은 기본 코드에서 시작해 직면한 병목을 하나씩 고치며 잰다. 규칙, 실행 방법, 단계별 예상·실측은 [STEPS.md](STEPS.md)에 있다.

| 파일 | 내용 |
|---|---|
| `tools/step-run.sh` | 한 회: 지금 커밋으로 reservation-service를 빌드해 새로 띄우고, 새 회차에 L·S·H 중 하나를 건 뒤 지표·GC 로그·대사·CPU를 모은다 |
| `step-overlays.txt` | 기준 환경 위에 얹을 덮어쓰기 목록. 설정 단계(G1, R1, P1)는 여기에 한 줄을 더하는 커밋이다 |
| `compose.step-observe.yml` | 기준 환경의 GC 로그 켜기. GC 종류는 JVM이 고르게 둔다 |
| `compose.measure-gateway.yml`, `config-measure/` | 기준 환경의 Gateway 인증 차단기 느린 호출 판정 끄기(K1-H가 Gateway에서 막히지 않게). 공유 `config-repo`는 고치지 않는다 |
| `tools/invariants.sql` | 회차 하나의 대사(중복 판매, 매수 불일치, 좌석 상태 불일치)와 마지막 선점 시각 |
| `STEPS.md` | 단계 기록 |
