# 오픈 피크 k6 시나리오 (ISE-181)

오픈 피크 흐름의 구간별 부하 시나리오와 그것들이 같이 쓰는 공통 코드다. 시나리오 명세는 [open-peak-scenarios.md](../../docs/design/infra/open-peak-scenarios.md), 구간별 병목과 개선 계획은 [open-peak-load-plan.md](../../docs/design/infra/open-peak-load-plan.md)에 있다. k6는 설치하지 않고 Docker 이미지(`grafana/k6:1.0.0`)로 돌린다.

좌석 선점 K1·K1-H는 [`reservation-service/k6/`](../../reservation-service/k6/README.md)에 따로 있다. 공통 코드가 자리를 잡으면 이 폴더의 `lib`을 쓰도록 옮길지 정한다.

## 1. 구성

| 경로 | 내용 |
|---|---|
| `run.sh` | Docker로 시나리오 하나를 돌리고 결과를 `results/`에 남긴다. 저장소 루트 `.env`에서 서명 키와 관리자 계정을 읽어 환경변수로만 넘긴다 |
| `lib/env.js` | 공통 환경변수(`BASE_URL`, `TOKEN_MODE`, `USER_ID_BASE`)와 숫자 변수 읽기 |
| `lib/tokens.js` | 측정용 Access 토큰·입장 토큰 서명(`TOKEN_MODE=sign`, local 전용), UUID |
| `lib/http.js` | URL, 인증 헤더, 응답 `data`·오류 `code` 읽기, 결과 분류 |
| `lib/admin.js` | 관리자 로그인, 공연·회차·좌석(15,000석) 만들기, 오픈 시각까지 기다리기 |
| `scenarios/q1-queue-enter.js` | Q1 대기열 진입 몰림 |
| `results/` | 요약 JSON, 요청별 원자료 CSV(gzip), 화면 출력. 커밋하지 않는다 |

시나리오 상태:

| ID | 스크립트 | 상태 |
|---|---|---|
| Q1 | `scenarios/q1-queue-enter.js` | 있음. 2026-11-05 local에서 300명으로 동작 확인(오류 0, 순번 300 = 고유 사용자 300) |
| G1, B1, Q2, Q3, PAY1, R1, X1, L1, L2, L3, O1 | - | 명세만 있다. 순서는 계획 문서 4절 |

## 2. 실행

Docker Desktop과 Gateway(`localhost:8080`)가 떠 있어야 한다. 저장소 루트에서 Git Bash로 실행한다. 배포 메모리로 재려면 [`reservation-service/k6/README.md`](../../reservation-service/k6/README.md) 3절의 덮어쓰기 파일과 함께 스택을 띄운다.

```bash
# Q1: 30,000명이 10초 안에 진입(기본값). 회차를 만들고 40초 뒤 오픈에 맞춰 시작한다
bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
# 짧게 확인: 300명, 10%가 연타
USERS=300 DUP_RATIO=0.1 USER_ID_BASE=5000000 bash loadtest/k6/run.sh scenarios/q1-queue-enter.js
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
| `TOKEN_MODE` | `sign` | k6가 토큰을 직접 서명한다(local 전용). 배포 측정 방식은 미정(load-test.md 5절) |
| `USER_ID_BASE` | 1000000 | 가상 사용자 ID 시작값 |
| `SCHEDULE_ID` | 없음 | 주면 회차를 만들지 않고 그 회차로 잰다 |
| `USERS` / `ENTER_SECONDS` | 30000 / 10 | Q1: 진입할 사용자 수와 그 시간(초). 초당 속도는 둘을 나눈 값 |
| `DUP_RATIO` / `REPEAT` | 0 / 3 | Q1-c: 연타하는 사용자 비율과 횟수 |
| `OPEN_IN_SECONDS` | 40 | 회차를 만든 뒤 오픈까지. 회차 캐시 갱신(30초)보다 길어야 진입이 캐시를 탄다 |
| `PRE_VUS` / `MAX_VUS` | 초당 속도(최대 2000) / 5000 | 열린 모델의 VU 풀. `dropped_iterations`가 생기면 늘린다 |

새 시나리오가 변수를 더하면 `run.sh`의 `PASS_ENV` 목록에도 더한다. Docker가 목록에 있는 변수만 컨테이너로 넘긴다.

## 3. 시나리오를 더할 때

- 파일은 `scenarios/<id>-<내용>.js`로 둔다. 첫 줄 주석에 명세의 ID와 한 줄 설명을 적는다.
- 회차는 `setup()`에서 `createSchedule`로 새로 만든다. 15,000석 생성에 수 초 걸리므로 `setupTimeout`을 넉넉히 둔다.
- 반복 하나를 새 사용자 한 명으로 본다(`userIdOf(exec.scenario.iterationInTest)`). 같은 사용자를 다시 쓰면 매수 한도·멱등키·입장 토큰 만료가 섞인다.
- 명세의 판정 기준은 `thresholds`에 적는다. 넘어도 측정은 끝까지 돈다.
- 정상 거절(409 등)이 오류율에 섞이지 않게 `http.setResponseCallback(http.expectedStatuses(...))`를 둔다.

## 4. 주의

- 측정 결과는 측정 환경 기록표([load-test.md](../../docs/design/infra/load-test.md) 7절)와 함께 남긴다. local에서 잰 결과에는 "local"을 붙인다.
- `.env`의 키와 관리자 비밀번호는 환경변수로만 넘어간다. 화면 출력과 결과 파일에 쓰지 않는다.
- Gateway의 503 중 지연이 수 ms인 것은 인증 Redis 차단기(`auth-redis`)가 열린 것일 수 있다. Prometheus `resilience4j_circuitbreaker_state{name="auth-redis",state="open"}`로 출처를 나눈다.
