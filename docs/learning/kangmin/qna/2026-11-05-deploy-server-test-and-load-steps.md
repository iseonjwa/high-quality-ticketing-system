---
title: 배포 서버에 접속해 통합 테스트와 부하 측정을 어떤 순서로 진행하는가
date: 2026-11-05
updated: 2026-11-05
asker: kangmin
level: 중급
tags: [deployment, ssh, smoke-test, e2e, k6, load-test, grafana]
---

# 배포 서버에 접속해 통합 테스트와 부하 측정을 어떤 순서로 진행하는가

## 질문
배포 서버 접속 안내와 부하 측정 계획을 받았다. 서버 접속, 통합 테스트, 부하 테스트, 측정까지 어떤 순서로 하는가.

## 짧은 답
- 접속 → 관리 화면 터널 → 스모크 테스트 → 추첨 E2E 순서로 확인하고, 부하 측정은 로컬 준비를 거쳐 11/13 이후 발생기 EC2에서 한다.
- `system-tests`는 `127.0.0.1:8080`과 개인 `.env`에 묶여 있어 배포 서버 검증에 쓰지 않는다.
- 부하는 배포 서버와 노트북에서 크게 걸지 않는다. 같은 VPC의 임시 발생기에서 Gateway 사설 주소를 부른다.
- 값을 근거로 쓰려면 먼저 발생기 포화 여부를 확인하고, 환경 기록표를 결과와 함께 남긴다.

## 자세히

### 1. 접속과 관리 화면
```bash
ssh ubuntu@52.63.10.174        # 기본 위치의 개인 키를 쓴다
docker ps                      # 읽기 전용 확인

# 새 터미널에서 켜 둔다. Grafana 3000, Kafka-UI 8085, Prometheus 9090, Alertmanager 9093, Zipkin 9411
ssh -N -L 3000:127.0.0.1:3000 -L 8085:127.0.0.1:8085 -L 9090:127.0.0.1:9090 \
  -L 9093:127.0.0.1:9093 -L 9411:127.0.0.1:9411 ubuntu@52.63.10.174
```
보안 그룹이 등록된 IP의 SSH만 받으므로 접속이 갑자기 안 되면 `curl https://checkip.amazonaws.com` 결과를 서버 담당에게 보낸다. 서버에서는 볼륨 삭제, 직접 배포, `.env` 열기, `authorized_keys` 수정, k6 실행을 하지 않는다.

### 2. 통합 확인 수단 비교

| 수단 | 범위 | 서버 데이터 변경 | 배포 서버 사용 |
|---|---|---|---|
| `script/deploy/smoke.sh <주소>` | 화면, 공개 API, 401 인증 필터 | 없음 | 쓴다 |
| `http/lottery-e2e.http` | 관리자 설정부터 추첨, 자동결제까지 | 공연, 회차, 좌석 15,000석, 사용자 생성 | 팀 채널에 알린 뒤 쓴다 |
| `:system-tests:systemTest` | 전체 스택 API·연동·장애 | 있음 | 쓰지 않는다. 주소가 `127.0.0.1:8080`으로 고정이고 Redis 정지 옵션도 있다 |

`systemTest`를 쓰려고 SSH 터널로 로컬 8080을 서버에 잇는 우회는 하지 않는다. 시험 데이터가 서버에 쌓이고, 토큰 서명에 쓰는 로컬 `.env`의 비밀값은 서버와 맞지 않아야 정상이다. 운영 비밀값을 가져오는 것도 금지다.

추첨 E2E의 `payment.result` → 예매 확정 → 응모 PAID 구간은 `docs/design/lottery/e2e-runbook.md` 기준으로 서비스 통합 테스트까지만 확인했고 compose 스택에서는 미확인이다. 배포 서버 실행의 가치는 이 구간에 있다. `http/http-client.private.env.json`(git 제외)에 `deploy` 환경을 두고 `gateway`를 `http://52.63.10.174`로 설정한다. 관리자 계정은 서버 `.env`를 열지 않고 서버 담당에게 받는다.

### 3. 부하 측정 준비: 왜 발생기를 분리하는가

| 위치 | 장점 | 안 쓰는 이유 |
|---|---|---|
| 배포 서버 | 지연 없음 | 대상과 CPU를 나눠 써 값이 왜곡된다 |
| 노트북 | 무료 | 한국-시드니 왕복 약 0.27초가 결과에 섞인다 |
| 같은 VPC 임시 EC2 | 지연이 작고 일정하다 | 만들고 지우는 수고와 비용을 감수한다 |

