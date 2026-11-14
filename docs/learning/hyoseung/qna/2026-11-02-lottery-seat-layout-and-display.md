---
title: 추첨석(LOTTERY)은 어느 자리를 떼고, 회차 좌석 조회 화면에는 어떻게 보여 주나
date: 2026-11-02
asker: hyos1
level: 기초
tags: [lottery, seat-grade, schedule-seats, seat-map]
---

# 추첨석(LOTTERY)은 어느 자리를 떼고, 회차 좌석 조회 화면에는 어떻게 보여 주나

## 질문
추첨석은 상태가 AVAILABLE이라 그대로 보여 주면 팔릴 것처럼 보인다. 회차 좌석 조회에서 어떻게 보여 줘야 하나? 추첨석은 좌석 어디로 정해지고 어떤 구조가 되나?

## 답

### 1. 추첨석은 S 등급 좌석의 앞쪽에서 뗀다
근거는 ADR 0037과 `docs/design/lottery/options.md` D9다.

- 운영자가 추첨을 설정할 때 떼어 올 등급(`source_grade`, 기본 S)과 좌석 수(`seat_count`)를 정한다.
- 설정 API는 그 회차의 S 등급 AVAILABLE 좌석을 **좌석 ID가 작은 순서로** `seat_count`개 골라 `schedule_seats.grade`를 `LOTTERY`로 바꾼다.
- 시드 공연장은 구역 A~E가 VIP, F~O가 R, P~Y가 S다. 좌석은 구역 → 열 → 번호 순서로 들어가서 S 중 ID가 가장 작은 좌석은 P구역 1열이다.
- 예: `seat_count = 300`이면 P구역 1~10열(10열 × 30석)이 추첨석이 된다. 600이면 P구역 전체다.
- 바뀌는 건 `grade` 한 칸뿐이다. `price`(S 가격)와 `status`(AVAILABLE)는 그대로다.

```
schedule_seats 행 예시 (회차 1)
id     section row seat grade     price    status
6001   P       1   1    LOTTERY   121000   AVAILABLE   <- 원래 S
6002   P       1   2    LOTTERY   121000   AVAILABLE
...
6301   P       11  1    S         121000   AVAILABLE   <- 추첨석 아님
```

### 2. 추첨석의 일생
| 시점 | grade | status | 누가 잡을 수 있나 |
|---|---|---|---|
| 추첨 설정 | S → LOTTERY | AVAILABLE | 아무도(일반 선점 쿼리가 `grade <> 'LOTTERY'`로 뺀다) |
| 추첨·당첨 배정 | LOTTERY | HELD → SOLD | 추첨 배정 워커만 |
| 종결(추첨 후 최장 60분) | LOTTERY → S | 팔린 좌석은 SOLD, 남은 좌석은 AVAILABLE | 남은 좌석은 일반 판매로 돌아간다 |

팔린 추첨석은 종결 뒤 등급이 S로 돌아가도 `reservations.source = 'LOTTERY'`로 추첨 판매였음을 구분한다.

### 3. 회차 좌석 조회에서는 "보이지만 고를 수 없는 좌석"으로 보여 준다
선택지는 둘이다.

| 방법 | 화면 | 문제 |
|---|---|---|
| 응답에서 뺀다 | 좌석 배치도에 구멍이 생긴다 | 사용자가 공연장 모양을 알 수 없다. 왜 비었는지 모른다 |
| 넣되 고를 수 없게 표시한다 | 회색 "추첨석"으로 칠한다 | 응답에 표시용 값이 하나 더 필요하다 |

두 번째가 맞다. D9 후속 결정도 "좌석이 화면에서 사라지지 않게" 일반 오픈 뒤에는 추첨을 추가하지 못하게 했다. 좌석은 화면에 있어야 한다는 전제다.

SA 11장 응답 예시에 이미 맞는 칸이 있다. 좌석마다 `available`(true/false)만 내려 주고 HELD·SOLD는 구분하지 않는다(SA 8-1).

```json
{ "section": "P", "grade": "S", "price": 121000,
  "seats": [ { "seatId": 6001, "row": 1, "number": 1, "available": false } ] }
```

- 추첨석은 `available: false`로 내려 주면 된다. 새 필드가 필요 없다.
- `available` 기준을 선점 쿼리와 똑같이 `status = AVAILABLE AND grade <> LOTTERY`로 맞춘다. SA는 추첨석을 따로 적지 않았으므로 이 기준은 구현할 때의 해석이다.
- 처음에는 `grade: "LOTTERY"`나 `selectable`을 새로 내보내자고 답했는데, SA 응답 모양을 확인하지 않은 답이었다. `available`로 충분하다.
- 회색 "추첨석"처럼 이유까지 보여 주려면 필드가 더 필요하다. SA에 없으므로 MVP에서는 하지 않는다.
- 화면이 실수로 추첨석을 보내도 선점 쿼리가 막아 409가 된다. 조회는 안내용이고, 실제로 막는 건 DB 조건이다.

```
[ 무대 ]
 A  B  C  D  E   VIP   ■■■□■■□■
 F ... O         R     ■□□■■■□■
 P (1~10열)      추첨   ▒▒▒▒▒▒▒▒   <- 회색, 클릭 안 됨
 P (11~20열) ... Y S   □□■□□□■□

□ 선택 가능   ■ 이미 선점·판매   ▒ 추첨석
```

## 정리
- 추첨석은 새 좌석이 아니라 S 좌석의 `grade`만 `LOTTERY`로 바꾼 것이다. ID가 작은 앞쪽 S 좌석부터 뗀다.
- 상태는 AVAILABLE로 남지만 일반 선점은 등급 조건으로 잡지 못한다.
- 회차 좌석 조회는 추첨석을 빼지 않고 SA 응답의 `available: false`로 내려 준다. 기준은 선점 쿼리와 같게 맞춘다.
- ADR 0037은 아직 "제안(좌석 담당·팀 확인 대기)" 상태다. 좌석 담당이 확인하면 확정된다.
