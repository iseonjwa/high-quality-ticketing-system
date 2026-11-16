---
title: P4b 상태 쿼리에서 둘 다 15,000행을 읽는데 권우님 방식이 왜 가벼운가
date: 2026-11-13
asker: hyos1
level: 중급
tags: [jpa, query, projection, gc, seat-query, p4b]
---

# P4b 상태 쿼리: 같은 15,000행, 다른 비용

## 질문

오픈 직후처럼 모든 좌석이 비어 있으면 둘 다 15,000행을 읽는데, 차이는 읽는 컬럼 수(내 것 id·등급·상태 3개, 권우님 것 id 1개)인가.

## 답

맞다. 행 수는 같고, 행마다 무엇을 받아 어떻게 다루는지가 다르다.

| | 내 것(#534) | 권우님 것(`experiment/ISE-79-k6-steps`) |
|---|---|---|
| DB가 보내는 열 | id, grade, status | id |
| 거르는 곳 | Java에서 `isHoldable()` | SQL `status = 'AVAILABLE' AND grade <> 'LOTTERY'` |
| Java에서 만드는 것 | 행마다 `SeatStateRow` + `Long`, 문자열을 enum으로 변환 | `long[]` 하나 |
| 배치와 맞추기 | `HashMap<Long, Integer>`로 위치 찾기 | 정렬된 배열 이진 탐색 |

- 행 수가 같아도 주고받는 양이 적고, 요청마다 만드는 객체가 15,000개 이상 적어 GC 부담이 적다.
- 표가 팔리면 내 것은 계속 15,000행, 권우님 것은 남은 좌석 ID만 받아 차이가 커진다.

## 왜 중요한가

P4b 뒤 배포 병목은 커넥션 5개였다(2026-11-12 배포 측정). 상태 쿼리가 가벼우면 커넥션을 쥐는 시간도 준다. 코드로 방향이 분명해 P4b끼리 비교 측정은 하지 않고 권우님 구조를 가져가기로 했다.

## 더 볼 것

- `docs/ai-judgments/hyoseung/daily/2026-11-13.md`
- ADR 0054(좌석 배치 로컬 캐시)
