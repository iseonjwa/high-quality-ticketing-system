---
title: k6는 터미널 말고 화면으로 결과를 볼 수 있나
date: 2026-11-05
asker: hyos1
level: 기초
tags: [k6, load-test, web-dashboard, grafana]
---

# k6는 터미널 말고 화면으로 결과를 볼 수 있나

## 질문

k6는 터미널 전용인가. Workbench처럼 화면으로 보는 도구가 있나.

## 답

테스트 실행은 터미널(`k6 run`)에서 한다. 버튼을 눌러 돌리는 GUI 프로그램은 아니다. 결과는 화면으로 볼 수 있다.

| 방법 | 무엇을 보나 | 언제 |
|---|---|---|
| 웹 대시보드(k6 기본 기능) | 실행 중 초당 요청 수, 응답 시간, 오류를 브라우저 그래프로 본다. HTML 보고서로 저장할 수 있다 | 노트북에서 배우는 단계 |
| Grafana 연결(Prometheus remote write) | k6 지표와 서버 지표(GC, 힙, Hikari)를 한 화면에서 겹쳐 본다 | 팀 공통 측정 기반(`docs/design/infra/load-test.md` 3절) |
| k6 Studio(별도 데스크톱 앱) | 브라우저 사용을 녹화해 스크립트를 만든다. 결과 화면 도구가 아니다 | 지금은 쓰지 않는다 |

### 웹 대시보드 켜기 (PowerShell)

```powershell
$env:K6_WEB_DASHBOARD = "true"
$env:K6_WEB_DASHBOARD_EXPORT = "report.html"   # 선택: 끝날 때 HTML 보고서 저장
k6 run 스크립트.js
```

- 기본 주소는 `http://127.0.0.1:5665`다.
- 테스트가 끝나면 대시보드도 닫힌다. 남기려면 EXPORT로 보고서를 저장한다.
- 10초 테스트는 그래프로 보기에 짧다. 1~2분은 돌린다.
- 결과의 기본은 터미널 요약표다. 요약표를 먼저 읽을 줄 알고 그래프는 흐름(언제부터 느려졌나)을 보는 데 쓴다.

### 팀은 어떻게 보기로 했나

- ADR 0046(2026-11-03)과 `docs/design/infra/load-test.md`: k6 결과를 Prometheus remote write로 보내 서버 지표와 같은 Grafana에서 같은 시간축으로 본다. nGrinder를 고르지 않은 이유 중 하나가 결과가 Grafana와 따로 논다는 점이다.
- 2026-11-05 기준 아직 안 된 것: `infra/prometheus.yml`에 remote write 수신 설정이 없고, 발생기에서 Prometheus로 보내는 경로(9090 개방, SSH 터널 등)도 미정이다.
- 그래서 ISE-79 측정은 k6 요약과 서버 지표를 따로 모아 문서 표로 정리했다. 구간별 분석에는 `--out json`이나 remote write로 요청별 기록이 필요하다고 계획 문서에 적혀 있다.
- 노트북 연습은 웹 대시보드로 보고, 공식 측정은 공통 측정 기반(ISE-181)이 갖춰지면 Grafana로 본다.

### 2026-11-05 저녁 갱신

- 최권우가 `feat/ISE-181-test-load-test-k6`를 올렸다(dev 미머지). ISE-181 담당이 안준현에서 최권우로 바뀌었다. 공통 코드는 `loadtest/k6/lib/`, 시나리오 12개는 `loadtest/k6/scenarios/`, 실행은 `bash loadtest/k6/run.sh scenarios/<파일>`(Docker `grafana/k6:1.0.0`)이다.
- 결과는 아직 파일이다: `results/`에 `--summary-export` 요약 JSON, `--out csv` 요청별 원자료, 화면 출력. remote write 수신은 여전히 없다.
- 배포 서버 Grafana는 `http://52.63.10.174:3000`으로 터널 없이 연다(ISE-180, 팀원 IP만, 익명 보기).
- `open-peak-load-plan.md`의 효승 담당은 Z2 공연 조회, Z6 좌석 조회(ISE-189), Z9 확정 소비자이고 측정은 최권우다. O1 local 실행(200명)에서도 좌석 조회 p95 4.15초로 가장 느렸다.
- local Docker Desktop의 k6 컨테이너는 약 32초마다 3초 멈춘다(최권우 README 4절). 노트북 결과의 3초 근처 최대값은 발생기 탓일 수 있다.

### 각자 스크립트를 만들어 재나

- 효승 구간(B1 공연 조회, K1 좌석 조회·선점, R1 확정)은 최권우가 `loadtest/k6/scenarios/`와 `reservation-service/k6/`에 이미 만들었다.
- 새 시나리오는 README 3절 규칙(`scenarios/`에 두고 `lib/`을 쓰고 판정은 `thresholds`)대로 더할 수 있다.
- 개선 전·후 비교는 같은 스크립트·같은 조건이어야 한다. 그래서 측정은 팀 스크립트(K1)로 하고, 직접 쓰는 작은 스크립트는 k6를 익히는 용도로만 쓴다.
- 직접 설치한 k6는 v2.3.0, 팀 Docker 이미지는 v1.0.0이다. 결과를 적을 때 k6 버전도 함께 적는다.
- 2026-11-05 이강민 답: 오늘은 각자 로컬에서 개선 전 수치를 잰다. 스크립트는 권우 것을 참고해 각자 만드는 중이고 팀 공통 방식은 아직 없다.
- 그래도 좌석 조회는 최권우 P0 수치와 비교해야 하므로 K1을 그대로 쓴다.
- 배포 서버 부하는 노트북에서 주지 않는다(준현 공지: 노트북은 스크립트 확인 정도, 서버 측정은 발생기 EC2). 서버 측정은 측정 담당(최권우)·서버 담당(안준현)과 맞춘다.

### 직접 설치한 k6와 Docker의 역할

- k6.exe(직접 설치)는 부하를 거는 쪽이다. PowerShell에서 `k6 run first.js`로 바로 돌고 `http://localhost:8080`(Gateway)을 부른다.
- Docker 컨테이너는 부하를 받는 쪽(Gateway, reservation-service, DB 등)이다.
- 팀 `run.sh`는 k6까지 컨테이너(`grafana/k6:1.0.0`)로 돌린다. 컨테이너 안의 localhost는 자기 자신이라 `host.docker.internal:8080`을 쓴다.

## 출처

- k6 문서 "Web dashboard"(k6 v0.49부터 기본 포함)
- `docs/design/infra/load-test.md` 3절, 90행
- `docs/decisions/0046-load-test-k6.md`
- `docs/design/reservation/k6-seathold-bottleneck-plan.md`(브랜치 `feat/ISE-79-seat-hold-race-condition`)
