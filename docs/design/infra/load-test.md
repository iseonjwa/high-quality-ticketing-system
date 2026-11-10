# 부하 측정 설계

결론: 부하 도구는 k6이고, 같은 VPC의 팀 공용 발생기 EC2(측정 날 사이 중지)에서 Gateway를 내부 주소로 부른다. 결과는 Prometheus remote write로 서버 지표와 같은 Grafana에서 본다. 결정은 2026-11-03에 사용자가 했고 기록은 ADR 0046이다([README](README.md)). 값은 따로 적지 않은 한 `[가정]`이다.

## 1. 도구와 결과 보관

- 도구: k6. 스크립트는 루트 `loadtest/k6/`에 둔다(9절).
- 결과: k6가 Prometheus remote write로 보낸다. 서버 지표(CPU, 메모리, 결제 지표 등)와 같은 Grafana에서 겹쳐 본다.
- 설정(정함 2026-11-08, ISE-191): 평소 배포 Prometheus는 remote write 수신을 켜지 않고 9090도 열지 않는다(SA 9-2). 측정 모드 덮어쓰기(`loadtest/deploy/compose.measure.yml`)만 `--web.enable-remote-write-receiver`와 9090을 열고, 배포 서버 보안 그룹은 발생기 보안 그룹에서 오는 9090만 허용한다(5절 "결과 전송").
- 확인(2026-11-10): 11/9 Q1~Q3 측정 뒤 배포 Prometheus에 `k6_*` 지표 78개가 들어와 있고, Grafana의 Prometheus 데이터 소스로도 `testid`별로 조회된다. k6 전용 대시보드는 없어 Explore에서 본다.

## 2. 발생기

- 같은 리전·같은 VPC에 연산형 EC2를 둔다. 2026-11-09에 팀 공용 발생기 `iseonjwa-loadgen`(`c6i.xlarge`)을 만들었고, 측정 날 사이에는 중지하고 팀 마지막 측정 뒤 종료한다. 쓰는 규칙과 11/9 준비 결과는 [`guides/loadgen.md`](../../guides/loadgen.md)에 있다.
- k6 주소는 사설 IP(`172.31.4.93`)만 쓴다. 공인 IP로 보내면 같은 서브넷이어도 GB당 0.01달러가 양방향으로 붙고, 보안 그룹 규칙(소스 = 발생기 보안 그룹)에도 막힌다.
- Gateway는 VPC 내부 주소로 부른다. 배포 EC2 보안그룹은 발생기 보안그룹에서 8080만 허용한다([deployment.md](deployment.md) 3절). SA 3-1과 9-2의 "측정 기기 IP 허용"을 같은 VPC 방식으로 구체화한 것이다.
- 이 경로는 프론트 nginx를 거치지 않는다. 부하 도구는 Gateway를 직접 부른다([ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md) 4절).
- 발생기 자체의 한계(CPU 포화)로 측정이 왜곡되지 않는지 확인하는 방법은 미정이다. 발생기 CPU를 같이 기록하는 것을 기본으로 둔다.

### 2-1. 발생기 만들기와 지우기 (계정 주인, 측정 일정에 맞춰)

AWS 콘솔은 계정 주인만 쓴다(팀원 콘솔 초대 안 함). 그래서 발생기는 측정 시간이 정해지면 계정 주인이 그 시간 전에 만든다. 2026-11-05에 배포 서버의 위치를 확인했다.

