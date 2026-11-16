# 배포 서버 좌석 선점 단계 측정 실행표 (ISE-296)

- 상태: 측정 전(2026-11-16 작성). 측정자: 최권우(`MEASURER=kwonwoo`)
- 관련 이슈: ISE-296(세 방식 비교), ISE-265(Kafka-UI 캡처), ISE-266(A0), ISE-293(커넥션 대기 초과 503), ADR 0062 후속(동시 처리 상한 값), ADR 0064(풀 8은 조건부)
- 계획과 지표 정의: [async-seathold-remeasure-plan.md](async-seathold-remeasure-plan.md) 4절. 이 문서는 그 계획을 배포 서버에서 한 번에 도는 순서와 명령만 적는다.
- 공용 절차: [loadgen.md](../../guides/loadgen.md) 4·5절, [deploy-server-access.md](../../guides/deploy-server-access.md) 5-5절, [loadtest/k6/README.md](../../../loadtest/k6/README.md) "배포 서버 측정"

측정 모드를 한 번 켜 두고, 덮어쓰기 파일만 바꿔 reservation-service를 다시 띄우며 단계를 올린다. 모든 단계가 같은 배포 이미지에서 설정만 다르다.

## 1. 전제와 시작 전 확인

전제
- ISE-293(커넥션 대기 초과 → 503)이 main에 들어가 배포돼 있다. 안 들어갔으면 결과의 500과 503을 함께 세고, 기록표에 적는다.
- 이 문서와 함께 만든 파일 세 개가 배포 서버 체크아웃(`~/iseonjwa`, 배포 커밋)에 있다. `measure-mode.sh on`은 서버 저장소의 덮어쓰기 파일을 읽기 때문이다. 이 PR이 main에 들어가기 전이면 S0·S2 단계를 돌릴 수 없다.
  - `loadtest/k6/compose.seat-hold-prereject-off.yml`, `loadtest/k6/compose.seat-hold-limit.yml`, `script/loadtest/watch-seathold-outbox.sh`

시작 전 체크리스트
- [ ] 팀 채널에 다른 사람이 측정 중이 아닌지 보고 시작 알림을 올렸다(문구: loadgen.md 4절, 예상 시간은 3절 합계). "main 머지 금지"를 넣었다
- [ ] Jenkins 잡을 껐다. 못 끄면 시작 알림의 "main 머지 금지"로 대신한다
- [ ] 서버: `ssh iseonjwa 'cd ~/iseonjwa && script/loadtest/measure-mode.sh status'`로 꺼져 있는 것을 봤다
- [ ] 서버: `cat ~/iseonjwa/.deploy/current`로 배포 커밋을 적었다(기록표). ISE-293과 이 PR이 들어간 커밋인지 본다
- [ ] 발생기: `cd ~/kwonwoo/iseonjwa && git switch dev && git pull && git log --oneline -1`
- [ ] 발생기: `mkdir -p reservation-service/k6/results && chmod 777 reservation-service/k6/results`. `reservation-service/k6/run.sh`는 `loadtest/k6/run.sh`와 달리 리눅스 실행 사용자를 컨테이너에 넘기지 않아, 결과 폴더에 쓰지 못할 수 있다(ISE-225와 같은 원인, 발생기에서 미확인)
- [ ] 브라우저: Grafana `https://52.63.10.174/grafana/`의 좌석 선점 대시보드(`infra/grafana/dashboards/seathold.json`)를 연다. Kafka-UI는 터널(`ssh -N -L 8085:127.0.0.1:8085 -L 9090:127.0.0.1:9090 iseonjwa`)로 `http://localhost:8085`

## 2. 단계

