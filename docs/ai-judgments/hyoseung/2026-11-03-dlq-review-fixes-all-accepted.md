---
title: payment.result DLQ 처리기(#282)의 AI 리뷰 지적을 선택 사항까지 모두 받아 고치고 테스트를 5개에서 9개로 늘림
date: 2026-11-03
updated: 2026-11-14
judge: 반효승
ai: Claude Code(준현 세션 Opus 코드 리뷰 + 효승 세션 Claude Code가 리뷰 정리·수정)
verdict: 수용
area: 이벤트 신뢰성
presentation: 후보
shared: 예
---

# payment.result DLQ 처리기(#282)의 AI 리뷰 지적을 선택 사항까지 모두 받아 고치고 테스트를 5개에서 9개로 늘림

## 맥락
- 해결하려던 작업: ISE-74. 예매 서비스가 `payment.result`를 처리하다 재시도까지 실패하면 `payment.result.dlq`로 가는데, 이 DLQ를 받아 한 번 다시 처리하고 그래도 실패하면 `dead_letters`에 `OPEN`으로 남기는 처리기를 만들었다(PR #282). 결제 승인 이벤트를 놓치면 돈은 받았는데 예매가 확정되지 않는다.
- AI 활용 방식: 처리기 구현은 Claude Code가 했다. 준현이 자기 세션의 Claude Code(Opus)로 PR을 리뷰해 인라인 4건을 남겼고([리뷰](https://github.com/iseonjwa/high-quality-ticketing-system/pull/282#pullrequestreview-5752689589), 준현 기록 [2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md](../junhyun/2026-11-03-review-hyoseung-dlq-and-reconciliation-api.md)), 효승 세션의 Claude Code가 그 리뷰를 정리해 무엇을 고칠지 추천했다.

## AI 제안
- 리뷰(준현 세션 AI)
  - 머지 전 수정 1: 다시 던지는 인프라 오류가 `TransientDataAccessException`·`DataAccessResourceFailureException`뿐이다. DB가 꺼지거나 연결 풀이 바닥날 때 나는 `CannotCreateTransactionException`은 업무 실패로 분류되어 승인 이벤트가 `OPEN`으로 끝나고 예매가 만료될 수 있다.
  - 머지 전 수정 2: 무한 재시도 처리기라서 NUL 문자나 100자를 넘는 `message_key` 때문에 `dead_letters` 저장이 실패하면 DLQ 파티션이 멈춘다. 결제 쪽 `clean()`·`truncate()`를 가져온다.
  - 선택: `DEAD_LETTER_OPEN` 로그에 `reservationId`·`paymentId`를 넣는다. 같은 이벤트가 먼저 `OPEN`이 된 뒤 다시 처리에 성공해도 닫히지 않는다.
  - 알림을 로그로 대신하는 것은 결제 쪽 대사(RC-1·RC-2)와 `reconciliation_mismatch_open` 알림이 받쳐 주므로 MVP로 괜찮다.
- 정리(효승 세션 AI): 머지 전 수정은 필수이고, 선택 사항도 작으니 같이 고치자고 추천했다.

## 판단
- 결과: 수용(선택 사항까지 전부)
- 판단자: 반효승
- 근거: "다 해줘. 그리고 준현님에게 변경사항 말씀드려줘."
- 검토한 대안: 머지 전 수정만 하고 선택 사항은 후속 이슈로 미루는 안. 받지 않은 지적은 없다. 알림을 로그로 대신하는 것은 리뷰 의견대로 그대로 뒀다.

## 반영
- 커밋 `3987eb68` fix(reservation): DLQ 처리기의 DB 연결 실패 분류, NUL·길이 정리, OPEN 닫기 #ISE-74
  - `PaymentResultDlqService`: `CannotCreateTransactionException`도 인프라 오류로 보고 기록하지 않고 다시 던진다. `DEAD_LETTER_OPEN`·재처리 로그에 `reservationId`·`paymentId`를 더했다.
  - `DeadLetterMessage`: 저장 전에 NUL 문자를 치환하고 길이를 자른다(`clean`, `truncate`).
  - `dead_letters` 저장소: 먼저 `OPEN`으로 남은 이벤트가 나중에 다시 처리되면 `RESOLVED`로 닫는다.
  - ADR 0027 한 줄.

## 검증
- `PaymentResultDlqListenerTest` 5개 → 9개. 더한 시험: 트랜잭션을 시작하지 못한 DB 연결 실패도 기록하지 않고 던진다, NUL 문자와 긴 키도 치환하고 잘라 저장한다, 원문의 NUL 문자도 치환해 저장한다, 먼저 `OPEN`으로 남은 이벤트가 나중에 다시 처리되면 `RESOLVED`로 닫는다.
- PR CI(`build`, `records-check`) 통과 뒤 머지했다.

## 판단 이력
- 2026-11-03 수용: 리뷰 지적을 선택 사항까지 모두 반영하기로 함(일지 [2026-11-03](daily/2026-11-03.md)).
- 2026-11-04 반영: 커밋 `3987eb68`.

## 발표·면접 포인트
- DLQ 처리기는 "건너뛰면 의무가 사라지는" 마지막 소비자라 예외 분류가 핵심이다. 연결 획득 실패를 업무 실패로 보면 장애가 풀린 뒤에도 승인 한 건이 사람만 보는 기록으로 남는다. 이걸 AI 리뷰가 찾았고, 시험으로 고정했다.
- AI가 만든 코드를 다른 사람의 AI 리뷰로 다시 검토했다. 받은 지적마다 시험을 하나씩 더해, 리뷰가 맞았는지를 말이 아니라 시험으로 남겼다.