| 항목 | 값 | 이유 |
|---|---|---|
| AMI | Ubuntu 24.04 LTS (x86) | 배포 서버와 같다. `script/deploy/install-docker.sh`를 그대로 쓴다 |
| 유형 | `c6i.xlarge` 추천(4 vCPU, 8GiB, 시드니 시간당 0.222달러). 가벼운 시나리오는 `c6i.large`(0.111달러) | 아래 "유형" 설명 |
| 네트워크 | 배포 서버와 같은 VPC `vpc-08032543fc446b5c7`, 같은 서브넷 `subnet-07b3d9d0e31a7d35d`(가용 영역 `ap-southeast-2a`), 퍼블릭 IP 자동 할당 켬 | 같은 가용 영역이면 사설 IP 통신이 무료이고 지연이 가장 작다. 가용 영역이 다르면 GB당 0.01달러씩 양방향 전송 요금이 붙는다. 퍼블릭 IP는 SSH 접속과 이미지 받기용이다 |
| 키 페어 | `iseonjwa-deploy` | 계정 주인(또는 에이전트)이 처음 접속해 팀원 공개 키를 넣는다 |
| 보안 그룹 | 새 `iseonjwa-loadgen-sg`: 인바운드 22 = 팀원 IP `/32`만 | 3절 표의 "발생기 EC2" 행 |
| 배포 서버 보안 그룹 | `iseonjwa-deploy-sg`에 인바운드 TCP 8080과 9090, 소스 = 보안 그룹 `iseonjwa-loadgen-sg` | 측정 중에만 둔다. 8080은 Gateway, 9090은 k6 결과를 Prometheus remote write로 보내는 길이다 |
| 발생기 SSH 키 | 배포 서버 `~/.ssh/authorized_keys`(팀원 키)를 발생기로 복사한다 | 팀 공용이라 팀원 모두 직접 접속한다(2026-11-09 변경) |
| 디스크 | gp3 20GB | k6 이미지와 결과 파일이면 충분하다 |

**유형:** 좌석 선점 K1은 좌석 조회 응답이 약 830KB이다. 초당 100건이면 응답만 초당 약 83MB(약 0.66Gbps)다.
- `c6i.large`의 네트워크 기준 대역폭은 0.781Gbps라 여유가 거의 없다.
- `c6i.xlarge`는 1.562Gbps다(AWS 연산 최적화 인스턴스 표).
- 발생기 대역폭이 먼저 막히면 k6의 `dropped_iterations`가 생기고, 서버 한계와 구분할 수 없다.
- 응답이 작은 시나리오는 `c6i.large`로도 된다.
- 유형은 측정 일정을 정할 때 고른다. 측정 중에도 중지 → 유형 변경 → 시작으로 바꿀 수 있다.

**순서**
1. 측정 30분 전에 위 표대로 발생기를 만든다. 배포 서버 보안 그룹에 8080·9090 규칙을 더한다.
2. 발생기 공인 IP를 받으면 접속해 준비한다. 계정 주인이나 에이전트가 한다.
   - `install-docker.sh` 실행
   - 저장소 clone
   - 팀원 공개 키 등록
   - `docker pull grafana/k6:1.0.0`
3. **측정 모드를 켠다**(그날 측정자가 배포 서버에서, ADR 0046 변경 2026-11-09. 켜기 전에 `measure-mode.sh status`로 다른 사람이 켜 두지 않았는지 본다): `script/loadtest/measure-mode.sh on [덮어쓰기 파일...]`. Q2는 `loadtest/k6/compose.queue-hold.yml`을 인자로 준다.
4. **키를 옮긴다**: 서버의 `~/iseonjwa/.env.measure`를 발생기 저장소 루트의 `.env`로 옮긴다. 내 노트북을 거치는 방법이 가장 단순하다. 키 파일이 노트북에 남지 않게 옮긴 뒤 지운다.
   ```bash
   scp iseonjwa:iseonjwa/.env.measure /tmp/measure.env && scp /tmp/measure.env <발생기 접속 주소>:iseonjwa/.env && rm /tmp/measure.env
   # 노트북을 거치지 않고 한 번에: scp -3 iseonjwa:iseonjwa/.env.measure <발생기 접속 주소>:iseonjwa/.env
   ssh <발생기 접속 주소> 'chmod 600 ~/iseonjwa/.env'
   ```