| 단계 | 서버에서 켜는 명령(`ssh iseonjwa 'cd ~/iseonjwa && ...'`) | 바뀌는 것 | 같이 끝나는 일 |
|---|---|---|---|
| S0 | `MEASURER=kwonwoo script/loadtest/measure-mode.sh on loadtest/k6/compose.seat-hold-prereject-off.yml` | 동기형, 캐시 사전 거절 끔, 상한 끔 | 캐시 켬/끔 비교의 "끔" |
| S1 | `MEASURER=kwonwoo script/loadtest/measure-mode.sh on` | 동기형, 사전 거절 켬(운영값), 상한 끔 | 캐시 켬/끔 비교, A0 동기형 |
| S2 | `MEASURER=kwonwoo script/loadtest/measure-mode.sh on loadtest/k6/compose.seat-hold-limit.yml` | S1 + 동시 처리 상한 4 | ADR 0062 후속(상한을 config-repo에서 켤지) |
| O | `MEASURER=kwonwoo script/loadtest/measure-mode.sh on loadtest/k6/compose.seat-hold-limit.yml loadtest/k6/compose.seat-hold-async.yml` | 접수형 Outbox | A0 접수형, ISE-265 캡처, Outbox 적체 |
| D | `MEASURER=kwonwoo script/loadtest/measure-mode.sh on loadtest/k6/compose.seat-hold-limit.yml loadtest/k6/compose.seat-hold-async.yml loadtest/k6/compose.seat-hold-direct.yml` | 접수형 직접 발행 | ISE-296 세 방식 비교 |
| S2' | S2와 같은 명령 | S2와 같음 | 처음과 끝의 편차 확인 |
| (선택) P8 | `RES_POOL_MAX=8 SEAT_HOLD_LIMIT=7 MEASURER=kwonwoo script/loadtest/measure-mode.sh on loadtest/deploy/compose.reservation-pool.yml loadtest/k6/compose.seat-hold-limit.yml` | 풀 8, 상한 7 | ADR 0064 조건(S2에서 CPU 여유가 있는데 `hikaricp_connections_pending`이 주 병목)일 때만 |
| (선택) AUTO | 강민님 담당(ADR 0059). `SEAT_MODE=auto` O1 재측정을 같은 창에서 할지 강민님과 정한다 | | 이 실행표 범위 밖 |

- `measure-mode.sh on`은 인자로 준 파일이 전체 목록이다. 앞 단계 파일을 다시 적지 않으면 빠진다. O·D에 상한 파일을 함께 주는 것은 S2와 조건을 맞추려는 것이다. 상한 없이 비교하려면 빼고, 기록표에 적는다.
- 이미 켜진 상태에서 다시 `on`하면 키(`.env.measure`)는 그대로고 설정이 바뀐 서비스만 다시 뜬다. 발생기 `.env`를 다시 옮기지 않는다. 처음 `on` 뒤에만 키를 옮긴다(loadgen.md 4절 4번).
- O·D에서 S2'로 돌아가기 전에 `seat_hold_requests`의 REQUESTED가 0이어야 한다(ADR 0058, 학습 노트 `2026-11-12-mode-switch-replay-gap.md` 8절). 4-3절 스크립트의 `_requested` 줄이나 아래 명령으로 본다.

```bash
ssh iseonjwa "docker exec iseonjwa-postgres-reservation-1 psql -U iseonjwa -d reservation -At -c \"select count(*) from seat_hold_requests where status = 'REQUESTED'\""
```

## 3. 한 단계의 순서

| 순서 | 어디서 | 할 일 | 시간(추정) |
|---|---|---|---|
| 1 | 서버 | 2절 명령으로 켠다. `--wait`가 끝날 때까지 기다린다(최대 420초) | 2~4분 |
| 2 | 브라우저 | 설정이 들어갔는지 본다(3-1절). 다르면 측정하지 않는다 | 1분 |
| 3 | 서버 | 예열: `script/deploy/warmup.sh`(기본 초당 25회 120초). 다시 뜬 JVM은 JIT 컴파일 전이라 첫 측정이 20~30% 느리다 | 2분 |
| 4 | 서버 | 회차 만들기(3-2절). 회차 캐시 갱신(30초) 때문에 1분 뒤부터 쓴다 | 2~3분(미확인) |
| 5 | 발생기 | A0 10회(3-3절). 접수형은 회마다 REQUESTED가 0이 될 때까지 기다린다 | 10~15분 |
| 6 | 발생기 | K1-H 램프 1회(3-4절). O·D는 서버에서 Outbox 표본을 함께 돈다(4절) | 약 8분 |
| 7 | 메모 | 기록표 한 줄(6절), 이상한 점 | 2분 |

