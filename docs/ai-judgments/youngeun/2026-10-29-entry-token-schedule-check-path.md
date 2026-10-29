---
title: 입장 토큰의 회차 비교를 Gateway가 경로로 하도록 좌석 선점 경로를 /schedules/{scheduleId}/reservations로 바꾸기로 했다
date: 2026-10-29
updated: 2026-10-29
judge: youngeun
ai: Claude (Cowork 앱 대화)
verdict: 수용
area: 대기열
presentation: 아님
shared: 예
---

# 입장 토큰의 회차 비교를 Gateway가 경로로 하도록 좌석 선점 경로를 /schedules/{scheduleId}/reservations로 바꾸기로 했다

## 맥락
- 해결하려던 작업: ISE-20(입장 토큰 claim 정의)을 진행하면서, 검증 규칙 "토큰의 회차 ID = 요청 회차"를 어디서 어떻게 비교할지 정해야 했다. 좌석 조회는 경로에 회차 ID가 있지만 선점 `POST /api/v1/reservations`는 본문에 있다.
- AI 활용 방식: Gateway가 본문을 읽지 않는다면 어떤 선택지가 있는지, 비용과 안전성, `aud` 값과의 관계를 물었다.

## AI 제안
- 선택지: A(선점 경로를 `/schedules/{scheduleId}/reservations`로 바꿔 Gateway가 경로로 비교), B-1(Gateway가 토큰의 회차 ID를 헤더로 넘기고 선점 서비스가 본문과 비교), C(Gateway가 본문을 읽음, 비추천).
- A를 추천했다. 경로 비교 비용은 Gateway가 이미 파싱한 경로에서 값을 꺼내는 정도로 서명 검증에 비해 무시할 만하고, 검증이 Gateway 한 곳에 모여 API마다 비교를 빠뜨릴 위험이 없다고 했다. 선점 컨트롤러가 아직 없고 Gateway 라우팅이 이미 `/api/v1/schedules/**`라 변경 비용이 작다고 했다.
- "서비스에서 직접 확인하는 게 더 확실하다"는 의견에는, 좌석이 그 회차의 좌석인지 같은 업무 검증은 서비스에 있어야 하지만 Gateway 우회 대비라는 주장은 SA 7-2 신뢰 모델(서비스는 Gateway 헤더를 서명 없이 믿음)에서는 성립하지 않는다고 했다.
- `aud`는 회차를 누가 비교하느냐가 아니라 토큰을 누가 열어 검증하느냐로 정해지므로, A나 B-1이면 `gateway`를 유지한다고 했다.
- 회차 비교 위치는 선점 API 소유자(최권우)가 정할 문제이니 요구사항("회차 비교 필수, Gateway는 본문을 읽지 않음")을 전달하라고 했다.

## 판단
- 결과: 수용
- 판단자: youngeun
- 근거: "A 방식으로 가기로 했어". 이유는 미기재. 그 전에 "gateway는 바쁜데 경로 수정을 한다고 해도 경로로 비교하는 방식이 별로이지 않을까?", "서비스에서 직접 확인하는 게 더 확실해 보인다는데 이게 맞아?"라고 되물었다.
- 검토한 대안: B-1(헤더 전달 후 서비스 비교, 좌석 선점 담당의 `docs/sa-review/07-reservation-system-review.md` G2 권장안), C(Gateway 본문 읽기)

## 반영
- 계약 ①: [`docs/design/queue/contracts.md`](../../design/queue/contracts.md) (상태: 제안)
- 좌석 선점 계약 `docs/design/reservation/contracts.md` 1절·5-2절 경로 변경
- SA MASTER 사본: 머리말 제안 표 11·12번, 6-4, 7-3, 8-1, 11-4, 11-5
- Linear: ISE-20, ISE-58

## 검증
구현 전. ISE-58 완료 기준에 "다른 회차의 토큰으로 `/schedules/{다른 id}/seats`·`/reservations` 요청 → 403"을 둔다.

## 판단 이력
- 2026-10-29 수용: 선점 경로를 바꾸고 Gateway가 경로로 회차를 비교하기로 함

## 발표·면접 포인트
- Gateway가 요청 본문을 읽지 않게 하려고 API 경로를 바꿨다. 토큰 검증을 한 곳에 모으는 대신 선점 API 계약을 바꾸는 비용을 택했고, 구현 전이라 비용이 작을 때 정했다.
