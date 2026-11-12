---
title: 선점만 재는 K1-H 측정에서 Gateway 서킷 브레이커가 왜 문제가 되는가
date: 2026-11-07
updated: 2026-11-07
asker: KWON-WOO
level: 중급
tags: [performance, k6, gateway, circuit-breaker, resilience4j, measurement]
---

# 선점만 재는 K1-H 측정에서 Gateway 서킷 브레이커가 왜 문제가 되는가

## 질문
K1-H는 무슨 작업인데 서킷브레이커를 묻는거야?

맥락: base 단계 K1-H 스트레스(초당 50→800명)를 재자 100명부터 Gateway가 503을 냈다. 다음 진행 방식을 고르기 전에 K1-H가 무엇이고 서킷 브레이커가 왜 걸리는지 물었다.

## 짧은 답
- K1-H는 좌석 선점 경로만 떼어 재는 k6 시나리오다. 좌석 조회는 측정 전에 한 번만 하고, 측정 중에는 선점(`POST /holds`)만 보낸다. 목적은 S·P1·P3 같은 선점 쪽 개선의 효과를 숫자로 보는 것이다.
- 모든 요청은 Gateway를 지난다. Gateway는 요청마다 Redis에서 로그아웃 토큰인지 확인하고, 이 호출에 서킷 브레이커 `auth-redis`를 건다.
- 초당 100명부터 Gateway CPU가 차서 이 Redis 호출이 50ms를 넘는 "느린 호출"로 세어졌다. 브레이커가 열리자 인증이 필요한 요청이 모두 즉시 503이 됐다. 이때 reservation-service는 한가했다.
- 그래서 K1-H는 선점 경로가 아니라 Gateway 브레이커의 한계를 재게 됐다. 측정 목적을 지키려면 이 브레이커를 어떻게 다룰지 정해야 한다. 그래서 물었다.

## 자세히

### 1. K1-H가 하는 일
K1은 사용자 흐름 전체(좌석 조회 → 고민 → 선점)를 흉내 낸다. 좌석 조회가 15,000석, 약 830KB라서 K1 결과는 거의 좌석 조회 한계로 정해진다. K1-H(`SEAT_MAP=once`)는 좌석 목록을 `setup`에서 한 번 받아 두고, 반복에서는 그 목록에서 골라 선점만 보낸다. 계획 순서는 base → (S3) → S1·S2·S4 → P1 → P3 → (P2) → (P5)다. 자세한 비교는 [K1과 K1-H 차이](2026-11-05-k1-vs-hold-only-scenario.md)에 있다.

### 2. 요청이 지나는 길
```text
k6 → Gateway [JWT 검증 → Redis 블랙리스트 확인(서킷 브레이커 auth-redis)] → reservation-service [선점 트랜잭션]
```
`GatewayAuthenticationFilter`는 인증이 필요한 요청마다 `redis.hasKey("blacklist:" + jti)`를 100ms 시한으로 부르고, 이 호출을 `redisBreaker.run(...)`으로 감싼다. 브레이커가 열려 있거나 호출이 실패하면 `503 SERVICE_UNAVAILABLE "일시적으로 인증을 확인할 수 없습니다"`로 바로 끝낸다.

### 3. 브레이커 설정과 열린 이유
`config-repo/gateway.yml`의 `resilience4j.circuitbreaker.instances.auth-redis`:

| 설정 | 값 | 뜻 |
|---|---|---|
| `sliding-window-size` / `minimum-number-of-calls` | 20 / 20 | 최근 20번 호출로 판단한다 |
| `slow-call-duration-threshold` | 50ms | 50ms를 넘으면 느린 호출로 센다 |
| `slow-call-rate-threshold` | 50 | 느린 호출이 절반 이상이면 연다 |
| `wait-duration-in-open-state` | 10s | 연 뒤 10초 동안 모든 호출을 거절한다 |
| `permitted-number-of-calls-in-half-open-state` | 5 | 10초 뒤 5번 시험해 닫을지 정한다 |

base H 실측에서는 Gateway CPU가 100~180%까지 올랐고, 느린 호출 비율이 90~100%였다. Redis CPU는 10% 아래였다. Redis가 느린 것이 아니라 Gateway의 이벤트 루프가 바빠 응답을 늦게 처리했고, 그 대기까지 호출 시간에 들어가 50ms를 넘은 것으로 본다. 창이 20번뿐이라 순간적인 지연에도 쉽게 열리고, 열리면 10초 동안 거절되는 요청이 쌓인다. 거절된 호출은 약 6만 1천 건이었다.

### 4. 왜 K1에서는 안 보였나
K1은 reservation-service가 GC 정지와 커넥션 풀 5개 때문에 초당 5~10명에서 먼저 무너졌다. Gateway를 지나는 요청이 적어 브레이커까지 갈 일이 없었다. K1-H는 무거운 좌석 조회를 뺐기 때문에 처음으로 Gateway에 초당 수백 건이 몰렸고, 그래서 Gateway가 먼저 드러났다. 병목 하나를 떼면 그 앞이나 뒤의 다른 병목이 드러난다.

### 5. 왜 사용자에게 묻는가
- **측정 목적과 어긋난다.** K1-H는 선점 경로 개선을 재려고 만들었다. 브레이커가 먼저 막으면 S·P1·P3을 넣어도 "503 → 503"이라 차이가 보이지 않는다.
- **담당 영역 밖이다.** Gateway 인증 필터와 `gateway.yml`은 다른 팀원 담당이다. 실제 설정을 바꾸려면 contract 이슈로 알리고 합의해야 한다.
- **측정 원칙과 부딪힌다.** "직면한 병목을 하나씩 고친다"는 원칙대로라면 이것도 단계다. 그런데 고칠 대상이 내 코드가 아니다.

그래서 선택지가 넷이다. 측정 브랜치에서만 기준을 완화해 측정 환경으로 기록하기(추천), 단계로 넣고 반영은 Gateway 담당에게 맡기기, K1-H만 Gateway 우회하기, 그대로 기록하기.

## 이 프로젝트에서는
- 브레이커 설정: `config-repo/gateway.yml`(`auth-redis`)
- 호출부: `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`의 `isRevoked`
- 측정 기록: `experiment/ISE-79-k6-steps` 브랜치의 `reservation-service/k6/STEPS.md` base 절

## 면접 포인트
- "서킷 브레이커의 느린 호출 기준이 호출하는 쪽 부하에 영향을 받는 이유는?" 호출 시간은 호출하는 쪽에서 잰다. 그래서 호출하는 쪽 스레드나 이벤트 루프가 밀리면 대상이 멀쩡해도 느린 호출로 세어진다. 기준을 정할 때 대상의 응답 시간뿐 아니라 호출하는 쪽이 포화될 때의 지연도 봐야 한다.
- "부하 테스트에서 병목 하나를 떼었더니 다른 곳이 먼저 막혔다면?" 측정하려는 경로가 무엇인지 정하고, 앞단 병목은 측정 환경으로 따로 기록하거나 담당에게 넘긴다. 결과를 해석할 때 그 조건을 같이 적는다.

## 참고
- base H 실측: 50명 오류 0(p95 46ms), 100명 503 10%, 800명 97%, 잠금 대기 0
- [Resilience4j CircuitBreaker 문서](https://resilience4j.readme.io/docs/circuitbreaker)
