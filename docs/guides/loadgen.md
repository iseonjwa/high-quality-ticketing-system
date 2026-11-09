# 팀 공용 부하 발생기 사용 가이드

k6 부하 발생기 EC2를 팀원 5명이 같이 쓰는 방법이다. 누가 언제 쓰는지 알리는 법, 측정 시작과 끝에 할 일, 처음 접속과 연결 확인, 막혔을 때 볼 것을 모았다. 2026-11-09 기준이다(ISE-215).

- 설계와 결정: [`design/infra/load-test.md`](../design/infra/load-test.md) 2-1·5절, [ADR 0046](../decisions/0046-load-test-k6.md)
- k6 실행 명령과 환경변수: [`loadtest/k6/README.md`](../../loadtest/k6/README.md) "배포 서버 측정"
- 배포 서버 접속과 서버 규칙: [`deploy-server-access.md`](deploy-server-access.md)
- 발생기 만들기·중지·종료와 보안 그룹은 AWS 콘솔 작업이라 AWS 담당(안준현)이 한다.

## 1. 지금 상태

| 항목 | 값 |
|---|---|
| 이름·유형 | `iseonjwa-loadgen`, `c6i.xlarge`(4 vCPU, 8GiB, 네트워크 기준 1.562Gbps), Ubuntu 24.04, 디스크 20GB |
| 퍼블릭 IP | `3.106.225.224`. **중지했다가 켜면 바뀐다.** 바뀌면 AWS 담당이 팀 채널에 올린다 |
| 사설 IP | `172.31.13.231` |
| 배포 서버(측정 대상) | 사설 `172.31.4.93`. Gateway `:8080`, Prometheus remote write `:9090` |
| 보안 그룹 | 발생기 `iseonjwa-loadgen-sg`(22번은 팀원 IP `/32`만). 배포 서버 `iseonjwa-deploy-sg`는 발생기 보안 그룹에서 오는 8080·9090만 받는다 |
| 계정 | `ubuntu` 하나. 배포 서버의 `authorized_keys`(계정 주인, Jenkins, 강민·권우·효승·영은)를 복사했다 |
| 설치된 것 | Docker 29.9.3, Docker Compose v5.7.0, 이미지 `grafana/k6:1.0.0`(k6 v1.0.0, go1.24.2). 서버 전체에 설치돼 있어 다시 설치하지 않는다 |
| 시계 | chrony가 Amazon Time Sync Service(`169.254.169.123`)에 자동으로 맞춘다. 11/9 오차 1.2µs(배포 서버 0.13µs) |

## 2. 같이 쓰는 규칙

| 규칙 | 이유 |
|---|---|
| **측정은 한 번에 한 명** | 같은 배포 서버를 재므로 두 측정이 겹치면 결과가 섞인다 |
| **시작 전·끝난 뒤 팀 채널에 알린다**(4·5절 문구) | 다른 사람이 측정 중인지 알 방법이 채널뿐이다 |
| **k6 주소는 사설 IP `172.31.4.93`만** | 아래 "주소와 요금" |
| 각자 폴더 `~/<영문 이름>/iseonjwa` | 계정이 하나라 폴더를 같이 쓰면 브랜치·결과 파일이 엉킨다 |
| 측정 전용 키 `.env`는 그날 측정자 폴더에만, 끝나면 지운다 | 측정 모드를 켤 때마다 새로 만들어지는 키다. 운영 키와 관리자 비밀번호는 발생기에 두지 않는다(ADR 0046 변경) |
| 측정 날 사이에는 **중지(Stop)**, 팀 마지막 측정 뒤 **종료(Terminate)** | 중지 동안은 디스크 비용만 든다. 지킬 데이터가 없어 마지막에는 종료한다 |
| 공용 설치물을 바꾸지 않는다 | `docker system prune`, 다른 사람 폴더 삭제, `apt upgrade`, `do-release-upgrade`는 다른 사람의 측정 환경을 바꾼다. 필요하면 팀 채널에 먼저 묻는다 |
| 측정 중 main 머지 금지 | Jenkins가 2분마다 main을 보고 배포하면 측정 모드가 풀린다 |

**주소와 요금**(AWS 담당 안내, 11/9): K1은 응답이 약 830KB라 초당 100건으로 10분 돌리면 응답만 약 50GB다.

| 보내는 곳 | 결과 |
|---|---|
| 발생기 → 사설 IP `172.31.4.93` | 0원(같은 가용 영역 사설 통신) |
| 발생기 → 공인 IP `52.63.10.174` | 같은 서브넷이어도 GB당 0.01달러가 양방향으로 붙어 1회 약 1달러. 보안 그룹 규칙(소스 = 발생기 보안 그룹)에도 맞지 않아 연결이 막힌다 |
| 노트북 → 인터넷 너머 | 2회 만에 월 무료 100GB를 다 쓰고, 그 뒤 1회 약 5.7달러. 한국–시드니 왕복(약 0.27초)도 결과에 섞인다 |