5. 발생기에서 Gateway 사설 주소(`http://172.31.4.93:8080`)로 짧은 확인 실행을 하고 본 측정을 한다. 결과는 `PROM_RW_URL`로 Prometheus에 보낸다(`loadtest/k6/README.md` "배포 서버 측정"). 측정 중 발생기 CPU·네트워크(`docker stats`, `dropped_iterations`)도 기록한다.
6. 측정하는 동안에는 main 릴리스를 하지 않는다. Jenkins가 main을 2분마다 확인해 새 커밋이 있으면 배포하고, 그러면 측정 모드가 풀린다.
7. 끝나면 결과 파일(`results/`)을 내려받는다.
8. **측정 모드를 끈다**: `script/loadtest/measure-mode.sh off`. 운영 키와 샘플링 1.0, 닫힌 9090으로 돌아오고 스모크를 돈다.
9. 측정자 폴더의 `.env`를 지운다. 그날 측정이 모두 끝났으면 발생기를 **중지(Stop)**한다. 팀 공용이라 측정 날 사이에는 중지만 한다(2026-11-09 변경).
10. 팀 마지막 측정 뒤에는 발생기를 **종료(Terminate)**하고 배포 서버 보안 그룹의 8080·9090 규칙과 `iseonjwa-loadgen-sg`를 지운다. 발생기에는 지킬 데이터가 없어 종료가 맞다.

## 3. 범위와 분담

범위는 사용자 결정으로 결제 담당(안준현)이 오픈 피크 전체 시나리오까지 설계한다. 2026-11-05에 ISE-181 담당이 최권우로 바뀌었다. 구간별 시나리오와 개선 계획은 [open-peak-load-plan.md](open-peak-load-plan.md)에 있다. 동기형 선점 기준선 측정(ISE-25, 권영은)과 겹치지 않게 다음처럼 나눈다.

| 구분 | 담당 | 내용 |
|---|---|---|
| 공통 기반 | 안준현 | k6 실행 구조, 가상 사용자·토큰 준비, 회차·좌석 초기화, 발생기, 결과 보관, 측정 환경 기록표 |
| 시나리오 1·3 | 안준현 | 오픈 피크 한 흐름, PG 지연·장애 중 결제 |
| 시나리오 2 (선점 경합) | 공통 기반의 시나리오로 안준현이 만들고, ISE-25 기준선 측정이 이것을 쓴다 | 입장한 사용자들이 한정 좌석에 몰리는 경우 |

ISE-25 쪽 일정과 이슈 경계는 권영은 담당과 정한다. 기준선이 쓸 시나리오의 매개변수(가상 사용자 수, 좌석 수)는 ISE-25에서 정하고 공통 기반은 받아 쓰기만 한다.

## 4. 시나리오 (초안)

모든 숫자는 `[가정]`이고 기준선 측정 뒤 SA 3-2 방식으로 갱신한다.

1. **오픈 피크 한 흐름**: 오픈 시각에 가상 사용자 N명이 대기열에 동시에 진입한다. 서버가 준 `nextPollAfterMillis` 간격으로 순번을 조회하고, READY가 되면 입장 토큰을 받아 좌석을 조회한다. 인기 구역에서 선점 경합을 한다(이미 선점이면 409를 받고 다른 좌석으로 재시도). 이어 결제 승인과 확정 확인을 한다.
2. **선점 경합**: 입장한 사용자들이 한정 좌석에 몰린다. ISE-25 기준선이 쓴다.
3. **PG 지연·장애 중 결제**: Mock PG 장애 주입(지연, 오류)을 켠 채 결제 부하를 건다. 장애 주입 API는 `test`·`loadtest` 프로필에서만 열린다([mock-pg.md](../payment/mock-pg.md) 5절). compose에서 mock-pg를 `local,loadtest`로 띄우는 덮어쓰기 파일이 필요하다(ISE-71, mock-pg.md가 제안만 하고 팀 확인을 기다리는 항목).

폴링 간격은 서버가 주는 값을 따른다. SA 9-4에 따르면 기본 3초, 순번이 멀수록 길어지고(최대 10초) jitter가 0~+20% 더해진다.

## 5. 준비물