단계당 약 30~35분, 여섯 단계 약 3시간 30분이다(추정, 회차 만들기 시간은 미확인). 선택 단계는 따로 더한다.

### 3-1. 설정 확인

Actuator는 `health`·`prometheus`만 연다(ADR 0057 맥락). 설정 값을 직접 볼 길이 없어 지표와 응답으로 확인한다. Prometheus는 터널의 `http://localhost:9090`이나 Grafana Explore에서 본다.

| 단계 | 확인 | 기대 |
|---|---|---|
| 모두 | `hikaricp_connections_max{application="reservation-service"}` | 5(P8만 8) |
| S0 | K1-H 램프 중 `seathold_pre_rejected_total` | 늘지 않는다. A0는 409가 없어 이 지표로 확인할 수 없다 |
| S1·S2 | K1-H 램프 중 `seathold_pre_rejected_total` | 매진 뒤 늘어난다 |
| S2·O·D | `seathold_concurrency_in_use{application="reservation-service"}` | 시계열이 있다. 상한이 꺼져 있으면 이 Gauge를 등록하지 않는다(`SeatHoldConcurrencyLimit`) |
| S0·S1 | 같은 지표 | 시계열이 없다 |
| O·D | A0 첫 회의 선점 응답 | 202. 201이면 모드가 바뀌지 않았다 |
| D | `seathold_direct_publish_total{result="sent"}` | 접수 수만큼 는다. O에서는 늘지 않는다 |

### 3-2. 회차 만들기(서버)

A0는 회마다 빈 좌석 1,000석이 필요하고 새 회차는 1,500석이다(ADR 0060). 그래서 A0 10회 + K1-H 1회 + K1-H 예열 1회로 단계마다 12개를 만든다. 출력의 회차 id를 순서대로 쓴다.

```bash
ssh iseonjwa 'cd ~/iseonjwa && for i in $(seq 1 12); do script/deploy/seed-demo.sh open "$(date -u -d "-5 minutes" +%Y-%m-%dT%H:%M:%SZ)" | sed -n "s/^새 회차 id: \([0-9]*\).*/\1/p"; done'
```

- 회차는 시연 공연 아래에 쌓인다. 측정이 끝나도 지우는 API는 없다(미확인). 측정 회차 수를 끝 알림에 적는다.

### 3-3. A0 10회(발생기)

```bash
cd ~/kwonwoo/iseonjwa
export BASE_URL=http://172.31.4.93:8080 PROM_RW_URL=http://172.31.4.93:9090/api/v1/write MOCK_PG_URL=
# 한 회. STAGE는 단계 이름, SID는 3-2절의 회차 id
run_a0() {
  SCHEDULE_ID="$2" bash loadtest/k6/run.sh scenarios/a0-assignment-baseline.js | tail -n 5
  printf '%s\ta0\t%s\t%s\n' "$1" "$2" "$(ls -t loadtest/k6/results/a0-assignment-baseline-*-summary.json | head -1)" >> ~/kwonwoo/ise296-runs.tsv
}
for SID in <회차 id 10개>; do run_a0 S1 "$SID"; sleep 20; done
# 단계의 10회를 표로
python3 loadtest/k6/tools/a0-report.py $(awk -F'\t' '$1=="S1" && $2=="a0" {print $4}' ~/kwonwoo/ise296-runs.tsv)
```

- 판정 "무효"(요청 수가 1,000이 아니거나 409가 있음)는 새 회차로 다시 잰다.
- 접수형(O·D)의 A0는 202 접수 시간만 잰다. 처리 완료는 서버 `seathold_consumer_delay_seconds`로 본다. 앞 회의 REQUESTED가 남은 채 다음 회를 돌리면 소비자 적체가 섞이므로, `sleep 20` 대신 2절의 REQUESTED 0 확인 뒤에 다음 회를 돌린다.
- 동기형 A0는 409가 없는 성공 경로라 S0과 S1의 차이(사전 거절)는 거의 보이지 않을 것으로 본다(추정). 사전 거절의 효과는 K1-H에서 본다.

### 3-4. K1-H 램프 1회(발생기)

좌석 조회를 떼고 선점 경로만 단계적으로 올린다. 예열은 다른 회차로 30초 한다(`TOKEN_MODE=sign`이어야 한다).

