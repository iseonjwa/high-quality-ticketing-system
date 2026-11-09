---
title: Windows 시계를 맞추자 통과한 테스트 4건은 왜 실패했나
date: 2026-11-09
updated: 2026-11-09
asker: KWON-WOO
level: 중급
tags: [test, clock, docker, postgresql, redis]
---

# Windows 시계를 맞추자 통과한 테스트 4건은 왜 실패했나

## 질문
`:reservation-service:test`에서 시각을 비교하는 테스트 4건이 실패했다. Windows 시간을 동기화한 뒤 다시 돌리니 `BUILD SUCCESSFUL`이었다. 기존에 실패했던 이유는 무엇인가?

## 짧은 답
- 테스트 하나 안에서 두 시계를 섞어 썼기 때문이다. 시작 시각은 컨테이너(PostgreSQL `now()`, Redis `TIME`)에서 받고, 비교 기준은 호스트 JVM(`Instant.now()`, 앱 `Clock`)에서 받았다.
- 이 기기의 Windows 시계는 실제 시각보다 약 7초 늦었다. Docker 컨테이너는 Docker Desktop VM의 시계를 쓰는데, 그 시계는 맞았다.
- 두 시계가 7초 어긋나자 "정확히 300초", "10분 이하"처럼 경계에 딱 붙은 검사가 몇 초 차이로 깨졌다.
- 코드 버그가 아니라 실행 환경 문제다. 시계를 맞추자 374건 중 실패가 0건이 됐다(건너뜀 11건).

## 자세히

### 시계가 둘이다
Docker Desktop(Windows)에서 컨테이너는 WSL2 가상 머신 위에서 돈다. 컨테이너 안의 `date`, PostgreSQL `now()`, Redis `TIME`은 모두 이 VM의 시계다. JUnit을 돌리는 JVM은 Windows에서 돌므로 `Instant.now()`는 Windows 시계다. 두 시계는 따로 동기화된다. 한쪽만 틀릴 수 있다.

이번에 비교한 값(같은 순간에 측정):

| 출처 | 시각(UTC) |
|---|---|
| Windows `date` | 07:50:16 |
| 인터넷(`www.google.com`의 `Date` 헤더) | 07:50:23 |
| 테스트 PostgreSQL 컨테이너 `date` | 07:50:23 |

Windows만 7초 늦었다. 처음에는 컨테이너 시계를 의심해 `hwclock -s`로 맞춰 보았지만 차이가 그대로였다. 외부 시각과 비교하고 나서야 Windows 쪽이 틀린 것을 알았다. Windows 시간 서비스(`w32time`)가 멈춰 있어 동기화되지 않던 상태였다.

### 실패한 테스트별로 보면

| 테스트 | 시작 시각(컨테이너) | 비교 기준(호스트) | 결과 |
|---|---|---|---|
| `LotteryPaymentMetricsTest` 결제 성공 집계 | 추첨 시각 = DB `now() - interval '5 minute'` | 결제 반영 시각 = 앱 `Clock` | 300초 이상이어야 하는데 294.19초 |
| `QueueApiTest` 토큰 발급 | 발급 시각 = Redis `TIME`(`issue-token.lua`), 만료 = 발급 + 10분 | `Instant.now()` | 10분 이하여야 하는데 10분 5초 남음 |
| `ReservationApiTest` 만료 10초 전 토큰 거절 | 선점 기한 = DB `now() + interval '5 seconds'` | 앱 `Clock` | 호스트가 보기에 기한이 약 12초 남아 "10초 전" 조건에 들지 않음 |
| `SeatHoldApiTest` 결제 토큰 만료 뒤 재요청 | 선점 기한 = DB `now() + interval '5 second'` | 앱 `Clock` | 위와 같은 이유로 토큰을 다시 줄 수 있다고 판단해 409 대신 201 |

공통점은 한 문장으로 정리된다. **"DB나 Redis가 정한 시각"과 "JVM이 보는 지금"의 차이를 초 단위로 검사하는 테스트**다. 호스트가 늦으면 호스트 입장에서 미래 시각이 더 멀리 있는 것처럼 보이고, 지나간 시간은 짧아 보인다.

### 이번 대사 테스트는 왜 영향을 받지 않았나
`SeatLedgerReconciliationTest`는 시각을 비교하지 않는다. 선점 기한을 넘길 때도 `now() - interval '1 hour'`처럼 크게 뺀다. 기존 `SeatHoldExpiryTest`에도 같은 이유를 적은 주석이 있다(Docker DB 시계가 호스트보다 앞설 수 있어 넉넉히 뺀다). 여유를 크게 두면 몇 초 어긋나도 깨지지 않는다.

### 고치는 방법
1. 환경: 호스트 시계를 맞춘다(아래 명령). 이번에는 이것으로 해결했다.
2. 테스트 설계(선택): 시작 시각과 비교 기준을 같은 시계에서 얻는다. 예를 들어 비교 기준도 `SELECT now()`로 DB에서 받거나, 기한을 DB `now()` 대신 앱 `Clock`으로 계산해 넣는다. 다른 담당의 테스트라 이번에는 고치지 않았다.

```powershell
# 관리자 PowerShell
Start-Service w32time
w32tm /config /manualpeerlist:"time.windows.com,0x9" /syncfromflags:manual /update
w32tm /resync /force
```

## 이 프로젝트에서는
- 시계 확인: `date -u` / `docker exec iseonjwa-test-postgres-reservation-1 date -u` / `curl -sI https://www.google.com | grep -i '^date'`. 세 값이 1초 안이면 정상이다.
- 부하 측정에도 영향이 있다. k6 결과의 시각과 컨테이너 로그·Prometheus 시각을 맞춰 볼 때 기기 시계가 어긋나 있으면 구간이 밀린다. 측정 기기를 옮기면 먼저 시계를 확인한다.
- 팀원 기록에도 같은 현상이 있다: `docs/ai-judgments/youngeun/2026-11-09-q1d-local-limit-and-clock-drift.md`.

## 면접 포인트
- "로컬에서만 가끔 실패하는 시간 테스트"를 보면 시계가 여럿인지 먼저 본다. 컨테이너·DB·애플리케이션 서버의 시계는 다를 수 있다.
- 분산 시스템에서도 같다. 서버마다 시계가 조금씩 다르므로, 만료·순서 판정은 시각을 하나의 출처(DB `now()`, Redis `TIME`)에서 정하고 그 출처끼리만 비교한다.
- 테스트에서는 `Clock`을 주입받아 고정하거나, 경계 값에 몇 초 여유를 두거나, 비교 양쪽을 같은 시계에서 얻는다.

## 참고
- 실행 결과: 동기화 전 실패 4건(위 표의 실패 메시지), 동기화 뒤 `:reservation-service:test` 374건 중 실패 0건, 건너뜀 11건.
- 코드: `reservation-service/src/main/resources/redis/queue/issue-token.lua`(`redis.call('TIME')`), `queue/infrastructure/JwtEntryTokenIssuer.java`, `lottery/application/LotteryPaymentMetricsTest.java` 106행.
- `docs/troubleshooting.md` TS-46