| 준비 | 내용 | 비고 |
|---|---|---|
| 가상 사용자 | 계정과 Access 토큰을 미리 만든다 | 토큰 유효 30분(SA 9-4)이라 측정 직전에 만들거나 재발급이 필요하다 |
| 서명 키(Access 토큰·입장 토큰) | 정함(2026-11-08, 사용자): 측정 동안만 측정 전용 키로 바꿔 띄운다. `script/loadtest/measure-mode.sh on`이 `JWT_ACCESS_SECRET`·`ENTRY_TOKEN_SECRET`을 새로 만들어 `.env.measure`(권한 600)에 두고, 발생기의 k6(`TOKEN_MODE=sign`)가 그 키로 서명한다 | 아래 "측정용 토큰 키" |
| 회차·좌석 초기화 | 측정마다 좌석을 초기화하는 스크립트 | `BookingFlowSystemTest`는 회차마다 좌석 15,000석을 만든다([testing.md](../../guides/testing.md)) |
| 추적 샘플링 | 정함(2026-11-08): 측정 모드 덮어쓰기(`loadtest/deploy/compose.measure.yml`)가 `TRACING_SAMPLE_RATE`를 0.01로 둔다 | `compose.deploy.yml`은 시연용으로 1.0이다. 측정 모드가 꺼지면 1.0으로 돌아온다. SA가 말하는 "요청 제한 완화"는 `config-repo/application-loadtest.yml`에 없다. 요청 제한 설정이 어디에 있는지는 미확인 |
| 결과 전송 | 정함(2026-11-08): Prometheus remote write. 측정 모드가 `--web.enable-remote-write-receiver`와 9090을 열고, 보안 그룹은 발생기 보안 그룹에서 오는 9090만 허용한다. `run.sh`는 `PROM_RW_URL`이 있으면 `--out experimental-prometheus-rw`로 보낸다 | 지표 이름은 `k6_*`, 시나리오 구분은 `testid` 태그 |
| Mock PG | 정함(2026-11-08): 기준 지연 300ms. 배포 mock-pg는 이미 `loadtest` 프로필이라 장애 API가 열려 있고 포트는 밖에 열지 않는다. 서버 안에서 일회용 curl 컨테이너로 건다 | 명령: [`loadtest/k6/README.md`](../../../loadtest/k6/README.md) "배포 서버 측정" |
| 기록 도구(`loadtest/k6/tools/`) | 정함(2026-11-08): 세 스크립트는 컨테이너 이름이 배포 서버와 같아 고치지 않고 서버에서 돈다. 측정과 같은 시간에 서버 SSH 창에서 실행하고 결과 CSV를 내려받는다 | 명령: [`loadtest/k6/README.md`](../../../loadtest/k6/README.md) "배포 서버 측정"의 "기록 도구" |
| 측정 환경 기록표 | 7절 양식 | SA 3-1 항목 그대로 |

**측정용 토큰 키**(정함 2026-11-08, ADR 0046 "변경"): 발급 API를 기다리지 않고, 측정 동안만 측정 전용 키로 바꿔 띄운다. 운영 키는 발생기에 두지 않는다.
- `measure-mode.sh on`은 `JWT_ACCESS_SECRET`·`ENTRY_TOKEN_SECRET` 두 개만 새 무작위 값(`openssl rand -hex 32`)으로 `.env.measure`에 두고, `.env` 다음에 얹어(`--env-file .env --env-file .env.measure`) 스택을 다시 띄운다. 이미 있으면 다시 만들지 않아 측정 중 다시 실행해도 발생기 키와 어긋나지 않는다.
- 측정 모드 동안 기존 로그인은 끊긴다(Access 서명 키가 바뀐다). Refresh 키는 그대로다.
- 측정 중 main 머지로 Jenkins가 배포하면 `deploy.sh`가 `.env`만 쓰므로 측정 모드가 풀린다. 측정 동안 main 머지를 멈추거나 Jenkins 잡을 끈다.
- `measure-mode.sh off`가 운영 키(`.env`)로 다시 띄우고 스모크를 돈다. 측정 전용 키는 이때 버려져 측정 뒤 발생기에 남은 키로는 아무것도 열 수 없다.
- 관리자 계정은 발생기에 두지 않는다. 회차 준비는 서버에서 `script/deploy/seed-demo.sh open <OPEN_AT>`로 하고, Q1~Q3은 `SCHEDULE_ID`로 그 회차를 받는다.

