---
title: 팀 공용 부하 발생기로 배포 서버를 재는 순서 (한 단계씩)
date: 2026-11-11
updated: 2026-11-11
asker: Junhyun An
level: 입문
tags: [load-test, k6, loadgen, measure-mode, prometheus, aws]
---

# 팀 공용 부하 발생기로 배포 서버를 재는 순서 (한 단계씩)

## 질문
"loadgen.md를 보고 부하 발생 방법 좀 브리핑해 줘. 한 단계 한 단계 자세하게."

## 짧은 답
기계 세 대(내 노트북, 발생기 EC2, 배포 서버)를 오가며 한다. 배포 서버에서 측정 모드를 켜고, 측정 전용 키를 발생기 내 폴더로 옮기고, 서버에서 회차를 만든 뒤, 발생기에서 사설 IP로 `run.sh`를 돌린다. 끝나면 결과를 받고 키를 지우고 측정 모드를 끈다. 계정 주인(AWS 담당)이 나라서 발생기 켜기·IP 공지·중지·종료도 내가 한다.

## 바탕 개념

**왜 발생기가 따로 있나.** 부하를 거는 쪽이 느리거나 멀면 그 지연과 한계가 결과에 섞인다. 노트북에서 시드니 서버를 부르면 왕복 약 0.27초가 모든 요청에 붙고 전송 요금도 나온다. 그래서 배포 서버와 같은 가용 영역에 발생기 EC2(`c6i.xlarge`)를 두고 사설 IP로 부른다. 같은 가용 영역의 사설 통신은 요금이 0원이고 지연이 거의 없다(11/9 연기 테스트 p95 16ms).

**측정 모드가 바꾸는 것.** `measure-mode.sh on`은 세 가지를 바꿔 스택을 다시 띄운다.
1. 측정 전용 서명 키 두 개(`JWT_ACCESS_SECRET`, `ENTRY_TOKEN_SECRET`)를 새로 만들어 `.env.measure`에 둔다. k6는 사용자 3만 명을 실제로 로그인시키지 않고 토큰을 직접 서명한다(`TOKEN_MODE=sign`). 서명하려면 키가 있어야 하는데, 운영 키를 발생기에 두지 않으려고 측정 동안만 쓰는 키로 바꾼다. 끌 때 이 키는 버려진다.
2. 추적 샘플링을 1.0에서 0.01로 낮춘다. 모든 요청의 트레이스를 저장하는 비용이 측정 결과를 흐리지 않게 한다.
3. Prometheus의 remote write 수신과 9090 포트를 연다. k6 결과(`k6_*` 지표)를 서버 Prometheus로 보내 Grafana에서 서버 지표와 같은 시간축으로 본다.

부작용이 둘 있다. Access 서명 키가 바뀌니 기존 로그인이 끊긴다. 측정 중 main이 머지되면 Jenkins의 `deploy.sh`가 `.env`만으로 다시 띄워 측정 모드가 풀린다.

**관리자 비밀번호는 서버 밖으로 나가지 않는다.** 회차·추첨은 서버에서 `seed-demo.sh`가 만들고 k6는 그 ID(`SCHEDULE_ID`, `LOTTERY_ID`)만 받는다. 그래서 setup에서 관리자 로그인이 꼭 필요한 시나리오(PAY1, R1, B1, G1, X1, O1)는 아직 배포에서 돌지 않는다.

## 이 프로젝트에서의 순서