```bash
cd ~/kwonwoo/iseonjwa
BASE_URL=http://172.31.4.93:8080 SCHEDULE_ID=<회차 id> TOKEN_MODE=sign SEAT_MAP=once MODEL=ramp \
  STAGES=20,40,80,160,320 STAGE_SECONDS=60 MAX_VUS=4000 RECOVERY_SECONDS=120 \
  WARMUP_SECONDS=30 WARMUP_SCHEDULE_ID=<예열 회차 id> USER_ID_BASE=<단계마다 다른 값> \
  bash reservation-service/k6/run.sh | tail -n 60
printf '%s\tk1h\t%s\t%s\n' S1 <회차 id> "$(ls -t reservation-service/k6/results/k1-seathold-holdonly-ramp-*-summary.json | head -1)" >> ~/kwonwoo/ise296-runs.tsv
```

- `TOKEN_MODE=sign`은 발생기 저장소 루트 `.env`(측정 전용 키)를 읽는다.
- `reservation-service/k6/run.sh`는 `PROM_RW_URL`을 넘기지 않는다. K1-H 결과는 요약 JSON과 원자료 CSV로만 남고 Grafana의 k6 패널에는 없다. 서버 지표는 그대로 Grafana에서 본다.
- 1,500석이라 낮은 단계에서 매진되고, 그 뒤는 409 경로(사전 판정·사전 거절)를 잰다. 오픈 직후 수요 ≫ 좌석인 상황과 같다. STAGES는 미확인 값이다. 첫 단계(S0)에서 320에서도 실패가 없으면 다음 단계부터 640을 더하고 기록표에 적는다.
- `USER_ID_BASE`는 단계마다 바꾼다(예: S0 3000000, S1 3100000, ...). 같은 사용자가 같은 회차에서 입장 토큰을 두 번 쓰면 403이다(ADR 0053).

## 4. 단계별로 볼 지표

| 지표(Prometheus) | 무엇을 보나 | 단계 |
|---|---|---|
| `hikaricp_connections_pending`, `hikaricp_connections_active` | 커넥션 대기. S2에서 CPU 여유가 있는데 대기가 주 병목이면 P8 단계를 한다 | 모두 |
| `seathold_seat_lock_seconds_bucket` | 좌석 UPDATE 잠금 대기. 50ms 버킷 밖 비율이 잠금 시한 초과 비율이다 | 모두 |
| `seathold_concurrency_in_use`, `seathold_concurrency_wait_seconds_*`, `seathold_concurrency_held_seconds_*`, `seathold_concurrency_rejected_total` | 상한 사용, 자리 대기, 자리 쥔 시간, 503 거절. 쥔 시간이 길면 상한을 늘려도 잠금에 막힌 요청만 는다 | S2·O·D |
| `seathold_result_total{outcome}`, `seathold_pre_rejected_total` | 선점 결과와 사전 거절 수 | 모두 |
| `seathold_consumer_result_total{outcome}`, `seathold_consumer_delay_seconds_*` | 소비자 결과(`late`, `lock_timeout` 포함)와 접수 → 처리 시작 지연. SA 9-4 목표 2초 | O·D |
| `seathold_direct_publish_total{result}` | 직접 발행 실패 수 | D |
| `http_server_requests_seconds_count{status}` | 500·503 수. ISE-293 반영이면 커넥션 대기 초과가 503으로 나온다 | 모두 |
| DB CPU | `docker stats`(아래 명령). 대시보드에 컨테이너 CPU 패널이 있는지는 미확인 | 모두 |

```bash
# 서버: 측정 동안 5초마다 컨테이너 CPU·메모리를 남긴다. 단계마다 라벨을 바꾼다
ssh iseonjwa 'cd ~/iseonjwa && mkdir -p loadtest/k6/results && while true; do docker stats --no-stream --format "{{.Name}},{{.CPUPerc}},{{.MemUsage}}" | sed "s/^/$(date +%s),/"; sleep 5; done > loadtest/k6/results/dstats-S1.csv'
# 서버: O·D 단계의 K1-H 동안 Outbox 적체 표본(15초 간격, 15분)
ssh iseonjwa 'cd ~/iseonjwa && bash script/loadtest/watch-seathold-outbox.sh O-k1h 900'
```

