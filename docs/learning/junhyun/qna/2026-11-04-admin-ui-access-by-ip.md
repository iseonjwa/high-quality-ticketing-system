---
title: 배포 서버의 관리 화면(Grafana 등)을 IP 주소로 바로 열 수 있나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [security-group, ssh-tunnel, grafana, jenkins, deployment]
---

# 배포 서버의 관리 화면(Grafana 등)을 IP 주소로 바로 열 수 있나

## 질문
`http://52.63.10.174/`로 서비스 화면에 들어가는 것처럼, 관리 화면도 IP 주소로 들어갈 수 있나.

## 짧은 답
지금은 열 수 없다. 설계상 막아 두었다. 관리 화면은 서버 안의 `127.0.0.1`(서버 자신)에만 묶여 있다. 보안 그룹도 그 포트를 열지 않았다. 그래서 SSH 터널로만 들어간다. 바깥에 열 수는 있지만 로그인이 있는 화면(Grafana, Jenkins)만, 팀원 IP에만 여는 것이 맞다. 로그인이 없는 화면(Prometheus, Alertmanager, Kafka-UI, Zipkin)은 열지 않는다.

## 자세히

### 1. 화면이 바깥에서 보이려면 두 문이 열려야 한다
- **컨테이너 포트를 어디에 묶나(compose `ports`)**
  - `"80:80"`은 서버의 모든 주소(0.0.0.0)에 묶는다. 바깥에서 오는 연결도 받는다.
  - `"127.0.0.1:3000:3000"`은 서버 자신에게만 묶는다. 서버 안에서만 닿고, 보안 그룹을 열어도 바깥에서는 닿지 않는다.
- **AWS 보안 그룹 인바운드**: 서버 앞의 방화벽이다. 규칙에 없는 포트와 출발지는 서버에 닿기 전에 막힌다.
- 2026-11-04 배포 서버 상태:

  | 화면 | 묶인 곳 | 보안 그룹 | 로그인 |
  |---|---|---|---|
  | 서비스(frontend nginx) 80 | 0.0.0.0 | 전체 허용 | 서비스 로그인 |
  | Gateway 8080 | 0.0.0.0 | 허용 없음(부하 측정 때만) | 서비스 토큰 |
  | Grafana 3000 | 127.0.0.1 | 허용 없음 | 있음(admin, 가입 꺼짐) |
  | Kafka-UI 8085 | 127.0.0.1 | 허용 없음 | 없음(읽기 전용) |
  | Prometheus 9090 | 127.0.0.1 | 허용 없음 | 없음 |
  | Alertmanager 9093 | 127.0.0.1 | 허용 없음 | 없음(알림 끄기·silence를 누구나 만든다) |
  | Zipkin 9411 | 127.0.0.1 | 허용 없음 | 없음 |
  | Jenkins 8080(Jenkins 서버) | 127.0.0.1 | 허용 없음 | 있음(admin) |

### 2. SSH 터널
- `ssh -L 내컴퓨터포트:서버안주소:서버안포트 ubuntu@서버`는 SSH 연결 안에 통로를 하나 만든다. 내 컴퓨터의 포트로 들어온 요청이 그 통로로 서버에 가서, 서버 안의 `127.0.0.1:포트`로 전달된다.
- 그래서 바깥에는 22(SSH)만 열려 있어도 관리 화면을 쓸 수 있다. SSH는 키 인증이라 키가 없는 사람은 들어오지 못한다.
- 한 번에 여러 화면을 연다.
  ```bash
  ssh -i ~/.ssh/iseonjwa-deploy.pem -N \
    -L 3000:127.0.0.1:3000 -L 8085:127.0.0.1:8085 -L 9090:127.0.0.1:9090 \
    -L 9093:127.0.0.1:9093 -L 9411:127.0.0.1:9411 \
    ubuntu@52.63.10.174
  # 그다음 브라우저에서 http://localhost:3000 (Grafana) 등
  ```
- Jenkins는 서버가 따로라 `ssh -i <키> -N -L 8081:127.0.0.1:8080 ubuntu@3.24.208.227` 뒤 `http://localhost:8081`로 연다.

### 3. IP로 열 때의 대가
- **인증 없는 화면은 주소만 알면 누구나 본다.** 인터넷의 스캐너가 공인 IP의 열린 포트를 몇 분 안에 찾는다. 지표, 트레이스, Kafka 메시지(예매·결제 이벤트)가 그대로 보이고, Alertmanager에서는 알림을 끌 수 있다.
- **로그인이 있는 화면도 HTTP면 비밀번호가 평문으로 오간다.** 이 서버는 HTTPS(도메인·인증서)가 없다. 같은 와이파이의 다른 사람이 볼 수 있다.
- **IP 제한은 출발지 IP가 바뀌면 다시 고쳐야 한다.** 집, 카페, 부트캠프 장소마다 공인 IP가 다르다. 보안 그룹 규칙을 그때마다 고친다.
- 그래서 연다면 "로그인이 있는 화면 + 팀원 IP(`/32`)만"으로 범위를 좁힌다.

## 이 프로젝트에서는
- 설계: `docs/design/infra/deployment.md` 3절 표다. 공개 진입점은 nginx 80 하나이고, 관리 포트(3000, 8085, 9090, 9411, 8761)는 "허용 없음, SSH 터널로만"이다. Jenkins UI도 터널로만 연다(ADR 0044, SA 9-2).
- 바꾸려면 두 곳을 함께 고친다.
  - `compose.deploy.yml`에서 Grafana 포트를 `!override`로 `"3000:3000"`으로 바꾼다.
  - 보안 그룹 `iseonjwa-deploy-sg`에 3000을 팀원 IP로만 더한다.
  - 설계를 바꾸는 것이니 `deployment.md` 표와 결정 기록도 같이 고친다.
- Grafana 하나로 Prometheus 지표, Loki 로그, Zipkin 트레이스를 모두 볼 수 있다(데이터 소스). 시연에 필요한 화면은 대부분 Grafana에 있다.

## 면접 포인트
- 관리 화면은 공개망에 두지 않고, 키 인증 SSH 하나만 열어 그 위로 터널을 뚫어 쓴다. 공격받을 수 있는 면을 포트 하나로 줄인다.
- 컨테이너 포트를 `127.0.0.1`에 묶는 것과 방화벽(보안 그룹)은 서로 다른 겹이다. 한쪽 설정이 실수로 풀려도 다른 쪽이 막는다(심층 방어).

## 참고
- [Control traffic to your AWS resources using security groups (AWS)](https://docs.aws.amazon.com/vpc/latest/userguide/vpc-security-groups.html)
- [Networking in Compose: ports (Docker)](https://docs.docker.com/reference/compose-file/services/#ports)
- [ssh(1) -L 옵션 (OpenBSD manual)](https://man.openbsd.org/ssh#L)