### 0. 처음 한 번
발생기에 접속해 내 폴더에 저장소를 받는다. 계정이 `ubuntu` 하나라 폴더를 사람마다 나눈다.

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<발생기 퍼블릭 IP>
mkdir -p ~/junhyun && cd ~/junhyun
git clone https://github.com/iseonjwa/high-quality-ticketing-system.git iseonjwa
cd iseonjwa && git switch dev && git log --oneline -1
```

Docker와 `grafana/k6:1.0.0`은 이미 깔려 있어 다시 설치하지 않는다. 퍼블릭 IP는 발생기를 중지했다 켜면 바뀐다.

### 1. 팀 채널에서 자리 잡기
측정은 한 번에 한 명이다. 같은 서버를 재므로 겹치면 결과가 섞인다. 다른 사람이 측정 중이 아닌지 보고 시작 알림을 올린다. 발생기가 중지 상태면 AWS 콘솔에서 켜고 새 퍼블릭 IP를 올린다.

```
[발생기 사용 시작] 안준현 / R1-d / 예상 14:00~14:40
측정 모드: 켬(안준현) / 측정이 끝날 때까지 main 머지를 멈춰 주세요
```

### 2. 배포 서버에서 측정 모드 켜기

```bash
ssh iseonjwa
cd ~/iseonjwa
script/loadtest/measure-mode.sh status          # 다른 사람이 켜 두지 않았는지
MEASURER=junhyun script/loadtest/measure-mode.sh on
script/loadtest/measure-mode.sh status          # 켜졌는지, 덮어쓰기 파일이 맞는지
```

- 시나리오에 따라 덮어쓰기 파일을 인자로 준다. Q2는 `loadtest/k6/compose.queue-hold.yml`, S1 개선 전은 `loadtest/deploy/compose.seat-before.yml`, auth-redis 비교는 `loadtest/deploy/compose.gateway-bulkhead.yml`이다. 인자는 누적이 아니라 전체 목록이다. 다시 `on`하면 그 목록으로 바뀐다.
- 켜면 슬랙 측정 전용 채널로 알림이 간다. 서버 `.env`에 `SLACK_WEBHOOK_LOADTEST`가 비어 있으면 성능-모니터링 채널로 간다. 알림이 어느 채널에 왔는지 보면 웹훅이 들어갔는지 알 수 있다(ISE-224).
- Jenkins 잡을 끌 수 있으면 끈다.

### 3. 측정 전용 키 옮기기 (노트북에서)

```bash
scp -3 iseonjwa:iseonjwa/.env.measure ubuntu@<발생기 퍼블릭 IP>:junhyun/iseonjwa/.env
ssh ubuntu@<발생기 퍼블릭 IP> 'chmod 600 ~/junhyun/iseonjwa/.env'
```

- `scp -3`은 노트북을 거쳐 흘려보내기만 해 노트북에 파일이 남지 않는다.
- `run.sh`가 저장소 루트 `.env`에서 키를 읽어 환경변수로만 넘긴다. 키 값은 화면에 찍지 않는다.
- 측정 모드를 켠 채 다시 `on`하면 키를 새로 만들지 않으므로 다시 옮기지 않아도 된다.
- `load-test.md` 2-1절 4번 예시는 목적지가 `iseonjwa/.env`(개인 폴더 규칙 전 경로)다. 지금은 내 폴더 `junhyun/iseonjwa/.env`로 쓴다.

### 4. 연결 확인 (발생기에서)

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 'http://172.31.4.93:8080/api/v1/concerts?page=0&size=20'
nc -zv -w 5 172.31.4.93 9090
```

| 결과 | 뜻 |
|---|---|
| 8080이 HTTP 코드(200 등) | 보안 그룹 통과, Gateway가 답했다 |
| 8080이 `000` | 응답 없음. 보안 그룹 8080 규칙을 본다 |
| 9090 `succeeded` | 측정 모드에서 정상 |
| 9090 `Connection refused` | 보안 그룹은 통과, 9090을 듣는 것이 없다. 측정 모드가 꺼져 있다 |
| 9090 `timed out` | 보안 그룹이 패킷을 버린다 |

### 5. 측정 대상 만들기 (배포 서버에서)
관리자 계정이 서버 `.env`에 있으므로 서버에서 만든다.

```bash
# 5분 전에 열린 15,000석 회차. 출력의 "새 회차 id"가 SCHEDULE_ID
script/deploy/seed-demo.sh open "$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)"
# 추첨: 2분 뒤 응모 시작, 9분 뒤 마감, 10분 뒤 추첨. 마지막 두 줄의 LOTTERY_ID
script/deploy/seed-demo.sh lottery "$(date -u -d '+2 minutes' +%Y-%m-%dT%H:%M:%SZ)" \
  "$(date -u -d '+9 minutes' +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d '+10 minutes' +%Y-%m-%dT%H:%M:%SZ)"
```

- 회차 캐시가 30초마다 갱신되므로 측정 1분 전에는 만든다.
- 좌석을 파는 시나리오(R1-d)와 대기열 시나리오는 실행마다 새 회차를 쓴다. 앞 실행의 좌석·대기자가 남으면 결과가 섞인다.

### 6. 결제 시나리오만: Mock PG 지연 (배포 서버에서)
Mock PG 포트는 밖에 열려 있지 않아 서버 안에서 compose 네트워크에 일회용 curl 컨테이너를 붙여 건다. 앱이 다시 뜨면 설정이 사라지므로 측정 모드를 켠 뒤, 측정 직전에 건다. R1-d는 승인 → 확정 지연만 보려고 걸지 않는다.

```bash
docker run --rm --network iseonjwa_default curlimages/curl:8.10.1 -s -X POST -H 'Content-Type: application/json' \
  -d '{"target":"APPROVE","mode":"DELAY","delayMs":300}' http://mock-pg:8090/mock-pg/faults
docker run --rm --network iseonjwa_default curlimages/curl:8.10.1 -s http://mock-pg:8090/mock-pg/faults   # 확인
```

### 7. k6 실행 (발생기에서, tmux 안에서)

```bash
tmux new -s k6                      # SSH가 끊겨도 k6가 계속 돈다. 빠지기 Ctrl-b d, 다시 붙기 tmux attach -t k6
cd ~/junhyun/iseonjwa && git pull && git log --oneline -1
export BASE_URL=http://172.31.4.93:8080 MOCK_PG_URL= PROM_RW_URL=http://172.31.4.93:9090/api/v1/write
SCHEDULE_ID=<회차 id> bash loadtest/k6/run.sh scenarios/r1d-confirm.js
```

