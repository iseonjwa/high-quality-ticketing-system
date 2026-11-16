# 성능 측정

부하 측정 그래프, 개선 전후 비교 수치(동기형·접수형 선점 비교 등), Grafana·k6 결과 캡처를 둔다.

수치는 측정 조건이 없으면 비교할 수 없다. 그래프마다 표의 `조건` 칸에 측정 날짜, 서버 크기, 시나리오 ID(예: [open-peak-scenarios](../../design/infra/open-peak-scenarios.md)의 G1), 부하 모양(VU·도착률), 원본 결과 링크를 적는다.

정본:
- 측정 도구와 환경: [`design/infra/load-test.md`](../../design/infra/load-test.md), ADR [0046](../../decisions/0046-load-test-k6.md)
- 오픈 피크 측정 계획과 시나리오: [`design/infra/open-peak-load-plan.md`](../../design/infra/open-peak-load-plan.md), [`open-peak-scenarios.md`](../../design/infra/open-peak-scenarios.md)
- 좌석 선점 동시성 결과: [`design/reservation/concurrency-results.md`](../../design/reservation/concurrency-results.md)
- 추첨 측정: [`design/lottery/numbers-and-measurement.md`](../../design/lottery/numbers-and-measurement.md)
- 실행 기록: [`test-scenarios/runs/`](../../test-scenarios/README.md)

| 날짜 | 파일 | 내용 | 조건 |
|---|---|---|---|
| 2026-11-16 | [2026-11-16-seat-query-one-page.md](2026-11-16-seat-query-one-page.md) | 좌석 조회 개선 발표 한 장(효승). 단계별 실패 0 최대 초당 5 → 40 → 100 → 200, 단계마다 막힌 곳과 다음을 고른 이유, 남은 한계, 예상 질문 3개 | 배포 서버 m7i.xlarge, S1 좌석 조회만 30초 × 3회, 회차 104(15,000석, 약 820KB), 상한 끔, 풀 5, 측정 전 ANALYZE, 11/12·11/14 |
| 2026-11-14 | [2026-11-14-gateway-overload-one-page.md](2026-11-14-gateway-overload-one-page.md) | Gateway 과부하 개선 발표 한 장. 상한 64·거절 앞당김(H1)·요청 로그 줄임(H2) 전·후 표, 개선 내역 3줄, 예상 질문 3개. 원천은 [11/12 발표 소스](../2026-11-12-gateway-overload-source.md) | 배포 서버 m7i.xlarge, G1 순번 조회 1,000~2,500/s 단계별 30초, 2026-11-11 6차 L500·L64(`8b95fe68`)·7차 H1·H2(`1e9b1c75`), 회차당 1회 |
| 2026-11-13 | [2026-11-13-safety-net-limit-source.md](2026-11-13-safety-net-limit-source.md), 그림 `2026-11-13-safety-net-limit-summary`·`-inflight`(.svg·.png, 원본 `-charts.py`) | Gateway 동시 처리 상한 64·256·512·끔을 O1 섞인 흐름으로 비교. 장면 4개, 예상 질문, 숫자 사용 주의. remote write 카디널리티로 Prometheus OOM 난 일 | 배포 서버 m7i.xlarge, O1 15,000명·VU 500·입장 50/s·PG 300ms, 값마다 1회(512는 2회), main 43015207 |

## 미리보기

![상한 값별 Gateway 처리 중 수와 메모리](2026-11-13-safety-net-limit-summary.png)

![오픈 뒤 Gateway 처리 중 요청 수](2026-11-13-safety-net-limit-inflight.png)