그래서 `BASE_URL=http://172.31.4.93:8080`, `PROM_RW_URL=http://172.31.4.93:9090/api/v1/write`로 쓴다.

## 3. 처음 쓸 때

접속에는 배포 서버와 같은 개인 키를 쓴다. 접속이 안 되면 7절을 본다.

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<발생기 퍼블릭 IP>
# PowerShell은 키 경로를 $HOME\.ssh\id_ed25519로 쓴다
```

발생기 안에서 내 폴더에 저장소를 받는다. 공개 저장소라 인증이 필요 없다.

```bash
mkdir -p ~/<영문 이름> && cd ~/<영문 이름>
git clone https://github.com/iseonjwa/high-quality-ticketing-system.git iseonjwa
cd iseonjwa && git switch dev && git log --oneline -1   # 측정한 코드 기준으로 기록한다
```

측정 날에는 `git pull`로 최신 `dev`를 받는다. 다른 사람 폴더는 건드리지 않는다.

## 4. 측정 시작할 때

| 순서 | 누가 | 할 일 |
|---|---|---|
| 1 | 측정자 | 팀 채널에서 다른 사람이 측정 중이 아닌지 보고 시작 알림을 올린다 |
| 2 | AWS 담당 | 발생기가 중지 상태면 켜고 새 퍼블릭 IP를 올린다 |
| 3 | AWS 담당 | 배포 서버에서 측정 모드를 켜고(`script/loadtest/measure-mode.sh on`), main 머지를 멈추거나 Jenkins 잡을 끈다. 이때부터 기존 로그인이 끊긴다 |
| 4 | AWS 담당 | 서버의 `~/iseonjwa/.env.measure`를 측정자 폴더 `~/<영문 이름>/iseonjwa/.env`로 옮기고 `chmod 600` 한다(`load-test.md` 2-1절 4번) |
| 5 | 측정자 | 6절 연결 확인으로 8080·9090이 열렸는지 본다(측정 모드에서는 9090이 열려 `succeeded`가 나온다) |
| 6 | 측정자 | 회차가 필요한 시나리오는 서버에서 회차를 만들고 그 ID로 실행한다(`loadtest/k6/README.md` "배포 서버 측정") |
| 7 | 측정자 | 긴 측정은 발생기 안에서 `tmux`로 돌린다. SSH가 끊겨도 k6가 계속 돈다(`tmux new -s k6`, 다시 붙기 `tmux attach -t k6`) |

시작 알림 문구:

```
[발생기 사용 시작] <이름> / <시나리오 ID> / 예상 <시각>~<시각>
측정 모드: 켜기 요청(@AWS 담당) / main 머지 멈춰 주세요
```

## 5. 측정 끝날 때

| 순서 | 누가 | 할 일 |
|---|---|---|
| 1 | 측정자 | 결과 파일(`loadtest/k6/results/`)을 내 PC로 내려받는다. 결과는 커밋하지 않는다 |
| 2 | 측정자 | 내 폴더의 `.env`를 지운다(`rm ~/<영문 이름>/iseonjwa/.env`) |
| 3 | AWS 담당 | 측정 모드를 끈다(`measure-mode.sh off`). 운영 키, 샘플링 1.0, 닫힌 9090으로 돌아오고 스모크가 돈다. Jenkins 잡을 다시 켠다 |
| 4 | 측정자 | `load-test.md` 7절 측정 환경 기록표를 채운다 |
| 5 | AWS 담당 | 그날 측정이 모두 끝났으면 발생기를 중지한다. 팀 마지막 측정 뒤에는 종료하고 8080·9090 규칙과 `iseonjwa-loadgen-sg`를 지운다 |

끝 알림 문구:

```
[발생기 사용 끝] <이름> / <시나리오 ID> / 결과 받음, .env 삭제함
측정 모드 끄기 요청(@AWS 담당). 오늘 남은 측정: 있음(<이름> <시각>) / 없음 → 발생기 중지 부탁드립니다
```

## 6. 연결 확인과 연기 테스트

발생기 안에서 한다. 측정 모드 없이도 된다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 'http://172.31.4.93:8080/api/v1/concerts?page=0&size=20'
nc -zv -w 5 172.31.4.93 9090
```

| 결과 | 뜻 |
|---|---|
| 8080이 HTTP 코드(200, 401, 404 등) | 보안 그룹 통과. Gateway가 답했다 |
| 8080이 `000` | 응답이 없다. 보안 그룹 8080 규칙을 AWS 담당에게 확인 |
| 9090이 `Connection refused` | 보안 그룹은 통과했고 9090을 듣는 것이 없다. 측정 모드가 꺼진 상태면 정상이다 |
| 9090이 `timed out` | 보안 그룹이 패킷을 버린다. 9090 규칙을 AWS 담당에게 확인 |

- `/actuator/health`는 Gateway가 노출하지 않아 404가 나온다. 길 확인에는 문제없지만 공개 API로 확인하는 편이 헷갈리지 않는다.

