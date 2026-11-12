---
title: 부하 발생기 EC2는 무엇이고, 지금 떠 있는 AWS 구성에 어떻게 더해 쓰는가
date: 2026-11-08
updated: 2026-11-09
asker: kangmin
level: 기초
tags: [aws, ec2, vpc, security-group, load-test, k6, fault-injection]
---

# 부하 발생기 EC2는 무엇이고, 지금 떠 있는 AWS 구성에 어떻게 더해 쓰는가

## 질문
"발생기 EC2"가 장애 발생기를 말하는가? 지금 떠 있는 AWS 구성에 어떻게 더하고 띄워서 쓰는가.

## 짧은 답
- 장애 발생기가 아니라 **부하 발생기(load generator)**다. k6를 돌려 요청을 보내는 임시 EC2다. 장애는 이미 있는 수단(Mock PG 장애 주입 API, 배포 서버에서 컨테이너 멈추기)으로 넣으므로 서버를 더 띄우지 않는다.
- 배포 서버와 같은 VPC·같은 서브넷에 한 대를 띄우고, 배포 서버의 Gateway 사설 주소(8080)를 부른다. 측정 때만 띄우고 끝나면 종료(Terminate)한다.
- AWS 콘솔은 계정 주인(안준현)만 쓰므로 발생기는 계정 주인이 만든다. 절차의 정본은 `docs/design/infra/load-test.md` 2-1절이다.

## 자세히

### 1. 부하 발생기와 장애 주입은 다르다
| 구분 | 하는 일 | 어디서 | 추가 서버 |
|---|---|---|---|
| 부하 발생기 | k6로 초당 N건의 요청을 보낸다 | 임시 발생기 EC2 | 필요 |
| 장애 주입 | Mock PG를 지연·거절·응답 유실 모드로 바꾸거나, 결제 서버·릴레이를 멈춘다 | Mock PG 관리 API(`{"target":"APPROVE","mode":"DECLINE","count":1}` 같은 요청), 배포 서버 SSH에서 컨테이너 조작 | 불필요 |

둘을 함께 쓰면 "부하가 걸린 상태에서 장애가 나면 어떻게 되나"를 잰다.

### 2. 지금 구성과 더할 것
```
[VPC vpc-08032543fc446b5c7, 서브넷 ap-southeast-2a]
  배포 EC2 m7i-flex.xlarge (사설 172.31.4.93)   ← 앱 8개 + DB·Redis·Kafka·관측 도구
     ├ 80  : 전체 공개(프론트 nginx)
     ├ 22  : 팀원 IP, Jenkins 보안 그룹
     └ 8080: 평소 닫힘 → 측정 중에만 발생기 보안 그룹에 연다
  Jenkins EC2 t3.small (사설 172.31.38.150)    ← main 커밋을 2분마다 확인해 배포
  (추가) 발생기 EC2 c6i.xlarge                  ← k6, 측정 때만
     └ 22: 팀원 IP
```

| 설정 | 값 | 이유 |
|---|---|---|
| 유형 | `c6i.xlarge`(4 vCPU, 시간당 약 0.222달러). 응답이 작은 시나리오는 `c6i.large` | 좌석 조회처럼 응답이 큰 시나리오는 `c6i.large`의 네트워크가 먼저 막힌다. 발생기가 먼저 막히면 서버 한계와 구분할 수 없다 |
| 네트워크 | 배포 서버와 같은 VPC·서브넷 | 같은 가용 영역의 사설 IP 통신은 무료이고 지연이 가장 작다 |
| 보안 그룹 | 새 `iseonjwa-loadgen-sg`(22만 팀원 IP) | 발생기 자체를 외부에 열 이유가 없다 |
| 배포 서버 보안 그룹 | 8080 인바운드, 소스 = `iseonjwa-loadgen-sg` | IP가 아니라 보안 그룹을 소스로 두면 발생기를 다시 만들어도 규칙을 바꾸지 않는다 |

### 3. 왜 따로 띄우나
| 선택지 | 문제 |
|---|---|
| 배포 서버에서 k6 실행 | k6가 서버 CPU를 나눠 써서 측정값이 왜곡된다 |
| 노트북에서 실행 | 인터넷 구간의 지연과 집 회선 대역폭이 섞인다. 8080을 인터넷에 열어야 한다 |
| 같은 VPC 발생기 | 비용이 들지만(4시간 약 1달러) 서버 한계만 잴 수 있다 |

