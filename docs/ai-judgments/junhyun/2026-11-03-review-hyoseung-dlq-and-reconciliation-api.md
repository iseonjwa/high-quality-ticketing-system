---
title: 반효승 PR #282(payment.result DLQ 처리기)·#283(대사 내부 조회 API) AI 리뷰를 받아들여 PR에 남기고, 대사 응답 모양은 #283에 맞춰 결제 쪽 어댑터를 고치기로 함
date: 2026-11-03
updated: 2026-11-03
judge: junhyun
ai: Claude Code(메인 Opus) 코드 리뷰
verdict: 수용
area: 이벤트 신뢰성
presentation: 아님
shared: 예
---

# 반효승 PR #282·#283 AI 리뷰를 받아들여 PR에 남기고, 대사 응답 모양은 #283에 맞춰 결제 쪽 어댑터를 고치기로 함

## 맥락
- 해결하려던 작업: 반효승이 올린 예매 쪽 PR 두 개를 리뷰했다. #282(ISE-74)는 `payment.result.dlq` 처리기와 `dead_letters`이고, #283(ISE-75)은 결제 서비스 대사가 부르는 읽기 전용 내부 조회 API다. #283은 같은 날 올린 결제 쪽 #284(ISE-168, 예매 조회 클라이언트와 대사 RC-1~RC-6)가 호출하는 쪽이다.
- AI 활용 방식: "지금 올라온 효승님 PR 리뷰해 줘"라고 요청했다. AI가 두 PR의 diff와 주변 코드(소비 서비스, 공통 오류 처리기, 상태 전이 쿼리, 결제 쪽 `DeadLetterService`, #284의 `ReservationLookupHttpClient`·`FakeReservationServer`)를 읽고, 브랜치끼리 병합을 시뮬레이션했다.

## AI 제안
#282
- 머지 전 수정: 다시 던지는 인프라 오류가 `TransientDataAccessException`·`DataAccessResourceFailureException`뿐이다. DB가 꺼지거나 연결 풀이 바닥날 때 나는 `CannotCreateTransactionException`은 처리 실패로 분류되어, 승인 이벤트가 `OPEN`으로 끝나고 예매가 만료될 수 있다(돈은 받았는데 티켓도 환불 요청도 없음).
- 머지 전 수정: `retryForever` 처리기라서 NUL 문자나 `message_key` 100자 초과로 `dead_letters` 저장이 실패하면 DLQ 파티션이 멈춘다. 결제 쪽 `clean()`·`truncate()`를 가져온다.
- 선택: `DEAD_LETTER_OPEN` 로그에 `reservationId`·`paymentId`를 넣는다. 같은 eventId가 먼저 `OPEN`이 된 뒤 성공해도 닫히지 않는 점을 고친다.
- 리뷰 포인트에 대한 답: 알림을 로그로 대신하는 것은 결제 쪽 RC-1·RC-2와 `reconciliation_mismatch_open` 알림이 받쳐 주므로 MVP로 괜찮다. DLQ 재처리와 RC-1·RC-2는 겹치거나 어긋나지 않는다.

#283
- 계약 불일치: #283은 `SuccessResponse`의 `data`로 감싸고 ID 조회의 `data`는 배열이다. #284 어댑터와 가짜 서버는 최상위 `{"items": [...]}`를 전제로 해서, 그대로 이으면 매 회차 조회가 실패한다. 팀 공통 응답 형식인 #283을 그대로 두고 #284의 어댑터·가짜 서버·`contracts.md` 5절을 고친다(`size` 없음, `nextCursor` 숫자, `updatedAt`은 마지막 갱신 시각).
- 머지 전 rebase: ADR 0029 상태 줄이 dev(#281, ISE-167)와 충돌한다.
- 리뷰 포인트에 대한 답: 결제 쪽이 매 회차 7일 기간을 겹쳐 묻고 `CONFIRMED`·`CANCELED`에서 나가는 전이가 확정 → 취소뿐이라, "현재 상태 + `updated_at`" 기준으로 RC-4를 찾기에 충분하다. 조건은 나중에 만들 취소 전이도 `updated_at`을 갱신하는 것이다.

## 판단
- 결과: 수용
- 판단자: junhyun
- 근거: 미기재. 리뷰를 본 뒤 "코멘트로 달아 줘"라고 해 리뷰 전체와 응답 모양 결정(#283에 맞추고 #284를 고침)을 받아들였다.
- 검토한 대안: #283이 감싸지 않은 `{items}`를 돌려주게 바꾸는 안. AI가 팀 공통 응답 형식과 #284 `contracts.md` 5절의 "예매 쪽 응답이 다르면 어댑터를 맞춘다"를 이유로 고르지 않았다.

## 반영
- PR 리뷰 코멘트: [#282 리뷰](https://github.com/iseonjwa/high-quality-ticketing-system/pull/282#pullrequestreview-5752689589)(인라인 4건), [#283 리뷰](https://github.com/iseonjwa/high-quality-ticketing-system/pull/283#pullrequestreview-5752690634)(인라인 2건).
- #284의 어댑터·가짜 서버·`contracts.md` 5절 수정은 미반영이다.

## 검증
- 두 PR의 CI(`build`, `records-check`)는 통과했다.
- 리뷰 내용은 코드 읽기로 판단했다. `CannotCreateTransactionException` 경로와 NUL 저장 실패는 로컬에서 재현하지 않았다.
- 응답 모양 불일치는 #283 컨트롤러와 #284의 `ReservationLookupHttpClient`(`body.path("items")`)·`FakeReservationServer`(`Map.of("items", ...)`)를 대조해 확인했다.
- 충돌은 `git merge-tree`로 확인했다. #283은 dev·#284와 ADR 0029에서 충돌하고, #282는 dev·#283·#284와 충돌하지 않는다.

## 판단 이력
- 2026-11-03 수용: AI 리뷰를 #282·#283에 코멘트로 남기고, 대사 응답 모양은 #283에 맞춰 #284를 고치기로 했다.

## 발표·면접 포인트
- DLQ 처리기는 "건너뛰면 의무가 사라지는" 소비자라서 예외 분류가 핵심이다. 연결 획득 실패(`CannotCreateTransactionException`)를 업무 실패로 보면, 장애가 풀리는 순간 처리 중이던 승인 한 건이 사람이 볼 기록으로만 남고 확정되지 않는다.
- 서비스 간 계약은 양쪽이 각자 가정으로 먼저 만들 수 있지만, 가짜 서버로 검증한 응답 모양이 실제 응답과 같다는 보장은 없다. 머지 전에 양쪽 구현을 대조해야 한다.