K1 스크립트는 2026-11-05 기준 `origin/feat/ISE-79-seat-hold-race-condition`의 `reservation-service/k6/`에 있고 main에는 없다. 로컬 확인은 다음처럼 짧게 한다.

```bash
SCHEDULE_ID=<회차 ID> TOKEN_MODE=sign RATE=10 DURATION_SECONDS=30 bash reservation-service/k6/run.sh
```

`TOKEN_MODE=sign`은 local 전용이다. 배포 환경의 서명 키를 발생기에 두지 않는다.

### 4. 배포 서버 측정 (11/13~11/18 예정)
1. 팀 채널에서 시간을 정하고 main 릴리스를 멈춘다. Jenkins가 main을 2분마다 확인해 배포하기 때문이다.
2. 서버 담당이 발생기를 만들고 배포 서버 보안 그룹에 발생기 보안 그룹의 8080을 연다.
3. 발생기에서 `BASE_URL=http://172.31.4.93:8080`으로 짧게 확인한 뒤 본 측정을 한다.
4. 결과 파일을 내려받고, 발생기와 8080 규칙을 지운다. 발생기는 종료(Terminate)한다.

측정 전 남은 결정은 토큰 준비 방식, `loadtest` 프로필 내용, 샘플링 비율(배포 1.0, `loadtest` 0.01), k6 결과 저장 경로, 배포 서버의 G1 반영이다. 11/5 기준 배포 서버 앱은 Serial GC가 자동 선택된 상태라 G1 측정값과 바로 비교할 수 없다.

### 5. 결과 해석 순서
1. **측정 유효성**: `dropped_iterations`, 발생기 CPU·네트워크를 본다. 발생기가 먼저 막혔으면 서버 한계로 해석하지 않는다.
2. **사용자 체감**: 처리량, p95·p99, 5xx·타임아웃 비율을 본다. 선점 409는 경합의 결과일 수 있어 분리한다.
3. **포화점**: 부하를 단계별로 올려 처리량이 멈추고 p95가 급증하는 지점을 찾는다.
4. **원인**: 같은 시각의 서비스별 CPU·메모리, GC, Hikari 연결 대기, Kafka 랙을 겹쳐 본다.
5. **재현 조건**: `docs/design/infra/load-test.md` 7절 기록표(장비, 힙, 풀, 데이터 규모, 시나리오, 커밋, 샘플링)를 함께 남긴다.

전후 비교는 한 번에 한 조건만 바꾸고 같은 시나리오를 반복해 편차를 함께 적는다.

## 이 프로젝트에서는
- 일정: 11/6 리허설까지 켬, 11/7~11/12 중지, 11/13~11/19 다시 켠 뒤 삭제.
- 접속 정본: `docs/guides/deploy-server-access.md`, 측정 정본: `docs/design/infra/load-test.md`. 둘 다 2026-11-05 main 기준이다.
- 측정 데이터가 리허설에 남지 않게 부하 측정은 11/6 전에 하지 않는다.
- 2026-11-05 시점 문서의 측정 담당 표와 시나리오에는 추첨이 없다. Linear는 확인하지 못했다.
- 이 노트의 명령은 문서와 스크립트를 읽어 정리한 것이고, 작성 시점에 서버 접속과 실행은 하지 않았다.

## 면접 포인트
- 통합 테스트를 왜 운영 환경에 그대로 돌리지 않았는가: 운영 비밀값과 데이터를 지키기 위해 데이터를 바꾸지 않는 스모크와, 합의 후 실행하는 E2E로 나눴다.
- 부하 측정값을 어떻게 믿을 수 있게 하는가: 발생기 분리, 발생기 포화 확인, 환경 기록, 한 번에 한 조건 변경, 반복 측정으로 재현 가능하게 한다.

## 참고
- `docs/guides/deploy-server-access.md`, `docs/design/infra/load-test.md`, `docs/design/lottery/e2e-runbook.md`, `docs/guides/testing.md`
- `script/deploy/smoke.sh`, `http/lottery-e2e.http`, `system-tests/src/test/java/com/iseonjwa/verification/WorkspaceClient.java`
- ADR 0046(`docs/decisions/0046-load-test-k6.md`)