## 6. 지표

| 지표 | 보는 곳 |
|---|---|
| 처리량, p95·p99, 오류율 | k6 결과(remote write) |
| 포화점(가상 사용자를 늘려 처리량이 멈추고 p95가 급증하는 지점) | k6 결과, 단계별 부하 |
| 서비스별 CPU·메모리 | `docker stats` 또는 cAdvisor. 어느 쪽인지 미정 |
| DB 연결 대기 | Hikari 제공 지표 |
| Kafka 랙 | Kafka 클라이언트 제공 지표 |
| 결제 지표 | [payment/observability.md](../payment/observability.md)의 지표와 대시보드 |

`payment_approve_seconds_bucket`의 시계열 수와 메모리는 부하에서 확인된 적이 없다(payment/observability.md "미확인"). 이 측정에서 함께 본다.

## 7. 측정 환경 기록표 (양식)

결과마다 아래 표를 채워 결과 옆에 둔다(SA 3-1 항목 그대로).

| 항목 | 값 |
|---|---|
| 대상 장비 (CPU·메모리·OS·EC2 타입) | |
| 부하 도구 장비·네트워크 (EC2 타입·리전·VPC 내부 여부) | |
| Docker 할당 | |
| JVM 힙(`-Xmx`)·컨테이너 메모리 상한 (서비스별) | |
| 커넥션 풀·스레드 수 | |
| 데이터 규모 (회차 수, 좌석 수, 가상 사용자 수) | |
| 시나리오 (번호와 매개변수) | |
| 커밋 해시 | |
| 샘플링 비율 (`loadtest` 프로필 값) | |

## 8. 측정 뒤

결과로 배포 EC2 크기(SA U10)와 컨테이너 상한을 정한다([deployment.md](deployment.md) 6절). 결과 문서의 위치와 형식은 측정 이슈에서 정한다.

## 9. 남은 결정

| 항목 | 상태 |
|---|---|
| 스크립트 위치 | 루트 `loadtest/k6/`로 정함(2026-11-05, 최권우). 좌석 선점 K1·K1-H는 `reservation-service/k6/`에 둔다 |
| 요청 제한 완화 설정 위치 | 미확인 |
| 서비스별 자원 지표 도구(`docker stats` / cAdvisor) | 미정 |
| 발생기 인스턴스 타입 | 정함(2026-11-09): 팀 공용 발생기 `iseonjwa-loadgen`을 `c6i.xlarge`로 만들었다(2절). 응답이 작은 시나리오만 잴 때는 중지 → 유형 변경으로 `c6i.large`로 내릴 수 있다 |
| 시나리오별 가상 사용자 수·좌석 수 | `[가정]`, 기준선 측정 뒤 갱신 |
| ISE-25와의 이슈 경계 | 권영은 담당과 정함 |

## SA와 다른 점/더한 점 (SA 변경 요청 대상)

- SA 3-1과 9-1·9-2의 "다른 기기에서 실행하는 부하 도구"가 "측정할 때만 띄우는 같은 VPC의 임시 EC2"로 바뀐다. 8080 허용 대상은 측정 기기 IP에서 발생기 보안그룹이 된다.
- SA 3-1의 측정 대상은 좌석 선점 API 하나인데, 여기서는 오픈 피크 한 흐름과 PG 장애 중 결제까지 넓힌다. 선점 경합 시나리오가 SA 3-1의 대상에 해당한다.
- Prometheus에 remote write 수신이 더해진다(SA 9-1 구성 요소 변경). 측정 모드 동안만 켜고(`--web.enable-remote-write-receiver`) 9090을 발생기 보안 그룹에만 연다(2026-11-08).
