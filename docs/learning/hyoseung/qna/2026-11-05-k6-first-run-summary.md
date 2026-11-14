---
title: k6 첫 실행 요약표는 어떻게 읽나
date: 2026-11-05
asker: hyos1
level: 기초
tags: [k6, load-test, p95, warmup]
---

# k6 첫 실행 요약표는 어떻게 읽나

## 질문

`first.js`(VU 1명, 10초, `sleep(1)`)로 공연 상세를 불렀다. 한 사람이 1초에 한 번씩 10초 동안 10번 요청하고 결과를 본 것이 맞나. 요약표는 어떻게 읽나.

## 답

거의 맞다. 두 가지를 바로잡는다.

- `GET /api/v1/concerts/2`는 회차가 아니라 공연 상세(회차 목록 포함)다.
- "응답을 받고 1초 쉬고 다시"라서 한 바퀴가 1초보다 길다(`iteration_duration` 평균 1.06초, 초당 0.935건).

### 실제 결과 (2026-11-05, 로컬, k6 v2.3.0)

| 줄 | 값 | 뜻 |
|---|---|---|
| `checks_succeeded` | 100%(10/10) | `check` 조건("200 응답") 통과 비율 |
| `http_req_failed` | 0% | HTTP 실패 비율 |
| `http_reqs` | 10, 0.935/s | 요청 수와 초당 요청 수 |
| `http_req_duration` | avg 62.77ms, med 18.97ms, p95 264.27ms, max 456.61ms | 요청 하나의 시간. 가장 중요한 줄 |
| `iteration_duration` | avg 1.06s | `default` 한 바퀴(요청 + `sleep(1)`) |
| `vus` | 1 | 가짜 사용자 수 |
| `data_received` | 5.2kB | 한 번에 약 520B. 좌석 조회(약 785KB)의 약 1/1,500 |

### 응답 시간 해석

- 중앙값 19ms, 최댓값 456ms: 대부분 빠르고 한두 건만 느렸다. 보통 첫 요청이 느리다(연결 수립, 서버 워밍업). 팀 계획서가 측정 전 30초 워밍업을 두는 이유다.
- 표본 10개의 p95는 느린 1~2건이 그대로 p95가 되므로 믿기 어렵다. 측정은 수백 건 이상으로 한다.
- 평균이 중앙값의 3배다. 느린 값 하나가 평균을 끌어올린다. 그래서 부하 측정은 평균보다 p95·p99를 본다.

## 출처

- 효승 노트북 `C:\Users\hyozz\k6-practice\first.js` 실행 결과
- `docs/design/infra/open-peak-scenarios.md` 1-1절(워밍업), 브랜치 `feat/ISE-181-test-load-test-k6`
