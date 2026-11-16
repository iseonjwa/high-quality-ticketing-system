---
title: AI가 먼저 추천한 SKIP LOCKED를 측정 뒤 거부하고 잠금 대기 50ms를 택함
date: 2026-11-13
updated: 2026-11-13
judge: KWON-WOO
ai: Claude Code (구현 뒤 동시성 테스트로 500ms·50ms·SKIP LOCKED 비교, 추천안 제시)
verdict: 거부
area: 동시성
presentation: 후보
shared: 예
---

# AI가 먼저 추천한 SKIP LOCKED를 측정 뒤 거부하고 잠금 대기 50ms를 택함

## 맥락
- 해결하려던 작업: ISE-261. 핫 좌석의 잠금 대기가 커넥션을 쥐어 다른 API까지 막는 번짐을 줄이려 했다.
- AI 활용 방식: AI가 개선안 중 `FOR UPDATE SKIP LOCKED`를 먼저 하라고 추천했고, 사용자가 받아들였다([앞선 판단](2026-11-13-seathold-skip-locked-first.md)). AI가 구현하고 동시성 테스트를 돌렸다.

## AI 제안
- 처음 제안: `SKIP LOCKED`로 잠긴 좌석을 기다리지 않는다. 잠금을 쥔 쪽이 롤백할 때만 잘못된 409가 생기고, 1석 요청이 대부분이면 손해가 작다고 봤다.
- 구현 뒤 보고: C2(10명이 좌석 4개를 겹치게 요청, 500건)에서 성공이 83건에서 56건으로 줄었다. 여러 좌석 요청이 롤백하기 전 몇 ms 동안 잡은 좌석까지 건너뛰기 때문이다. 처음 예상이 틀렸다.
- 새 추천: 좌석 쿼리는 기다림(`FOR UPDATE`)으로 두고 `lock_timeout`만 500ms에서 50ms로 줄인다. C2 성공 78·87건, C3 최대 대기 221~225ms였다. 번짐은 없어지지 않고 시작점이 초당 약 20건에서 45~150건으로 오른다.

## 판단
- 결과: 거부 (처음 제안한 `SKIP LOCKED`). 새 추천(50ms)은 수용했다.
- 판단자: KWON-WOO
- 근거: 선점에 실패할 사용자가 잠깐 잠근 테이블 행에 다른 사용자가 접근하지 못하는 것은 문제가 된다.
- 검토한 대안: 500ms 유지, `SKIP LOCKED` 유지, 결정 전에 측정 브랜치에서 Z·K1 부하로 비교

## 반영
- [ADR 0061](../../decisions/0061-seat-hold-lock-timeout-50ms.md), [TS-73](../../troubleshooting.md)
- `SyncSeatHoldService.LOCK_TIMEOUT` 50ms, `JpaSeatHoldInventoryRepository`에 SKIP LOCKED를 쓰지 않는 이유, C3 목표 400ms, `SeatHoldLockWaitTest`
- 학습 노트 1절 정정

## 검증
- 비교 측정: 위 수치(방식마다 1~3회).
- 최종 코드의 테스트 결과는 같은 브랜치의 PR 본문에 적는다.

## 판단 이력
- 2026-11-13 수용: `SKIP LOCKED`를 먼저 하기로 했다.
- 2026-11-13 거부: 구현 뒤 측정에서 성공이 약 1/3 준 것을 보고 50ms로 바꿨다.

## 발표·면접 포인트
- 대기를 없애는 개선은 지연만 보면 좋아 보인다. 성공 건수를 같이 재서 숨은 대가를 찾았다. 전부 아니면 전부로 여러 행을 잠그는 작업은 롤백이 흔해, 잠금을 건너뛰면 곧 비는 행까지 놓친다.
- AI의 "드물 것"이라는 예상을 받아들이지 않고 측정으로 확인한 사례다.
