# 0037. 추첨석은 설정 때 한 등급에서 떼어 좌석 등급 LOTTERY로 표시하고, 일반 선점에서 제외한다

- 상태: 제안 (추첨 담당 결정, 좌석 담당·팀 확인 대기)
- 날짜: 2026-10-31
- 결정자: 이강민 (추첨 담당)
- 관련 AI 판단: [추첨석 등급 분리](../ai-judgments/kangmin/2026-10-29-lottery-seat-allocation-grade.md), [후속 결정](../ai-judgments/kangmin/2026-10-31-lottery-seat-split-followups.md), [선반영 후 보고](../ai-judgments/kangmin/2026-10-31-lottery-sa-sync-apply-first-report-later.md)
- 상세: [`docs/design/lottery/options.md`](../design/lottery/options.md) D9

## 맥락
- 추첨 좌석을 일반 판매와 어떻게 나눌지 정해야 했다. SA 4-3은 "응모 → 추첨 → 일반 오픈" 순서에 기대어 둘이 겹치지 않는다고 봤고, SA 7-1은 이것을 Reservation 하나로 묶은 근거로 썼다.
- 시간 순서에만 기대면 운영자가 일반 오픈을 앞당기거나 다른 회차와 시간이 겹칠 때 일반 선점이 추첨 좌석을 잡을 수 있다.

## 결정
- 추첨 설정 때 한 등급(`lotteries.source_grade`, 기본 S)의 AVAILABLE 좌석을 좌석 ID 오름차순으로 `seat_count`개 골라 `schedule_seats.grade = 'LOTTERY'`로 바꾼다. 모자라면 설정이 실패한다.
- 일반 선점은 `grade <> 'LOTTERY'` 좌석만 잡는다. 좌석 생성 API는 `LOTTERY`를 받지 않는다.
- 설정·수정은 `now() < LEAST(open_at, entry_start_at)`일 때만 받는다.
- 추첨이 DONE이 되면 그 회차의 `LOTTERY` 좌석을 `source_grade`로 되돌린다. 팔린 추첨석은 `reservations.source = 'LOTTERY'`로 구분한다.
- SA 7-1의 3개 서비스 근거 중 추첨 문장을 "추첨석을 떼어 두어 실시간 선점과 같은 좌석을 다투지 않는다"로 바꾼다(10/31 선반영).

## 검토한 대안
- **좌석에 할당 구분 컬럼 추가**: 원래 등급이 남아 여러 등급에서 뗄 수 있다. 컬럼이 늘고, 지금은 한 등급만 쓰므로 이득이 작다.
- **미리 떼지 않고 추첨 직후 AVAILABLE 좌석 배정**: 일반 선점 코드를 건드리지 않는다. 시간 규칙이 틀리면 추첨 좌석이 일반 판매로 팔린다.
- **추첨이 시스템 예매로 좌석을 먼저 선점**: 주인 없는 예매, 매수 한도 예외, 긴 만료가 예매 흐름 코드에 퍼진다.

## 결과
- 얻는 것: 시간 규칙과 상관없이 일반 판매가 추첨 좌석을 잡지 못한다. 추첨석이 등급 값 하나로 보여 조회·반환이 쉽다. 같은 회차 일반 오픈 시각 검증(`open_at >= draw_at + 70분`)이 필요 없다.
- 감수하는 것: 좌석 담당 코드(일반 선점 쿼리)에 조건이 하나 는다. 종결 때 반환 작업이 생긴다. 등급 칸이 가격 등급과 판매 경로를 함께 뜻한다.
- 바꿀 조건: 여러 등급을 추첨에 넣기로 하면 구분 컬럼 방식으로 바꾼다.