- `watch-seathold-outbox.sh`는 구역 키별 PENDING 건수와 가장 오래된 행의 나이, 전체 PENDING(`_total`), REQUESTED 수(`_requested`)를 한 CSV에 남긴다. D 단계는 Outbox를 쓰지 않아 `_total`이 0이다.

## 5. Kafka-UI 캡처(O·D 단계, ISE-265)

O 단계 K1-H가 도는 동안 찍는다. 파일은 `docs/presentation/performance/`에 PNG로 둔다(ISE-265, PR #698의 표와 맞춘다).

- [ ] 토픽 목록(파티션 수가 보이게)
- [ ] `seat-hold.request` 토픽 상세(파티션 6, 메시지가 들어오는 것)
- [ ] 컨슈머 그룹 `reservation.seat-hold`(멤버, 파티션별 랙)
- [ ] 컨슈머 그룹 `reservation.seat-hold-dlq`(DLQ가 비어 있는지)
- [ ] `payment.result`, 예매 Outbox 토픽, 결제 명령 토픽의 컨슈머 그룹(이 측정에서는 흐르지 않을 수 있다. 결제가 없는 시나리오라서다)

## 6. 측정 환경 기록표

단계마다 한 줄 채운다. 칸은 [async-seathold-remeasure-plan.md](async-seathold-remeasure-plan.md) 4-5절과 같다.

| 칸 | 값 |
|---|---|
| 날짜·측정자 | 2026-11-16 저녁, 최권우 |
| 커밋 해시 | 서버 `.deploy/current`, 발생기 `git log --oneline -1` |
| 방식(S0·S1·S2·O·D·S2')·시나리오 | |
| 환경 | 배포 서버(사설 172.31.4.93), 발생기 `c6i.xlarge` |
| CPU·메모리(서버, 발생기) | 서버 미확인(EC2 타입을 적는다), 발생기 4 vCPU·8GiB |
| JVM `-Xmx` | 미확인(서버 compose 덮어쓰기에서 확인) |
| 커넥션 풀(`maximum-pool-size`) | 5(P8만 8) |
| 동시 처리 상한 | 끔(S0·S1), 4(S2·O·D·S2'), 7(P8) |
| 캐시 사전 거절 | 끔(S0), 켬(그 밖) |
| 소비자 동시성·파티션 수·잠금 시한 | 2·6·50ms(config-repo) |
| Outbox 간격·배치 | 500ms·20(코드 기본값, 계획 4-5절) |
| 결과 파일 위치 | `~/kwonwoo/ise296-runs.tsv`에 단계·시나리오·회차·요약 파일을 적는다 |

## 7. 유효 판정

계획 4-4절을 따른다. 무효면 같은 단계에서 새 회차로 다시 잰다.
- k6 종료 코드가 137(메모리 부족)이 아니다. A0의 99는 임계값 위반이라 표의 판정 열을 본다
- Prometheus 표본 간격이 15초 안이다
- `dropped_iterations`가 적다. 생기면 발생기 `docker stats`를 같이 적는다(loadgen.md 7절)
- 좌석 대사 위반이 0이다(측정 뒤 대사 API, 실행 방법은 [seat-ledger-reconciliation.md](seat-ledger-reconciliation.md))
- `setup`·`warmup`·`probe` 태그는 집계에서 뺀다

## 8. 끝낼 때

loadgen.md 5절 순서를 따른다.
1. 발생기 결과(`loadtest/k6/results/`, `reservation-service/k6/results/`, `~/kwonwoo/ise296-runs.tsv`)와 서버 결과(`~/iseonjwa/loadtest/k6/results/outbox-*`, `dstats-*`)를 내려받는다. 커밋하지 않는다.
2. 발생기 `.env`를 지운다.
3. 서버에서 `MEASURER=kwonwoo script/loadtest/measure-mode.sh off`, `status`로 꺼졌는지 본다. 껐던 Jenkins 잡을 켠다.
4. 끝 알림을 올린다. 만든 회차 수를 적는다.
5. 결과는 계획 문서 6절과 ISE-296에 채운다.
