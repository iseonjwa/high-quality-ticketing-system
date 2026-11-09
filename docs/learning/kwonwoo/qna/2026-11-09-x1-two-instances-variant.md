---
title: 선점 만료 시나리오 X1의 "reservation-service 2대" 변형은 무엇을 보려는 것인가
date: 2026-11-09
updated: 2026-11-09
asker: KWON-WOO
level: 중급
tags: [k6, scheduler, concurrency, skip-locked, load-test]
---

# 선점 만료 시나리오 X1의 "reservation-service 2대" 변형은 무엇을 보려는 것인가

## 질문
오픈 피크 시나리오 명세(`docs/design/infra/open-peak-scenarios.md`) X1에 있는 "변형: reservation-service 2대(헛수고 확인, S4 전후)"의 "2대"는 무엇인가?

## 짧은 답
- reservation-service를 두 개 띄워서, 두 서버의 만료 스케줄러가 동시에 돌 때 생기는 헛수고(wasted work)를 보자는 변형이다.
- 만료 스케줄러는 서버마다 하나씩 돈다. 두 대면 두 스케줄러가 같은 "만료 대상 100건" 목록을 함께 읽는다. 한쪽이 먼저 처리하면 다른 쪽은 그 행의 잠금을 기다렸다가 0행 갱신으로 끝난다. 정합성은 맞지만 시간과 커넥션을 헛되이 쓴다.
- S4(`FOR UPDATE SKIP LOCKED`로 대상을 한 건씩 집기)가 이 헛수고를 없애는 개선이다. "S4 전후"는 그 효과를 k6 부하 아래에서 보자는 뜻이다.
- 같은 질문은 이미 JUnit 동시성 시나리오 C6(스케줄러 두 인스턴스)으로 쟀다. 헛수고 100 → 0, 소요 1,366ms → 733ms였다. 그래서 X1에서 2대를 따로 재는 것은 빼도 된다는 것이 AI의 의견이다.

## 자세히

### 왜 두 대에서만 생기나
만료 스케줄러(`SeatHoldExpiryScheduler`)는 5초마다 기한이 지난 HELD 예매 ID를 최대 100건 조회한 뒤 한 건씩 만료한다. 서버가 한 대면 스케줄러가 하나라 겹칠 일이 없다(`fixedDelay`라 한 서버 안에서는 주기도 겹치지 않는다).

두 대면 이렇게 된다.

```text
서버 A: 대상 조회 → [1, 2, 3, ...] → 1번 예매 행 잠금 → 만료 → 커밋
서버 B: 대상 조회 → [1, 2, 3, ...] → 1번 예매 행 잠금 대기 ... → 조건부 UPDATE 0행(이미 EXPIRED) → 다음 건
```

조건부 UPDATE(`status = 'HELD'`일 때만) 덕분에 좌석·매수를 두 번 돌려주지는 않는다. 정합성 문제는 없고 비용 문제다. B는 A가 끝낼 때까지 잠금을 기다리며 커넥션을 쥐고 있다가 아무것도 하지 못하고 끝난다.

### S4가 바꾸는 것
S4는 대상을 건별 트랜잭션 안에서 `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`로 집는다. 다른 서버가 잠근 행은 기다리지 않고 건너뛰므로, 두 서버가 서로 다른 행을 나눠 처리한다. S4는 ISE-79 브랜치(`feat/ISE-79-seat-hold-race-condition`)에 커밋돼 있고 아직 dev에는 없다.

| 측정(JUnit C6, 만료 100건, 스케줄러 2개) | S0~S3 | S4 |
|---|---|---|
| 헛수고(집었지만 만료하지 않은 시도) | 100 | 0 |
| 소요 | 1,191~1,420ms | 733ms |

출처: ISE-79 브랜치 `docs/design/reservation/concurrency-results.md`.

### X1에서 2대를 재기 어려운 이유
- local 노트북에서는 Docker 메모리(7.6GB)가 모자라 reservation-service를 두 개 띄울 수 없다. 좌석 측정 계획의 P6(서버 2대·읽기 복제본)도 같은 이유로 뺐다(`k6-seathold-bottleneck-plan.md` 3-2절).
- 배포 환경도 EC2 한 대에 서비스마다 하나씩 뜬다. 두 대는 실제 운영 모양이 아니다.

### 선택지
| 안 | 내용 | 장단점 |
|---|---|---|
| A. 뺀다 | X1 변형에서 지우고, 헛수고 근거는 JUnit C6 결과를 쓴다 | 비용 0. k6 부하 아래의 모습은 보지 못한다 |
| B. 배포 서버에서만 | 측정 날 배포 서버에서 reservation-service를 하나 더 띄워 잰다 | 실제 부하에서 본다. compose 변경과 메모리 여유 확인이 필요하다 |
| C. 그대로 둔다 | "결정 대기"로 남긴다 | 명세가 할 수 없는 변형을 계속 품는다 |

AI 추천은 A다. 질문("두 스케줄러가 같은 행을 두고 헛수고하는가, S4가 없애는가")에 C6가 이미 답했고, X1의 본래 질문은 "만료 속도와 선점 지연"이기 때문이다.

## 이 프로젝트에서는
- 명세: `docs/design/infra/open-peak-scenarios.md` X1(2026-11-09 "결정 대기"로 표시)
- 스케줄러: `reservation-service/.../seathold/presentation/scheduling/SeatHoldExpiryScheduler.java`
- C6 시나리오: `docs/design/reservation/concurrency-scenarios.md`

## 면접 포인트
- "스케줄러를 여러 인스턴스에서 돌리면 무엇이 문제인가?" 같은 대상을 함께 집는다. 조건부 UPDATE로 정합성은 지켜도 잠금 대기와 헛수고가 생긴다. 해결은 리더 선출(ShedLock 등)로 한 대만 돌리거나, `FOR UPDATE SKIP LOCKED`로 대상을 나눠 집는 것이다. 후자는 인스턴스가 늘수록 처리량도 늘어난다.
- "정합성 문제와 비용 문제를 어떻게 구분했나?" 불변식(좌석·매수 반환 한 번)은 테스트로 확인하고, 헛수고는 "집었지만 만료하지 않은 시도" 수로 따로 셌다.

## 참고
- PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`: https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
