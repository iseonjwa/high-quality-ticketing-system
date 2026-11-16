---
title: 공연·좌석 ERD(venue, venue_seat, concert, schedule, schedule_seat)를 예시 데이터로 읽는 법
date: 2026-10-28
updated: 2026-10-28
asker: hyos1
level: 기초
tags: [erd, database, modeling, concert, schedule-seat]
---

# 공연·좌석 ERD(venue, venue_seat, concert, schedule, schedule_seat)를 예시 데이터로 읽는 법

## 질문
ERD가 눈에 그려지지 않는다. 공연·좌석 영역 구조를 A4에 그려 보며 이해할 수 있게 설명해 달라.

## 답
ERD는 테이블 이름과 화살표부터 보면 읽히지 않는다. 현실의 물건 → 예시 데이터 몇 줄 → 관계 그림 순서로 본다.

### 1. 현실에서 무엇이 있나
| 현실 | 테이블 | 설명 |
|---|---|---|
| 공연장 건물 | `venue` | 공연이 바뀌어도 그대로다 |
| 건물의 좌석 도면(A구역 1열 1번) | `venue_seat` | 건물에 박힌 의자. 공연과 무관하다 |
| 작품("아이유 콘서트") | `concert` | 제목, 설명 |
| 특정 날짜의 공연 | `schedule` | 회차마다 시작 시각·오픈 시각·장소가 있다 |
| 그 회차의 좌석 표 | `schedule_seat` | 가격과 판매 상태(AVAILABLE·HELD·SOLD) |

의자(`venue_seat`)는 하나지만 그 의자에 대한 표(`schedule_seat`)는 회차마다 따로 있다. 15일에 팔린 자리가 16일에는 비어 있어야 하기 때문이다.

### 2. 예시 데이터 (좌석 3개, 공연 1개, 회차 2개)
```
venue                     venue_seat
id | name                 id | venue_id | section | row | seat | grade
1  | KSPO DOME            10 | 1        | A       | 1   | 1    | VIP
                          11 | 1        | A       | 1   | 2    | VIP
                          12 | 1        | B       | 1   | 1    | R

concert                   schedule
id | title                id | concert_id | venue_id | starts_at   | open_at
1  | 아이유 콘서트         100| 1          | 1        | 11/15 19:00 | 11/1 20:00
                          101| 1          | 1        | 11/16 19:00 | 11/1 20:00

schedule_seat
id   | schedule_id | venue_seat_id | section | grade | price  | status
1000 | 100         | 10            | A       | VIP   | 165000 | SOLD
1001 | 100         | 11            | A       | VIP   | 165000 | AVAILABLE
1002 | 100         | 12            | B       | R     | 143000 | HELD
1003 | 101         | 10            | A       | VIP   | 165000 | AVAILABLE
1004 | 101         | 11            | A       | VIP   | 165000 | AVAILABLE
1005 | 101         | 12            | B       | R     | 143000 | AVAILABLE
```
`schedule_seat` 행 수 = 회차 수 × 좌석 수다(실제 2회차 × 15,000석 = 30,000행, SA 12-9).

### 3. 관계
```
   venue ──1:N── venue_seat
     │1               │1
     │N               │N
  schedule ──1:N── schedule_seat
     │N
     │1
  concert
```
| 관계 | 의미 | FK |
|---|---|---|
| venue 1:N venue_seat | 공연장 하나에 좌석 여러 개 | `venue_seat.venue_id` |
| concert 1:N schedule | 공연 하나에 회차 여러 개 | `schedule.concert_id` |
| venue 1:N schedule | 공연장 하나에서 회차 여러 번 | `schedule.venue_id` |
| schedule 1:N schedule_seat | 회차 하나에 표 15,000장 | `schedule_seat.schedule_id` |
| venue_seat 1:N schedule_seat | 의자 하나에 회차마다 표 하나 | `schedule_seat.venue_seat_id` |

`schedule_seat`는 "어느 회차의 어느 의자"이므로 `UNIQUE(schedule_id, venue_seat_id)`로 같은 회차에 같은 의자 표가 두 장 생기는 것을 막는다.

### 3-1. DB가 막아 주지 못하는 규칙 (2026-10-29 추가)
`schedule_seat`가 가리키는 의자(`venue_seat`)는 반드시 그 회차의 공연장(`schedule.venue_id`) 의자여야 한다. 그런데 FK는 "존재하는 의자인가"만 확인하고 "같은 공연장의 의자인가"는 확인하지 않는다. 다른 공연장 의자로 회차 좌석을 만들어도 DB는 통과시킨다. 그래서 회차 좌석을 만들 때 `INSERT ... SELECT ... FROM venue_seat WHERE venue_id = <회차의 venue_id>`처럼 애플리케이션이 조건을 지켜야 한다.

### 4. 왜 이렇게 생겼나
- `concert`와 `schedule` 분리: 제목·설명은 작품에 하나, 날짜·오픈 시각·장소는 회차마다 다르다.
- `schedule_seat`에 `section`·`grade` 복사: 좌석 조회마다 15,000행을 `venue_seat`와 조인하지 않으려는 의도적 비정규화다. `price`는 회차마다 다를 수 있어 원래 여기에 있다.
- `status`는 `schedule_seat`에만: "팔렸다"는 의자가 아니라 그 회차 표에 대한 사실이다.
- 권우 님 영역과의 접점: `schedule_seat 1 ── N reservation_seat N ── 1 reservation`(ISE-10).

### 5. 그려 본 뒤 확인할 질문
1. 회차를 하나 더 추가하면 어떤 테이블에 몇 행이 생기는가.
2. "11월 15일 공연의 빈 VIP 좌석"은 어느 테이블만 보면 되는가.
3. 공연장 도면에 의자를 하나 추가하면 이미 만들어진 회차 좌석은 어떻게 되는가.

## 핵심 정리
- 처음 보는 ERD는 예시 데이터를 손으로 몇 줄 써 봐야 읽힌다. FK 컬럼(`_id`)이 곧 화살표다.
- 변하지 않는 것(건물·도면·작품)과 회차마다 달라지는 것(일정·표·가격·상태)을 나눈 구조다.

## 면접 포인트
- "`venue_seat`와 `schedule_seat`를 왜 나눴나": 물리 좌석은 하나지만 판매 상태는 회차마다 독립이라서다. 대신 회차 생성 때 좌석 수만큼 행을 복사하는 비용이 생기고, 이는 `INSERT ... SELECT` 한 문장으로 처리한다.

## 참고
- `docs/core/SA MASTER.md` 12-3(테이블), 12-6(ERD), 12-9(적재량)