### 4. 순서
1. 측정 시간을 정하고 계정 주인에게 알린다. 계정 주인이 30분 전에 발생기를 만들고 8080 규칙을 더한다.
2. 발생기 준비: `script/deploy/install-docker.sh`, 저장소 clone, `docker pull grafana/k6:1.0.0`.
3. 짧은 확인 실행 → 본 측정. `BASE_URL=http://172.31.4.93:8080`만 바꾼다. 발생기의 CPU·네트워크와 k6 `dropped_iterations`를 함께 기록한다.
4. 측정 중에는 main에 머지하지 않는다. Jenkins가 새 커밋을 배포해 측정 도중 서버가 바뀐다.
5. 결과 파일을 내려받고 발생기를 종료한다. 8080 규칙과 발생기 보안 그룹도 지운다.

### 5. 11/9 갱신: ISE-191 측정 모드가 들어온 뒤 준비물
ISE-191(PR `#380`)로 배포 측정 방식이 정해졌다. 정본은 `docs/design/infra/load-test.md` 2-1·5절, `loadtest/k6/README.md` "배포 서버 측정"이다.

| 누가 | 준비 |
|---|---|
| 계정 주인 | 발생기 EC2 생성(`c6i.xlarge`, 같은 서브넷 `ap-southeast-2a`, 키 `iseonjwa-deploy`, 새 `iseonjwa-loadgen-sg`), 배포 서버 보안 그룹에 8080·**9090**(remote write) 인바운드 추가, 측정 모드 `script/loadtest/measure-mode.sh on`·`off` |
| 측정 담당 | 발생기에 Docker·저장소·`grafana/k6:1.0.0` 준비, 서버 `.env.measure`를 발생기 `.env`로 옮기기(권한 600), `BASE_URL`·`MOCK_PG_URL=`(비움)·`PROM_RW_URL` 설정, Mock PG 장애는 서버 안에서 curl 컨테이너로 걸기 |
| 팀 | 측정 동안 main 머지 멈춤(Jenkins가 배포하면 측정 모드가 풀린다) |

추첨 시나리오에 남은 막힌 곳(코드로 확인):
- L1·L2는 `setup()`에서 `adminLogin()`으로 회차·추첨을 직접 만든다(`l1-lottery-entry.js`, `l2-lottery-billing.js`). 운영 관리자 비밀번호는 발생기에 두지 않는 규칙이라 배포에서는 그대로 못 돈다. Q1~Q3처럼 서버에서 회차·추첨을 만들고 ID를 넘기게 고치거나, 측정 동안만 쓰는 관리자 계정을 둘지 정해야 한다.
- L2는 응모자의 빌링키를 API(`POST /api/v1/payments/billing-keys`)로 직접 등록한다. 그래서 `seed-demo.sh`의 시연 카드 재적재와는 별개다.
- L2의 PG 지연 300ms와 거절 5%(`DECLINE_RATE`)는 `MOCK_PG_URL`을 비우면 건너뛴다. 서버에서 직접 걸어야 한다.

## 이 프로젝트에서는
- 추첨 측정(L1 응모 몰림, L2 자동결제)은 이 발생기에서 돌린다. 장애 주입(F1)은 발생기 없이 배포 서버 SSH와 Mock PG API로 한다.
- 결제 서버를 멈추거나 outbox 릴레이를 세우는 구체적 방법은 F1 준비 때 확인한다. 이 노트 시점에는 확인하지 못했다.

## 면접 포인트
- 부하 도구를 측정 대상과 같은 장비에서 돌리면 자원을 나눠 쓰고, 인터넷을 거치면 네트워크가 섞인다. 같은 가용 영역의 별도 장비가 서버 한계만 재는 방법이다.
- 발생기가 먼저 포화되지 않았다는 근거(CPU, 대역폭, `dropped_iterations`)를 결과와 함께 남겨야 측정값을 믿을 수 있다.

## 참고
- `docs/design/infra/load-test.md` 2-1절, `docs/design/infra/deployment.md` 3절
- `docs/guides/deploy-server-access.md` 5절
- `docs/learning/kangmin/qna/2026-11-05-load-test-ec2-cost.md`