연기 테스트(smoke test): 공개 API 공연 목록을 VU 2개로 20초(초당 약 2건) 부른다. 저장소 시나리오는 측정 전용 키나 관리자 계정이 필요해 측정 모드 없이 돌지 않으므로 이 스크립트로 k6 → Gateway 길만 확인한다. 아주 약하지만 같은 서버를 쓰니 다른 사람이 측정 중일 때는 돌리지 않는다.

```bash
docker run --rm -i grafana/k6:1.0.0 run - <<'EOF'
import http from 'k6/http';
import { check, sleep } from 'k6';
export const options = { vus: 2, duration: '20s', thresholds: { http_req_failed: ['rate<0.01'] } };
export default function () {
  const res = http.get('http://172.31.4.93:8080/api/v1/concerts?page=0&size=20');
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(1);
}
EOF
```

11/9 결과(측정 모드 꺼짐): 요청 40건 모두 200, `http_req_failed` 0%, `http_req_duration` 중앙값 9.91ms, p95 16.35ms, 최대 24.43ms. 같은 서브넷이라 네트워크 지연이 거의 없다.

## 7. 막혔을 때

| 증상 | 원인과 처리 |
|---|---|
| `Permission denied (publickey)` | 발생기 `authorized_keys`에 내 키가 없다. 22번 포트는 열린 것이다. 내 키 지문(`ssh-keygen -lf ~/.ssh/id_ed25519.pub`)을 AWS 담당에게 보내 확인한다 |
| `Connection timed out`(SSH) | 내 공인 IP가 22번 규칙에 없다. `curl.exe https://checkip.amazonaws.com`으로 IP를 확인해 AWS 담당에게 보낸다 |
| PowerShell에서 `curl`이 보안 경고를 띄움 | Windows PowerShell 5.1의 `curl`은 `Invoke-WebRequest` 별칭이다. `N`으로 취소하고 `curl.exe`를 쓴다 |
| 리눅스에서 `$HOME\.ssh\...`가 안 됨 | 리눅스 경로 구분자는 `/`다. 발생기 안에서는 `~/.ssh/...`로 쓴다 |
| `docker: permission denied` | Docker 설치 직후라면 다시 로그인해야 `docker` 그룹이 적용된다 |
| k6 `dropped_iterations`가 생김 | 발생기 VU 풀이나 대역폭이 모자라다. 서버 한계와 섞이므로 `docker stats`로 발생기 CPU·네트워크를 같이 기록한다 |

## 8. 에이전트가 지킬 것

이 저장소의 에이전트가 발생기 작업을 돕거나 대신할 때 지킨다.

- k6 주소(`BASE_URL`, `PROM_RW_URL`)는 사설 IP `172.31.4.93`만 쓴다. 공인 IP나 노트북에서 배포 서버로 부하를 거는 명령을 만들지 않는다.
- 측정을 시작하는 명령 전에 다른 사람이 측정 중이 아닌지와 팀 채널 알림 여부를 사용자에게 확인한다. 연기 테스트도 같다.
- `.env`(측정 전용 키 포함)와 배포 서버 `.env`를 열거나 출력하거나 옮기지 않는다. 키 옮기기와 측정 모드 켜고 끄기는 AWS 담당이 한다.
- 개인 키 내용을 출력하거나 보내지 않는다. 키에 암호가 있으면 비대화형 SSH는 실패하므로 사용자가 직접 접속하거나 `ssh-agent`에 올리게 한다. 비대화형 시험이 거부로 나와도 서버에 키가 없다고 단정하지 않는다(11/9 오판 사례).
- AWS 콘솔, 보안 그룹, 인스턴스 중지·종료는 AWS 담당에게 요청할 문구만 만든다.
- 공용 설치물과 다른 사람 폴더를 바꾸지 않는다.
- k6 결과는 `results/<이름>-summary.json`이나 stdout 끝부분(`tail -n 60`)만 읽는다. stdout 전체를 읽지 않고, `K6_PROGRESS=1`(진행 표시줄 켜기)은 사용자가 요청할 때만 쓴다. 진행 표시줄이 실행 한 번에 토큰 약 1만 개를 쓴다(ISE-219).

## 9. 남은 막힘

- **관리자 계정이 필요한 시나리오**: L1·L2를 포함해 B1, G1, PAY1, R1, X1, L3, O1은 `setup()`에서 `adminLogin()`으로 공연·회차·추첨을 만든다. 운영 관리자 비밀번호를 발생기에 두지 않는 규칙 때문에 지금은 배포에서 돌지 않는다. Q1~Q3처럼 서버에서 만들고 ID만 넘기는 방식(추천)과 측정 전용 관리자 계정 방식이 있고, 어느 쪽이든 AWS 담당 동의가 필요하다. 앞 방식은 AWS 담당이 만든 공유 스크립트 `script/deploy/seed-demo.sh`에 추첨 만들기를 더해야 하고, 뒤 방식은 ADR 0046 변경의 규칙에 예외를 만든다.
