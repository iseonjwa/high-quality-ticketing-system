---
title: 좌석 조회 RATE=3에서 61%가 실패했는데 왜 응답은 빨랐나
date: 2026-11-06
asker: hyos1
level: 기초
tags: [k6, load-test, entry-token, gateway, 403, troubleshooting]
---

# 좌석 조회 RATE=3에서 61%가 실패했는데 왜 응답은 빨랐나

## 질문

좌석 조회를 초당 3건 30초로 돌렸더니 94건 중 55건이 실패했다. 바로 장애가 난 것인가. 35 × 3 = 105건이 나간 것 아닌가. 그런데 응답 시간은 왜 다 짧나.

## 답

장애가 아니라 입장 토큰 만료였다.

- 요청 수: 초당 3건 × 30초 = 좌석 조회 90건 + setup 4건 = 94건.
- 빠른 실패는 앞단에서 막혔다는 신호다. 과부하로 무너지면 느리게 실패한다.

### 확인 순서

1. reservation-service 로그(`RequestLogFilter`): 좌석 조회는 모두 200. 실패는 서비스에 오지 않았다.
2. Prometheus `http_server_requests_seconds_count{status="403"}`: gateway에서 난 403.
3. `gateway/.../security/EntryTokenFilter.java`: 입장 토큰이 유효하지 않으면 서비스를 부르지 않고 403 `ENTRY_TOKEN_INVALID`.
4. 시각: 첫 토큰 발급 07:59:46(RATE=1 실행), RATE=3 실행에서는 이미 입장한 사용자라 새 만료 시각을 받지 못했다. 마지막 성공 08:09:45.9 = 첫 발급 + 10분. `config-repo/reservation-service.yml`의 `entry-token-ttl: 10m`.

같은 사용자로 다시 돌려 측정 도중 토큰이 만료됐다. 입장 후 10분이 지나면 좌석 조회를 막는 정상 동작이다.

### 성공 건만 보기

`http_req_duration`의 `{ expected_response:true }` 줄은 성공 요청만 모은다. 성공 35건은 중앙값 172ms, p95 220ms로 RATE=1과 비슷했다. 전체 줄은 빠른 실패가 섞여 중앙값 7ms로 보였다.

### 고친 방법

`setup()`에서 실행마다 새 사용자를 가입시킨다(`POST /api/v1/users`, 이메일 `k6-${Date.now()}@test.local`). 대기열과 입장 토큰이 매번 새로 시작한다. `check`에 상태 코드 조건을 더해 실패 원인을 요약표에서 바로 보이게 한다.

### 교훈

실패율만 보고 장애라고 판단하지 않는다. 어떤 상태 코드로, 얼마나 빨리 실패했는지, 어느 계층(Gateway인지 서비스인지)에서 났는지부터 본다.

## 출처

- `gateway/src/main/java/com/iseonjwa/gateway/security/EntryTokenFilter.java`
- `config-repo/reservation-service.yml`(`entry-token-ttl: 10m`)
- `reservation-service/.../queue/presentation/QueueController.java`
- 효승 노트북 `C:\Users\hyozz\k6-practice\K6_LOG.md`
