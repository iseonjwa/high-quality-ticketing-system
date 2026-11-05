---
title: AWS 지출 한도(spend limit)에 걸려 프로젝트가 멈추면 어떻게 하나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [aws, spend-limit, billing, ec2, deployment]
---

# AWS 지출 한도(spend limit)에 걸려 프로젝트가 멈추면 어떻게 하나

## 질문
배포 서버를 띄운 첫날 "비용 때문에 멈췄다"는 연락이 왔다. 한도를 늘려야 하나.

## 짧은 답
늘려야 한다. 지출 한도는 "이 프로젝트에 한 달에 낼 최대 금액"이다. 실제 비용이 한도에 닿거나, 켜 둔 조기 제어가 한도에 곧 닿을 것으로 예상하면 AWS가 리소스를 멈춘다. 이번 배포 계획의 11월 비용은 약 65~70달러다. 한도는 100달러로 올리고, 시연 중 서버를 멈출 수 있는 조기 제어 "Pause top cost drivers"는 끈다.

## 자세히

### 1. 지출 한도란
- 새 가입 방식(Builder ID) 계정의 Paid plan에서만 건다. 프로젝트마다 따로 걸고, 단위는 월이다.
- 세전 비용 기준이다. **크레딧은 빼고 센다.** 크레딧이 남아 있어도 사용량이 한도에 닿으면 멈춘다.
- 최솟값은 20달러와 "보수적 예상 지출" 중 큰 값이다. 이미 켜 둔 리소스가 많으면 최솟값도 올라간다.
- 알림은 두 가지 경우에 온다. 실제 비용이 한도의 50%, 75%, 90%에 닿았을 때, 그리고 10일 안에 한도에 닿을 것으로 보일 때다.

### 2. 선택해서 켜는 조기 제어
한도에 닿기 전에 단계적으로 막는다. 한도에 실제로 닿기 전이라도 "이 속도면 닿는다"는 예상만으로 동작한다.

| 제어 | 언제 | 하는 일 |
|---|---|---|
| Stop new resource launches | 한도까지 약 7일 남았을 때 | 새 리소스 생성을 막는다(AWS 관리 SCP). 떠 있는 것은 그대로다 |
| Pause idle resources | 약 5일 | 14일 동안 CPU 최고치 5% 미만이고 네트워크가 하루 5MB 미만인 EC2 등을 멈춘다 |
| Pause top cost drivers | 약 4일 | 비용이 가장 큰 EC2·RDS·Lambda·Bedrock·SageMaker를 멈춘다. 시연 서버가 멈출 수 있다 |

### 3. 한도에 닿으면
- 프로젝트가 일시 중지되고 모든 리소스가 멈춘다. 데이터(EBS 디스크)는 남는다.
- 다시 켜려면 AWS Settings → Billing에서 한도를 올린다. 그다음 일부 리소스는 직접 다시 시작해야 할 수 있다(EC2 콘솔에서 인스턴스 시작).
- **멈춘 뒤 90일 동안 아무것도 하지 않으면 프로젝트 데이터를 영구 삭제한다.**
- 멈춰 있어도 EBS 디스크와 탄력적 IP 요금은 계속 나간다.

### 4. EC2를 멈췄다 다시 켜면 바뀌는 것
- 탄력적 IP를 붙인 서버는 공인 IP가 그대로다. 사설 IP도 그대로다.
- 탄력적 IP가 없는 서버는 공인 IP가 새로 바뀐다. 여기서는 Jenkins 서버가 그렇다.
- 컨테이너는 `restart: unless-stopped`라 Docker가 부팅 때 다시 띄운다. 디스크의 DB 데이터도 남는다.

## 이 프로젝트에서는
- 2026-11-04 17:10쯤 배포 서버와 Jenkins 서버가 모두 응답하지 않게 됐다. 몇 분 전까지 화면과 API는 200이었고, 뒤에는 사이트·SSH 모두 시간 초과였다.
- 11월 예상 비용은 시드니 온디맨드 기준이다([요금 노트](2026-11-04-deploy-cost-and-single-host-db.md), ADR 0047).
  - 11/4~11/6 켬: 약 20달러(하루 약 6.8달러)
  - 11/7~11/12 중지: 약 2달러(디스크·탄력적 IP)
  - 11/13~11/19 켬: 약 48달러
  - 합계 약 70달러다. 부하 발생기 EC2(ISE-181)를 몇 시간 띄우는 비용이 조금 더해진다.
- 그래서 한도는 100달러로 올린다. 시연 서버가 예상만으로 멈추지 않게 "Pause top cost drivers"는 끄고, "Stop new resource launches"는 부하 발생기를 띄울 때 막히지 않는지 본다.
- 11/7~11/12의 중지는 한도에 맡기지 않고 EC2 콘솔에서 직접 "인스턴스 중지"를 한다.
- 다시 켠 뒤 확인할 것:
  - EC2 두 대가 "실행 중"인지
  - Jenkins 서버의 새 공인 IP(SSH용)
  - 배포 서버의 컨테이너 상태와 `script/deploy/smoke.sh`
  - Jenkins 폴링(`DEPLOY_HOST`는 사설 IP라 그대로 맞다)

## 면접 포인트
- 클라우드 비용 통제는 알림(Budgets)과 강제 중지(spend limit)가 다르다. 강제 중지는 비용을 확실히 막는 대신 서비스가 멈춘다. 시연·운영 기간에는 한도를 예상치보다 넉넉히 잡고, 예상만으로 멈추는 제어는 끈다.
- 탄력적 IP가 없는 인스턴스는 중지·시작 때 공인 IP가 바뀐다. 다른 서버가 이 서버에 붙을 때는 사설 IP를 쓰면 영향이 없다.

## 참고
- [Create a spend limit in AWS Settings (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/create-spend-limit.html)
- [Update a spend limit in AWS Settings (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/update-spend-limit.html)
- [AWS reimagines the getting started experience (AWS News Blog)](https://aws.amazon.com/blogs/aws/aws-reimagines-the-getting-started-experience/)