`run.sh`가 하는 일:
- `.env`의 키를 환경변수로만 넘기고 `grafana/k6:1.0.0` 컨테이너로 시나리오를 돌린다. 리눅스에서는 실행한 사람의 uid:gid로 돌려 `results/`에 쓸 수 있게 한다(ISE-225).
- `results/<시나리오>-<시각>-summary.json`, `-raw.csv.gz`, `-stdout.txt`를 남긴다.
- `PROM_RW_URL`이 있으면 `k6_*` 지표에 `testid=<시나리오>-<시각>` 태그를 붙여 Prometheus로 보낸다.
- 진행 표시줄은 기본으로 끈다(`--quiet`). 보고 싶으면 `K6_PROGRESS=1`을 준다.
- `MOCK_PG_URL`을 비우면 시나리오가 장애 주입을 건너뛴다. 6단계에서 서버에 건 값이 그대로 쓰인다.

### 8. 측정 중에 같이 볼 것
- Grafana `http://52.63.10.174:3000`에서 `k6_*` 지표를 `testid`로 거르고, 같은 시간의 서비스 지표를 본다.
- 발생기 다른 창에서 `docker stats`로 k6 컨테이너 CPU·네트워크를 본다. `dropped_iterations`가 생기면 발생기 VU 풀이나 대역폭이 모자란 것이라 서버 한계와 섞인다.
- 시나리오별 기록 도구는 서버 SSH 창에서 같은 시간에 돌린다(Q3 `watch-admitted.sh`, X1 `watch-held.sh`, L2·L3 `watch-lottery.sh`).

### 9. 끝내기

| 순서 | 어디서 | 할 일 |
|---|---|---|
| 1 | 노트북 | 결과를 받는다. `scp -r ubuntu@<발생기 IP>:junhyun/iseonjwa/loadtest/k6/results ./loadgen-results-1111`. 결과는 커밋하지 않는다 |
| 2 | 발생기 | 키를 지운다. `rm ~/junhyun/iseonjwa/.env` |
| 3 | 배포 서버 | `MEASURER=junhyun script/loadtest/measure-mode.sh off`. 운영 키, 샘플링 1.0, 닫힌 9090으로 돌아오고 스모크가 돈다. 켜져 있던 시간과 스모크 결과가 슬랙에 간다. `status`로 꺼졌는지 보고 Jenkins 잡을 다시 켠다. 다음 사람이 바로 이어 써도 끄고 넘긴다 |
| 4 | 문서 | `load-test.md` 7절 측정 환경 기록표를 채운다. 서버 이미지 커밋은 서버의 `~/iseonjwa/.deploy/current`, k6 스크립트 커밋은 발생기의 `git log --oneline -1`이다 |
| 5 | 팀 채널 | 끝 알림. 그날 마지막 측정이면 발생기를 중지(Stop)한다. 팀 마지막 측정 뒤에는 종료(Terminate)하고 8080·9090 규칙과 `iseonjwa-loadgen-sg`를 지운다 |

## 지금 배포에서 돌릴 수 있는 시나리오

| 돈다 | 아직 못 돈다(setup에서 관리자 로그인) |
|---|---|
| Q1, Q1-d, Q2, Q3(`SCHEDULE_ID`), L1~L3(`LOTTERY_ID`, ISE-216), S1, B1-d, R1-d | PAY1, R1, B1, G1, X1, O1 |

결제 담당 몫 중 지금 바로 되는 것은 R1-d다. PAY1은 `SCHEDULE_ID`를 받게 바꿔야 배포에서 돈다(ISE-192 검토에서 맡겠다고 적은 일). `loadgen.md` 9절은 L1·L2도 막힌 것으로 적었지만 11/9 ISE-216(#425)으로 풀렸다.

## 주의
- 주소는 사설 IP `172.31.4.93`만 쓴다. 공인 IP `52.63.10.174`로 보내면 같은 서브넷이어도 전송 요금이 붙고 보안 그룹(소스 = 발생기 보안 그룹)에도 막힌다.
- 측정 중 main 머지 금지. 측정 기간(11/10~11/13)에는 팀원 측정이 이어지므로 채널 확인이 먼저다.
- 공용 설치물을 바꾸지 않는다(`docker system prune`, `apt upgrade`, 다른 사람 폴더 삭제).
- 에이전트는 측정 모드 켜고 끄기, 키 옮기기를 대신하지 않는다(`loadgen.md` 8절). 명령만 만든다.

## 출처
- `docs/guides/loadgen.md`, `docs/guides/deploy-server-access.md` 5-5절
- `docs/design/infra/load-test.md` 2-1·5·7절
- `loadtest/k6/README.md` "배포 서버 측정", `loadtest/k6/run.sh`, `script/loadtest/measure-mode.sh` 머리 주석
